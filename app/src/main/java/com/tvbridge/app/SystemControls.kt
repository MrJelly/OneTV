package com.tvbridge.app

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.provider.Settings
import android.view.*
import android.widget.*

object SystemControls {
    enum class Kind{Hdmi,DisplayPort,Av,Tuner,Other,Usb}
    data class Input(val id:String,val label:String,val intent:Intent,val kind:Kind=Kind.Other,val connected:Boolean=false,val deviceName:String?=null)
    fun network(context:Context):String {
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if(Build.VERSION.SDK_INT>=23&&manager!=null)runCatching{
            val active=manager.activeNetwork?.let{manager.getNetworkCapabilities(it)}
            if(active!=null){
                val networks=if(active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&
                    !active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)&&!active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){
                    manager.allNetworks.filter{manager.getNetworkInfo(it)?.isConnected==true}.mapNotNull{manager.getNetworkCapabilities(it)}
                }else listOf(active)
                if(networks.any{it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)})return "有线网络"
                if(networks.any{it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)})return "Wi-Fi"
                if(networks.any{it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)})return "移动网络"
            }
        }
        val info=runCatching{manager?.activeNetworkInfo}.getOrNull()
        return when{info?.isConnected!=true->"未连接";info.type==ConnectivityManager.TYPE_WIFI->"Wi-Fi";info.type==ConnectivityManager.TYPE_ETHERNET->"有线网络";info.type==ConnectivityManager.TYPE_MOBILE->"移动网络";else->"网络已连接"}
    }
    private val fengOsPanel=ComponentName("cn.formovie.fengos.settings","cn.formovie.fengos.settings.ui.activity.MainActivity")
    private fun fengOsSettings(context:Context)=Intent(Intent.ACTION_MAIN).setComponent(fengOsPanel).takeIf{resolves(context,it)}

    fun networkIntents(context:Context):List<Intent> {
        val wired=network(context)=="有线网络"
        return listOfNotNull(
            // Aerial 829cb68 (PR #1540): LeTV's own network page; the explicit
            // component only resolves where LeTV's settings app is installed.
            Intent("com.letv.setting.NETWORK").setComponent(ComponentName("com.stv.globalsetting","com.stv.globalsetting.activity.NetworkActivity")),
            fengOsSettings(context)?.let{Intent(Settings.ACTION_WIFI_SETTINGS).setPackage(fengOsPanel.packageName)},
            ChanghongSettings.intent(context,ChanghongSettings.NETWORK),
            Intent(if(wired)"com.xiaomi.mitv.settings.ETHERNET_SETTINGS"else "com.xiaomi.mitv.settings.WIFI_SETTINGS").setPackage("com.xiaomi.mitv.settings"),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.android.tv.settings","com.android.tv.settings.connectivity.NetworkActivity")),
            Intent("com.pptv.intent.action.WIFI_ACTIVITY").setComponent(ComponentName("com.pptv.setting","com.pptv.setting.wifi.WifiWorkActivity")),
            Intent(if(wired)Settings.ACTION_WIRELESS_SETTINGS else Settings.ACTION_WIFI_SETTINGS),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings","com.tcl.settings.ui.MainActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.helios.setting","com.helios.activity.StartActivity")),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),Intent(Settings.ACTION_SETTINGS)
        ).filter{it.component==ChanghongSettings.component||resolves(context,it)}.distinctBy{context.packageManager.resolveActivity(it,0)?.activityInfo?.let{a->a.packageName+"/"+a.name}}
    }
    private fun resolves(context:Context,intent:Intent)=runCatching{context.packageManager.resolveActivity(intent,0)?.activityInfo?.exported==true}.getOrDefault(false)
    fun open(context:Context,options:List<Intent>,reportFailure:Boolean=true):Boolean {
        for(intent in options)if(runCatching{HisenseInputAdapter.open(context,intent)}.getOrDefault(false))return true
        for(intent in options)if(intent.component==ChanghongSettings.component&&ChanghongSettings.open(context,intent))return true
        for(intent in options)if(HisenseInputAdapter.openLegacy(context,intent))return true
        for(intent in options)if(resolves(context,intent))try{context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true}catch(_:RuntimeException){}
        if(reportFailure)NativeUi.notice(context,"系统未提供此入口");return false
    }
    fun sources(context:Context):List<Input> {
        val standard=if(Build.VERSION.SDK_INT>=21)InputFramework.list(context)else emptyList()
        if(standard.any{it.kind!=Kind.Tuner})return standard.take(64)
        val vendor=XgimiInputAdapter.load(context).ifEmpty{KonkaInputAdapter.load(context)}
            .ifEmpty{HisenseInputAdapter.load(context)}.ifEmpty{HisenseInputAdapter.loadLegacy(context)}
            .ifEmpty{InputRoutes.skyworth(context)}
        return (vendor+standard).distinctBy{it.id}.take(64)
    }
    fun openSources(activity:Activity){
        val unit=NativeUi.scale(activity);fun dp(n:Int)=(n*2*unit).toInt()
        val body=android.widget.FrameLayout(activity)
        val loading=android.widget.TextView(activity).apply{text="正在读取信号源…";gravity=android.view.Gravity.CENTER;NativeUi.textSize(this,30f);setTextColor(GlassUi.ink(activity));setPadding(0,dp(16),0,dp(16))}

        val list=android.widget.ListView(activity).apply{divider=null;dividerHeight=0;isVerticalScrollBarEnabled=false;visibility=android.view.View.INVISIBLE;selector=GlassUi.selectedBorder(this,18f)}
        val inSettings=activity.window.decorView.findViewWithTag<View>(NativeUi.CONTROL_DETAIL)?.height?.let{it>0}==true
        body.addView(list,android.widget.FrameLayout.LayoutParams(-1,if(inSettings)0 else dp(250)))
        body.addView(loading,android.widget.FrameLayout.LayoutParams(-1,if(inSettings)0 else -2,Gravity.CENTER))
        val dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("信号源").setView(body).setNegativeButton("关闭",null),fillDetailWith=if(inSettings)list else null)
        if(inSettings)loading.layoutParams=loading.layoutParams.apply{height=-2}
        AppThreads.onIo{val inputs=sources(activity.applicationContext);activity.runOnUiThread{
            if(!dialog.isShowing||activity.isFinishing||activity.isDestroyed)return@runOnUiThread
            if(inputs.isEmpty()){dialog.dismiss();inputPanel(activity);return@runOnUiThread}
            loading.visibility=android.view.View.GONE;list.visibility=android.view.View.VISIBLE
            list.adapter=object:ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,inputs.map{it.label}+"信号源设置"){
                override fun getView(position:Int,convert:View?,parent:ViewGroup):View {
                    val input=inputs.getOrNull(position)
                    return LinearLayout(activity).apply{
                        gravity=Gravity.CENTER_VERTICAL
                        setPadding(dp(12),0,dp(12),0)
                        minimumHeight=dp(38)
                        addView(TextView(activity).apply{
                            text=getItem(position)
                            NativeUi.textSize(this,30f)
                            setTextColor(GlassUi.ink(activity))
                            maxLines=1
                            ellipsize=android.text.TextUtils.TruncateAt.END
                            val icon=when(input?.kind){
                                Kind.Hdmi,Kind.DisplayPort->"input_hdmi"
                                Kind.Tuner->"input_tuner"
                                Kind.Av->"input_av"
                                Kind.Other,Kind.Usb->"monitor"
                                null->"settings"
                            }
                            setCompoundDrawablesRelative(AppIcons.Vector(icon){currentTextColor}.apply{setBounds(0,0,dp(18),dp(18))},null,null,null)
                            compoundDrawablePadding=dp(8)
                        },LinearLayout.LayoutParams(0,-2,1f))
                        val status=input?.deviceName?:if(input?.connected==true)"已连接"else null
                        if(status!=null)addView(TextView(activity).apply{
                            text=status
                            setPadding(dp(8),0,0,0)
                            maxWidth=dp(95)
                            maxLines=1
                            ellipsize=android.text.TextUtils.TruncateAt.END
                            NativeUi.textSize(this,NativeUi.BODY_TEXT_SIZE)
                            setTextColor(GlassUi.ink(activity))
                        },LinearLayout.LayoutParams(-2,-2))
                    }
                }
            }
            list.setOnItemClickListener{_,_,index,_->
                if(index==inputs.size){DesktopGuardService.sourceSwitchStarted();dialog.dismiss();inputPanel(activity);return@setOnItemClickListener}
                DesktopGuardService.sourceSwitchStarted()
                dialog.dismiss()
                val intent=inputs[index].intent
                val jmgoHome=intent.component==ComponentName("com.jmgo.tv","com.jmgo.tv.MainActivity")&&intent.hasExtra("inputSrc")
                if(jmgoHome)DesktopGuardService.allowStockLauncher()
                if(!open(activity,listOf(intent),false)){
                    if(jmgoHome)DesktopGuardService.allowStockLauncher(false)
                    inputPanel(activity)
                }
            }
            list.setSelection(0);list.requestFocus()
        }}
    }
    private fun inputPanel(context:Context){
        val hotkey=Intent("com.android.sky.SendHotKey")
        val receiver=runCatching{context.packageManager.queryBroadcastReceivers(hotkey,0)}.getOrDefault(emptyList())
            .mapNotNull{it.activityInfo}.firstOrNull{it.packageName.substringAfterLast('.')=="ccosservice"&&it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0&&it.exported&&it.enabled&&it.applicationInfo.enabled&&(it.permission.isNullOrEmpty()||context.checkCallingOrSelfPermission(it.permission)==PackageManager.PERMISSION_GRANTED)}
        if(receiver!=null){
            hotkey.component=ComponentName(receiver.packageName,receiver.name)
            hotkey.putExtra("specialKey",android.view.KeyEvent.KEYCODE_TV_INPUT)
            if(runCatching{context.sendBroadcast(hotkey)}.isSuccess)return
        }
        changhongInputPanel(context)?.let{intent->if(runCatching{context.startService(intent)}.getOrNull()!=null)return}
        if(DeviceAdapters.xgimiPage(context,"Settings://com.xgimi.settings.signalSource/hdmi_misckey"))return
        if(open(context,listOf(Intent("android.settings.TV_INPUT_SETTINGS"),Intent("android.intent.action.TV_INPUT_SETTINGS"),Intent("coocaa.intent.action.SOURCE_DIALOG")),false))return
        for(intent in listOf(Intent("com.tcl.insrc.SHOW_WINDOW").setPackage("com.tcl.settings"),Intent("com.hisense.tv.start_inputs").setComponent(ComponentName("com.hisense.tv.inputs","com.hisense.tv.InputsUIService")))){
            val info=runCatching{context.packageManager.resolveService(intent,0)?.serviceInfo}.getOrNull()
            if(info?.exported==true)try{if(Build.VERSION.SDK_INT>=26&&info.packageName=="com.hisense.tv.inputs")context.startForegroundService(intent)else context.startService(intent);return}catch(_:RuntimeException){}
        }
        // Aerial 5d52b73: MStar tvsetting's input list is the panel the
        // remote's input key shows; SOURCE_INFO below is only a banner on
        // some MStar sets. Only an exported one.
        val mstarList=ComponentName("com.mstar.tvsetting","com.mstar.tvsetting.hotkey.InputSourceListViewActivity")
        if(runCatching{context.packageManager.getActivityInfo(mstarList,0)}.getOrNull()?.exported==true&&runCatching{context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(mstarList).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.isSuccess)return
        // Aerial 829cb68: LeTV EUI has no tv_input service; its source app is
        // SignalSourceManager. Prime the desktop tags first so it does not
        // crash on them, then launch it fresh (no action, NEW_TASK |
        // CLEAR_TASK), the way Dangbei's launcher does.
        val letvSources=ComponentName("com.stv.signalsourcemanager","com.stv.signalsourcemanager.MainActivity")
        if(runCatching{context.packageManager.getActivityInfo(letvSources,0)}.getOrNull()?.exported==true){
            InputRoutes.primeLetvDesktopTags()
            if(runCatching{context.startActivity(Intent().setComponent(letvSources).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}.isSuccess)return
        }
        val mstar=Intent("com.mstar.tv.tvplayer.ui.intent.action.SOURCE_INFO").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(runCatching{context.packageManager.resolveActivity(mstar,0)?.activityInfo}.getOrNull()?.exported==true&&runCatching{context.startActivity(mstar)}.isSuccess)return
        // Aerial 44285e6: YunOS sets (Hisense/Haier HS350) list inputs but no
        // passthrough intent answers; the system TVSource list is behind
        // this URI. Only where it resolves to an exported activity.
        val alliance=Intent(Intent.ACTION_VIEW,android.net.Uri.parse("alliance://start_signal")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(runCatching{context.packageManager.resolveActivity(alliance,0)?.activityInfo}.getOrNull()?.exported==true&&runCatching{context.startActivity(alliance)}.isSuccess)return
        NativeUi.notice(context,"设备未提供可用的信号源入口")
    }
    private fun changhongInputPanel(context:Context):Intent?{
        val intent=Intent("CH_MENU_SOURCE").setComponent(ComponentName("com.changhong.SourceMenu","com.changhong.source.SourceMenuService")).putExtra("MenuSource","menuSource").putExtra("keyCode",2001)
        return intent.takeIf{
            runCatching{
                val info=context.packageManager.resolveService(it,0)?.serviceInfo
                info!=null&&info.exported&&info.enabled&&info.applicationInfo.enabled&&(info.permission.isNullOrEmpty()||context.checkCallingOrSelfPermission(info.permission)==PackageManager.PERMISSION_GRANTED)
            }.getOrDefault(false)
        }
    }
    private fun wifiIcon(context:Context):String {
        val level=runCatching{
            val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val rssi=wifi.connectionInfo.rssi
            if(rssi in -126..0)android.net.wifi.WifiManager.calculateSignalLevel(rssi,4)else 3
        }.getOrDefault(3)
        return "wifi"+level
    }
    fun quickActions(activity:Activity):LinearLayout {
        val unit=NativeUi.scale(activity);fun dp(n:Int)=(n*2*unit).toInt()
        val body=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        val net=network(activity)
        val iconSize=(36*unit).toInt()
        val buttonWidth=(android.graphics.Paint().apply{textSize=NativeUi.BODY_TEXT_SIZE*unit}.measureText("系统设置")+iconSize+dp(26)).toInt()
        fun row(title:String,icon:String,action:()->Unit):Button{
            val b=Button(activity).apply{
                text=title;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
                NativeUi.textSize(this,NativeUi.BODY_TEXT_SIZE);gravity=Gravity.CENTER;includeFontPadding=false
                setPadding(dp(10),0,dp(10),0);maxLines=1;setTextColor(GlassUi.colors(activity))
                if(Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null}
                background=GlassUi.surface(this,20f,faint=true)
                val image=AppIcons.Vector(icon){currentTextColor}.apply{setBounds(0,0,iconSize,iconSize)}
                setCompoundDrawablesRelative(image,null,null,null);compoundDrawablePadding=dp(6)
                val inset=((buttonWidth-paint.measureText(title)-iconSize-compoundDrawablePadding)/2).toInt().coerceAtLeast(0)
                setPadding(inset,0,inset,0)
                setOnClickListener{NativeUi.selectControl(this);action()};NativeUi.touchClicks(this)
            }
            body.addView(b,LinearLayout.LayoutParams(buttonWidth,-1).apply{if(body.childCount>0)leftMargin=dp(6)})
            return b
        }
        row("信号源","monitor"){openSources(activity)}
        val netButton=row(if(net=="网络已连接")"网络"else net,when(net){"有线网络"->"ethernet";"未连接"->"network_off";"Wi-Fi"->wifiIcon(activity);"移动网络"->"cellular";else->"monitor"}){
            if(!DeviceAdapters.xgimiPage(activity,"Settings://com.xgimi.settings.net/wifi_misckey"))open(activity,networkIntents(activity))
        }
        // The header is built once, so without this the network button kept
        // showing the state from when the settings page was opened. Follow
        // connectivity changes while the header is on screen.
        var shown=""
        fun refreshNet(){
            val n=network(activity);val label=if(n=="网络已连接")"网络" else n
            val icon=when(n){"有线网络"->"ethernet";"未连接"->"network_off";"Wi-Fi"->wifiIcon(activity);"移动网络"->"cellular";else->"monitor"}
            // Capability/link callbacks fire in bursts (Wi-Fi signal jitter, hand-overs); only touch the
            // view when what it shows actually changes.
            if("$label|$icon"==shown)return
            shown="$label|$icon"
            netButton.text=label
            netButton.setCompoundDrawablesRelative(AppIcons.Vector(icon){netButton.currentTextColor}.apply{setBounds(0,0,iconSize,iconSize)},null,null,null)
            val inset=((buttonWidth-netButton.paint.measureText(label)-iconSize-netButton.compoundDrawablePadding)/2).toInt().coerceAtLeast(0)
            netButton.setPadding(inset,0,inset,0)
        }
        // Settle for a moment before reading: during a Wi-Fi/Ethernet hand-over the old network is lost
        // a beat before the new default is announced, which would flash "未连接".
        val settle=Runnable{refreshNet()}
        fun scheduleRefresh(){body.removeCallbacks(settle);body.postDelayed(settle,400)}
        val manager=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if(Build.VERSION.SDK_INT>=21&&manager!=null){
            val callback=object:ConnectivityManager.NetworkCallback(){
                override fun onAvailable(network:android.net.Network){scheduleRefresh()}
                override fun onLost(network:android.net.Network){scheduleRefresh()}
                override fun onCapabilitiesChanged(network:android.net.Network,capabilities:NetworkCapabilities){scheduleRefresh()}
                override fun onLinkPropertiesChanged(network:android.net.Network,linkProperties:android.net.LinkProperties){scheduleRefresh()}
            }
            body.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:android.view.View){
                    refreshNet()
                    // Track the default network (what network() reads): the
                    // default-network callback fires on every Wi-Fi/cellular/
                    // ethernet hand-over; an empty NetworkRequest could be
                    // filtered differently by vendor firmwares.
                    runCatching{if(Build.VERSION.SDK_INT>=24)manager.registerDefaultNetworkCallback(callback) else manager.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),callback)}
                }
                override fun onViewDetachedFromWindow(v:android.view.View){body.removeCallbacks(settle);runCatching{manager.unregisterNetworkCallback(callback)}}
            })
        }else{
            val receiver=object:android.content.BroadcastReceiver(){override fun onReceive(c:Context,intent:Intent){scheduleRefresh()}}
            body.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:android.view.View){runCatching{activity.registerReceiver(receiver,android.content.IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))}}
                override fun onViewDetachedFromWindow(v:android.view.View){body.removeCallbacks(settle);runCatching{activity.unregisterReceiver(receiver)}}
            })
        }
        // Aerial #1528: LeTV's own settings lead where installed (the
        // explicit component resolves nowhere else); EUI has no usable
        // AOSP settings page.
        row("系统设置","settings"){open(activity,listOfNotNull(Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.stv.globalsetting","com.stv.globalsetting.activity.SettingActivity")),fengOsSettings(activity),ChanghongSettings.intent(activity),Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.konka.systemsetting","com.konka.systemsetting.MainActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.konka.tvsettings","com.konka.settingcenter.settings.view.MainSettingsActivity"))))}
        return body
    }
    @android.annotation.TargetApi(21)
    private object InputFramework {
        fun list(context:Context):List<Input> = runCatching{
            val manager=context.getSystemService(Context.TV_INPUT_SERVICE) as android.media.tv.TvInputManager
            val profile=DeviceAdapters.profile(context)
            val all=manager.tvInputList
            val children=if(Build.VERSION.SDK_INT>=24)all.filter{it.parentId!=null}.groupBy{it.parentId}else emptyMap()
            val sources=all.filter{input->
                (Build.VERSION.SDK_INT<24 || input.parentId==null) && (input.isPassthroughInput ||
                    input.type==android.media.tv.TvInputInfo.TYPE_TUNER &&
                    input.serviceInfo.applicationInfo.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0) &&
                    (Build.VERSION.SDK_INT<24 || !runCatching{input.isHidden(context)}.getOrDefault(false)) &&
                    DeviceAdapters.hardware(input.id) !in profile.hidden
            }.mapNotNull{input->
                val hardware=DeviceAdapters.hardware(input.id)
                val kind=when(input.type){
                    android.media.tv.TvInputInfo.TYPE_HDMI -> Kind.Hdmi
                    android.media.tv.TvInputInfo.TYPE_DISPLAY_PORT -> Kind.DisplayPort
                    android.media.tv.TvInputInfo.TYPE_TUNER -> Kind.Tuner
                    android.media.tv.TvInputInfo.TYPE_COMPOSITE,android.media.tv.TvInputInfo.TYPE_SVIDEO,android.media.tv.TvInputInfo.TYPE_COMPONENT -> Kind.Av
                    else -> Kind.Other
                }
                val label=hardware?.let{profile.labels[it]}?:(if(Build.VERSION.SDK_INT>=24)runCatching{input.loadCustomLabel(context)?.toString()}.getOrNull()else null)
                    ?:runCatching{input.loadLabel(context).toString()}.getOrNull()?.takeIf{it.isNotBlank()}?:return@mapNotNull null
                fun connected(id:String)=runCatching{manager.getInputState(id)==android.media.tv.TvInputManager.INPUT_STATE_CONNECTED}.getOrDefault(false)
                val cec=children[input.id]?.firstOrNull{connected(it.id)}
                val name=cec?.let{runCatching{it.loadLabel(context).toString()}.getOrNull()}
                    ?.takeIf{it.isNotBlank()&&!it.equals(label,true)&&!genericCec.matches(it)}
                val uri=if(kind==Kind.Tuner)android.media.tv.TvContract.buildChannelsUriForInput(input.id)
                    else android.media.tv.TvContract.buildChannelUriForPassthroughInput(input.id)
                val portIntent=Intent(Intent.ACTION_VIEW,uri)
                val cecIntent=cec?.let{Intent(Intent.ACTION_VIEW,android.media.tv.TvContract.buildChannelUriForPassthroughInput(it.id))}
                val route=InputRoutes.route(context,input.id,hardware,input.type,label,profile,cecIntent?.takeIf{resolves(context,it)}?:portIntent)
                Input(input.id,label,route,kind,kind!=Kind.Tuner&&(cec!=null||connected(input.id)),name)
            }
            val shared=sources.filter{it.kind==Kind.Tuner}.groupingBy{it.label}.eachCount()
            sources.map{source->
                if(source.kind!=Kind.Tuner||(shared[source.label]?:0)<2)source else {
                    val service=source.id.substringBefore("/HW").lowercase()
                    when {
                        "analog" in service||"atv" in service -> source.copy(label="Tuner (ATV)")
                        "digital" in service||"dvb" in service||"dtv" in service||"dtmb" in service -> source.copy(label="Tuner (DTV)")
                        else -> source
                    }
                }
            }.sortedWith(compareBy<Input>{it.kind.ordinal}.thenBy{it.label})
        }.getOrDefault(emptyList())
        private val genericCec=Regex("(?i)(HDMI|CEC|Playback|Recorder|Tuner)([ _-]*[0-9]+)?")
    }
}
