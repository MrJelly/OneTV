package com.tvbridge.app

import android.content.Context
import android.os.SystemClock

internal object BootOpen {
    const val NONE=0
    const val PENDING=1
    const val DONE=2
    private val procStart=SystemClock.elapsedRealtime()
    private fun prefs(context:Context)=context.getSharedPreferences("startup",0)
    fun armWake(context:Context){
        val p=prefs(context)
        p.edit().putLong("boot_open_wake_seq",p.getLong("boot_open_wake_seq",0L)+1L).apply()
    }
    fun sync(context:Context):Int{
        val p=prefs(context)
        val id=DesktopRecovery.BOOT_ID
        val stamp=System.currentTimeMillis()-SystemClock.elapsedRealtime()
        val wake=p.getLong("boot_open_wake_seq",0L)
        val storedWake=p.getLong("boot_open_mark_wake",-1L)
        val storedId=p.getString("boot_open_mark_id",null)
        val storedStamp=p.getLong("boot_open_mark_stamp",Long.MIN_VALUE)
        val bootChanged=if(storedWake<0L)true
            else if(id!=null&&storedId!=null)id!=storedId
            else kotlin.math.abs(stamp-storedStamp)>=120000L
        val storedProc=p.getLong("boot_open_proc",-1L)
        val procChanged=storedProc!=procStart
        if(procChanged)p.edit().putLong("boot_open_proc",procStart).apply()
        if(bootChanged||wake!=storedWake||procChanged){
            val phase=if(LauncherApps(context).bootApp()!=null)PENDING else NONE
            val e=p.edit().putLong("boot_open_mark_wake",wake).putLong("boot_open_mark_stamp",stamp).putInt("boot_open_phase",phase)
            if(id!=null)e.putString("boot_open_mark_id",id) else e.remove("boot_open_mark_id")
            e.apply()
            return phase
        }
        val phase=p.getInt("boot_open_phase",NONE)
        if(phase==PENDING&&LauncherApps(context).bootApp()==null){p.edit().putInt("boot_open_phase",NONE).apply();return NONE}
        return phase
    }
    fun pending(context:Context)=sync(context)==PENDING
    fun done(context:Context){if(sync(context)==PENDING)prefs(context).edit().putInt("boot_open_phase",DONE).apply()}
    fun noteOpened(context:Context,id:String){if(pending(context)&&LauncherApps(context).bootApp()==id)done(context)}
    fun label(context:Context)=when(sync(context)){PENDING->"待打开";DONE->"已完成";else->"未设置"}
}
