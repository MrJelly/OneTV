package com.tvbridge.app

import android.content.*
import android.content.pm.PackageManager
import org.json.JSONArray

class LauncherApps(private val context:Context){
    companion object{
        private val orderLock=Any()
        private val catalogs=java.util.WeakHashMap<PackageManager,List<Entry>>()
        private val icons=android.util.LruCache<String,android.graphics.drawable.Drawable.ConstantState>(48)
        private val banners=android.util.LruCache<String,android.graphics.drawable.Drawable.ConstantState>(48)
        private val tints=android.util.LruCache<String,IntArray>(64)
        fun invalidate(context:Context)=synchronized(catalogs){catalogs.remove(context.packageManager);synchronized(icons){icons.evictAll()};synchronized(banners){banners.evictAll()};synchronized(tints){tints.evictAll()}}
    }
    data class Entry(val component:ComponentName,val label:String,val category:String=Intent.CATEGORY_LAUNCHER,val updated:Long=0)
    private val prefs=context.getSharedPreferences("launcher-apps",Context.MODE_PRIVATE)
    fun installed():List<Entry> = synchronized(catalogs){
        val pm=context.packageManager
        catalogs[pm]?.let{return@synchronized it}
        val found=linkedMapOf<String,Entry>()
        for(category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER,"android.intent.category.APPSTORE_LAUNCHER")){
            val matches=runCatching{pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category),0)}.getOrDefault(emptyList())
            for(result in matches){
                val info=result.activityInfo?:continue
                if(info.packageName==context.packageName||!info.exported||!info.enabled||found.containsKey(info.packageName))continue
                val stamp=try{pm.getPackageInfo(info.packageName,0).lastUpdateTime}catch(_:PackageManager.NameNotFoundException){0L}
                found[info.packageName]=Entry(ComponentName(info.packageName,info.name),result.loadLabel(pm).toString(),category,stamp)
            }
        }
        try{
            for(app in pm.getInstalledApplications(0)){
                if(app.packageName==context.packageName||found.containsKey(app.packageName))continue
                val intent=try{pm.getLaunchIntentForPackage(app.packageName)}catch(_:Exception){null}?:continue
                val component=intent.component?:continue
                val stamp=try{pm.getPackageInfo(app.packageName,0).lastUpdateTime}catch(_:PackageManager.NameNotFoundException){0L}
                found[app.packageName]=Entry(component,try{pm.getApplicationLabel(app).toString()}catch(_:Exception){app.packageName},Intent.CATEGORY_LAUNCHER,stamp)
            }
        }catch(_:Exception){}
        val gitv=ComponentName("com.gitv.dvb.live","com.gitv.dvb.live.activity.PlayActivity")
        if(gitv.packageName !in found){
            runCatching{pm.getActivityInfo(gitv,0)}.getOrNull()?.takeIf{it.exported&&it.enabled&&it.applicationInfo.enabled}?.let{info->
                found[gitv.packageName]=Entry(gitv,info.loadLabel(pm).toString(),category="")
            }
        }
        found.values.sortedBy{it.label.lowercase()}.also{if(it.isNotEmpty())catalogs[pm]=it}
    }
    fun icon(entry:Entry):android.graphics.drawable.Drawable {
        val key="${entry.component.flattenToString()}:${entry.updated}"
        synchronized(icons){icons.get(key)?.let{return it.newDrawable(context.resources)}}
        val original=try{context.packageManager.getActivityIcon(entry.component)}catch(_:Exception){context.packageManager.defaultActivityIcon}
        val image=android.graphics.Bitmap.createBitmap(128,128,android.graphics.Bitmap.Config.ARGB_8888)
        val w=original.intrinsicWidth.coerceAtLeast(1);val h=original.intrinsicHeight.coerceAtLeast(1);val scale=128f/maxOf(w,h)
        val width=(w*scale).toInt();val height=(h*scale).toInt()
        original.setBounds((128-width)/2,(128-height)/2,(128+width)/2,(128+height)/2);original.draw(android.graphics.Canvas(image))
        val drawable=android.graphics.drawable.BitmapDrawable(context.resources,image)
        drawable.constantState?.let{synchronized(icons){icons.put(key,it)}}
        return drawable
    }
    fun banner(entry:Entry):android.graphics.drawable.Drawable? {
        if(android.os.Build.VERSION.SDK_INT<21)return null
        val key="${entry.component.flattenToString()}:${entry.updated}"
        synchronized(banners){banners.get(key)?.let{return it.newDrawable(context.resources)}}
        val loaded=try{context.packageManager.getApplicationInfo(entry.component.packageName,0).loadBanner(context.packageManager)}catch(_:Exception){null}?:return null
        loaded.constantState?.let{synchronized(banners){banners.put(key,it)}}
        return loaded
    }
    /** [top, bottom, glow] colours sampled from the app icon, Aerial-style. */
    fun tileTint(entry:Entry):IntArray {
        val key="${entry.component.flattenToString()}:${entry.updated}"
        synchronized(tints){tints.get(key)?.let{return it}}
        val result=sampleTint(entry)
        synchronized(tints){tints.put(key,result)}
        return result
    }
    private fun sampleTint(entry:Entry):IntArray {
        val bitmap=(icon(entry) as? android.graphics.drawable.BitmapDrawable)?.bitmap?:return intArrayOf(0xff3a3a3f.toInt(),0xff26262b.toInt(),0xff66666e.toInt())
        return sampleBitmap(bitmap)
    }
    fun sampleBitmap(bitmap:android.graphics.Bitmap):IntArray {
        val grey=intArrayOf(0xff3a3a3f.toInt(),0xff26262b.toInt(),0xff66666e.toInt())
        val n=24
        val small=android.graphics.Bitmap.createScaledBitmap(bitmap,n,n,true)
        val weight=FloatArray(12);val sum=Array(12){FloatArray(3)};val hsv=FloatArray(3)
        val borderBins=IntArray(12);var borderColored=0
        for(y in 0 until n)for(x in 0 until n){
            val p=small.getPixel(x,y)
            if(android.graphics.Color.alpha(p)<128)continue
            android.graphics.Color.colorToHSV(p,hsv)
            if(hsv[1]<0.3f||hsv[2]<0.25f)continue
            val bin=(hsv[0]/30f).toInt()%12
            val w=hsv[1]*hsv[2]
            weight[bin]+=w;sum[bin][0]+=android.graphics.Color.red(p)*w;sum[bin][1]+=android.graphics.Color.green(p)*w;sum[bin][2]+=android.graphics.Color.blue(p)*w
            if(x==0||y==0||x==n-1||y==n-1){borderBins[bin]++;borderColored++}
        }
        if(small!==bitmap)small.recycle()
        var best=0;for(i in 1 until 12)if(weight[i]>weight[best])best=i
        if(weight[best]<n*n*0.03f)return grey
        val w=weight[best]
        val dom=android.graphics.Color.rgb((sum[best][0]/w).toInt(),(sum[best][1]/w).toInt(),(sum[best][2]/w).toInt())
        fun shade(color:Int,f:Float)=android.graphics.Color.rgb((android.graphics.Color.red(color)*f).toInt().coerceIn(0,255),(android.graphics.Color.green(color)*f).toInt().coerceIn(0,255),(android.graphics.Color.blue(color)*f).toInt().coerceIn(0,255))
        val flat=borderColored>=20&&borderBins[best]*2>=borderColored
        return if(flat)intArrayOf(shade(dom,0.95f),shade(dom,0.72f),dom) else intArrayOf(shade(dom,0.66f),shade(dom,0.42f),dom)
    }
    private fun readIds(key:String):List<String>? = try{val raw=prefs.getString(key,null)?:return null;val a=JSONArray(raw);(0 until a.length()).map{a.getString(it)}}catch(_:Exception){null}
    private fun writeIds(key:String,ids:List<String>){prefs.edit().putString(key,JSONArray(ids).toString()).apply()}
    private object Defaults{
        /** Bumped key: the pre-Aerial order (seeded from the retired favourites) is void. */
        const val ORDER_KEY="app-order-v2"
        /** Aerial's first-run arrangement (LayoutStore.Defaults): streaming apps first. */
        val preferredOrder=listOf("com.ktcp.video","com.gitvdemo.video","com.cibn.tv","com.hunantv.license","com.xiaodianshi.tv.yst","com.netflix.ninja","com.google.android.youtube.tv","com.apple.atve.androidtv.appletv","com.disney.disneyplus","com.amazon.amazonvideo.livingroom","com.hbo.hbonow","com.spotify.tv.android","com.mitv.mivideoplayer","com.quark.yun.tv")
        /** The TV's own utilities go last, in this order among themselves. */
        val utilityOrder=listOf("com.xiaomi.mitv.settings","com.android.tv.settings","com.xiaomi.mitv.tvmanager","com.mitv.tvhome","com.google.android.tvlauncher","com.google.android.apps.tv.launcherx")
        fun isUtility(pkg:String)=pkg in utilityOrder||pkg.startsWith("com.android.")
        val defaultComparator=compareBy<Entry>({preferredOrder.indexOf(it.component.packageName).let{i->if(i<0)Int.MAX_VALUE else i}},{if(isUtility(it.component.packageName))1 else 0},{utilityOrder.indexOf(it.component.packageName)},{it.label.lowercase()})
    }
    /** The one app order shared by the home grid and the phone layout page. With no saved
     *  order the arrangement is Aerial's default (preferred apps first, utilities last, the
     *  rest by label); afterwards the saved order rules and new installs join at the end. */
    fun orderedIds():List<String> = synchronized(orderLock){
        val modules=AppModules.all.map{it.id}
        val defaults=modules+installed().sortedWith(Defaults.defaultComparator).map{it.component.flattenToString()}
        val valid=defaults.toSet()
        val stored=readIds(Defaults.ORDER_KEY)
        val saved=stored.orEmpty().filter{it in valid}
        val base=if(saved.none{it in modules})modules+saved else saved
        val merged=(base+defaults).distinct()
        if(merged!=stored)writeIds(Defaults.ORDER_KEY,merged)
        merged
    }
    fun setOrder(ids:List<String>)=synchronized(orderLock){
        val current=orderedIds()
        val valid=current.toSet()
        val chosen=ids.filter{it in valid}.distinct()
        val modules=AppModules.all.map{it.id}
        val order=if(chosen.none{it in modules})modules+chosen else chosen
        writeIds(Defaults.ORDER_KEY,(order+current).distinct())
    }
    fun move(id:String,offset:Int):Boolean=synchronized(orderLock){
        val ids=orderedIds().toMutableList()
        val from=ids.indexOf(id)
        if(from<0)return@synchronized false
        ids.removeAt(from)
        ids.add((from+offset).coerceIn(0,ids.size),id)
        writeIds(Defaults.ORDER_KEY,ids)
        true
    }
    fun hiddenIds()=readIds("home_hidden").orEmpty()
    fun setHidden(id:String,hidden:Boolean)=synchronized(orderLock){
        val ids=hiddenIds()
        writeIds("home_hidden",if(hidden)(ids+id).distinct() else ids-id)
    }
    fun folderNames()=readIds("home_folders").orEmpty()
    fun folderMembers(name:String)=readIds("home_folder:"+name).orEmpty()
    fun folderLayout(order:List<String>):Map<String,List<String>> = synchronized(orderLock){
        val positions=order.withIndex().associate{it.value to it.index}
        folderNames().associateWith{folder->
            folderMembers(folder).filter{it in positions}.distinct().sortedBy{positions.getValue(it)}
        }.filterValues{it.isNotEmpty()}
    }
    fun moveToFolder(id:String,name:String?)=synchronized(orderLock){
        val folders=folderNames()
        val names=if(name==null)folders else (folders+name).distinct()
        val editor=prefs.edit()
        val remaining=names.filter{folder->
            val before=folderMembers(folder)
            val after=if(folder==name)(before+id).distinct() else before-id
            if(after.isEmpty())editor.remove("home_folder:"+folder)
            else if(after!=before)editor.putString("home_folder:"+folder,JSONArray(after).toString())
            after.isNotEmpty()
        }
        editor.putString("home_folders",JSONArray(remaining).toString()).apply()
    }
    fun renameFolder(old:String,name:String):Boolean=synchronized(orderLock){
        val folders=folderNames()
        if(old !in folders||name.isBlank()||name in folders)return@synchronized false
        prefs.edit().putString("home_folders",JSONArray(folders.map{if(it==old)name else it}).toString())
            .putString("home_folder:"+name,JSONArray(folderMembers(old)).toString()).remove("home_folder:"+old).apply()
        true
    }
    fun dissolveFolder(name:String)=synchronized(orderLock){
        prefs.edit().putString("home_folders",JSONArray(folderNames()-name).toString())
            .remove("home_folder:"+name).apply()
    }
    fun bootApp():String?{
        val startup=context.getSharedPreferences("startup",0)
        return startup.getString("boot_app",null)?:if(startup.getBoolean("live",false))"module:live" else null
    }
    fun toggleBootApp(id:String):Boolean{
        val enable=bootApp()!=id
        context.getSharedPreferences("startup",0).edit().putString("boot_app",if(enable)id else null).remove("live").apply()
        return enable
    }
    fun displayTitle(id:String,fallback:String)=prefs.getString("home_title:"+id,null)?:fallback
    fun setTitle(id:String,name:String){
        prefs.edit().putString("home_title:"+id,name.takeIf{it.isNotBlank()}).apply()
    }
    fun appTitle(id:String)=(AppModules.all.firstOrNull{it.id==id}?.title
        ?:installed().firstOrNull{it.component.flattenToString()==id}?.label)?.let{displayTitle(id,it)}

    fun swap(first:String,second:String)=synchronized(orderLock){
        val ids=orderedIds().toMutableList()
        val from=ids.indexOf(first)
        val to=ids.indexOf(second)
        if(from<0||to<0||from==to)return@synchronized
        ids[from]=second
        ids[to]=first
        writeIds(Defaults.ORDER_KEY,ids)
    }
    fun launch(entry:Entry):Boolean {
        if(entry.component.packageName in setOf("com.android.settings","com.android.tv.settings","com.changhong.easysetting")){
            val intent=ChanghongSettings.intent(context)
            if(intent!=null&&ChanghongSettings.open(context,intent))return true
        }
        fun direct()=try{context.startActivity(Intent(Intent.ACTION_MAIN).apply{if(entry.category.isNotEmpty())addCategory(entry.category)}.setComponent(entry.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));true}catch(_:RuntimeException){false}
        val opened=if(DesktopTakeover.isStockHome(context,entry.component))DesktopGuardService.openStock(context,entry.component,::direct)else direct()
        if(!opened)DesktopGuardService.allowStockLauncher(false)
        return opened
    }
}
