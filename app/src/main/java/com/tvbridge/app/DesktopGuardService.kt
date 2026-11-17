package com.tvbridge.app

import android.accessibilityservice.*
import android.app.*
import android.content.*
import android.hardware.input.InputManager
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent

class DesktopGuardService:AccessibilityService(){
    companion object{
        @Volatile var connected=false;private set
        @Volatile private var instance:DesktopGuardService?=null
        @Volatile private var allowStockUntil=0L
        @Volatile private var allowStockFrom:String?=null
        private var deafRestarted=false
        private var deafAtBootRetried=false
        private var oneResumed=false
        private var oneResumedAt=0L
        private var onePausedAt=0L
        fun allowStockLauncher(value:Boolean=true,pkg:String?=null){
            allowStockUntil=if(value)SystemClock.uptimeMillis()+3600000 else 0
            allowStockFrom=if(value)pkg else null
            instance?.let{DesktopRecovery.noteStockVisit(it,if(value)SystemClock.elapsedRealtime()+3600000L else null)}
        }
        fun onOneResumed(){
            allowStockUntil=0;allowStockFrom=null
            oneResumed=true;oneResumedAt=SystemClock.uptimeMillis()
            instance?.let{s->
                DesktopRecovery.noteStockVisit(s,null)
                s.front=ComponentName(s,MainActivity::class.java)
                s.frontAt=SystemClock.elapsedRealtime()
                s.configureKeys()
            }
        }
        fun onOnePaused(){
            oneResumed=false;onePausedAt=SystemClock.uptimeMillis()
            instance?.let{s->
                if(s.front?.packageName==s.packageName)s.front=null
                s.configureKeys()
                if(DesktopPolicy.backOnPause(s)&&SystemClock.uptimeMillis()-s.homeInOneAt<400&&SystemClock.uptimeMillis()-s.recentsRequestedAt>=2000)s.ui.post{if(s.active())s.launch(explicit=true)}
            }
        }
        fun onBootCompleted(wake:Boolean=false){
            instance?.let{guard->guard.ui.post{
                if(wake)guard.onWake()
                else{
                    guard.activities.clear()
                    guard.refresh(force=true)
                    guard.configureKeys()
                    guard.settle(boot=true)
                }
            }}
        }
        fun showPowerMenu():Boolean =
            Build.VERSION.SDK_INT>=21 && instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)==true
        fun sourceSwitchStarted(){instance?.sourceGraceUntil=SystemClock.uptimeMillis()+3000}
        fun openStock(context:Context,component:ComponentName,direct:()->Boolean):Boolean {
            allowStockLauncher(true,component.packageName)
            DesktopRecovery.noteStockVisit(context,SystemClock.elapsedRealtime()+3600000L)
            val s=instance
            val resolved=context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
            fun home():Boolean {
                if(s!=null){s.ownHomeAt=SystemClock.uptimeMillis();if(s.performGlobalAction(GLOBAL_ACTION_HOME))return true}
                return runCatching{context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true}.getOrDefault(false)
            }
            return if(resolved==component.packageName)home()||direct() else direct()||home()
        }
    }
    private val ui=Handler(Looper.getMainLooper())
    @Volatile private var hasScanned=false
    private var refreshed=-30000L
    private var lastLaunch=-3000L
    private val automaticLaunches = java.util.ArrayDeque<Long>()
    private var automaticPausedUntil = 0L
    private var front:ComponentName?=null
    private var frontAt=0L
    private var lastUsageQuery=0L
    @Volatile private var homeDown=false
    private var homeHeld=false
    private var lastHomeUp=-3000L
    private var gamepad=false
    private var mouse=false
    private var registered=false
    private var sawWindow=false
    private var deaf=false
    private var ownHomeAt=-3000L
    private var homeInOneAt=-3000L
    private var lastHomeInOneAt=-3000L
    private var recentsRequestedAt=-3000L
    private var homeAt=-3000L
    private var keySeen=false
    private var bootStockHolding=false
    private var bootStockShown=false
    private var bootStockMs=0
    private val endBootStockHold=Runnable{
        if(!bootStockHolding)return@Runnable
        bootStockHolding=false
        inspectFront()
    }
    private var windowCount=0L
    private var homeWindows=0L
    private var sourceGraceUntil=0L
    private var chooserBackFor=Long.MIN_VALUE
    private var chooserHomeAt=Long.MIN_VALUE/2
    private val chooserFallback=Runnable{
        if(active()&&DesktopPolicy.isHomeChooser(front))launch(explicit=true)
    }
    private fun chooserThenHome(){
        chooserHomeAt=SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
    private var unlockPending=false
    private val unlockPoll=object:Runnable{
        override fun run(){
            if(!connected||!unlockPending)return
            if(DesktopTakeover.unlocked(this@DesktopGuardService))onUserUnlocked()
            else ui.postDelayed(this,2000)
        }
    }
    private val activities=java.util.concurrent.ConcurrentHashMap<ComponentName,Boolean>()
    private fun isActivity(component:ComponentName):Boolean{
        if(!DesktopTakeover.unlocked(this)){
            val ok=runCatching{packageManager.getActivityInfo(component,0)}.isSuccess
            if(ok)activities[component]=true
            return ok
        }
        return activities.getOrPut(component){runCatching{packageManager.getActivityInfo(component,0)}.isSuccess}
    }
    private val hold=Runnable{homeHeld=true;requestRecents()}
    private fun requestRecents(){
        recentsRequestedAt=SystemClock.uptimeMillis();openRecents()
    }
    private val recheck=Runnable{inspectFront()}
    private val switchHold=Runnable{inspectFront()}
    private val bootChecks=listOf(1500L,4000L,12000L,25000L,45000L).associateWith{Runnable{inspectFront(woke=true)}}
    private var screenOff=false
    private fun active()=!screenOff&&!InstallHandoff.active(this)&&DesktopTakeover.wanted(this)&&DesktopTakeover.screenReady(this)
    private fun stock(component:ComponentName?)=component!=null&&(component in homeComponents()||DesktopPolicy.bootCover(component))
    private fun firmwarePushedAtBoot(pkg:String)=pkg in DesktopPolicy.FIRMWARE_BOOT_APPS&&SystemClock.elapsedRealtime()<180000&&!keySeen
    private fun allowedStock()=SystemClock.uptimeMillis()<allowStockUntil
    private fun automaticHold()=maxOf(sourceGraceUntil,recentsRequestedAt+3000)-SystemClock.uptimeMillis()
    private fun restoreStockVisit(){
        val leftMs=DesktopRecovery.stockVisitLeftMs(this)
        if(leftMs>0L){
            allowStockUntil=SystemClock.uptimeMillis()+leftMs
        }
    }
    private fun onUserUnlocked(){
        unlockPending=false
        ui.removeCallbacks(unlockPoll)
        activities.clear()
        hasScanned=false
        DesktopTakeover.afterUnlock(this)
        restoreStockVisit()
        if(InstallHandoff.consumeAfterSelfUpdate(this)){
            ui.postDelayed({if(active()&&Hub.activity?.visible!=true)launch(explicit=true)},1500)
        }
        refresh(force=true)
        configureKeys()
        settle(boot=true)
    }
    private fun refresh(force:Boolean=false){
        if(!DesktopTakeover.unlocked(this))return
        DesktopState.refresh(this)
        if(force||SystemClock.elapsedRealtime()-refreshed>30000){
            val firstScan=!hasScanned
            hasScanned=true
            refreshed=SystemClock.elapsedRealtime()
            AppThreads.onState{
                activities.clear()
                if((firstScan||force)&&SystemClock.elapsedRealtime()<180000){
                    ui.post{if(instance===this)inspectFront(woke=true)}
                }
            }
        }
    }
    private fun usageAllowed()=if(DesktopState.hasDataFor(this))DesktopState.usageAllowed else DesktopPolicy.usageAllowed(this)
    private fun homeComponents():Set<ComponentName> = if(DesktopState.hasDataFor(this))DesktopState.homes else DesktopTakeover.homes(this)
    private var foregroundQuery=false
    private var pendingSettle=false
    private fun readFront(lookback:Long=14400000,settling:Boolean=false):ComponentName? {
        if(settling)pendingSettle=true
        val elapsedNow=SystemClock.elapsedRealtime()
        if(usageAllowed()&&!foregroundQuery&&(settling||elapsedNow-lastUsageQuery>=1000)){
            val window=if(lastUsageQuery>0&&elapsedNow>=lastUsageQuery)minOf(lookback,elapsedNow-lastUsageQuery+5000)else minOf(lookback,elapsedNow)
            lastUsageQuery=elapsedNow;foregroundQuery=true
            AppThreads.onState{
                val wallNow=System.currentTimeMillis()
                val latest=DesktopForeground.latest(this,window)
                val endElapsed=SystemClock.elapsedRealtime()
                ui.post{
                    foregroundQuery=false
                    if(!connected)return@post
                    latest?.let{(component,at)->
                        val age=(wallNow-at).coerceAtLeast(0L)
                        val eventElapsed=(endElapsed-age).coerceAtLeast(0L)
                        if(eventElapsed>frontAt){front=component;frontAt=eventElapsed}
                    }
                    val doSettle=settling||pendingSettle
                    pendingSettle=false
                    if(doSettle)takeOverSettled(front)
                    else if(active()&&!allowedStock()&&stock(front))launch()
                }
            }
        }
        return front
    }
    private fun inspectFront(woke:Boolean=false){
        if(!active()||allowedStock())return
        refresh();val current=readFront(settling=woke)
        if(woke){if(!foregroundQuery){pendingSettle=false;takeOverSettled(current)}}
        else if(stock(current))launch()
    }
    private fun takeOverSettled(current:ComponentName?){
        if(!active()||allowedStock()||suppressWakeTakeover())return
        if(DesktopRecovery.leftForAnApp&&SystemClock.elapsedRealtime()<180000)return
        if(current==null){
            if(oneResumed||Hub.activity?.visible==true)return
            if(SystemClock.uptimeMillis()-lastLaunch<1500)return
            if(SystemClock.elapsedRealtime()>=180000&&DesktopRecovery.diedAwayFromHome(this))return
            if(!DesktopTakeover.revivedOften(this)&&!DesktopPolicy.startsOnSignalSource(this))launch()
            return
        }
        if(DesktopPolicy.isJmgoInput(current))return
        if(stock(current)||DesktopPolicy.isHomeChooser(current)||homeComponents().any{it.packageName==current.packageName})launch()
    }
    private var lastScreenOn=Long.MIN_VALUE
    private fun onWake(){
        screenOff=false
        lastScreenOn=SystemClock.elapsedRealtime()
        refresh(force=true)
        configureKeys()
        if(DesktopTakeover.returnsAfterStandby(this))settle()
        ui.removeCallbacks(watch);ui.post(watch)
    }
    private fun suppressWakeTakeover()=!DesktopTakeover.returnsAfterStandby(this)&&
        lastScreenOn!=Long.MIN_VALUE&&SystemClock.elapsedRealtime()-lastScreenOn<6000

    private val wakeCheck=Runnable{
        if(DesktopTakeover.returnsAfterStandby(this))inspectFront(woke=true)
    }
    private fun settle(boot:Boolean=false){
        ui.removeCallbacks(recheck)
        if(boot)bootChecks.forEach{(delay,task)->ui.removeCallbacks(task);ui.postDelayed(task,delay)}
        else if(DesktopTakeover.returnsAfterStandby(this)){
            ui.removeCallbacks(wakeCheck)
            for(delay in longArrayOf(1500,3000,6000))ui.postDelayed(wakeCheck,delay)
        }
    }
    private val watch=object:Runnable{override fun run(){
        if(!connected)return
        recoverAfterInstaller()
        configureKeys()
        val elapsed = SystemClock.elapsedRealtime()
        val wakeWindow = lastScreenOn != Long.MIN_VALUE && elapsed - lastScreenOn < 15_000L
        val settling = if (wakeWindow) DesktopTakeover.returnsAfterStandby(this@DesktopGuardService)
            else elapsed < 60_000L
        val watchForeground = DeviceProfile.watchForeground || deaf
        if(active()&&!allowedStock()&&Hub.activity?.visible!=true){
            if(settling)inspectFront(woke=true)
            else if(watchForeground){
                readFront(30000)
                if(stock(front))launch()
            }
        }
        ui.postDelayed(this,if(settling||watchForeground)1000 else 5000)
    }}
    private fun recoverAfterInstaller(){
        if(!DesktopTakeover.wanted(this))return
        if(InstallHandoff.consumeAfterSelfUpdate(this)){
            if(active()&&Hub.activity?.visible!=true)launch(explicit=true)
            return
        }
        if(!InstallHandoff.active(this))return
        val pkg=runCatching{rootInActiveWindow?.packageName?.toString()}.getOrNull()?:return
        if(InstallHandoff.returnedToStock(this,homeComponents().any{it.packageName==pkg}))launch(explicit=true)
    }
    private val afterHome=object:Runnable{override fun run(){
        if(!active()||SystemClock.uptimeMillis()-homeAt>6000)return
        val component=readFront()
        if(DesktopPolicy.isHomeChooser(component)){launch(explicit=true);return}
        if(stock(component)){
            allowStockUntil=0;allowStockFrom=null;launch(explicit=true)
            if(windowCount==homeWindows&&usageAllowed()){
                deaf=true
                if(!deafRestarted){deafRestarted=true;DesktopTakeover.rebindIfStalled(this@DesktopGuardService,unresponsive=true)}
            }
        }else if(!usageAllowed()&&windowCount==homeWindows&&SystemClock.uptimeMillis()-homeAt>=1500)launch(explicit=true)
        else if(deaf&&windowCount==homeWindows&&SystemClock.uptimeMillis()-homeAt<1500)ui.postDelayed(this,150)
    }}
    private val packageRefresh=Runnable{refresh(force=true)}
    private val packageReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
        if(!connected)return
        DeviceProfile.invalidate(this@DesktopGuardService)
        ui.removeCallbacks(packageRefresh);ui.postDelayed(packageRefresh,1500)
    }}
    private val shutdownReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
        if(intent.action=="com.dangbei.action.STR_SHUTDOWN")DesktopTakeover.stepOutForShutdown(this@DesktopGuardService)
    }}
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
        when(intent.action){
            Intent.ACTION_CLOSE_SYSTEM_DIALOGS->if(intent.getStringExtra("reason")=="homekey"&&active()&&SystemClock.uptimeMillis()-ownHomeAt>1500){
                allowStockUntil=0;allowStockFrom=null;sourceGraceUntil=0;homeAt=SystemClock.uptimeMillis();homeWindows=windowCount;DesktopRecovery.noteHomeKey(this@DesktopGuardService)
                if(DesktopPolicy.backOnPause(this@DesktopGuardService)&&(Hub.activity?.visible==true||homeAt-lastLaunch in 0..1000)){
                    homeInOneAt=homeAt
                    lastHomeInOneAt=homeAt
                }
                if(BrowserActivity.active!=null||Hub.activity?.visible==true||front==null||stock(front))launch(explicit=true)
                ui.removeCallbacks(afterHome);ui.postDelayed(afterHome,if(deaf)150 else 300);ui.postDelayed(afterHome,1500)
                ui.removeCallbacks(switchHold);ui.postDelayed(switchHold,5500)
            }
            Intent.ACTION_SCREEN_ON->onWake()
            Intent.ACTION_USER_UNLOCKED->onUserUnlocked()
            Intent.ACTION_SCREEN_OFF->{
                screenOff=true
                DesktopRecovery.noteScreenOff(this@DesktopGuardService)
                bootChecks.values.forEach{ui.removeCallbacks(it)};ui.removeCallbacks(recheck);ui.removeCallbacks(wakeCheck);ui.removeCallbacks(hold);ui.removeCallbacks(watch);homeDown=false;configureKeys();DesktopTakeover.suspendForStandby(this@DesktopGuardService)
            }
        }
    }}
    private val inputListener=object:InputManager.InputDeviceListener{
        override fun onInputDeviceAdded(deviceId:Int)=scanPads()
        override fun onInputDeviceRemoved(deviceId:Int)=scanPads()
        override fun onInputDeviceChanged(deviceId:Int)=scanPads()
    }
    private fun scanPads(){
        var pad=false
        var pointer=false
        runCatching{
            for(id in InputDevice.getDeviceIds()){
                val dev=InputDevice.getDevice(id)?:continue
                if(dev.isVirtual||(dev.vendorId==1&&dev.productId==1))continue
                if(dev.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD)pad=true
                if(dev.sources and InputDevice.SOURCE_MOUSE==InputDevice.SOURCE_MOUSE&&(dev.vendorId!=0||dev.productId!=0))pointer=true
            }
        }
        gamepad=pad
        mouse=pointer
        configureKeys()
    }
    // The vendor answers come from DeviceProfile: probed once per package
    // set on the state thread, never on this thread. Only the dynamic inputs
    // (active, gamepad, mouse, the automatic-pause window) are read per call.
    private fun configureKeys(){
        val info=serviceInfo?:return
        DeviceProfile.ensure(this)
        val padGame=gamepad&&Hub.activity?.visible!=true&&front?.packageName?.let{it!=packageName&&!stock(front)}==true
        val mouseUnsafe=mouse&&DeviceProfile.xiaomiInput
        val filter=active()&&SystemClock.uptimeMillis()>=automaticPausedUntil&&!padGame&&!mouseUnsafe&&!DeviceProfile.passKeys&&!DeviceProfile.volumeFilterUnsafe&&(Build.VERSION.SDK_INT<=30||!DeviceProfile.backOnPause||Hub.activity?.visible==true)
        val flag=AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        var next=if(filter||homeDown)info.flags or flag else info.flags and flag.inv()
        if(DesktopTakeover.unlocked(this)&&DeviceAdapters.profile(this).neverFilterKeys)next=next and flag.inv()
        if(filter&&Build.VERSION.SDK_INT<=23&&DeviceProfile.otherFilter)next=next and AccessibilityServiceInfo.DEFAULT.inv()
        if(next!=info.flags){info.flags=next;serviceInfo=info}
    }
    override fun onCreate(){
        super.onCreate()
        // Aerial c21c8f3: on sets where the guard binds before the unlock the
        // main activity may never start, so the enabled-list watch is
        // registered here too, not only from the activity-side recover().
        DesktopTakeover.watchEnabledList(this)
    }
    override fun onServiceConnected(){
        instance=this;connected=true
        DeviceProfile.ensure(this)
        DesktopState.refresh(this)
        val isUnlocked=DesktopTakeover.unlocked(this)
        if(isUnlocked){
            restoreStockVisit()
            val selfUpdated=InstallHandoff.consumeAfterSelfUpdate(this)
            if(selfUpdated){
                ui.postDelayed({if(active()&&Hub.activity?.visible!=true)launch(explicit=true)},1500)
            }
            refresh(force=true)
            // Aerial f991579: bound in a new process past the boot window with
            // nothing seen yet (firmware force-stops third-party apps as the
            // stock launcher comes up): let usage stats name the front and
            // the settled take-over decide, instead of waiting for a window
            // change that may never come.
            if(!selfUpdated&&Build.VERSION.SDK_INT>=24&&SystemClock.elapsedRealtime()>=180000&&SystemClock.elapsedRealtime()-android.os.Process.getStartElapsedRealtime()<60000&&usageAllowed()){
                readFront(settling=true)
            }
        }else{
            unlockPending=true
            ui.removeCallbacks(unlockPoll)
            ui.postDelayed(unlockPoll,2000)
        }
        scanPads()
        Hub.activity?.desktopConnectionChanged()
        if(!registered){
            androidx.core.content.ContextCompat.registerReceiver(this,receiver,IntentFilter(Intent.ACTION_SCREEN_ON).apply{addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);addAction(Intent.ACTION_USER_UNLOCKED)},androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this,packageReceiver,IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addAction(Intent.ACTION_PACKAGE_REPLACED);addDataScheme("package")},androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            if(DesktopPolicy.dangbeiStandbyDisable()){
                androidx.core.content.ContextCompat.registerReceiver(this,shutdownReceiver,IntentFilter("com.dangbei.action.STR_SHUTDOWN"),"android.permission.DEVICE_POWER",ui,androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            }
            (getSystemService(Context.INPUT_SERVICE) as? InputManager)?.registerInputDeviceListener(inputListener,ui);registered=true
        }
        if(!isUnlocked)DesktopTakeover.afterUnlock(this)
        else if(DesktopTakeover.wanted(this)&&(SystemClock.elapsedRealtime()<180000||DesktopTakeover.returnsAfterStandby(this)))settle(boot=SystemClock.elapsedRealtime()<180000)
        if(isUnlocked&&SystemClock.elapsedRealtime()<180000){
            val bootMs=DeviceAdapters.profile(this).bootStockMs
            if(bootMs>0){
                bootStockHolding=true;bootStockShown=false;bootStockMs=bootMs
                val hold=maxOf(20000L,bootMs.toLong())
                allowStockUntil=SystemClock.uptimeMillis()+hold+bootMs
                ui.postDelayed(endBootStockHold,hold)
            }
        }
        if(Build.VERSION.SDK_INT<=23&&!deafAtBootRetried)ui.postDelayed({
            if(connected&&!sawWindow&&active()&&!deafAtBootRetried){deafAtBootRetried=true;DesktopTakeover.rebindIfStalled(this,unresponsive=true)}
        },8000)
        ui.post(watch)
    }
    override fun onKeyEvent(event:KeyEvent):Boolean{
        keySeen=true
        if(event.keyCode!=KeyEvent.KEYCODE_HOME||(!homeDown&&(!active()||SystemClock.uptimeMillis()<automaticPausedUntil)))return false
        if(event.action==KeyEvent.ACTION_DOWN){if(event.repeatCount==0){
            homeDown=true;homeHeld=false;ui.removeCallbacks(hold)
            if(event.eventTime-lastHomeUp in 0..400){homeHeld=true;lastHomeUp=-3000;if(Hub.activity?.visible==true){ui.post{Hub.activity?.showHome()}}else launch(explicit=true)}
            else ui.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong())
        };return true}
        if(event.action==KeyEvent.ACTION_UP&&homeDown){homeDown=false;ui.removeCallbacks(hold);if(!event.isCanceled&&!homeHeld){lastHomeUp=event.eventTime;allowStockUntil=0;allowStockFrom=null;sourceGraceUntil=0;launch(explicit=true)};configureKeys();return true}
        return false
    }
    private fun openRecents(){
        if(!active())return
        val tcl=ComponentName("com.tcl.cyberui","com.tcl.global.history.receiver.GlobalHistoryReceiver")
        val receiver=runCatching{packageManager.getReceiverInfo(tcl,0)}.getOrNull()
        if(receiver?.enabled==true&&receiver.applicationInfo.enabled&&receiver.exported&&receiver.permission.isNullOrEmpty()){
            if(runCatching{sendBroadcast(Intent("com.tcl.action.OPEN_GLOBAL_HISTORY").setComponent(tcl).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))}.isSuccess)return
        }
        try{startActivity(Intent("com.dangbei.recentstask.action.SHOW_RECENTS_FOR_USER").addCategory(Intent.CATEGORY_DEFAULT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return}catch(_:RuntimeException){}
        // Aerial 4aa07eb: Skyworth's own recent-apps activity, where SystemUI
        // has none; only where it is enabled, exported and needs no
        // permission we lack.
        val skyRecents=ComponentName("com.skyworthdigital.recentsmanager","com.skyworthdigital.recentsmanager.RecentTaskActivity")
        val skyInfo=runCatching{packageManager.getActivityInfo(skyRecents,0)}.getOrNull()
        if(skyInfo!=null&&skyInfo.enabled&&skyInfo.applicationInfo.enabled&&skyInfo.exported&&(skyInfo.permission.isNullOrEmpty()||androidx.core.content.ContextCompat.checkSelfPermission(this,skyInfo.permission)==android.content.pm.PackageManager.PERMISSION_GRANTED)){
            try{startActivity(Intent().setComponent(skyRecents).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return}catch(_:RuntimeException){}
        }
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        if(event?.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return
        val pkg=event.packageName?.toString()?:return;val cls=event.className?.toString()?:return
        sawWindow=true;deaf=false;windowCount++
        val component=ComponentName(pkg,if(cls.startsWith('.'))pkg+cls else cls)
        if(pkg==packageName){if(isActivity(component)){front=component;frontAt=SystemClock.elapsedRealtime()};return}
        if(InstallHandoff.active(this)&&DesktopTakeover.wanted(this)){
            refresh()
            if(InstallHandoff.returnedToStock(this,stock(component)||homeComponents().any{it.packageName==pkg}))launch(explicit=true)
            return
        }
        if(!active())return
        if(Build.VERSION.SDK_INT<=22&&!allowedStock()&&pkg=="com.jamdeo.launcher"&&cls=="android.widget.RelativeLayout"&&homeComponents().any{it.packageName==pkg}){performGlobalAction(GLOBAL_ACTION_BACK);launch();return}
        if(firmwarePushedAtBoot(pkg)&&isActivity(component)){launch();return}
        refresh()
        // Aerial e688701 + efe171f: the Home chooser right after a Home press.
        // Skipped when the wide query already answers One (a preferred record
        // without CATEGORY_DEFAULT): closing that chooser only brings it back
        // on the next press. Otherwise answer with Back at once instead of
        // waiting for the after-home check: over One the Back alone restores
        // One, with a settled fallback at the same 1.5 s deadline; over
        // another app the Back is followed by a system Home, which bypasses
        // the app-switch protection. Once per press, and our own follow-up
        // Home never counts as a new press.
        if(DesktopTakeover.unlocked(this)&&DesktopPolicy.isHomeChooser(component)&&DesktopTakeover.wideHome(this)?.packageName!=packageName){
            val pressedAt=maxOf(homeAt,homeInOneAt)
            val before=front
            if(before?.packageName==packageName&&SystemClock.uptimeMillis()-pressedAt<=1500&&chooserBackFor!=pressedAt){
                chooserBackFor=pressedAt
                ui.removeCallbacks(afterHome)
                ui.removeCallbacks(chooserFallback)
                performGlobalAction(GLOBAL_ACTION_BACK)
                ui.postDelayed(chooserFallback,(1500-(SystemClock.uptimeMillis()-pressedAt)).coerceAtLeast(0L))
                return
            }
            if(before!=null&&before.packageName!=packageName&&SystemClock.uptimeMillis()-pressedAt<=1500&&chooserBackFor!=pressedAt&&SystemClock.uptimeMillis()-chooserHomeAt>400){
                chooserBackFor=pressedAt
                ui.removeCallbacks(afterHome)
                performGlobalAction(GLOBAL_ACTION_BACK)
                chooserThenHome()
                return
            }
        }
        if(pkg in DesktopPolicy.sourceSwitchers)sourceGraceUntil=SystemClock.uptimeMillis()+3000
        if(allowedStock()&&allowStockFrom!=null&&pkg!=allowStockFrom&&!stock(component)&&isActivity(component)){
            allowStockUntil=0;allowStockFrom=null
            DesktopRecovery.noteStockVisit(this,null)
        }
        if(!stock(component)&&!isActivity(component))return
        if(stock(component))DesktopRecovery.noteStockHomeInFront(this)
        if(bootStockHolding&&!bootStockShown&&stock(component)){
            bootStockShown=true
            ui.removeCallbacks(endBootStockHold)
            ui.postDelayed(endBootStockHold,bootStockMs.toLong())
        }
        if(stock(component)||isActivity(component)){front=component;frontAt=SystemClock.elapsedRealtime()}
        if(!allowedStock()&&stock(component)){
            ui.removeCallbacks(recheck)
            ui.postDelayed(recheck, 350)
        }
        configureKeys()
    }
    private fun launch(explicit:Boolean=false){
        if(!active()||(!explicit&&(allowedStock()||suppressWakeTakeover())))return
        val now = SystemClock.uptimeMillis()
        if (explicit) {
            automaticLaunches.clear()
            automaticPausedUntil = 0L
        } else if (now < automaticPausedUntil) {
            return
        }
        if(!explicit){
            val remaining=automaticHold()
            if(remaining>0){
                ui.removeCallbacks(recheck);ui.postDelayed(recheck,remaining)
                return
            }
        }
        if(explicit&&BrowserActivity.active?.returnToOneHome()==true)return
        if(explicit&&Hub.activity?.returnToOneHome()==true)return
        if(!explicit&&(BrowserActivity.active!=null))return
        if(Hub.activity?.visible==true){if(stock(front)){ui.removeCallbacks(recheck);ui.postDelayed(recheck,150)};return}
        if(SystemClock.uptimeMillis()-lastLaunch<700){ui.removeCallbacks(recheck);ui.postDelayed(recheck,750);return}
        if (!explicit) {
            while (automaticLaunches.isNotEmpty() && now - automaticLaunches.first >= 30_000L) {
                automaticLaunches.removeFirst()
            }
            if (automaticLaunches.size >= 3) {
                automaticPausedUntil = now + 60_000L
                configureKeys()
                return
            }
            automaticLaunches.addLast(now)
        }
        lastLaunch=now
        val boot=!explicit&&SystemClock.elapsedRealtime()<180000&&!BootReceiver.arrivedThisBoot(this)
        val hasBootApp=LauncherApps(this).bootApp()!=null
        val reopenBootTarget=!explicit&&SystemClock.elapsedRealtime()<180000&&hasBootApp&&!DesktopRecovery.homeKeyWithin(this,5000)&&DesktopRecovery.bootTargetOpenedWithin(this,60000)
        val openBootApp=(boot||reopenBootTarget)&&hasBootApp
        try{startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("show_home",!openBootApp).putExtra("from_boot",boot).putExtra("reopen_boot",reopenBootTarget))}
        catch(e:RuntimeException){getSharedPreferences("startup",0).edit().putString("guard_error",e.javaClass.simpleName).apply()}
    }
    override fun onInterrupt(){}
    private fun disconnect(){
        bootChecks.values.forEach{ui.removeCallbacks(it)}
        unlockPending=false
        ui.removeCallbacks(unlockPoll)
        if(instance===this){instance=null;connected=false}
        Hub.activity?.desktopConnectionChanged()
        ui.removeCallbacksAndMessages(null)
        if(registered){
            runCatching{unregisterReceiver(receiver)}
            runCatching{unregisterReceiver(packageReceiver)}
            runCatching{unregisterReceiver(shutdownReceiver)}
            (getSystemService(Context.INPUT_SERVICE) as? InputManager)?.unregisterInputDeviceListener(inputListener)
            registered=false
        }
    }
    override fun onUnbind(intent:Intent?):Boolean{disconnect();return super.onUnbind(intent)}
    override fun onDestroy(){disconnect();super.onDestroy()}
}
