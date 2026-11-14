package com.tvbridge.app

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.os.*
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

object DesktopTakeover {
    private val recovery=Handler(Looper.getMainLooper())
    private var recoveryPending=false
    private var lastRebind=-60000L
    private var lastRescan=-60000L
    private fun rescanIfMissing(context:Context){
        if(Build.VERSION.SDK_INT>22||!enabled(context)||SystemClock.elapsedRealtime()-lastRescan<60000)return
        val me=ComponentName(context,DesktopGuardService::class.java)
        val installed=runCatching{(context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).installedAccessibilityServiceList.any{info->info.resolveInfo?.serviceInfo?.let{ComponentName(it.packageName,it.name)==me}==true}}.getOrNull()?:return
        if(installed)return
        val pm=context.packageManager;val marker=ComponentName(context,BootReceiver::class.java)
        val before=pm.getComponentEnabledSetting(marker)
        runCatching{
            pm.setComponentEnabledSetting(marker,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP)
            lastRescan=SystemClock.elapsedRealtime()
            recovery.postDelayed({runCatching{pm.setComponentEnabledSetting(marker,before,android.content.pm.PackageManager.DONT_KILL_APP)}},1500)
        }
    }
    private var revivalNoted=false
    private var standbyWatch:BroadcastReceiver?=null
    private var standbyOut=false
    fun noteRevival(context:Context){
        if(!wanted(context)||revivalNoted||SystemClock.elapsedRealtime()<120000)return
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if(!(if(Build.VERSION.SDK_INT>=21)power.isInteractive else power.isScreenOn))return
        val updated=runCatching{context.packageManager.getPackageInfo(context.packageName,0).lastUpdateTime}.getOrDefault(0)
        if(System.currentTimeMillis()-updated in 0..120000)return
        revivalNoted=true;val now=System.currentTimeMillis()
        prefs(context).edit().putString("guard_revivals",(revivals(context)+now).joinToString(",")).apply()
    }
    private fun revivals(context:Context):List<Long>{
        val now=System.currentTimeMillis();val boot=now-SystemClock.elapsedRealtime()
        return prefs(context).getString("guard_revivals","").orEmpty().split(',').mapNotNull{it.toLongOrNull()}.filter{it>=boot&&now-it in 0..600000}
    }
    fun revivedOften(context:Context)=unlocked(context)&&revivals(context).size>=3
    private fun removeGuard(context:Context){
        val resolver=context.contentResolver;val me=ComponentName(context,DesktopGuardService::class.java)
        if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
        runCatching{val others=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter{it.isNotBlank()&&ComponentName.unflattenFromString(it)!=me};Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,others.joinToString(":"))}
    }
    fun suspendForStandby(context:Context){
        if(!wanted(context)||!DesktopPolicy.standbyNeedsUnbind())return
        val app=context.applicationContext;standbyOut=true
        if(standbyWatch==null){
            val watch=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
                if(i.action!=Intent.ACTION_SCREEN_ON)return
                runCatching{app.unregisterReceiver(this)};standbyWatch=null;standbyOut=false;recover(app)
            }}
            standbyWatch=watch
            androidx.core.content.ContextCompat.registerReceiver(app,watch,IntentFilter(Intent.ACTION_SCREEN_ON),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        removeGuard(app)
    }
    private var unlockWatch:BroadcastReceiver?=null
    fun unlocked(context:Context)=Build.VERSION.SDK_INT<24||(context.getSystemService(Context.USER_SERVICE) as? UserManager)?.isUserUnlocked!=false
    fun afterUnlock(context:Context){
        if(unlocked(context)){recover(context);return}
        if(unlockWatch!=null)return
        val app=context.applicationContext
        val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
            if(i.action!=Intent.ACTION_USER_UNLOCKED)return
            runCatching{app.unregisterReceiver(this)};unlockWatch=null;recover(app)
        }}
        unlockWatch=receiver
        androidx.core.content.ContextCompat.registerReceiver(app,receiver,IntentFilter(Intent.ACTION_USER_UNLOCKED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun recover(context:Context){
        val app=context.applicationContext
        if(!unlocked(app)){afterUnlock(app);return}
        if(!wanted(app))return
        rescanIfMissing(app)
        if(!ensureGuard(app)&&!enabled(app))return
        DesktopRecovery.ensure(app)
        if(DesktopGuardService.connected||recoveryPending)return
        recoveryPending=true
        recovery.postDelayed({
            recoveryPending=false
            rebindIfStalled(app)
        },5000)
    }
    internal fun rebindIfStalled(context:Context,unresponsive:Boolean=false){
        if(!wanted(context)||standbyOut||(!unresponsive&&DesktopGuardService.connected)||!enabled(context)||SystemClock.elapsedRealtime()-lastRebind<60000)return
        if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
        val resolver=context.contentResolver
        val me=ComponentName(context,DesktopGuardService::class.java)
        fun others()=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter{it.isNotBlank()&&ComponentName.unflattenFromString(it)!=me}
        runCatching{
            val reset=unresponsive&&Build.VERSION.SDK_INT<=23&&others().isEmpty()
            Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,others().joinToString(":"))
            if(reset)Settings.Secure.putInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)
            lastRebind=SystemClock.elapsedRealtime()
            recovery.postDelayed({
                if(wanted(context)&&!standbyOut)runCatching{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(others()+me.flattenToString()).joinToString(":"))}
                if(reset&&Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().isNotBlank())runCatching{Settings.Secure.putInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,1)}
            },1500)
        }
    }
    const val ATTEMPT="com.tvbridge.app.BOOT_ATTEMPT"
    private const val REQUEST=7130
    private val delays=longArrayOf(4000,12000,25000)
    private fun prefs(context:Context)=context.getSharedPreferences("startup",0)
    fun wanted(context:Context)=unlocked(context)&&prefs(context).getBoolean("desktop_guard",false)
    fun enabled(context:Context):Boolean {
        val component=ComponentName(context,DesktopGuardService::class.java)
        return Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any{ComponentName.unflattenFromString(it)==component}
    }
    fun configured(context:Context)=defaultHome(context)?.packageName==context.packageName||
        (wanted(context)&&enabled(context))
    fun label(context:Context)=when{!wanted(context)->"标准启动";!enabled(context)->"待授权";DesktopGuardService.connected->"兼容接管已连接";else->"兼容接管待连接"}
    private fun defaultHome(context:Context)=context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.let{ComponentName(it.packageName,it.name)}
    private fun originalHome(context:Context):ComponentName? {
        val saved=prefs(context).getString("previous_home",null)?.let{ComponentName.unflattenFromString(it)}?.takeUnless{it.packageName=="android"||it.className.endsWith("FallbackHome")||it.className.endsWith("ResolverActivity")}
        if(saved!=null&&saved.packageName!=context.packageName&&runCatching{context.packageManager.getActivityInfo(saved,0).enabled}.getOrDefault(false))return saved
        return context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)
            .filter{it.activityInfo.packageName!=context.packageName&&it.activityInfo.enabled&&it.activityInfo.exported}
            .sortedByDescending{runCatching{context.packageManager.getApplicationInfo(it.activityInfo.packageName,0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0}.getOrDefault(false)}
            .firstOrNull()?.activityInfo?.let{ComponentName(it.packageName,it.name)}
    }
    private fun homeEntry(context:Context,enable:Boolean){
        context.packageManager.setComponentEnabledSetting(ComponentName(context,"com.tvbridge.app.DesktopActivity"),if(enable)android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP)
    }
    fun returnsAfterStandby(context:Context)=prefs(context).getBoolean("desktop_wake",true)

    private fun enableHomeEntry(activity:Activity){
        val previous=defaultHome(activity)?.takeIf{it.packageName!=activity.packageName&&it.packageName!="android"&&!it.className.endsWith("FallbackHome")}?:originalHome(activity)
        if(previous!=null)prefs(activity).edit().putString("previous_home",previous.flattenToString()).apply()
        homeEntry(activity,true)
    }

    fun settings(activity:Activity,chooseHome:()->Unit,changed:()->Unit){
        val preferences=prefs(activity)
        fun guarding()=preferences.getBoolean("desktop_guard",false)
        fun guardLabel()=when{
            !guarding()->"关闭"
            DesktopGuardService.connected->"已连接"
            enabled(activity)->"正在连接"
            else->"待授权"
        }
        val labels=listOf("桌面守护","选择默认桌面","待机唤醒返回 One","还原原桌面")
        val body=android.widget.LinearLayout(activity).apply{orientation=android.widget.LinearLayout.VERTICAL}
        fun refresh(){
            val values=listOf(guardLabel(),
                if(defaultHome(activity)?.packageName==activity.packageName)"One" else "系统设置",
                if(returnsAfterStandby(activity))"开启" else "关闭")
            values.forEachIndexed{index,value->
                val button=body.getChildAt(index) as android.widget.Button
                button.setCompoundDrawablesRelative(null,null,NativeUi.trailing(button,value,ink=true),null)
            }
            changed()
        }
        labels.forEachIndexed{index,label->
            val button=NativeUi.optionButton(activity,label)
            body.addView(button,android.widget.LinearLayout.LayoutParams(-1,NativeUi.settingsRowHeight(activity)).apply{bottomMargin=NativeUi.settingsRowGap(activity)})
            button.setOnClickListener{
            when(index){
                0->if(guarding()&&DesktopGuardService.connected){
                    preferences.edit().putBoolean("desktop_guard",false).putBoolean("boot",false).remove("desktop_setup_pending").remove("desktop_setup_step").apply()
                    cancel(activity)
                    removeGuard(activity)
                    refresh()
                }else{
                    enableHomeEntry(activity)
                    preferences.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).putBoolean("desktop_setup_pending",true).remove("desktop_setup_step").apply()
                    finishSetup(activity,::refresh)
                }
                1->{enableHomeEntry(activity);chooseHome()}
                2->{
                    val next=!returnsAfterStandby(activity)
                    preferences.edit().putBoolean("desktop_wake",next).apply()
                    if(next&&!guarding())NativeUi.notice(activity,"请开启桌面守护，以便待机后返回 One")
                    refresh()
                }
                3->restore(activity,::refresh)
            }
            }
        }
        refresh()
        val dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("桌面应用").setView(body)
            .setNegativeButton("关闭",null),widthDp=300,initialFocus=body.getChildAt(0))
        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener{focused->if(focused&&dialog.isShowing)refresh()}
    }
    fun finishSetup(activity:Activity,changed:()->Unit){
        if(!prefs(activity).getBoolean("desktop_setup_pending",false))return
        val ready=ensureGuard(activity)||enabled(activity)
        changed()
        fun request(step:String,intent:Intent,message:String){
            val p=prefs(activity)
            if(p.getString("desktop_setup_step",null)==step){p.edit().putBoolean("desktop_setup_pending",false).apply();NativeUi.notice(activity,"授权尚未完成，可再次设置桌面");return}
            p.edit().putString("desktop_setup_step",step).apply();NativeUi.notice(activity,message)
            try{activity.startActivity(intent)}catch(_:RuntimeException){p.edit().putBoolean("desktop_setup_pending",false).apply();NativeUi.notice(activity,"设备未提供此授权入口")}
        }
        if(!ready){
            request("accessibility",Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),"请开启 One 桌面接管")
        }else if(Build.VERSION.SDK_INT>=29&&!GlobalPointer.permitted(activity)){
            request("overlay",Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:${activity.packageName}")),"请允许 One 后台显示")
        }else if(Build.VERSION.SDK_INT>=21&&!DesktopPolicy.usageAllowed(activity)){
            request("usage",Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),"请允许 One 识别前台应用，以免接管正在使用的应用")
        }else {
            prefs(activity).edit().putBoolean("desktop_setup_pending",false).remove("desktop_setup_step").apply()
            recover(activity);NativeUi.notice(activity,if(DesktopGuardService.connected)"桌面设置已保存"else "正在连接桌面守护")
            DesktopRecovery.offer(activity)
        }
    }
    private fun restore(activity:Activity,changed:()->Unit){
        val target=originalHome(activity)
        if(prefs(activity).contains("desktop_vt_previous")){
            NativeUi.notice(activity,"正在还原系统桌面配置")
            Thread({
                val ready=runCatching{Hub.adb.ready||(Hub.adb.enabled()&&Hub.adb.restore())}.getOrDefault(false)
                if(ready&&target!=null)Hub.adb.setDefaultHome(target)
                activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed){
                    if(!prefs(activity).contains("desktop_vt_previous"))restore(activity,changed)
                    else NativeUi.notice(activity,if(ready)"固件尚未完成还原，请重试"else "请恢复设备调试连接后再还原桌面")
                }}
            },"desktop-vendor-restore").start();return
        }
        prefs(activity).edit().putBoolean("boot",false).putBoolean("desktop_guard",false).remove("desktop_setup_pending").apply()
        cancel(activity);DesktopGuardService.allowStockLauncher()
        activity.packageManager.clearPackagePreferredActivities(activity.packageName)
        homeEntry(activity,false)
        Thread({
            if(target!=null&&runCatching{Hub.adb.ready}.getOrDefault(false))Hub.adb.setDefaultHome(target)
            activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed){
                changed()
                if(defaultHome(activity)?.packageName==activity.packageName){
                    try{activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS));NativeUi.notice(activity,"请选择原来的桌面")}catch(_:RuntimeException){NativeUi.notice(activity,"已停止接管，请在系统设置选择原桌面")}
                }else try{
                    val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if(target!=null)intent.component=target
                    activity.startActivity(intent)
                }catch(_:RuntimeException){NativeUi.notice(activity,"已停止 One 桌面接管")}
            }}
        },"desktop-restore").start()
    }
    private fun pending(context:Context,token:String="",step:Int=0)=PendingIntent.getBroadcast(context,REQUEST,Intent(context,BootAttemptReceiver::class.java).setAction(ATTEMPT).putExtra("token",token).putExtra("step",step),PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=23)PendingIntent.FLAG_IMMUTABLE else 0)
    fun cancel(context:Context){
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context));prefs(context).edit().remove("boot_token").apply()
        if(!wanted(context)){
            standbyOut=false;standbyWatch?.let{runCatching{context.applicationContext.unregisterReceiver(it)}};standbyWatch=null
            removeGuard(context);DesktopRecovery.stop(context)
        }
    }
    fun schedule(context:Context){
        cancel(context)
        val token=java.util.UUID.randomUUID().toString()
        prefs(context).edit().putString("boot_token",token).putLong("boot_started_elapsed",SystemClock.elapsedRealtime()).apply()
        scheduleStep(context,token,0)
    }
    private fun scheduleStep(context:Context,token:String,step:Int){
        val start=prefs(context).getLong("boot_started_elapsed",SystemClock.elapsedRealtime())
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setWindow(AlarmManager.ELAPSED_REALTIME,start+delays[step],1000,pending(context,token,step))
    }
    fun attempt(context:Context,intent:Intent){
        if(InstallHandoff.active(context)){cancel(context);return}
        val p=prefs(context);val token=intent.getStringExtra("token");val step=intent.getIntExtra("step",-1)
        if(!p.getBoolean("boot",false)||token.isNullOrEmpty()||token!=p.getString("boot_token",null)||step !in delays.indices)return
        if(SystemClock.elapsedRealtime()-p.getLong("boot_started_elapsed",0)>90000){cancel(context);return}
        if(Hub.activity?.visible==true||BrowserActivity.active!=null){BootReceiver.markArrived(context);return}
        if(DesktopGuardService.connected&&wanted(context)){DesktopGuardService.onBootCompleted();cancel(context);return}
        if(Build.VERSION.SDK_INT>=21){
            val front=if(DesktopPolicy.usageAllowed(context))DesktopForeground.latest(context,30000)?.first else null
            if(front!=null&&front.packageName!=context.packageName&&!front.className.endsWith("FallbackHome")&&!isStockHome(context,front)&&!DesktopPolicy.bootCover(front)){
                p.edit().putString("boot_status","foreground_preserved").apply();cancel(context);return
            }
        }
        try{
            p.edit().putString("boot_status","launch_requested").putInt("boot_attempt",step+1).apply()
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("from_boot",true))
        }catch(e:RuntimeException){p.edit().putString("boot_status","launch_rejected").putString("boot_error",e.javaClass.simpleName).apply()}
        if(step+1<delays.size)scheduleStep(context,token,step+1)else p.edit().remove("boot_token").apply()
    }
    fun isStockHome(context:Context,component:ComponentName)=homes(context).contains(component)
    fun homes(context:Context):Set<ComponentName> {
        val pm=context.packageManager
        val standard=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)
        val vendor=runCatching{pm.queryIntentActivities(Intent(Intent.ACTION_MAIN),android.content.pm.PackageManager.GET_RESOLVED_FILTER).filter{entry->
            entry.filter?.categoriesIterator()?.asSequence()?.any{DesktopPolicy.vendorCategory(it)}==true
        }}.getOrDefault(emptyList())
        val resolved=defaultHome(context)?.packageName
        val homes=(standard+vendor).flatMap{info->val a=info.activityInfo;val component=ComponentName(a.packageName,a.name)
            if(a.packageName==context.packageName||!a.enabled||a.packageName in DesktopPolicy.notLaunchers||
                (a.packageName in DesktopPolicy.inputPlayers&&a.packageName!=resolved)||!DesktopPolicy.acceptsPriority(component,info.priority))emptyList()
            else listOfNotNull(component,a.targetActivity?.let{ComponentName(a.packageName,it)})
        }.toSet()
        return homes+DesktopPolicy.extras(context,homes)
    }
    fun ensureGuard(context:Context):Boolean {
        if(standbyOut)return false
        if(wanted(context)&&DesktopPolicy.standbyNeedsUnbind()){
            val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if(!(if(Build.VERSION.SDK_INT>=21)power.isInteractive else power.isScreenOn)){suspendForStandby(context);return false}
        }
        if(!wanted(context)||androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false
        val resolver=context.contentResolver;val me=ComponentName(context,DesktopGuardService::class.java)
        return runCatching{
            val entries=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter{it.isNotBlank()}
            if(entries.none{ComponentName.unflattenFromString(it)==me})Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(entries+me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,1);true
        }.getOrDefault(false)
    }

}

class BootAttemptReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==DesktopTakeover.ATTEMPT)DesktopTakeover.attempt(context,intent)}}
