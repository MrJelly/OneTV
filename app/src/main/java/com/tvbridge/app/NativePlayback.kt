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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object NativePlayback {
    fun softwareAvailable():Boolean=NativeDecoders.available()
    fun loadControl(context:Context,startupMs:Int=2000):DefaultLoadControl {
        val lowRam=android.os.Build.VERSION.SDK_INT<=21||
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice==true
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000,if(lowRam)30_000 else 45_000,startupMs,5_000)
            .setTargetBufferBytes((if(lowRam)24 else 48)*1024*1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()
    }
    fun liveConfiguration():MediaItem.LiveConfiguration = MediaItem.LiveConfiguration.Builder()
        .setTargetOffsetMs(12_000).setMinPlaybackSpeed(0.98f).setMaxPlaybackSpeed(1.02f).build()
    fun player(context:Context,softwareTracks:Int=0,startupMs:Int=2000,official:Boolean=false):ExoPlayer {
        val renderers=if(official&&softwareTracks==0)OfficialDecoders.renderers(context)else NativeDecoders.renderers(context,softwareTracks)
        renderers.setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context,renderers)
            .setLoadControl(loadControl(context,startupMs))
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
    fun browserSource(context:Context,url:String,userAgent:String):MediaSource {
        val http=DefaultHttpDataSource.Factory().setUserAgent(userAgent)
            .setConnectTimeoutMs(3500).setReadTimeoutMs(3500)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context,http))
            .createMediaSource(MediaItem.fromUri(url))
    }
}
