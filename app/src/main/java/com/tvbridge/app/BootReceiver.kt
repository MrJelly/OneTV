package com.tvbridge.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_LOCKED_BOOT_COMPLETED,Intent.ACTION_USER_UNLOCKED,Intent.ACTION_MY_PACKAGE_REPLACED,"com.dangbei.action.BOOT_COMPLETED","com.dangbei.action.STR_BOOT_COMPLETED"))return
        val pending=goAsync()
        Thread({
            try{onBoot(context,intent)}finally{pending.finish()}
        },"OneBoot").start()
    }
    private fun onBoot(context: Context, intent: Intent) {
        val app=context.applicationContext
        if(!DesktopTakeover.unlocked(app)){DesktopTakeover.afterUnlock(app);return}
        val prefs=app.getSharedPreferences("startup",Context.MODE_PRIVATE)
        if(!prefs.getBoolean("boot",false))return
        if(intent.action=="com.dangbei.action.STR_BOOT_COMPLETED"&&!DesktopTakeover.returnsAfterStandby(app))return
        if(intent.action==Intent.ACTION_MY_PACKAGE_REPLACED){DesktopTakeover.recover(app);return}
        if(DesktopGuardService.connected&&DesktopTakeover.wanted(app)){
            DesktopGuardService.onBootCompleted()
            DesktopTakeover.recover(app)
            return
        }
        if(DesktopPolicy.startsOnSignalSource(app)){DesktopTakeover.recover(app);return}
        val wake=intent.action=="com.dangbei.action.STR_BOOT_COMPLETED"
        if(wake){
            val elapsed=SystemClock.elapsedRealtime();val previous=prefs.getLong("boot_last_wake_elapsed",-60000)
            if(elapsed-previous in 0L..15000L)return
            prefs.edit().putLong("boot_last_wake_elapsed",elapsed).apply()
        }
        val count=bootCount(app)
        prefs.edit().putString("boot_action",intent.action).putLong("boot_received_at",System.currentTimeMillis()).putString("boot_status","received").apply()
        if(!wake&&count>=0&&prefs.getInt("boot_arrived_count",-2)==count){prefs.edit().putString("boot_status","already_arrived").apply();return}
        if(!InstallHandoff.active(app)&&Hub.activity?.visible!=true&&BrowserActivity.active==null){
            DesktopTakeover.schedule(app)
            try{
                prefs.edit().putString("boot_status","launch_requested").apply()
                app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("from_boot",true))
            }catch(e:RuntimeException){
                prefs.edit().putString("boot_status","launch_rejected").putString("boot_error",e.javaClass.simpleName).apply()
            }
        }
        DesktopTakeover.recover(app)
    }
    companion object {
        private fun bootCount(context:Context)=if(Build.VERSION.SDK_INT>=24)Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)else -1
        fun arrivedThisBoot(context:Context):Boolean {
            if(!DesktopTakeover.unlocked(context))return false
            val prefs=context.getSharedPreferences("startup",Context.MODE_PRIVATE);val count=bootCount(context)
            if(count>=0)return prefs.getInt("boot_arrived_count",-2)==count
            val arrived=prefs.getLong("boot_arrived_at",0);val now=System.currentTimeMillis()
            return arrived>0&&arrived in (now-SystemClock.elapsedRealtime())..now
        }
        fun markArrived(context:Context){DesktopTakeover.cancel(context);context.getSharedPreferences("startup",Context.MODE_PRIVATE).edit().putString("boot_status","activity_arrived").putLong("boot_arrived_at",System.currentTimeMillis()).putInt("boot_arrived_count",bootCount(context)).apply()}
    }
}
