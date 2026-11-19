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
    private var enabledObserver: ContentObserver? = null
    private var watchTries = 0

    fun watchEnabledList(context: Context) {
        if (SystemClock.elapsedRealtime() >= 300000) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val app = context.applicationContext
        // Aerial c21c8f3: the firmware's rewrite lands once and settles; recheck
        // 1.5 s after a change instead of racing it, and count only the times
        // the guard actually had to be put back, at most three per boot.
        val recheck = Runnable {
            AppThreads.onState {
                DesktopState.refreshNow(app)
                DeviceProfile.invalidate(app)
                val shouldWrite = synchronized(stateLock) { !rebinding && !standbyOut && wanted(app) && !DesktopState.guardListed && watchTries < 3 }
                if (shouldWrite && ensureGuard(app)) {
                    synchronized(stateLock) { watchTries++ }
                }
            }
        }
        val obs = object : ContentObserver(recovery) {
            override fun onChange(selfChange: Boolean) {
                if (SystemClock.elapsedRealtime() >= 300000) {
                    val toUnregister = synchronized(stateLock) {
                        if (enabledObserver === this) enabledObserver = null
                        this
                    }
                    runCatching { app.contentResolver.unregisterContentObserver(toUnregister) }
                    return
                }
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
        recovery.postDelayed({
            val stale = synchronized(stateLock) {
                if (enabledObserver === obs) {
                    enabledObserver = null
                    obs
                } else null
            }
            stale?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        }, (300000L - SystemClock.elapsedRealtime()).coerceAtLeast(1000L))
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
    private var standbyWatch:BroadcastReceiver?=null
    private var standbyOut=false
    private var shutdownRecheck=false
    private val bootId:String? by lazy{runCatching{java.io.File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf{it.isNotEmpty()}}.getOrNull()}
    // Aerial de1c002: Dangbei's shutdown is a suspend and the firmware starts
    // the app straight again as Home; the announcement is kept on disk so the
    // new process also stays out until the screen lights or the hold expires.
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
        runCatching{val others=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter{it.isNotBlank()&&ComponentName.unflattenFromString(it)!=me};Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,others.joinToString(":"));DesktopState.noteGuardWrite(context,false)}
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
    // Some firmwares (Xiaomi after standby) switch ADB debugging off. While takeover is
    // wanted, One switches it back on whenever it is needed (recovery checkpoints, the
    // restore flow) with the WRITE_SECURE_SETTINGS grant from the one-time setup, and
    // leaves it on — there is no separate switch. Restoring the stock desktop stops it.
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
        DesktopState.refresh(app)
        DeviceProfile.warm(app)
        if (!unlocked(app)) { afterUnlock(app); return }
        reopenAdb(app)
        watchAdb(app)
        if (!wanted(app)) return
        if (standbyOut) return
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
            DesktopState.noteGuardWrite(context, false)
            if (reset) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            recovery.postDelayed({
                rebinding = false
                if (wanted(context) && !standbyOut) runCatching {
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others() + me.flattenToString()).joinToString(":"))
                    DesktopState.noteGuardWrite(context, true)
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
        if(DesktopState.hasDataFor(context))return DesktopState.guardListed
        val component=ComponentName(context,DesktopGuardService::class.java)
        return Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any{ComponentName.unflattenFromString(it)==component}
    }
    fun configured(context:Context)=defaultHome(context)?.packageName==context.packageName||
        (wanted(context)&&enabled(context))
    fun label(context:Context)=when{!wanted(context)->"标准启动";!enabled(context)->"待授权";DesktopGuardService.connected->"兼容接管已连接";else->"兼容接管待连接"}
    private fun defaultHome(context:Context)=if(DesktopState.hasDataFor(context))DesktopState.defaultHome else context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let{ComponentName(it.packageName,it.name)}
    // Aerial efe171f: the wide query (MAIN + HOME, no DEFAULT flag) next to
    // defaultHome's strict one; a preferred record written without
    // CATEGORY_DEFAULT answers this while the strict query falls to the chooser.
    fun wideHome(context:Context):ComponentName? = runCatching{context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.let{ComponentName(it.packageName,it.name)}}.getOrNull()
    /** The home entry ships disabled; while takeover is wanted it must be explicitly ENABLED (this also migrates installs from before). */
    private fun restoreHomeEntry(context:Context){
        if(DesktopState.hasDataFor(context)&&DesktopState.homeEntryEnabled)return
        val pm=context.packageManager;val entry=ComponentName(context,"com.tvbridge.app.DesktopActivity")
        runCatching{
            if(pm.getComponentEnabledSetting(entry)!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED){
                pm.setComponentEnabledSetting(entry,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP)
                DesktopState.refresh(context)
            }
        }
    }
    private fun restoreDisabledComponent(context:Context,component:ComponentName){
        if(DesktopState.hasDataFor(context)){
            val knownEnabled=when(component){
                ComponentName(context,"com.tvbridge.app.DesktopActivity")->DesktopState.homeEntryEnabled
                ComponentName(context,BootReceiver::class.java)->DesktopState.bootReceiverEnabled
                else->null
            }
            if(knownEnabled==true)return
        }
        val pm=context.packageManager
        runCatching{
            val state=pm.getComponentEnabledSetting(component)
            if(state!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT&&state!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED){
                pm.setComponentEnabledSetting(component,android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,android.content.pm.PackageManager.DONT_KILL_APP)
                DesktopState.refresh(context)
            }
        }
    }
    fun screenReady(context:Context):Boolean=runCatching{
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if(Build.VERSION.SDK_INT>=21)power.isInteractive else power.isScreenOn
    }.getOrDefault(false)
    private fun originalHome(context:Context):ComponentName? {
        val saved=prefs(context).getString("previous_home",null)?.let{ComponentName.unflattenFromString(it)}?.takeUnless{it.packageName=="android"||it.className.endsWith("FallbackHome")||it.className.endsWith("ResolverActivity")}
        if(saved!=null&&saved.packageName!=context.packageName&&runCatching{context.packageManager.getActivityInfo(saved,0).enabled}.getOrDefault(false))return saved
        val found=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)
            .filter{it.activityInfo.packageName!=context.packageName&&it.activityInfo.enabled&&it.activityInfo.exported}
            .sortedByDescending{runCatching{context.packageManager.getApplicationInfo(it.activityInfo.packageName,0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0}.getOrDefault(false)}
            .firstOrNull()?.activityInfo?.let{ComponentName(it.packageName,it.name)}
        // Coocaa-style firmwares boot a homepage component that declares no
        // HOME category; the query above cannot see it, the firmware config can.
        return found?:DesktopPolicy.coocaaHome(context)
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

    /**
     * The one tap is the handover moment, as in Aerial's install: the TV's own ADB
     * grants WRITE_SECURE_SETTINGS and (where needed) sets the default home, after
     * which ensureGuard keeps the takeover alive at every start. On Xiaomi sets
     * with the firmware launcher (com.mitv.tvhome) the default is deliberately not
     * forced: Aerial found a set that took set-home-activity and then could not
     * sleep on a short power press; the guard covers those sets instead.
     */
    private fun handover(activity:Activity,done:()->Unit){
        if(!handoverRunning.compareAndSet(false,true)){done();return}
        NativeUi.notice(activity,"正在配置桌面，请确认设备上的授权提示")
        AppThreads.onIo{
            try{
                val needsGrant=androidx.core.content.ContextCompat.checkSelfPermission(activity,android.Manifest.permission.WRITE_SECURE_SETTINGS)!=android.content.pm.PackageManager.PERMISSION_GRANTED||
                    (Build.VERSION.SDK_INT>=21&&!DesktopPolicy.usageAllowed(activity))||
                    (Build.VERSION.SDK_INT>=29&&!GlobalPointer.permitted(activity))||
                    !DesktopRecovery.listenerEnabled(activity)
                val needsHome=defaultHome(activity)?.packageName!=activity.packageName&&!DesktopPolicy.installed(activity,"com.mitv.tvhome")
                val adbReady=runCatching{Hub.adb.ready||(Hub.adb.enabled()&&Hub.adb.restore())||((needsGrant||needsHome)&&Hub.adb.connect(Hub.adb.port()))}.getOrDefault(false)
                val pending=prefs(activity)
                if(adbReady&&pending.getBoolean("desktop_guard",false)&&pending.getBoolean("desktop_setup_pending",false)){
                    Hub.adb.authorizeDesktop()
                    if(needsHome)
                        Hub.adb.setDefaultHome(ComponentName(activity,"com.tvbridge.app.DesktopActivity"))
                }
            }catch(_:Exception){
                // ADB unavailable: done() falls back to the settings-page walk.
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
        val labels=listOf("一键设置桌面","待机唤醒返回 One","还原原桌面")
        val body=android.widget.LinearLayout(activity).apply{orientation=android.widget.LinearLayout.VERTICAL}
        fun refresh(){
            val values=listOf(guardLabel(),if(returnsAfterStandby(activity))"开启" else "关闭")
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
                0->{
                    if(handoverRunning.get())return@setOnClickListener
                    enableHomeEntry(activity)
                    preferences.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).putBoolean("desktop_setup_pending",true).remove("desktop_setup_step").remove("desktop_setup_skipped").apply()
                    handover(activity){finishSetup(activity,::refresh)}
                    refresh()
                }
                1->{
                    val next=!returnsAfterStandby(activity)
                    preferences.edit().putBoolean("desktop_wake",next).apply()
                    if(next&&!guarding())NativeUi.notice(activity,"请开启桌面守护，以便待机后返回 One")
                    refresh()
                }
                2->if(!handoverRunning.get())restore(activity,::refresh)
            }
            }
        }
        refresh()
        val dialog=NativeUi.show(AlertDialog.Builder(activity).setTitle("桌面应用").setView(body)
            .setNegativeButton("关闭",null),widthDp=300,initialFocus=body.getChildAt(0))
        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener{focused->if(focused&&dialog.isShowing)refresh()}
    }
    fun finishSetup(activity:Activity,changed:()->Unit){
        if(handoverRunning.get()||!prefs(activity).getBoolean("desktop_setup_pending",false))return
        val ready=ensureGuard(activity)||enabled(activity)
        changed()
        fun request(step:String,intent:Intent,message:String){
            val p=prefs(activity)
            // Firmwares that strip a settings page (Coocaa/Tianci 5.1 has no
            // usage-access page, some have no accessibility page) must not kill
            // the whole chain: skip that step, continue with the rest, and let
            // the ADB handover be the path that grants it instead.
            fun skip(){
                val skipped=(p.getStringSet("desktop_setup_skipped",emptySet())?:emptySet())+step
                p.edit().putStringSet("desktop_setup_skipped",skipped).remove("desktop_setup_step").apply()
                finishSetup(activity,changed)
            }
            if(intent.resolveActivity(activity.packageManager)==null){skip();return}
            if(p.getString("desktop_setup_step",null)==step){p.edit().putBoolean("desktop_setup_pending",false).apply();NativeUi.notice(activity,"授权尚未完成，可再次设置桌面");return}
            p.edit().putString("desktop_setup_step",step).apply();NativeUi.notice(activity,message)
            try{activity.startActivity(intent)}catch(_:RuntimeException){skip()}
        }
        val skipped=prefs(activity).getStringSet("desktop_setup_skipped",emptySet())?:emptySet()
        if(!ready&&"accessibility" !in skipped){
            request("accessibility",Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),"请开启 One 桌面接管")
        }else if(Build.VERSION.SDK_INT>=29&&!GlobalPointer.permitted(activity)&&"overlay" !in skipped){
            request("overlay",Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:${activity.packageName}")),"请允许 One 后台显示")
        }else if(Build.VERSION.SDK_INT>=21&&!DesktopPolicy.usageAllowed(activity)&&"usage" !in skipped){
            request("usage",Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),"请允许 One 识别前台应用，以免接管正在使用的应用")
        }else {
            prefs(activity).edit().putBoolean("desktop_setup_pending",false).remove("desktop_setup_step").apply()
            recover(activity)
            NativeUi.notice(activity,when{
                defaultHome(activity)?.packageName==activity.packageName->"One 已是系统默认桌面"
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
                if(ready&&target!=null)Hub.adb.setDefaultHome(target)
                activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed){
                    if(!bootRestored)NativeUi.notice(activity,BOOT_APP_NOT_RESTORED)
                    if(!prefs(activity).contains("desktop_vt_previous"))restore(activity,changed)
                    else NativeUi.notice(activity,if(ready)"固件尚未完成还原，请重试"else "请恢复设备调试连接后再还原桌面")
                }}
            };return
        }
        prefs(activity).edit().putBoolean("boot",false).putBoolean("desktop_guard",false).remove("desktop_setup_pending").apply()
        watchAdb(activity)
        cancel(activity);DesktopGuardService.allowStockLauncher()
        activity.packageManager.clearPackagePreferredActivities(activity.packageName)
        homeEntry(activity,false)
        DesktopState.refresh(activity)
        AppThreads.onIo{
            val adbUp=runCatching{Hub.adb.ready||(Hub.adb.enabled()&&Hub.adb.restore())}.getOrDefault(false)
            // The TCL/FFALCON boot entry was pointed at One by the setup; put the original back and
            // say so when that could not be done, instead of leaving the TV booting into a missing/removed One.
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
            standbyOut = false
            standbyWatch?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
            standbyWatch = null
            val (prevEnabled, prevAdb) = synchronized(stateLock) {
                val e = enabledObserver; enabledObserver = null
                val a = adbObserver; adbObserver = null
                e to a
            }
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
        if(Hub.activity?.visible==true||BrowserActivity.active!=null){BootReceiver.markArrived(context);return}
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
        if(DesktopState.hasDataFor(context)&&DesktopState.guardListed&&DesktopState.accessibilityOn)return true
        val resolver=context.contentResolver;val me=ComponentName(context,DesktopGuardService::class.java)
        val written=runCatching{
            val entries=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter{it.isNotBlank()}
            if(entries.none{ComponentName.unflattenFromString(it)==me})Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(entries+me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,1);true
        }.getOrDefault(false)
        if(written){DesktopState.noteGuardWrite(context,true,true);DesktopState.refresh(context)}
        return written
    }

}

class BootAttemptReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==DesktopTakeover.ATTEMPT)DesktopTakeover.attempt(context,intent)}}
