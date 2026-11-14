package com.tvbridge.app

import java.net.URI
import java.util.Locale

class PlaybackAttempts {
    companion object {
        fun waitForMedia(bufferingMs:Long,sinceMediaMs:Long):Boolean = bufferingMs<60_000&&sinceMediaMs<20_000
    }
    enum class Format(val mime:String?) {
        AUTO(null), HLS("application/x-mpegURL"), DASH("application/dash+xml"),
        SMOOTH("application/vnd.ms-sstr+xml"), PROGRESSIVE("video/mp2t")
    }
    private var candidates=emptyList<Format>()
    private var index=0
    var softwareTracks=0; private set
    val software get()=softwareTracks!=0
    var rtspTcp=false; private set
    private var rtsp=false
    val format get()=candidates.getOrElse(index){Format.AUTO}
    fun reset(url:String){
        val uri=try{URI(url)}catch(_:Exception){null}
        rtsp=uri?.scheme.equals("rtsp",true);softwareTracks=0;rtspTcp=false;index=0
        val path=uri?.path.orEmpty().lowercase(Locale.ROOT)
        val initial=when {
            path.endsWith(".m3u8")->Format.HLS
            path.endsWith(".mpd")->Format.DASH
            Regex("\\.ism[l]?(/manifest.*)?$").containsMatchIn(path)->Format.SMOOTH
            else->if(uri?.scheme?.lowercase(Locale.ROOT) in listOf("http","https"))Format.PROGRESSIVE else Format.AUTO
        }
        candidates=if(uri?.scheme?.lowercase(Locale.ROOT) in listOf("http","https"))
            (listOf(initial,Format.PROGRESSIVE,Format.HLS,Format.DASH,Format.SMOOTH)).distinct()
        else listOf(Format.AUTO)
    }
    fun nextFormat():Boolean {if(index+1>=candidates.size)return false;index++;return true}
    fun softwareFallback(available:Boolean,tracks:Int=2):Boolean {
        val requested=tracks and 3
        if(!available||requested==0||(softwareTracks and requested)==requested)return false
        softwareTracks=softwareTracks or requested;return true
    }
    fun tcpFallback():Boolean {if(!rtsp||rtspTcp)return false;rtspTcp=true;return true}
}
