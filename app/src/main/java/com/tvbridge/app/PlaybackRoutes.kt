package com.tvbridge.app

import android.content.Context

class PlaybackRoutes(context:Context){
    private val prefs=context.getSharedPreferences("playback-routes",0)
    private var channel:LiveChannel?=null
    private val failed=mutableSetOf<Int>()
    fun begin(value:LiveChannel):Int {
        channel=value;failed.clear()
        val remembered=prefs.getString(PlaylistParser.digest(value.id),null)
        return value.streams.indexOfFirst{PlaylistParser.digest(it)==remembered}.coerceAtLeast(0)
    }
    fun rendered(index:Int){
        val value=channel?:return;val url=value.streams.getOrNull(index)?:return
        failed.clear()
        if(value.id=="cast"||LiveCatalog.isPage(url))return
        val key=PlaylistParser.digest(value.id);val digest=PlaylistParser.digest(url)
        if(prefs.getString(key,null)==digest)return
        val edit=prefs.edit()
        if(prefs.all.size>=200&&!prefs.contains(key))prefs.all.keys.take(50).forEach{edit.remove(it)}
        edit.putString(key,digest).apply()
    }
    fun next(index:Int):Int? {
        val value=channel?:return null;failed.add(index)
        return (1..value.streams.size).map{(index+it)%value.streams.size}.firstOrNull{it !in failed}
    }
    fun manual(){failed.clear()}
}
