package com.tvbridge.app

import android.content.Context

object LiveCatalog {
    const val SOURCE="one-plugin://live"
    data class Result(val channels:List<LiveChannel>,val error:String="")
    fun isPage(url:String):Boolean=try{
        val uri=android.net.Uri.parse(url)
        uri.scheme=="one-plugin"&&uri.host=="live"&&uri.userInfo==null&&uri.port == -1&&uri.query==null&&uri.fragment==null&&uri.path?.matches(Regex("/[0-9]{6,12}"))==true
    }catch(_:Exception){false}
    fun available(context:Context)=LiveLaunch.builtIn(context)
    fun channels(context:Context)=YangshipinCatalog.channels(context.assets)
    fun read(context:Context)=try{Result(channels(context))}catch(_:Exception){Result(emptyList(),"直播频道暂不可用，请重试")}
}
