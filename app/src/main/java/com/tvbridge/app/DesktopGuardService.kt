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
        private var instance:DesktopGuardService?=null
        private var allowStockUntil=0L
        private var deafRestarted=false
        private var deafAtBootRetried=false
        fun allowStockLauncher(value:Boolean=true){allowStockUntil=if(value)SystemClock.uptimeMillis()+3600000 else 0}
        fun onOneResumed(){allowStockUntil=0;instance?.let{s->s.front=ComponentName(s,MainActivity::class.java);s.frontAt=System.currentTimeMillis();s.configureKeys();if(s.recentsOnResume){s.recentsOnResume=false;s.ui.post{s.openRecents()}}}}
        fun onOnePaused(){instance?.let{s->
            s.configureKeys()
            if(DesktopPolicy.backOnPause(s)&&SystemClock.uptimeMillis()-s.homeInOneAt<400&&SystemClock.uptimeMillis()-s.recentsRequestedAt>=2000)s.ui.post{if(s.active())s.launch(explicit=true)}
        }}
        fun onBootCompleted(){instance?.let{guard->guard.ui.post{guard.settle(boot=true)}}}
        fun showPowerMenu():Boolean =
            Build.VERSION.SDK_INT>=21 && instance?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)==true
        fun sourceSwitchStarted(){instance?.sourceGraceUntil=SystemClock.uptimeMillis()+3000}
        fun openStock(context:Context,component:ComponentName,direct:()->Boolean):Boolean {
            allowStockLauncher()
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
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var homes=emptySet<ComponentName>()
    private var refreshed=-30000L
    private var lastLaunch=-3000L
    private var front:ComponentName?=null
    private var frontAt=0L
    private var lastUsageQuery=0L
    @Volatile private var homeDown=false
    private var homeHeld=false
    private var lastHomeUp=-3000L
    private var gamepad=false
    private var registered=false
    private var sawWindow=false
    private var deaf=false
    private var ownHomeAt=-3000L
    private var homeInOneAt=-3000L
    private var lastHomeInOneAt=-3000L
    private var recentsOnResume=false
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
    private val activities=java.util.concurrent.ConcurrentHashMap<ComponentName,Boolean>()
    private fun isActivity(component:ComponentName)=activities.getOrPut(component){runCatching{packageManager.getActivityInfo(component,0)}.isSuccess}
    private val hold=Runnable{homeHeld=true;requestRecents()}
    private fun requestRecents(){
        recentsRequestedAt=SystemClock.uptimeMillis();recentsOnResume=false;openRecents()
    }
    private val recheck=Runnable{inspectFront()}
    private val switchHold=Runnable{inspectFront()}
    private val bootChecks=listOf(1200L,4000L,12000L,25000L).map{Runnable{inspectFront(woke=true)}}
    private fun active()=!InstallHandoff.active(this)&&DesktopTakeover.wanted(this)&&runCatching{
        val power=getSystemService(Context.POWER_SERVICE) as PowerManager
        (if(Build.VERSION.SDK_INT>=21)power.isInteractive else power.isScreenOn)&&!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    }.getOrDefault(false)
    private fun stock(component:ComponentName?)=component!=null&&(component in homes||DesktopPolicy.bootCover(component))
    private fun firmwarePushedAtBoot(pkg:String)=pkg in DesktopPolicy.FIRMWARE_BOOT_APPS&&SystemClock.elapsedRealtime()<180000&&!keySeen
    private fun allowedStock()=SystemClock.uptimeMillis()<allowStockUntil
    private fun refresh(force:Boolean=false){worker.execute{if(force||SystemClock.elapsedRealtime()-refreshed>if(homes.isEmpty())1000 else 30000){homes=DesktopTakeover.homes(this);refreshed=SystemClock.elapsedRealtime();activities.clear()}}}
    private fun usageAllowed()=DesktopPolicy.usageAllowed(this)
    private var foregroundQuery=false
    private fun readFront(lookback:Long=14400000):ComponentName? {
        val now=System.currentTimeMillis()
        if(usageAllowed()&&!foregroundQuery&&now-lastUsageQuery>=1000){
            val window=if(lastUsageQuery>0&&now>=lastUsageQuery)minOf(lookback,now-lastUsageQuery+5000)else lookback
            lastUsageQuery=now;foregroundQuery=true
            worker.execute{
                val latest=DesktopForeground.latest(this,window)
                ui.post{
                    foregroundQuery=false
                    if(!connected)return@post
                    latest?.let{(component,at)->if(at>frontAt){front=component;frontAt=at}}
                    if(active()&&!allowedStock()&&stock(front))launch()
                }
            }
        }
        return front
    }
    private fun inspectFront(woke:Boolean=false){
        if(!active()||allowedStock())return
        refresh();val current=readFront()
        val stockTask=woke&&current?.packageName in homes.map{it.packageName}
        if(stock(current)||stockTask||(woke&&current==null&&!DesktopTakeover.revivedOften(this)&&!DesktopPolicy.startsOnSignalSource(this)))launch()
    }
    private fun settle(boot:Boolean=false){
        ui.removeCallbacks(recheck)
        if(boot)ui.post(recheck)else ui.postDelayed(recheck,350)
        if(boot)bootChecks.forEachIndexed{i,task->ui.removeCallbacks(task);ui.postDelayed(task,listOf(1200L,4000L,12000L,25000L)[i])}
        else ui.postDelayed({inspectFront(woke=true)},700)
    }
    private val watch=object:Runnable{override fun run(){
        if(!connected)return
        recoverAfterInstaller()
        configureKeys()
        if(active()&&!allowedStock()&&Hub.activity?.visible!=true&&(DesktopPolicy.watchForeground(this@DesktopGuardService)||deaf)){
            readFront(30000);if(stock(front))launch()
        }
        ui.postDelayed(this,if(DesktopPolicy.watchForeground(this@DesktopGuardService)||deaf)1000 else 5000)
    }}
    private fun recoverAfterInstaller(){
        if(!InstallHandoff.active(this)||!DesktopTakeover.wanted(this))return
        val pkg=runCatching{rootInActiveWindow?.packageName?.toString()}.getOrNull()?:return
        if(InstallHandoff.returnedToStock(this,homes.any{it.packageName==pkg}))launch(explicit=true)
    }
    private val afterHome=object:Runnable{override fun run(){
        if(!active()||SystemClock.uptimeMillis()-homeAt>6000)return
        val component=readFront()
        if(component?.packageName=="com.xgimi.framework"&&component.className.endsWith(".resolver.ResolverActivity")){launch(explicit=true);return}
        if(stock(component)){
            allowStockUntil=0;launch(explicit=true)
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
        ui.removeCallbacks(packageRefresh);ui.postDelayed(packageRefresh,1500)
    }}
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
        when(intent.action){
            Intent.ACTION_CLOSE_SYSTEM_DIALOGS->if(intent.getStringExtra("reason")=="homekey"&&active()&&SystemClock.uptimeMillis()-ownHomeAt>1500){
                allowStockUntil=0;sourceGraceUntil=0;homeAt=SystemClock.uptimeMillis();homeWindows=windowCount;DesktopRecovery.noteHomeKey(this@DesktopGuardService)
                if(DesktopPolicy.backOnPause(this@DesktopGuardService)&&(Hub.activity?.visible==true||homeAt-lastLaunch in 0..1000)){
                    homeInOneAt=homeAt
                    lastHomeInOneAt=homeAt
                }
                if(front==null||stock(front))launch(explicit=true)
                ui.removeCallbacks(afterHome);ui.postDelayed(afterHome,if(deaf)150 else 300);ui.postDelayed(afterHome,1500)
                ui.removeCallbacks(switchHold);ui.postDelayed(switchHold,5500)
            }
            Intent.ACTION_SCREEN_ON,Intent.ACTION_USER_UNLOCKED->{DesktopTakeover.afterUnlock(this@DesktopGuardService);refresh(force=true);settle();ui.removeCallbacks(watch);ui.post(watch)}
            Intent.ACTION_SCREEN_OFF->{ui.removeCallbacks(hold);ui.removeCallbacks(watch);homeDown=false;configureKeys();DesktopTakeover.suspendForStandby(this@DesktopGuardService)}
        }
    }}
    private val inputListener=object:InputManager.InputDeviceListener{
        override fun onInputDeviceAdded(deviceId:Int)=scanPads()
        override fun onInputDeviceRemoved(deviceId:Int)=scanPads()
        override fun onInputDeviceChanged(deviceId:Int)=scanPads()
    }
    private fun scanPads(){gamepad=runCatching{InputDevice.getDeviceIds().any{id->InputDevice.getDevice(id)?.let{!it.isVirtual&&(it.vendorId!=1||it.productId!=1)&&it.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD}==true}}.getOrDefault(false);configureKeys()}
    private fun configureKeys(){
        worker.execute{
        val info=serviceInfo?:return@execute
        val padGame=gamepad&&Hub.activity?.visible!=true&&front?.packageName?.let{it!=packageName&&!stock(front)}==true
        val filter=active()&&!padGame&&!DesktopPolicy.passKeys(this)&&!DesktopPolicy.volumeFilterUnsafe(this)&&(Build.VERSION.SDK_INT<=30||!DesktopPolicy.backOnPause(this)||Hub.activity?.visible==true)
        val flag=AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        var next=if(filter||homeDown)info.flags or flag else info.flags and flag.inv()
        if(DesktopTakeover.unlocked(this)&&DeviceAdapters.profile(this).neverFilterKeys)next=next and flag.inv()
        if(filter&&Build.VERSION.SDK_INT<=23){
            val others=runCatching{(getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).installedAccessibilityServiceList}.getOrDefault(emptyList())
            val enabled=android.provider.Settings.Secure.getString(contentResolver,android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').mapNotNull{ComponentName.unflattenFromString(it)}.toSet()
            if(others.any{ComponentName.unflattenFromString(it.id) in enabled&&it.resolveInfo?.serviceInfo?.packageName!=packageName&&it.flags and flag!=0&&it.flags and AccessibilityServiceInfo.DEFAULT==0})next=next and AccessibilityServiceInfo.DEFAULT.inv()
        }
        if(next!=info.flags){info.flags=next;serviceInfo=info}
        }
    }
    override fun onServiceConnected(){
        instance=this;connected=true;refresh();scanPads()
        Hub.activity?.desktopConnectionChanged()
        if(!registered){
            androidx.core.content.ContextCompat.registerReceiver(this,receiver,IntentFilter(Intent.ACTION_SCREEN_ON).apply{addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);addAction(Intent.ACTION_USER_UNLOCKED)},androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this,packageReceiver,IntentFilter().apply{addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addAction(Intent.ACTION_PACKAGE_REPLACED);addDataScheme("package")},androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            (getSystemService(Context.INPUT_SERVICE) as? InputManager)?.registerInputDeviceListener(inputListener,ui);registered=true
        }
        if(!DesktopTakeover.unlocked(this))DesktopTakeover.afterUnlock(this)
        else if(DesktopTakeover.wanted(this))settle(boot=SystemClock.elapsedRealtime()<180000)
        if(SystemClock.elapsedRealtime()<180000){
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
        if(event.keyCode!=KeyEvent.KEYCODE_HOME||(!homeDown&&!active()))return false
        if(event.action==KeyEvent.ACTION_DOWN){if(event.repeatCount==0){
            homeDown=true;homeHeld=false;ui.removeCallbacks(hold)
            if(event.eventTime-lastHomeUp in 0..400){homeHeld=false;lastHomeUp=-3000;if(Hub.activity?.visible==true){ui.post{Hub.activity?.showHome()}}else launch(explicit=true)}
            else ui.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong())
        };return true}
        if(event.action==KeyEvent.ACTION_UP&&homeDown){homeDown=false;ui.removeCallbacks(hold);if(!event.isCanceled&&!homeHeld){lastHomeUp=event.eventTime;allowStockUntil=0;sourceGraceUntil=0;launch(explicit=true)};configureKeys();return true}
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
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        if(event?.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return
        val pkg=event.packageName?.toString()?:return;val cls=event.className?.toString()?:return
        sawWindow=true;deaf=false;windowCount++
        val component=ComponentName(pkg,if(cls.startsWith('.'))pkg+cls else cls)
        if(pkg==packageName){if(isActivity(component)){front=component;frontAt=System.currentTimeMillis()};if(recentsOnResume){recentsOnResume=false;ui.post{openRecents()}};return}
        if(!active())return
        if(Build.VERSION.SDK_INT<=22&&pkg=="com.jamdeo.launcher"&&cls=="android.widget.RelativeLayout"&&homes.any{it.packageName==pkg}){performGlobalAction(GLOBAL_ACTION_BACK);launch();return}
        if(firmwarePushedAtBoot(pkg)&&isActivity(component)){launch();return}
        refresh()
        if(pkg in DesktopPolicy.sourceSwitchers)sourceGraceUntil=SystemClock.uptimeMillis()+3000
        if(stock(component))DesktopRecovery.noteStockHomeInFront(this)
        if(bootStockHolding&&!bootStockShown&&stock(component)){
            bootStockShown=true
            ui.removeCallbacks(endBootStockHold)
            ui.postDelayed(endBootStockHold,bootStockMs.toLong())
        }
        if(stock(component)||isActivity(component)){front=component;frontAt=System.currentTimeMillis()}
        if(!allowedStock()&&stock(component)){
            ui.removeCallbacks(recheck)
            if(SystemClock.uptimeMillis()<sourceGraceUntil)ui.postDelayed(recheck,sourceGraceUntil-SystemClock.uptimeMillis())else launch()
        }
        configureKeys()
    }
    private fun launch(explicit:Boolean=false){
        if(!active()||(!explicit&&allowedStock()))return
        if(!explicit&&(BrowserActivity.active!=null))return
        if(Hub.activity?.visible==true){if(stock(front)){ui.removeCallbacks(recheck);ui.postDelayed(recheck,150)};return}
        if(SystemClock.uptimeMillis()-lastLaunch<700){ui.removeCallbacks(recheck);ui.postDelayed(recheck,750);return}
        lastLaunch=SystemClock.uptimeMillis()
        val boot=!explicit&&SystemClock.elapsedRealtime()<180000&&!BootReceiver.arrivedThisBoot(this)
        val live=getSharedPreferences("startup",0).getBoolean("live",false)
        val reopenBootTarget=!explicit&&SystemClock.elapsedRealtime()<180000&&live&&!DesktopRecovery.homeKeyWithin(this,5000)&&DesktopRecovery.bootTargetOpenedWithin(this,60000)
        val autoLive=(boot||reopenBootTarget)&&live
        try{startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("show_home",!autoLive).putExtra("from_boot",boot).putExtra("reopen_boot",reopenBootTarget))}
        catch(e:RuntimeException){getSharedPreferences("startup",0).edit().putString("guard_error",e.javaClass.simpleName).apply()}
    }
    override fun onInterrupt(){}
    private fun disconnect(){
        if(instance===this){instance=null;connected=false}
        Hub.activity?.desktopConnectionChanged()
        ui.removeCallbacksAndMessages(null)
        if(registered){runCatching{unregisterReceiver(receiver)};runCatching{unregisterReceiver(packageReceiver)};(getSystemService(Context.INPUT_SERVICE) as? InputManager)?.unregisterInputDeviceListener(inputListener);registered=false}
    }
    override fun onUnbind(intent:Intent?):Boolean{disconnect();return super.onUnbind(intent)}
    override fun onDestroy(){disconnect();super.onDestroy()}
}
