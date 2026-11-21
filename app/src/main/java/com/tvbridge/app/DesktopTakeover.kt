package com.tvbridge.app

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.database.ContentObserver
import android.os.*
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

object DesktopTakeover {
    private val recovery = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var recoveryPending = false
    private var lastRebind = -60000L
    private var lastRescan = -60000L
    @Volatile private var rebinding = false
    private val handoverRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var setupPending=false
    @Volatile private var setupStep:String?=null
    private val setupSkipped=mutableSetOf<String>()
    private var enabledObserver: ContentObserver? = null
    private var lastWatchWrite = -60000L
    @Volatile private var homesCache:Set<ComponentName>?=null
    @Volatile private var homesCacheAt=0L
    @Volatile private var defaultHomeCache:ComponentName?=null
    @Volatile private var defaultHomeCacheAt=0L
    @Volatile private var defaultHomeCached=false

    fun watchEnabledList(context: Context) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val app = context.applicationContext
        val recheck = Runnable {
            AppThreads.onState {
                if (!wanted(app)) {
                    val toUnregister = synchronized(stateLock) {
                        val current = enabledObserver
                        enabledObserver = null
                        current
                    }
                    toUnregister?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
                } else {
                    DeviceProfile.invalidate(app)
                    val shouldWrite = synchronized(stateLock) {
                        !rebinding && !standbyOut && !enabled(app) &&
                            SystemClock.elapsedRealtime() - lastWatchWrite >= 60000
                    }
                    if (shouldWrite && ensureGuard(app)) {
                        synchronized(stateLock) { lastWatchWrite = SystemClock.elapsedRealtime() }
                    }
                }
            }
        }
        val obs = object : ContentObserver(recovery) {
            override fun onChange(selfChange: Boolean) {
                recovery.removeCallbacks(recheck)
                recovery.postDelayed(recheck, 1500)
            }
        }
        synchronized(stateLock) {
            if (enabledObserver != null) return
            val registered = runCatching {
                app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("enabled_notification_listeners"), false, obs)
                app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, obs)
            }.isSuccess
            if (!registered) {
                runCatching { app.contentResolver.unregisterContentObserver(obs) }
                return
            }
            enabledObserver = obs
        }
    }
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
    @Volatile private var standbyWatch:BroadcastReceiver?=null
    @Volatile private var standbyOut=false
    private var shutdownRecheck=false
    private val bootId:String? by lazy{runCatching{java.io.File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf{it.isNotEmpty()}}.getOrNull()}
    private fun noteShutdown(context:Context){
        if(!unlocked(context))return
        runCatching{prefs(context).edit().putLong("shutdown_announced_at",SystemClock.elapsedRealtime()).putString("shutdown_announced_boot_id",bootId).commit()}
    }
    private fun clearShutdown(context:Context){
        if(!unlocked(context))return
        if(prefs(context).contains("shutdown_announced_at"))prefs(context).edit().remove("shutdown_announced_at").remove("shutdown_announced_boot_id").apply()
    }
    private fun shuttingDown(context:Context):Boolean{
        if(!unlocked(context))return false
        val p=prefs(context)
        val at=p.getLong("shutdown_announced_at",0L)
        if(at==0L)return false
        if(p.getString("shutdown_announced_boot_id",null)!=bootId)return false
        return SystemClock.elapsedRealtime()-at in 0 until 120000L
    }
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
    fun stepOutForShutdown(service:AccessibilityService){
        if(!wanted(service)||!DesktopPolicy.dangbeiStandbyDisable())return
        noteShutdown(service)
        if(androidx.core.content.ContextCompat.checkSelfPermission(service,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
        waitForStandbyWake(service)
        if(Build.VERSION.SDK_INT>=24&&runCatching{service.disableSelf()}.isSuccess)return
        removeGuard(service)
    }
    fun suspendForStandby(context:Context){
        if(!wanted(context)||!DesktopPolicy.standbyNeedsUnbind())return
        waitForStandbyWake(context)
        removeGuard(context)
    }
    private fun waitForStandbyWake(context:Context){
        val app=context.applicationContext;standbyOut=true
        if(standbyWatch==null){
            val watch=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
                if(i.action!=Intent.ACTION_SCREEN_ON)return
                resumeAfterStandby(app)
                recover(app)
            }}
            standbyWatch=watch
            androidx.core.content.ContextCompat.registerReceiver(app,watch,IntentFilter(Intent.ACTION_SCREEN_ON),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
    fun resumeAfterStandby(context:Context){
        clearShutdown(context)
        val watch=standbyWatch
        standbyWatch=null
        standbyOut=false
        if(watch!=null)runCatching{context.applicationContext.unregisterReceiver(watch)}
    }
    private var unlockWatch:BroadcastReceiver?=null
    private var unlockPoll:Runnable?=null
    fun unlocked(context:Context)=Build.VERSION.SDK_INT<24||(context.getSystemService(Context.USER_SERVICE) as? UserManager)?.isUserUnlocked!=false
    fun afterUnlock(context:Context){
        if(unlocked(context)){recover(context);return}
        if(unlockWatch!=null)return
        val app=context.applicationContext
        fun done(){
            unlockWatch?.let{runCatching{app.unregisterReceiver(it)}};unlockWatch=null
            unlockPoll?.let{recovery.removeCallbacks(it)};unlockPoll=null
            recover(app)
        }
        val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
            if(i.action!=Intent.ACTION_USER_UNLOCKED)return
            done()
        }}
        unlockWatch=receiver
        androidx.core.content.ContextCompat.registerReceiver(app,receiver,IntentFilter(Intent.ACTION_USER_UNLOCKED),androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        val poll=object:Runnable{override fun run(){
            if(unlocked(app))done() else if(unlockWatch!=null)recovery.postDelayed(this,2000)
        }}
        unlockPoll=poll
        recovery.postDelayed(poll,2000)
    }
    fun reopenAdb(context:Context){
        if(!wanted(context))return
        if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
        runCatching{
            val resolver=context.contentResolver
            if(Settings.Global.getInt(resolver,Settings.Global.ADB_ENABLED,0)!=1)Settings.Global.putInt(resolver,Settings.Global.ADB_ENABLED,1)
        }
    }
    private var adbObserver: ContentObserver? = null
    private fun watchAdb(context: Context) {
        val app = context.applicationContext
        if (!wanted(app)) {
            val prev = synchronized(stateLock) { adbObserver.also { adbObserver = null } }
            prev?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
            return
        }
        val reopen = Runnable { reopenAdb(app) }
        val obs = object : ContentObserver(recovery) {
            override fun onChange(selfChange: Boolean) {
                recovery.removeCallbacks(reopen)
                recovery.postDelayed(reopen, 1500)
            }
        }
        synchronized(stateLock) {
            if (adbObserver != null) return
            if (runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, obs) }.isSuccess) {
                adbObserver = obs
            }
        }
    }
    fun recover(context: Context) {
        val app = context.applicationContext
        DeviceProfile.warm(app)
        if (!unlocked(app)) { afterUnlock(app); return }
        reopenAdb(app)
        watchAdb(app)
        if (!wanted(app)) return
        if (standbyOut) return
        if(DesktopPolicy.stockHomeMustRemainDefault(app)&&!DesktopPolicy.stockHomeBackgroundAllowed(app)){
            val p=prefs(app)
            if(!p.getBoolean("miui_home_setup_offered",false)){
                p.edit().putBoolean("miui_home_setup_offered",true).apply()
                setupPending=true
            }
        }
        restoreHomeEntry(app)
        restoreDisabledComponent(app, ComponentName(app, BootReceiver::class.java))
        if (SystemClock.elapsedRealtime() < 180000) {
            for (delay in longArrayOf(5000L, 15000L)) {
                recovery.postDelayed({
                    if (wanted(app)) {
                        restoreHomeEntry(app)
                        restoreDisabledComponent(app, ComponentName(app, BootReceiver::class.java))
                    }
                }, delay)
            }
        }
        rescanIfMissing(app)
        watchEnabledList(app)
        if (!ensureGuard(app) && !enabled(app)) return
        DesktopRecovery.ensure(app)
        val shouldSchedule = synchronized(stateLock) {
            if (DesktopGuardService.connected || recoveryPending) false
            else {
                recoveryPending = true
                true
            }
        }
        if (!shouldSchedule) return
        recovery.postDelayed({
            synchronized(stateLock) { recoveryPending = false }
            rebindIfStalled(app)
        }, 5000)
    }
    internal fun rebindIfStalled(context: Context, unresponsive: Boolean = false) {
        if (!wanted(context) || standbyOut || (!unresponsive && DesktopGuardService.connected) || !enabled(context)) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            if (now - lastRebind < 60000) return
            lastRebind = now
            rebinding = true
        }
        val resolver = context.contentResolver
        val me = ComponentName(context, DesktopGuardService::class.java)
        fun others() = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        runCatching {
            val reset = unresponsive && Build.VERSION.SDK_INT <= 23 && others().isEmpty()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others().joinToString(":"))
            if (reset) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            recovery.postDelayed({
                rebinding = false
                if (wanted(context) && !standbyOut) runCatching {
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others() + me.flattenToString()).joinToString(":"))
                }
                if (reset && Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().isNotBlank()) {
                    runCatching { Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) }
                }
            }, 1500)
        }.onFailure { rebinding = false }
    }
    private const val BOOT_APP_NOT_RESTORED="开机启动项仍指向 One：请连接 ADB 后再点一次「还原原桌面」，卸载前请先完成还原"
    const val ATTEMPT="com.tvbridge.app.BOOT_ATTEMPT"
    private const val REQUEST=7130
    private val delays=longArrayOf(4000,12000,25000)
    private fun prefs(context:Context)=context.getSharedPreferences("startup",0)
    fun wanted(context:Context)=unlocked(context)&&prefs(context).let{it.getBoolean("boot",true)&&it.getBoolean("desktop_guard",false)}
    fun enabled(context:Context):Boolean {
        val component=ComponentName(context,DesktopGuardService::class.java)
        return Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any{ComponentName.unflattenFromString(it)==component}
    }
    fun configured(context:Context)=TakeoverPolicy.configured(context)
    fun defaultHome(context:Context):ComponentName? {
        val now=SystemClock.elapsedRealtime()
        if(defaultHomeCached&&now-defaultHomeCacheAt<2000)return defaultHomeCache
        val found=runCatching{context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let{ComponentName(it.packageName,it.name)}}.getOrNull()
        defaultHomeCache=found;defaultHomeCacheAt=now;defaultHomeCached=true
        return found
    }
    private fun restoreHomeEntry(context:Context){
        if(DesktopPolicy.stockHomeMustRemainDefault(context)){
            if(DesktopPolicy.stockHomeBackgroundAllowed(context))homeEntry(context,false)
            return
        }
        val pm=context.packageManager;val entry=ComponentName(context,"com.tvbridge.app.DesktopActivity")
        runCatching{
            if(pm.getComponentEnabledSetting(entry)!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED){
                pm.setComponentEnabledSetting(entry,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP)
            }
        }
    }
    private fun restoreDisabledComponent(context:Context,component:ComponentName){
        val pm=context.packageManager
        runCatching{
            val state=pm.getComponentEnabledSetting(component)
            if(state!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT&&state!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED){
                pm.setComponentEnabledSetting(component,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,android.content.pm.PackageManager.DONT_KILL_APP)
            }
        }
    }
    fun screenReady(context:Context):Boolean=runCatching{
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if(Build.VERSION.SDK_INT>=21)power.isInteractive else power.isScreenOn
    }.getOrDefault(false)
    fun originalHome(context:Context):ComponentName? {
        val saved=prefs(context).getString("previous_home",null)?.let{ComponentName.unflattenFromString(it)}?.takeUnless{it.packageName=="android"||it.className.endsWith("FallbackHome")||it.className.endsWith("ResolverActivity")}
        if(saved!=null&&saved.packageName!=context.packageName&&runCatching{context.packageManager.getActivityInfo(saved,0).enabled}.getOrDefault(false))return saved
        val found=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)
            .filter{it.activityInfo.packageName!=context.packageName&&it.activityInfo.enabled&&it.activityInfo.exported}
            .sortedByDescending{runCatching{context.packageManager.getApplicationInfo(it.activityInfo.packageName,0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0}.getOrDefault(false)}
            .firstOrNull()?.activityInfo?.let{ComponentName(it.packageName,it.name)}
        return found?:DesktopPolicy.coocaaHome(context)
    }
    private fun homeEntry(context:Context,enable:Boolean){
        context.packageManager.setComponentEnabledSetting(ComponentName(context,"com.tvbridge.app.DesktopActivity"),if(enable)android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP)
    }
    fun returnsAfterStandby(context:Context)=true

    private fun enableHomeEntry(activity:Activity){
        val previous=defaultHome(activity)?.takeIf{it.packageName!=activity.packageName&&it.packageName!="android"&&!it.className.endsWith("FallbackHome")}?:originalHome(activity)
        if(previous!=null)prefs(activity).edit().putString("previous_home",previous.flattenToString()).apply()
        homeEntry(activity,!DesktopPolicy.stockHomeMustRemainDefault(activity))
    }

    private fun handover(activity:Activity,done:()->Unit){
        if(!handoverRunning.compareAndSet(false,true)){done();return}
        NativeUi.notice(activity,"正在配置桌面，请确认设备上的授权提示")
        AppThreads.onIo{
            try{
                val needsGrant=androidx.core.content.ContextCompat.checkSelfPermission(activity,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED||
                    (Build.VERSION.SDK_INT>=21&&!DesktopPolicy.usageAllowed(activity))||
                    (Build.VERSION.SDK_INT>=29&&!GlobalPointer.permitted(activity))||
                    !DesktopRecovery.listenerEnabled(activity)||!DesktopPolicy.stockHomeBackgroundAllowed(activity)
                val needsHome=defaultHome(activity)?.packageName!=activity.packageName&&!DesktopPolicy.installed(activity,"com.mitv.tvhome")&&!DesktopPolicy.stockHomeMustRemainDefault(activity)
                val adbReady=runCatching{Hub.adb.ready||(Hub.adb.enabled()&&Hub.adb.restore())||((needsGrant||needsHome)&&Hub.adb.connect(Hub.adb.port()))}.getOrDefault(false)
                val pending=prefs(activity)
                if(adbReady&&pending.getBoolean("desktop_guard",false)){
                    Hub.adb.authorizeDesktop()
                    if(needsHome)
                        Hub.adb.setDefaultHome(ComponentName(activity,"com.tvbridge.app.DesktopActivity"))
                }
            }catch(_:Exception){
            }finally{
                handoverRunning.set(false)
                activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed)done()}
            }
        }
    }

    fun settings(activity:Activity,changed:()->Unit){
        val preferences=prefs(activity)
        fun guarding()=preferences.getBoolean("desktop_guard",false)
        fun guardLabel()=when{
            handoverRunning.get()->"设置中"
            defaultHome(activity)?.packageName==activity.packageName->"One"
            guarding()&&DesktopGuardService.connected->"兼容接管"
            !guarding()->"未设置"
            enabled(activity)->"正在连接"
            else->"待授权"
        }
        val body=android.widget.LinearLayout(activity).apply{orientation=android.widget.LinearLayout.VERTICAL}
        var setupButton:android.widget.Button?=null
        fun refresh(){
            setupButton?.let{button->button.setCompoundDrawablesRelative(null,null,NativeUi.trailing(button,guardLabel(),ink=true),null)}
            changed()
        }
        fun addRow(label:String,onClick:()->Unit):android.widget.Button{
            val button=NativeUi.optionButton(activity,label)
            body.addView(button,android.widget.LinearLayout.LayoutParams(-1,NativeUi.settingsRowHeight(activity)).apply{bottomMargin=NativeUi.settingsRowGap(activity)})
            button.setOnClickListener{onClick()}
            return button
        }
        setupButton=addRow("一键设置桌面"){
            if(!handoverRunning.get()){
                enableHomeEntry(activity)
                preferences.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).apply()
                setupPending=true;setupStep=null;synchronized(setupSkipped){setupSkipped.clear()}
                handover(activity){finishSetup(activity,::refresh)}
                refresh()
            }
        }
        addRow("还原原桌面"){
            if(!handoverRunning.get())restore(activity,::refresh)
        }
        if(activity is MainActivity)addRow("桌面接管诊断"){DesktopDiagnostics.show(activity)}
        refresh()
        val dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("桌面应用").setView(body)
            .setNegativeButton("关闭",null),widthDp=300,initialFocus=body.getChildAt(0))
        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener{focused->if(focused&&dialog.isShowing)refresh()}
    }
    fun finishSetup(activity:Activity,changed:()->Unit){
        if(handoverRunning.get()||!setupPending)return
        val ready=ensureGuard(activity)||enabled(activity)
        val homeIsOne=runCatching{activity.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName==activity.packageName}.getOrDefault(false)
        changed()
        fun request(step:String,intent:Intent,message:String){
            fun skip(){
                synchronized(setupSkipped){setupSkipped+=step}
                setupStep=null
                finishSetup(activity,changed)
            }
            if(intent.resolveActivity(activity.packageManager)==null){skip();return}
            if(setupStep==step){setupPending=false;setupStep=null;NativeUi.notice(activity,"授权尚未完成，可再次设置桌面");return}
            setupStep=step;NativeUi.notice(activity,message)
            try{if(step=="home")activity.startActivityForResult(intent,7043) else activity.startActivity(intent)}catch(_:RuntimeException){
                if(step=="home"&&intent.action!=Settings.ACTION_HOME_SETTINGS){
                    try{activity.startActivityForResult(Intent(Settings.ACTION_HOME_SETTINGS),7043)}catch(_:RuntimeException){skip()}
                }else skip()
            }
        }
        val skipped=synchronized(setupSkipped){setupSkipped.toSet()}
        if(!ready&&"accessibility" !in skipped){
            request("accessibility",Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),"请开启 One 桌面接管")
        }else if(DesktopPolicy.stockHomeMustRemainDefault(activity)&&!DesktopPolicy.stockHomeBackgroundAllowed(activity)&&"miui_background" !in skipped){
            val permission=Intent("miui.intent.action.APP_PERM_EDITOR").setPackage("com.miui.securitycenter").putExtra("extra_pkgname",activity.packageName)
            request("miui_background",permission,"请允许 One 在后台弹出界面，确保 HOME 可以返回 One")
        }else if(!homeIsOne&&!DesktopPolicy.installed(activity,"com.mitv.tvhome")&&!DesktopPolicy.stockHomeMustRemainDefault(activity)&&"home" !in skipped){
            val roleIntent=if(Build.VERSION.SDK_INT>=29)runCatching{
                val roles=activity.getSystemService(android.app.role.RoleManager::class.java)
                if(roles!=null&&roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)&&!roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME))
                    roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME).takeIf{it.resolveActivity(activity.packageManager)!=null}
                else null
            }.getOrNull() else null
            request("home",roleIntent?:Intent(Settings.ACTION_HOME_SETTINGS),"请选择 One 为默认桌面，避免 HOME 先进入原桌面再返回")
        }else if(Build.VERSION.SDK_INT>=21&&!DesktopPolicy.usageAllowed(activity)&&"usage" !in skipped){
            request("usage",Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),"请开启 One 的使用情况访问，用于前台检测兜底")
        }else {
            setupPending=false;setupStep=null
            recover(activity)
            NativeUi.notice(activity,when{
                homeIsOne->"One 已是系统默认桌面"
                DesktopGuardService.connected->"兼容接管已开启"
                "accessibility" in skipped->"此设备未提供无障碍设置页，请用手机或电脑通过 ADB 交接开启接管"
                else->"正在连接桌面守护"
            })
            DesktopRecovery.offer(activity)
        }
    }
    private fun restore(activity:Activity,changed:()->Unit){
        reopenAdb(activity)
        val target=originalHome(activity)
        if(prefs(activity).contains("desktop_vt_previous")){
            NativeUi.notice(activity,"正在还原系统桌面配置")
            AppThreads.onIo{
                val ready=runCatching{Hub.adb.ready||(Hub.adb.enabled()&&Hub.adb.restore())}.getOrDefault(false)
                val bootRestored=!ready||runCatching{Hub.adb.restoreBootApp()}.getOrDefault(false)
                if(ready){
                    if(target!=null)Hub.adb.setDefaultHome(target) else Hub.adb.restoreVendorHomePkg()
                }
                activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed){
                    if(!bootRestored)NativeUi.notice(activity,BOOT_APP_NOT_RESTORED)
                    if(!prefs(activity).contains("desktop_vt_previous"))restore(activity,changed)
                    else NativeUi.notice(activity,if(ready)"固件尚未完成还原，请重试"else "请恢复设备调试连接后再还原桌面")
                }}
            };return
        }
        prefs(activity).edit().putBoolean("boot",false).putBoolean("desktop_guard",false).apply()
        setupPending=false;setupStep=null;synchronized(setupSkipped){setupSkipped.clear()}
        watchAdb(activity)
        cancel(activity);DesktopGuardService.allowStockLauncher()
        activity.packageManager.clearPackagePreferredActivities(activity.packageName)
        homeEntry(activity,false)
        AppThreads.onIo{
            val adbUp=runCatching{Hub.adb.ready||(Hub.adb.enabled()&&Hub.adb.restore())}.getOrDefault(false)
            val bootRestored=!Hub.adb.bootAppPending()||(adbUp&&runCatching{Hub.adb.restoreBootApp()}.getOrDefault(false))
            if(target!=null&&adbUp)Hub.adb.setDefaultHome(target)
            activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed){
                changed()
                if(!bootRestored)NativeUi.notice(activity,BOOT_APP_NOT_RESTORED)
                if(defaultHome(activity)?.packageName==activity.packageName){
                    try{activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS));NativeUi.notice(activity,"请选择原来的桌面")}catch(_:RuntimeException){NativeUi.notice(activity,"已停止接管，请在系统设置选择原桌面")}
                }else try{
                    val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if(target!=null)intent.component=target
                    activity.startActivity(intent)
                }catch(_:RuntimeException){NativeUi.notice(activity,"已停止 One 桌面接管")}
            }}
        }
    }
    private fun pending(context:Context,token:String="",step:Int=0)=PendingIntent.getBroadcast(context,REQUEST,Intent(context,BootAttemptReceiver::class.java).setAction(ATTEMPT).putExtra("token",token).putExtra("step",step),PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=23)PendingIntent.FLAG_IMMUTABLE else 0)
    fun cancel(context:Context){
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context));prefs(context).edit().remove("boot_token").apply()
        if (!wanted(context)) {
            val prevStandby: BroadcastReceiver?
            val prevEnabled: ContentObserver?
            val prevAdb: ContentObserver?
            synchronized(stateLock) {
                standbyOut = false
                prevStandby = standbyWatch; standbyWatch = null
                prevEnabled = enabledObserver; enabledObserver = null
                prevAdb = adbObserver; adbObserver = null
            }
            prevStandby?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
            prevEnabled?.let { runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) } }
            prevAdb?.let { runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) } }
            removeGuard(context)
            DesktopRecovery.stop(context)
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
        if(DesktopRecovery.leftForAnApp){cancel(context);return}
        if(!screenReady(context)){
            if(step+1<delays.size)scheduleStep(context,token,step+1)else cancel(context)
            return
        }
        if(Hub.activity?.visible==true||BrowserActivity.active!=null){AppThreads.main.post{Hub.activity?.onBootSignal()};BootReceiver.markArrived(context);return}
        if(DesktopGuardService.connected&&wanted(context)){DesktopGuardService.onBootCompleted();cancel(context);return}
        if(Build.VERSION.SDK_INT>=21){
            val front=if(DesktopPolicy.usageAllowed(context))DesktopForeground.latest(context,30000)?.first else null
            if(front!=null&&front.packageName!=context.packageName&&!front.className.endsWith("FallbackHome")&&!isStockHome(context,front)&&!DesktopPolicy.bootCover(front)&&!DesktopPolicy.isHomeChooser(front)){
                p.edit().putString("boot_status","foreground_preserved").apply();cancel(context);return
            }
        }
        try{
            p.edit().putString("boot_status","launch_requested").putInt("boot_attempt",step+1).apply()
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("from_boot",true))
        }catch(e:RuntimeException){p.edit().putString("boot_status","launch_rejected").putString("boot_error",e.javaClass.simpleName).apply()}
        if(step+1<delays.size)scheduleStep(context,token,step+1)else p.edit().remove("boot_token").putString("boot_status","launch_blocked").apply()
    }
    fun isStockHome(context:Context,component:ComponentName)=homes(context).contains(component)
    fun homes(context:Context):Set<ComponentName> {
        val now=SystemClock.elapsedRealtime()
        homesCache?.let{if(now-homesCacheAt<30000)return it}
        val found=computeHomes(context)
        homesCache=found;homesCacheAt=now
        return found
    }
    private fun computeHomes(context:Context):Set<ComponentName> {
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
        return homes+DesktopPolicy.extras(context,homes)+listOfNotNull(DesktopPolicy.coocaaHome(context))
    }
    fun ensureGuard(context:Context):Boolean {
        if(standbyOut)return false
        if(shuttingDown(context)){
            waitForStandbyWake(context)
            if(!shutdownRecheck){
                shutdownRecheck=true
                val app=context.applicationContext
                recovery.postDelayed({shutdownRecheck=false;ensureGuard(app)},121000)
            }
            return false
        }
        if(wanted(context)&&DesktopPolicy.standbyNeedsUnbind()){
            val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if(!(if(Build.VERSION.SDK_INT>=21)power.isInteractive else power.isScreenOn)){suspendForStandby(context);return false}
        }
        if(!wanted(context)||androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false
        val resolver=context.contentResolver;val me=ComponentName(context,DesktopGuardService::class.java)
        val entries=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter{it.isNotBlank()}
        if(entries.any{ComponentName.unflattenFromString(it)==me}&&runCatching{Settings.Secure.getInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)==1}.getOrDefault(false))return true
        val written=runCatching{
            if(entries.none{ComponentName.unflattenFromString(it)==me})Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(entries+me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,1);true
        }.getOrDefault(false)
        return written
    }

}

class BootAttemptReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==DesktopTakeover.ATTEMPT)DesktopTakeover.attempt(context,intent)}}
