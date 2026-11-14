package com.tvbridge.app

import android.content.*
import android.media.AudioManager
import android.os.*
import org.json.JSONObject
import java.util.concurrent.*

object Hub {
    @Volatile private var activityRef: java.lang.ref.WeakReference<MainActivity>? = null
    var activity: MainActivity?
        get() = activityRef?.get()
        set(value) { activityRef = value?.let { java.lang.ref.WeakReference(it) } }
    @JvmField @Volatile var server:LanServer?=null
    lateinit var live:LiveRepository
    lateinit var adb:AdbRemote;private set
    @JvmField var error=""
    @Volatile private var textMessage="请先选中电视输入框，并连接系统遥控"
    private val main=Handler(Looper.getMainLooper())
    @Synchronized fun start(context:Context){
        try{LegacyTls.install(context)}catch(_:Throwable){}
        if(server!=null)return
        val app=context.applicationContext
        val artworkCache=android.util.LruCache<String,ByteArray>(96)
        val bundleVersion=runCatching{app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime}.getOrDefault(0L)
        fun appArtwork(id:String):ByteArray?{
            val res=when(id){
                "module:live"->R.drawable.home_live_wide
                "module:files"->R.drawable.home_files_wide
                "module:browser"->R.drawable.home_browser_wide
                else->null
            }
            if(res!=null)return app.resources.openRawResource(res).use{it.readBytes()}
            val repository=LauncherApps(app)
            val entry=repository.installed().firstOrNull{it.component.flattenToString()==id}?:return null
            val key="$id:${entry.updated}:${AppArtwork.version()}"
            artworkCache.get(key)?.let{return it}
            return runCatching{
                val bitmap=android.graphics.Bitmap.createBitmap(160,90,android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(bitmap)
                val banner=AppArtwork.tile(app,entry.component.packageName)?:repository.banner(entry)
                val icon=banner?:repository.icon(entry)
                val tint=repository.tileTint(entry)
                val paint=android.graphics.Paint().apply{
                    shader=android.graphics.LinearGradient(0f,0f,0f,90f,tint[0],tint[1],android.graphics.Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f,0f,160f,90f,paint)
                val w=icon.intrinsicWidth.coerceAtLeast(1)
                val h=icon.intrinsicHeight.coerceAtLeast(1)
                val scale=if(banner!=null)maxOf(160f/w,90f/h) else 50f/maxOf(w,h)
                val x=(160-w*scale)/2
                val y=(90-h*scale)/2
                icon.setBounds(x.toInt(),y.toInt(),(160-x).toInt(),(90-y).toInt())
                icon.draw(canvas)
                val output=java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP,87,output)
                bitmap.recycle()
                output.toByteArray().also{artworkCache.put(key,it)}
            }.getOrNull()
        }
        fun appsSnapshot(known:String):String{
            val repository=LauncherApps(app)
            val ids=repository.orderedIds()
            val byId=repository.installed().associateBy{it.component.flattenToString()}
            val modules=AppModules.all.associateBy{it.id}
            val hidden=repository.hiddenIds().toSet()
            val folders=JSONObject()
            val layout=repository.folderLayout(ids)
            layout.forEach{(name,members)->folders.put(name,org.json.JSONArray(members))}
            val membership=layout.entries.flatMap{(name,members)->members.map{it to name}}.toMap()
            val arr=org.json.JSONArray()
            ids.forEach{id->
                val original=modules[id]?.title?:byId[id]?.label?:return@forEach
                val folder=membership[id]
                arr.put(JSONObject().put("id",id).put("title",repository.displayTitle(id,original))
                    .put("original",original).put("hidden",id in hidden).put("folder",folder?:JSONObject.NULL))
            }
            val artVersion="$bundleVersion:${AppArtwork.version()}:${byId.mapValues{it.value.updated}.hashCode()}"
            val version=(arr.toString()+folders.toString()+artVersion).hashCode().toString()
            if(known==version)return ""
            return JSONObject().put("version",version).put("apps",arr).put("folders",folders).put("artworkVersion",artVersion).toString()
        }
        if(!::live.isInitialized)live=LiveRepository(app)
        if(!::adb.isInitialized)adb=AdbRemote(app)
        try{
            server=LanServer(app.filesDir,app.assets.open("remote.html"),onFatal={dead->synchronized(this){if(server===dead)server=null}}){cmd,value->
                if(cmd=="inputText"){
                    textMessage="请先选中电视输入框；控制其他应用需先连接系统遥控"
                    if(!RemoteText.valid(value))return@LanServer false
                }
                if(cmd=="browserPlay"){
                    val done=CountDownLatch(1)
                    var played=false
                    main.post{
                        val browser=BrowserActivity.active
                        if(browser==null)done.countDown()
                        else browser.playDetectedVideo{played=it;done.countDown()}
                    }
                    return@LanServer done.await(5,TimeUnit.SECONDS)&&played
                }
                if(cmd=="adbConnect"){val connected=adb.connect(value.toIntOrNull()?:5555);if(connected)main.post{androidx.core.content.ContextCompat.startForegroundService(app,Intent(app,RemoteHostService::class.java))};return@LanServer connected}
                if(cmd=="adbDisconnect"){main.post{GlobalPointer.hide()};adb.disconnect();return@LanServer true}
                if(cmd=="pointerMode"||cmd in setOf("touchMove","touchClick","touchScroll")) {
                    if(cmd=="pointerMode"&&value=="on"&&adb.enabled()&&!adb.ready)adb.restore()
                    val done=CountDownLatch(1);var ok=false;var browserClick=false;var point=Pair(0,0);var scroll=intArrayOf()
                    main.post{try{
                        if(cmd=="pointerMode") {
                            if(value !in setOf("on","off"))return@post
                            if(value=="off"){GlobalPointer.hide();ok=true}
                            else if(adb.ready&&GlobalPointer.permitted(app)){
                                androidx.core.content.ContextCompat.startForegroundService(app,Intent(app,RemoteHostService::class.java));ok=GlobalPointer.show(app)
                            }
                        } else if(GlobalPointer.enabled&&adb.ready) {
                            if(cmd=="touchClick"){point=GlobalPointer.position();BrowserActivity.active?.let{it.pointerClick(point.first,point.second);browserClick=true};ok=true}
                            else{val parts=value.split(',');val dx=parts.getOrNull(0)?.toFloatOrNull();val dy=parts.getOrNull(1)?.toFloatOrNull()
                                if(parts.size==2&&dx!=null&&dy!=null&&dx.isFinite()&&dy.isFinite()){
                                    if(cmd=="touchMove")ok=GlobalPointer.move(dx,dy) else{scroll=GlobalPointer.scroll(dx,dy);ok=true}
                                }
                            }
                        }
                    }catch(_:RuntimeException){ok=false}finally{done.countDown()}}
                    if(!done.await(5,TimeUnit.SECONDS)||!ok)return@LanServer false
                    return@LanServer when(cmd){"touchClick"->browserClick||adb.pointerTap(point.first,point.second);"touchScroll"->adb.pointerScroll(scroll);else->true}
                }
                if(cmd=="wallpaperSet"){
                    val folder=java.io.File(app.filesDir,"received");val file=java.io.File(folder,value)
                    if(value!=file.name||!file.isFile||file.canonicalFile.parentFile!=folder.canonicalFile)return@LanServer false
                    try{HomeAppearance.importImage(app,file.inputStream());main.post{activity?.appearanceUpdated()};return@LanServer true}catch(_:Throwable){return@LanServer false}
                }
                if(cmd=="appsEdit"){
                    val ok=runCatching{
                        val data=JSONObject(value)
                        val repository=LauncherApps(app)
                        val id=data.optString("id")
                        val name=data.optString("name").trim()
                        val folder=data.optString("folder")
                        val valid=repository.appTitle(id)!=null
                        val folders=repository.folderNames()
                        when(data.optString("op")){
                            "rename"->valid&&name.length<=30&&run{repository.setTitle(id,name);true}
                            "hide","show"->valid&&run{repository.setHidden(id,data.getString("op")=="hide");true}
                            "folder"->valid&&(name.isEmpty()||name in folders)&&run{repository.moveToFolder(id,name.ifEmpty{null});true}
                            "newFolder"->valid&&name.length in 1..20&&name !in folders&&run{repository.moveToFolder(id,name);true}
                            "renameFolder"->name.length in 1..20&&repository.renameFolder(folder,name)
                            "dissolve"->folder in folders&&run{repository.dissolveFolder(folder);true}
                            else->false
                        }
                    }.getOrDefault(false)
                    if(ok)main.post{activity?.appsUpdated()}
                    return@LanServer ok
                }
                if(cmd=="appsOrder"){
                    val ok=try{
                        val arr=org.json.JSONArray(value)
                        val ids=(0 until arr.length()).map{arr.optString(it)}.filter{it.isNotEmpty()}
                        if(ids.isEmpty())false else {LauncherApps(app).setOrder(ids);true}
                    }catch(_:Exception){false}
                    if(ok)main.post{activity?.appsUpdated()}
                    return@LanServer ok
                }
                if(cmd in setOf("liveImport","liveSubscribe","liveSource","liveRemove","liveDirect")&&LiveLaunch.builtIn(app))return@LanServer false
                if(cmd in setOf("liveImport","liveSubscribe","liveSource","liveFavorite","liveRemove","liveDirect")){
                    val ok=when(cmd){"liveImport"->live.importText(value);"liveSubscribe"->live.subscribe(value);"liveSource"->{live.selectSource(value);true};"liveFavorite"->{live.toggleFavorite(value);true};"liveRemove"->{live.removeSource(value);true};else->{val item=JSONObject(value);live.addDirect(item.optString("name"),item.optString("url"))}}
                    main.post{activity?.liveUpdated()};return@LanServer ok
                }
                val done=CountDownLatch(1);var result=false;var openLiveWithAdb=false
                main.post{try{
                    val a=activity
                    result=when {
                        cmd=="livePlayer"->{
                            if(!LiveLaunch.selectInternal(app,value,live))false else {
                                if(a?.visible==true)a.openSelectedLive()
                                else if(adb.ready)openLiveWithAdb=true
                                else app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_live",true))
                                true
                            }
                        }
                        cmd=="volumeMute"->{
                            val audio=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            if(Build.VERSION.SDK_INT>=23)audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_TOGGLE_MUTE,AudioManager.FLAG_SHOW_UI)
                            else {
                                val saved=app.getSharedPreferences("remote_audio",Context.MODE_PRIVATE)
                                val current=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                if(current>0)saved.edit().putInt("before_mute",current).apply()
                                val volume=if(current>0)0 else saved.getInt("before_mute",audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/3).coerceIn(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,AudioManager.FLAG_SHOW_UI)
                            };true
                        }
                        cmd=="volumeUp"||cmd=="volumeDown"->{(app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC,if(cmd=="volumeUp")AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);true}
                        cmd=="power"->DesktopGuardService.showPowerMenu()
                        cmd=="pointerSetup"->{
                            app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pointer_setup",true))
                            true
                        }
                        cmd=="browserOpen"||(cmd=="browserNavigate"||cmd=="browserHome")&&BrowserActivity.active==null->{
                            val intent=Intent(app,BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if(cmd=="browserNavigate"){
                                val url=PlaylistParser.httpUrl(value)?:return@post
                                intent.putExtra("address",url)
                            }
                            if(BrowserActivity.active==null)app.startActivity(intent)
                            true
                        }
                        cmd=="home"&&BrowserActivity.active?.returnToOneHome()==true->true
                        BrowserActivity.active?.remoteCommand(cmd,value)==true->true
                        cmd=="home"&&a?.returnToOneHome()==true->true
                        cmd=="home"&&a?.visible==true->{a.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));true}
                        a?.visible==true&&cmd !in setOf("home","recents","power")->a.command(cmd,value)
                        else->false
                    }
                }catch(_:Exception){result=false}finally{done.countDown()}}
                if(!done.await(5,TimeUnit.SECONDS))return@LanServer false
                if(cmd=="inputText"){
                    if(result){textMessage="已输入到电视";return@LanServer true}
                    if(RemoteInputService.isActive&&RemoteInputService.commit(value,500)){textMessage="已输入到电视";return@LanServer true}
                    if(adb.enabled()){val ok=adb.sendText(value);textMessage=adb.message;return@LanServer ok}
                    return@LanServer false
                }
                if(result){if(openLiveWithAdb)adb.command("liveOpen");return@LanServer true}
                if(cmd.startsWith("browser"))return@LanServer false
                if(adb.enabled()&&(adb.ready||adb.restore()))return@LanServer adb.command(cmd)
                false
            }.also{s->
                s.capabilities{JSONObject().put("browser",JSONObject(BrowserActivity.active?.remotePlaybackState()?:"{}")).put("appearance",if(HomeAppearance.dark(app))"dark"else "light").put("systemRemote",adb.ready).put("adbEnabled",adb.enabled()).put("adbReady",adb.ready).put("adbMessage",adb.message).put("engineNeeded",false).put("engineInstalled",true).put("pointerReady",GlobalPointer.enabled&&adb.ready).put("overlayAllowed",GlobalPointer.permitted(app)).put("foreground",activity?.visible==true||BrowserActivity.active!=null).put("version",app.packageManager.getPackageInfo(app.packageName,0).versionName).put("bootEnabled",app.getSharedPreferences("startup",Context.MODE_PRIVATE).getBoolean("boot",false)).put("bootState",app.getSharedPreferences("startup",Context.MODE_PRIVATE).getString("boot_status","unknown")).toString()}
                s.liveData{live.snapshot(it)};s.appsData{appsSnapshot(it)};s.appArtwork{appArtwork(it)};s.liveMessage{live.lastMessage};s.playbackData{PlaybackDiagnostics.snapshot()};s.textMessage{textMessage};s.start()
            };error=""
        }catch(e:Exception){error=e.message.orEmpty();server=null}
    }
    @Synchronized fun stop(){main.post{GlobalPointer.hide()};server?.close();server=null}
}
