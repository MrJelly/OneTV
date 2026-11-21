package com.tvbridge.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

internal class HomeScreenSaver(private val activity:Activity,private val homeVisible:()->Boolean){
    private val ui=Handler(Looper.getMainLooper())
    private var resumed=false
    private var generation=0
    private var component:ComponentName?=null
    private var lastInput=SystemClock.uptimeMillis()
    private val idleMs=5*60_000L
    private val check=Runnable{
        if(resumed&&homeVisible()&&SystemClock.uptimeMillis()-lastInput>=idleMs){
            component?.let{target->runCatching{
                activity.startActivity(Intent(Intent.ACTION_MAIN).setComponent(target))
            }}
        }
    }
    fun resume(){
        resumed=true;lastInput=SystemClock.uptimeMillis()
        val ticket=++generation
        AppThreads.onIo{
            val target=runCatching{
                val resolver=activity.contentResolver
                val dream=Settings.Secure.getString(resolver,"screensaver_components").orEmpty()
                val timeout=Settings.System.getLong(resolver,Settings.System.SCREEN_OFF_TIMEOUT,0L)
                if(!dream.startsWith("com.dangbei.")||Settings.Secure.getInt(resolver,"screensaver_enabled",0)!=1||timeout<=30*60_000L)null
                else{
                    val entry=ComponentName("com.android.systemui","com.android.systemui.Somnambulator")
                    activity.packageManager.getActivityInfo(entry,0).takeIf{it.enabled&&it.exported&&
                        (it.permission.isNullOrEmpty()||activity.checkCallingOrSelfPermission(it.permission)==PackageManager.PERMISSION_GRANTED)}?.let{entry}
                }
            }.getOrNull()
            ui.post{if(resumed&&ticket==generation){component=target;arm()}}
        }
    }
    fun pause(){resumed=false;generation++;ui.removeCallbacks(check)}
    fun input(){lastInput=SystemClock.uptimeMillis();arm()}
    private fun arm(){
        ui.removeCallbacks(check)
        if(resumed&&component!=null)ui.postDelayed(check,(idleMs-(SystemClock.uptimeMillis()-lastInput)).coerceAtLeast(0L))
    }
}
