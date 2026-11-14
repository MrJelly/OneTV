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
        val info=(context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetworkInfo
        return when{info?.isConnected!=true->"未连接";info.type==ConnectivityManager.TYPE_WIFI->"Wi-Fi";info.type==ConnectivityManager.TYPE_ETHERNET->"有线网络";else->"网络已连接"}
    }
    private val fengOsPanel=ComponentName("cn.formovie.fengos.settings","cn.formovie.fengos.settings.ui.activity.MainActivity")
    private fun fengOsSettings(context:Context)=Intent(Intent.ACTION_MAIN).setComponent(fengOsPanel).takeIf{resolves(context,it)}

    fun networkIntents(context:Context):List<Intent> {
        val wired=network(context)=="有线网络"
        return listOfNotNull(
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
    fun sources(context:Context):List<Input> = (if(Build.VERSION.SDK_INT>=21)InputFramework.list(context)else emptyList()).ifEmpty{XgimiInputAdapter.load(context)}.ifEmpty{KonkaInputAdapter.load(context)}.ifEmpty{HisenseInputAdapter.load(context)}.ifEmpty{HisenseInputAdapter.loadLegacy(context)}.ifEmpty{InputRoutes.skyworth(context)}.take(64)
    fun openSources(activity:Activity){
        val unit=NativeUi.scale(activity);fun dp(n:Int)=(n*2*unit).toInt()
        val body=android.widget.LinearLayout(activity).apply{orientation=android.widget.LinearLayout.VERTICAL}
        val loading=android.widget.TextView(activity).apply{text="正在读取信号源…";gravity=android.view.Gravity.CENTER;NativeUi.textSize(this,30f);setTextColor(GlassUi.ink(activity));setPadding(0,dp(16),0,dp(16))}
        body.addView(loading,android.widget.LinearLayout.LayoutParams(-1,-2))
        val list=android.widget.ListView(activity).apply{divider=null;dividerHeight=0;isVerticalScrollBarEnabled=false;visibility=android.view.View.GONE;selector=GlassUi.selectedBorder(this,18f)}
        body.addView(list,android.widget.LinearLayout.LayoutParams(-1,dp(250)))
        val dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("信号源").setView(body).setNegativeButton("关闭",null))
        Thread({val inputs=sources(activity.applicationContext);activity.runOnUiThread{
            if(!dialog.isShowing||activity.isFinishing||activity.isDestroyed)return@runOnUiThread
            if(inputs.isEmpty()){dialog.dismiss();inputPanel(activity);return@runOnUiThread}
            loading.visibility=android.view.View.GONE;list.visibility=android.view.View.VISIBLE
            list.adapter=object:android.widget.ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,inputs.map{it.label}){
                override fun getView(position:Int,convert:android.view.View?,parent:android.view.ViewGroup):android.view.View=
                    android.widget.TextView(activity).apply{text=getItem(position);gravity=android.view.Gravity.CENTER_VERTICAL;NativeUi.textSize(this,30f);setTextColor(GlassUi.ink(activity));setPadding(dp(12),0,dp(12),0);minHeight=dp(38)}
            }
            list.setOnItemClickListener{_,_,index,_->
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
        }},"input-query").start()
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
        val mstar=Intent("com.mstar.tv.tvplayer.ui.intent.action.SOURCE_INFO").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(runCatching{context.packageManager.resolveActivity(mstar,0)?.activityInfo}.getOrNull()?.exported==true&&runCatching{context.startActivity(mstar)}.isSuccess)return
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
    fun quickActions(activity:Activity):LinearLayout {
        val unit=NativeUi.scale(activity);fun dp(n:Int)=(n*2*unit).toInt()
        val body=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        val net=network(activity)
        val buttonWidth=(android.graphics.Paint().apply{textSize=30*unit}.measureText("系统设置")+30*unit+dp(26)).toInt()
        fun row(title:String,icon:String,action:()->Unit){
            val b=Button(activity).apply{
                text=title;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
                NativeUi.textSize(this,30f);gravity=Gravity.CENTER;includeFontPadding=false
                setPadding(dp(10),0,dp(10),0);maxLines=1;setTextColor(GlassUi.colors(activity))
                if(Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null}
                background=GlassUi.surface(this,20f,faint=true)
                val image=AppIcons.Vector(icon){currentTextColor}.apply{setBounds(0,0,(30*unit).toInt(),(30*unit).toInt())}
                setCompoundDrawablesRelative(image,null,null,null);compoundDrawablePadding=dp(6)
                val inset=((buttonWidth-paint.measureText(title)-(30*unit).toInt()-compoundDrawablePadding)/2).toInt().coerceAtLeast(0)
                setPadding(inset,0,inset,0)
                setOnClickListener{NativeUi.selectControl(this);action()};NativeUi.touchClicks(this)
            }
            body.addView(b,LinearLayout.LayoutParams(buttonWidth,-1).apply{if(body.childCount>0)leftMargin=dp(6)})
        }
        row("信号源","monitor"){openSources(activity)}
        row(if(net=="网络已连接")"网络"else net,if(net=="有线网络")"ethernet"else "wifi"){
            if(!DeviceAdapters.xgimiPage(activity,"Settings://com.xgimi.settings.net/wifi_misckey"))open(activity,networkIntents(activity))
        }
        row("系统设置","settings"){open(activity,listOfNotNull(fengOsSettings(activity),ChanghongSettings.intent(activity),Intent(Settings.ACTION_SETTINGS)))}
        return body
    }
    @android.annotation.TargetApi(21)
    private object InputFramework {
        fun list(context:Context):List<Input> = runCatching{
            val manager=context.getSystemService(Context.TV_INPUT_SERVICE) as android.media.tv.TvInputManager
            val profile=DeviceAdapters.profile(context)
            manager.tvInputList.filter{it.isPassthroughInput&&(Build.VERSION.SDK_INT<24||(it.parentId==null&&!it.isHidden(context)))}.filter{DeviceAdapters.hardware(it.id) !in profile.hidden}.map{input->
                val hardware=DeviceAdapters.hardware(input.id)
                val generic=Intent(Intent.ACTION_VIEW,android.media.tv.TvContract.buildChannelUriForPassthroughInput(input.id))
                val label=hardware?.let{profile.labels[it]}?:(if(Build.VERSION.SDK_INT>=24)input.loadCustomLabel(context)?.toString()else null)?:input.loadLabel(context).toString()
                val route=InputRoutes.route(context,input.id,hardware,input.type,label,profile,generic)
                Input(input.id,label,route)
            }
        }.getOrDefault(emptyList())
    }
}
