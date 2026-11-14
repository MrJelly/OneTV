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
    private const val STOCK_HOME_AT="stock_home_at"
    private const val STOCK_HOME_BOOT="stock_home_boot"
    private const val HOME_KEY_AT="home_key_at"
    private const val HOME_KEY_BOOT="home_key_boot"
    private const val BOOT_TARGET_AT="boot_target_at"
    private const val BOOT_TARGET_BOOT="boot_target_boot"
    private const val SAME_BOOT_SLACK_MS=120000L
    private fun bootStamp()=System.currentTimeMillis()-SystemClock.elapsedRealtime()
    private fun thisBoot(elapsed:Long,stamp:Long)=elapsed in 0..SystemClock.elapsedRealtime()&&kotlin.math.abs(stamp-bootStamp())<SAME_BOOT_SLACK_MS
    private fun startupPrefs(context:Context)=context.getSharedPreferences("startup",Context.MODE_PRIVATE)
    fun setHomeInFront(context:Context,inFront:Boolean){
        startupPrefs(context).edit().putBoolean(HOME_FRONT,inFront).putLong(HOME_FRONT_AT,SystemClock.elapsedRealtime()).putLong(HOME_FRONT_BOOT,bootStamp()).apply()
    }
    fun noteStockHomeInFront(context:Context){
        startupPrefs(context).edit().putLong(STOCK_HOME_AT,SystemClock.elapsedRealtime()).putLong(STOCK_HOME_BOOT,bootStamp()).apply()
    }
    fun stockHomeSinceHomeLeft(context:Context):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(STOCK_HOME_AT,-1L)
        if(!thisBoot(at,prefs.getLong(STOCK_HOME_BOOT,0L)))return false
        return at>=prefs.getLong(HOME_FRONT_AT,-1L)
    }
    fun stockHomeSeenWithin(context:Context,ms:Long):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(STOCK_HOME_AT,-1L)
        if(!thisBoot(at,prefs.getLong(STOCK_HOME_BOOT,0L)))return false
        return SystemClock.elapsedRealtime()-at<ms
    }
    fun startAfterStopOverStock(context:Context):Boolean{
        if(Build.VERSION.SDK_INT<24||DesktopGuardService.connected||InstallHandoff.active(context))return false
        if(SystemClock.elapsedRealtime()<180000)return false
        val power=context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if(power?.isInteractive!=true)return false
        if(!stockHomeSinceHomeLeft(context)||!stockHomeSeenWithin(context,5000))return false
        return runCatching{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("show_home",true));true}.getOrDefault(false)
    }
    fun noteHomeKey(context:Context){
        startupPrefs(context).edit().putLong(HOME_KEY_AT,SystemClock.elapsedRealtime()).putLong(HOME_KEY_BOOT,bootStamp()).apply()
    }
    fun homeKeyWithin(context:Context,ms:Long):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(HOME_KEY_AT,-1L)
        if(!thisBoot(at,prefs.getLong(HOME_KEY_BOOT,0L)))return false
        return SystemClock.elapsedRealtime()-at<ms
    }
    fun noteBootTargetOpened(context:Context){
        startupPrefs(context).edit().putLong(BOOT_TARGET_AT,SystemClock.elapsedRealtime()).putLong(BOOT_TARGET_BOOT,bootStamp()).apply()
    }
    fun bootTargetOpenedWithin(context:Context,ms:Long):Boolean{
        val prefs=startupPrefs(context);val at=prefs.getLong(BOOT_TARGET_AT,-1L)
        if(!thisBoot(at,prefs.getLong(BOOT_TARGET_BOOT,0L)))return false
        return SystemClock.elapsedRealtime()-at<ms
    }
    fun listenerEnabled(context:Context):Boolean {
        val me=ComponentName(context,DesktopRevivalService::class.java)
        return Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners").orEmpty().split(':').any{ComponentName.unflattenFromString(it)==me}
    }
    @Synchronized fun ensure(context:Context){
        if(!DesktopTakeover.wanted(context))return
        val now=SystemClock.elapsedRealtime();val app=context.applicationContext
        if(Build.VERSION.SDK_INT<24&&androidx.core.content.ContextCompat.checkSelfPermission(app,android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED&&!listenerEnabled(app)){
            val me=ComponentName(app,DesktopRevivalService::class.java).flattenToString()
            val current=Settings.Secure.getString(app.contentResolver,"enabled_notification_listeners").orEmpty()
            if(current.split(':').none{it==me})runCatching{
                Settings.Secure.putString(app.contentResolver,"enabled_notification_listeners",if(current.isEmpty())me else "$current:$me")
            }
        }
        if(now>=nextSyncCheck){
            nextSyncCheck=now+300000
            Thread({if(DesktopTakeover.wanted(app)&&!DesktopSync.enabled(app))DesktopSync.configure(app,true)},"desktop-local-recovery").start()
        }
        if(Build.VERSION.SDK_INT>=24&&listenerEnabled(app)&&!DesktopRevivalService.connected&&now>=nextListenerCheck){
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
    companion object{@Volatile var connected=false;private set}
    override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT<21){connected=true;DesktopRecovery.revived(this)}}
    override fun onNotificationPosted(notification:android.service.notification.StatusBarNotification?){}
    override fun onNotificationRemoved(notification:android.service.notification.StatusBarNotification?){}
    override fun onListenerConnected(){connected=true;DesktopRecovery.revived(this);DesktopRecovery.startAfterStopOverStock(this)}
    override fun onListenerDisconnected(){connected=false;if(Build.VERSION.SDK_INT>=24&&DesktopTakeover.wanted(this)&&DesktopRecovery.listenerEnabled(this))requestRebind(ComponentName(this,DesktopRevivalService::class.java))}
    override fun onDestroy(){connected=false;super.onDestroy()}
}
