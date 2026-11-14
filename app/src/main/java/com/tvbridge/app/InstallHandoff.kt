package com.tvbridge.app

import android.content.Context

internal object InstallHandoff {
    private const val KEY="installer_started"
    private const val LEFT="installer_left_home"
    private fun prefs(context:Context)=context.getSharedPreferences("startup",0)
    fun begin(context:Context){DesktopTakeover.cancel(context);prefs(context).edit().putLong(KEY,System.currentTimeMillis()).remove(LEFT).commit()}
    fun opened(context:Context){if(active(context))prefs(context).edit().putBoolean(LEFT,true).commit()}
    fun returnedToStock(context:Context,stockInFront:Boolean):Boolean {
        if(!stockInFront||!active(context)||!prefs(context).getBoolean(LEFT,false))return false
        val updated=try{context.packageManager.getPackageInfo(context.packageName,0).lastUpdateTime}catch(_:Exception){return false}
        if(updated<prefs(context).getLong(KEY,Long.MAX_VALUE))return false
        finish(context);return true
    }
    fun active(context:Context):Boolean {
        val at=prefs(context).getLong(KEY,0)
        return at>0&&System.currentTimeMillis()-at in 0..15*60_000L
    }
    fun finish(context:Context){prefs(context).edit().remove(KEY).remove(LEFT).apply()}
}
