package com.tvbridge.app

import android.app.*
import android.content.*
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService

object DesktopRecovery {
    private var nextSyncCheck=0L
    private var nextListenerCheck=0L
    private const val HOME_FRONT="home_front"
    private const val HOME_FRONT_AT="home_front_at"
    private const val HOME_FRONT_BOOT="home_front_boot"
    private const val HOME_FRONT_BOOT_ID="home_front_boot_id"
    private const val STOCK_HOME_AT="stock_home_at"
    private const val STOCK_HOME_BOOT="stock_home_boot"
    private const val STOCK_HOME_BOOT_ID="stock_home_boot_id"
    private const val HOME_KEY_AT="home_key_at"
    private const val HOME_KEY_BOOT="home_key_boot"
    private const val HOME_KEY_BOOT_ID="home_key_boot_id"
    private const val BOOT_TARGET_AT="boot_target_at"
    private const val BOOT_TARGET_BOOT="boot_target_boot"
    private const val BOOT_TARGET_BOOT_ID="boot_target_boot_id"
    private const val SCREEN_OFF_AT="screen_off_at"
    private const val SCREEN_OFF_BOOT="screen_off_boot"
    private const val SCREEN_OFF_BOOT_ID="screen_off_boot_id"
    private const val STOCK_VISIT_AT="stock_visit_at"
    private const val STOCK_VISIT_UNTIL="stock_visit_until"
    private const val STOCK_VISIT_BOOT="stock_visit_boot"
    private const val STOCK_VISIT_BOOT_ID="stock_visit_boot_id"
    private const val STOCK_VISIT_PACKAGE="stock_visit_package"
    private const val SAME_BOOT_SLACK_MS=120000L
    @Volatile var leftForAnApp=false
        private set
    internal val BOOT_ID:String? by lazy{
        runCatching{java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()}.getOrNull()?.takeIf{it.isNotEmpty()}
    }
    private fun bootStamp()=System.currentTimeMillis()-SystemClock.elapsedRealtime()
    private fun thisBoot(prefs:SharedPreferences,elapsed:Long,stampKey:String,idKey:String):Boolean{
        if(elapsed !in 0..SystemClock.elapsedRealtime())return false
        val id=prefs.getString(idKey,null);val now=BOOT_ID
        if(id!=null&&now!=null)return id==now
        return kotlin.math.abs(prefs.getLong(stampKey,0L)-bootStamp())<SAME_BOOT_SLACK_MS
    }
    private fun startupPrefs(context:Context)=context.getSharedPreferences("startup",Context.MODE_PRIVATE)
    fun noteLeftForApp(){leftForAnApp=true}
    fun setHomeInFront(context:Context,inFront:Boolean){
        if(inFront)leftForAnApp=false
        startupPrefs(context).edit().putBoolean(HOME_FRONT,inFront).putLong(HOME_FRONT_AT,SystemClock.elapsedRealtime()).putLong(HOME_FRONT_BOOT,bootStamp()).putString(HOME_FRONT_BOOT_ID,BOOT_ID).apply()
    }
    fun homeLeftAt(context:Context):Long?{
        val prefs=startupPrefs(context);val at=prefs.getLong(HOME_FRONT_AT,-1L)
        if(!thisBoot(prefs,at,HOME_FRONT_BOOT,HOME_FRONT_BOOT_ID)||prefs.getBoolean(HOME_FRONT,false))return null
        return at
    }
    fun noteScreenOff(context:Context){
        if(!DesktopTakeover.unlocked(context))return
        startupPrefs(context).edit().putLong(SCREEN_OFF_AT,SystemClock.elapsedRealtime()).putLong(SCREEN_OFF_BOOT,bootStamp()).putString(SCREEN_OFF_BOOT_ID,BOOT_ID).commit()
    }
    fun screenOffSince(context:Context,elapsed:Long):Boolean{
        val prefs=startupPrefs(context);val off=prefs.getLong(SCREEN_OFF_AT,-1L)
        return off>=0&&thisBoot(prefs,off,SCREEN_OFF_BOOT,SCREEN_OFF_BOOT_ID)&&off>elapsed
    }
    fun diedAwayFromHome(context:Context):Boolean{
        val leftAt=homeLeftAt(context)?:return false
        return !stockHomeSinceHomeLeft(context)&&!screenOffSince(context,leftAt)
    }
    fun noteStockVisit(context:Context,untilElapsed:Long?,targetPackage:String?=null){
        if(!DesktopTakeover.unlocked(context))return
        val prefs=startupPrefs(context)
        if(untilElapsed==null){
            if(!prefs.contains(STOCK_VISIT_UNTIL))return
            prefs.edit().remove(STOCK_VISIT_AT).remove(STOCK_VISIT_UNTIL).remove(STOCK_VISIT_BOOT).remove(STOCK_VISIT_BOOT_ID).remove(STOCK_VISIT_PACKAGE).apply()
            return
        }
        prefs.edit().putLong(STOCK_VISIT_AT,SystemClock.elapsedRealtime()).putLong(STOCK_VISIT_UNTIL,untilElapsed).putLong(STOCK_VISIT_BOOT,bootStamp()).putString(STOCK_VISIT_BOOT_ID,BOOT_ID).putString(STOCK_VISIT_PACKAGE,targetPackage).commit()
    }
    fun stockVisitPackage(context:Context)=if(stockVisitLeftMs(context)>0)startupPrefs(context).getString(STOCK_VISIT_PACKAGE,null) else null
    fun stockVisitAgeMs(context:Context):Long=if(stockVisitLeftMs(context)>0)(SystemClock.elapsedRealtime()-startupPrefs(context).getLong(STOCK_VISIT_AT,0)).coerceAtLeast(0) else Long.MAX_VALUE
    fun stockVisitLeftMs(context:Context):Long{
        if(!DesktopTakeover.unlocked(context))return 0L
        val prefs=startupPrefs(context);val at=prefs.getLong(STOCK_VISIT_AT,-1L)
        if(!thisBoot(prefs,at,STOCK_VISIT_BOOT,STOCK_VISIT_BOOT_ID))return 0L
        return (prefs.getLong(STOCK_VISIT_UNTIL,0L)-SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }
    fun noteStockHomeInFront(context:Context){
        startupPrefs(context).edit().putLong(STOCK_HOME_AT,SystemClock.elapsedRealtime()).putLong(STOCK_HOME_BOOT,bootStamp()).putString(STOCK_HOME_BOOT_ID,BOOT_ID).apply()
    }
    fun stockHomeSinceHomeLeft(context:Context):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(STOCK_HOME_AT,-1L)
        if(!thisBoot(prefs,at,STOCK_HOME_BOOT,STOCK_HOME_BOOT_ID))return false
        return at>=prefs.getLong(HOME_FRONT_AT,-1L)
    }
    fun stockHomeSeenWithin(context:Context,ms:Long):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(STOCK_HOME_AT,-1L)
        if(!thisBoot(prefs,at,STOCK_HOME_BOOT,STOCK_HOME_BOOT_ID))return false
        return SystemClock.elapsedRealtime()-at<ms
    }
    fun startAfterStopOverStock(context:Context):Boolean{
        if(Build.VERSION.SDK_INT<24||DesktopGuardService.connected||InstallHandoff.active(context))return false
        if(SystemClock.elapsedRealtime()<180000)return false
        if(!DesktopTakeover.screenReady(context))return false
        if(!stockHomeSinceHomeLeft(context)||!stockHomeSeenWithin(context,5000))return false
        return runCatching{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("show_home",true));true}.getOrDefault(false)
    }
    fun noteHomeKey(context:Context){
        startupPrefs(context).edit().putLong(HOME_KEY_AT,SystemClock.elapsedRealtime()).putLong(HOME_KEY_BOOT,bootStamp()).putString(HOME_KEY_BOOT_ID,BOOT_ID).apply()
    }
    fun homeKeyWithin(context:Context,ms:Long):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(HOME_KEY_AT,-1L)
        if(!thisBoot(prefs,at,HOME_KEY_BOOT,HOME_KEY_BOOT_ID))return false
        return SystemClock.elapsedRealtime()-at<ms
    }
    fun noteBootTargetOpened(context:Context){
        leftForAnApp=true
        startupPrefs(context).edit().putLong(BOOT_TARGET_AT,SystemClock.elapsedRealtime()).putLong(BOOT_TARGET_BOOT,bootStamp()).putString(BOOT_TARGET_BOOT_ID,BOOT_ID).commit()
    }
    fun bootTargetOpenedWithin(context:Context,ms:Long):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(BOOT_TARGET_AT,-1L)
        if(!thisBoot(prefs,at,BOOT_TARGET_BOOT,BOOT_TARGET_BOOT_ID))return false
        return SystemClock.elapsedRealtime()-at<ms
    }
    fun listenerEnabled(context:Context):Boolean {
        val me=ComponentName(context,DesktopRevivalService::class.java)
        return Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners").orEmpty().split(':').any{ComponentName.unflattenFromString(it)==me}
    }
    private fun listenerListed(context:Context)=listenerEnabled(context)
    @Synchronized fun ensure(context:Context){
        if(!DesktopTakeover.wanted(context))return
        val now=SystemClock.elapsedRealtime();val app=context.applicationContext
        if(Build.VERSION.SDK_INT<24&&androidx.core.content.ContextCompat.checkSelfPermission(app,android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED&&!listenerListed(app)){
            val me=ComponentName(app,DesktopRevivalService::class.java).flattenToString()
            val current=Settings.Secure.getString(app.contentResolver,"enabled_notification_listeners").orEmpty()
            if(current.split(':').none{it==me})runCatching{
                Settings.Secure.putString(app.contentResolver,"enabled_notification_listeners",if(current.isEmpty())me else "$current:$me")
            }
        }
        if(now>=nextSyncCheck){
            nextSyncCheck=now+300000
            AppThreads.onIo{if(DesktopTakeover.wanted(app)&&!DesktopSync.enabled(app))DesktopSync.configure(app,true)}
        }
        if(Build.VERSION.SDK_INT>=24&&listenerListed(app)&&!DesktopRevivalService.connected&&now>=nextListenerCheck){
            nextListenerCheck=now+30000
            runCatching{NotificationListenerService.requestRebind(ComponentName(app,DesktopRevivalService::class.java))}
        }
    }
    fun revived(context:Context){
        if(!DesktopTakeover.unlocked(context)){DesktopTakeover.afterUnlock(context);return}
        if(!DesktopTakeover.wanted(context))return
        if(!DesktopGuardService.connected)DesktopTakeover.noteRevival(context)
        DesktopTakeover.recover(context)
        if(!RemoteHostService.running)runCatching{androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,RemoteHostService::class.java))}
    }
    fun offer(activity:Activity){
        if(listenerEnabled(activity))return
        val lowRam=Build.VERSION.SDK_INT==28&&(activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice==true
        if(lowRam){NativeUi.notice(activity,"此系统限制通知恢复，已使用本地恢复账号");return}
        NativeUi.show(AlertDialog.Builder(activity).setTitle("桌面后台恢复")
            .setMessage("允许系统重新唤醒 One，恢复被清理的桌面守护。Android 会申请通知访问权限；One 不读取或保存通知内容。本地恢复账号也会同步启用，不联网同步数据。")
            .setPositiveButton("前往授权"){_,_->
                try{
                    val intent=if(Build.VERSION.SDK_INT>=30)Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(activity,DesktopRevivalService::class.java).flattenToString())else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    activity.startActivity(intent)
                }catch(_:RuntimeException){try{activity.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))}catch(_:RuntimeException){NativeUi.notice(activity,"系统未提供通知授权入口，使用本地恢复")}}
            }.setNegativeButton("暂不授权",null))
    }
    fun stop(context:Context){
        nextSyncCheck=0;nextListenerCheck=0
        if(DesktopSync.enabled(context))DesktopSync.configure(context,false)
        if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED)runCatching{
            val me=ComponentName(context,DesktopRevivalService::class.java)
            val others=Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners").orEmpty().split(':').filter{it.isNotBlank()&&ComponentName.unflattenFromString(it)!=me}
            Settings.Secure.putString(context.contentResolver,"enabled_notification_listeners",others.joinToString(":"))
        }
    }
}

class DesktopRevivalService:NotificationListenerService(){
    companion object{
        @Volatile var connected=false;private set
        private val RETRY_DELAYS_MS=longArrayOf(5000,5000,10000,15000,30000,60000)
    }
    private val handler=Handler(Looper.getMainLooper())
    private var recovering=false
    private var destroyed=false
    private var attempt=0
    private var watchingUnlock=false
    private val unlocked=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){
            DesktopRecovery.revived(this@DesktopRevivalService)
            startRecovery()
        }
    }
    private val check=object:Runnable{
        override fun run(){
            if(destroyed||!recovering)return
            if(DesktopGuardService.connected){recovering=false;return}
            if(attempt>=RETRY_DELAYS_MS.size){recovering=false;return}
            val delay=RETRY_DELAYS_MS[attempt++]
            if(DesktopTakeover.unlocked(this@DesktopRevivalService)&&DesktopTakeover.wanted(this@DesktopRevivalService)){
                DesktopRecovery.revived(this@DesktopRevivalService)
                DesktopTakeover.rebindIfStalled(this@DesktopRevivalService)
            }
            handler.postDelayed(this,delay)
        }
    }
    private fun startRecovery(){
        if(destroyed||recovering)return
        recovering=true;attempt=0
        handler.removeCallbacks(check)
        handler.postDelayed(check,RETRY_DELAYS_MS[0])
    }
    override fun onCreate(){
        super.onCreate()
        if(Build.VERSION.SDK_INT<21){connected=true;DesktopRecovery.revived(this)}
        if(Build.VERSION.SDK_INT>=24){
            runCatching{
                androidx.core.content.ContextCompat.registerReceiver(this,unlocked,IntentFilter(Intent.ACTION_USER_UNLOCKED),androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
                watchingUnlock=true
            }
        }
        startRecovery()
    }
    override fun onNotificationPosted(notification:android.service.notification.StatusBarNotification?){}
    override fun onNotificationRemoved(notification:android.service.notification.StatusBarNotification?){}
    override fun onListenerConnected(){
        if(destroyed)return
        connected=true
        if(DesktopTakeover.unlocked(this)){
            DesktopRecovery.revived(this)
            DesktopRecovery.startAfterStopOverStock(this)
        }
        startRecovery()
    }
    override fun onListenerDisconnected(){connected=false;if(Build.VERSION.SDK_INT>=24&&DesktopTakeover.wanted(this)&&DesktopRecovery.listenerEnabled(this))requestRebind(ComponentName(this,DesktopRevivalService::class.java))}
    override fun onDestroy(){
        destroyed=true;recovering=false;connected=false
        handler.removeCallbacks(check)
        if(watchingUnlock)runCatching{unregisterReceiver(unlocked)}
        watchingUnlock=false
        super.onDestroy()
    }
}
