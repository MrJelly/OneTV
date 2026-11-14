package com.tvbridge.app

import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativePlaybackTest {
    @Test fun liveBufferHoldsThroughShortJitterAndRestartsWithMoreHeadroom(){
        val control=NativePlayback.loadControl(RuntimeEnvironment.getApplication())
        val id=androidx.media3.exoplayer.analytics.PlayerId.UNSET
        control.onPrepared(id)
        fun ready(duration:Long,rebuffering:Boolean)=control.shouldStartPlayback(androidx.media3.exoplayer.LoadControl.Parameters(
            id,androidx.media3.common.Timeline.EMPTY,androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(Any()),
            0,duration,1f,true,rebuffering,androidx.media3.common.C.TIME_UNSET))
        assertFalse(ready(1_000_000,false))
        assertTrue(ready(2_000_000,false))
        assertFalse(ready(4_000_000,true))
        assertTrue(ready(5_000_000,true))
        control.onReleased(id)
        assertEquals(12_000L,NativePlayback.liveConfiguration().targetOffsetMs)
    }
    @Test fun realFactoriesContainAllRequiredProtocolModules(){
        val context=RuntimeEnvironment.getApplication()
        for((url,type) in listOf(
            "rtsp://camera.test/live" to "RtspMediaSource",
            "https://test/live.ism/Manifest" to "SsMediaSource",
            "https://test/live.m3u8" to "HlsMediaSource",
            "https://test/live.mpd" to "DashMediaSource",
            "https://test/movie.mp4" to "ProgressiveMediaSource",
            "udp://239.1.1.1:1234" to "ProgressiveMediaSource")){
            val attempts=PlaybackAttempts();attempts.reset(url)
            val source=NativePlayback.source(context,url,attempts,DefaultLoadErrorHandlingPolicy())
            assertEquals(type,source.javaClass.simpleName)
        }
    }
    @Test fun extensionMismatchCanActuallyBuildADifferentMediaSource(){
        val attempts=PlaybackAttempts();val url="https://test/wrong.m3u8";attempts.reset(url)
        assertTrue(attempts.nextFormat())
        assertEquals("ProgressiveMediaSource",NativePlayback.source(RuntimeEnvironment.getApplication(),url,attempts,DefaultLoadErrorHandlingPolicy()).javaClass.simpleName)
    }
}
