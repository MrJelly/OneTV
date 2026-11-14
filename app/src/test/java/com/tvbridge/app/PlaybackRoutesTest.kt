package com.tvbridge.app

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlaybackRoutesTest {
    @Test fun remembersRenderedUrlAcrossReorderingAndWrapsWithoutRetryLoop(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get()
        val channel=LiveChannel("channel","频道","分组",listOf("https://example.test/a?token=secret","https://example.test/b","https://example.test/c"))
        val routes=PlaybackRoutes(a);assertEquals(0,routes.begin(channel));routes.rendered(2)
        val next=PlaybackRoutes(a);assertEquals(2,next.begin(channel))
        assertEquals(0,next.next(2));assertEquals(1,next.next(0));assertNull(next.next(1))
        next.manual();assertEquals(2,next.next(1))
        assertEquals(0,PlaybackRoutes(a).begin(channel.copy(streams=channel.streams.reversed())))
        assertEquals(0,PlaybackRoutes(a).begin(channel.copy(streams=listOf("https://new.test/live"))))
        assertFalse(a.getSharedPreferences("playback-routes",0).all.toString().contains("https://"))
    }
    @Test fun failedAttemptIsNotRememberedAndPluginPlaybackIsExcluded(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get();val channel=LiveChannel("channel2","频道","分组",listOf("https://a.test/live","https://b.test/live"))
        val routes=PlaybackRoutes(a);routes.begin(channel);assertEquals(1,routes.next(0));assertEquals(0,PlaybackRoutes(a).begin(channel))
        routes.begin(channel.copy(streams=listOf("one-plugin://live/600001859")));routes.rendered(0)
        assertTrue(a.getSharedPreferences("playback-routes",0).all.isEmpty())
    }
}
