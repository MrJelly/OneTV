package com.tvbridge.app

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ResolvingDataSource

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object NativePlayback {
    fun softwareAvailable():Boolean=NativeDecoders.available()
    fun loadControl(context:Context,startupMs:Int=2000,live:Boolean=false):DefaultLoadControl {
        val lowRam=android.os.Build.VERSION.SDK_INT<=21||
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice==true
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(if(live)8_000 else 15_000,if(live)20_000 else if(lowRam)30_000 else 45_000,startupMs,if(live)3_000 else 5_000)
            .setTargetBufferBytes((if(lowRam)24 else 48)*1024*1024)
            .setPrioritizeTimeOverSizeThresholds(live).build()
    }
    fun liveConfiguration():MediaItem.LiveConfiguration = MediaItem.LiveConfiguration.Builder()
        .setTargetOffsetMs(12_000).setMinPlaybackSpeed(0.98f).setMaxPlaybackSpeed(1.02f).build()
    fun player(context:Context,softwareTracks:Int=0,startupMs:Int=2000,official:Boolean=false,live:Boolean=false):ExoPlayer {
        val renderers=if(official&&softwareTracks==0)OfficialDecoders.renderers(context)else NativeDecoders.renderers(context,softwareTracks)
        renderers.setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context,renderers)
            .setLoadControl(loadControl(context,startupMs,live))
            .build()
    }
    fun source(context:Context,url:String,attempt:PlaybackAttempts,policy:LoadErrorHandlingPolicy):MediaSource {
        val item=MediaItem.Builder()
            .setUri(url)
            .apply{attempt.format.mime?.let{setMimeType(it)}}
            .setLiveConfiguration(liveConfiguration())
            .build()
        if(url.startsWith("rtsp:",true))return RtspMediaSource.Factory()
            .setForceUseRtpTcp(attempt.rtspTcp).setTimeoutMs(8000).setUserAgent("One/0.16 AndroidTV").createMediaSource(item)
        val http=DefaultHttpDataSource.Factory()
            .setUserAgent("One/0.16 AndroidTV").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000).setReadTimeoutMs(10000)
        val data=DefaultDataSource.Factory(context,http)
        return DefaultMediaSourceFactory(data).setLoadErrorHandlingPolicy(policy).createMediaSource(item)
    }
    fun browserSource(context:Context,url:String,userAgent:String,headers:Map<String,String>):MediaSource {
        val http=DefaultHttpDataSource.Factory().setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(3500).setReadTimeoutMs(3500)
        val cookies=android.webkit.CookieManager.getInstance()
        val data=ResolvingDataSource.Factory(http){spec->
            val cookie=cookies.getCookie(spec.uri.toString())
            if(cookie.isNullOrEmpty())spec else spec.withAdditionalHeaders(mapOf("Cookie" to cookie))
        }
        val path=url.substringBefore('?').substringBefore('#').lowercase()
        val item=MediaItem.Builder().setUri(url).apply{
            when{
                path.endsWith(".m3u8")->setMimeType(MimeTypes.APPLICATION_M3U8)
                path.endsWith(".mpd")->setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context,data))
            .createMediaSource(item)
    }
}
