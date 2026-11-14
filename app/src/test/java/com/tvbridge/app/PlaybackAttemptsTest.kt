package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackAttemptsTest {
    @Test fun slowSegmentsGetHeadroomButDeadOrEndlessBufferingStops(){
        assertTrue(PlaybackAttempts.waitForMedia(20_000,5_000))
        assertTrue(PlaybackAttempts.waitForMedia(55_000,18_000))
        assertFalse(PlaybackAttempts.waitForMedia(20_000,20_000))
        assertFalse(PlaybackAttempts.waitForMedia(60_000,1_000))
    }
    @Test fun wrongExtensionTriesEachParserOnce(){
        val attempts=PlaybackAttempts();attempts.reset("https://source.test/video.m3u8?token=secret")
        val seen=mutableListOf(attempts.format)
        while(attempts.nextFormat())seen.add(attempts.format)
        assertEquals(PlaybackAttempts.Format.HLS,seen.first())
        assertEquals(seen.size,seen.distinct().size)
        assertTrue(seen.containsAll(listOf(PlaybackAttempts.Format.DASH,PlaybackAttempts.Format.SMOOTH,PlaybackAttempts.Format.PROGRESSIVE)))
        assertFalse(attempts.nextFormat())
    }
    @Test fun smoothManifestIsRecognizedWithoutAnXmlSuffix(){
        for(url in listOf("https://test/a.ism/Manifest","https://test/a.isml/manifest(format=m3u8-aapl)")){
            val attempts=PlaybackAttempts();attempts.reset(url)
            assertEquals(PlaybackAttempts.Format.SMOOTH,attempts.format)
        }
    }
    @Test fun rtspRetriesTcpOnceAndNeverUsesHttpParsers(){
        val attempts=PlaybackAttempts();attempts.reset("rtsp://user:pass@camera.test/live")
        assertTrue(attempts.tcpFallback());assertFalse(attempts.tcpFallback());assertFalse(attempts.nextFormat())
        attempts.reset("https://test/live");assertFalse(attempts.rtspTcp);assertFalse(attempts.tcpFallback())
    }
    @Test fun softwareFallbackIsBoundedAndResetsForNewLine(){
        val attempts=PlaybackAttempts();attempts.reset("https://test/a")
        assertFalse(attempts.softwareFallback(false));assertFalse(attempts.software)
        assertTrue(attempts.softwareFallback(true));assertFalse(attempts.softwareFallback(true))
        attempts.reset("https://test/b");assertFalse(attempts.software)
    }
    @Test fun transportSchemesCannotBecomeSubscriptionDownloads(){
        assertNotNull(PlaylistParser.streamUrl("rtsp://user:pass@camera.test/live"))
        assertNotNull(PlaylistParser.streamUrl("udp://239.1.1.1:1234"))
        assertEquals("udp://239.1.1.1:1234",PlaylistParser.streamUrl("udp://@239.1.1.1:1234"))
        assertNull(PlaylistParser.streamUrl("udp://239.1.1.1"))
        assertNull(PlaylistParser.playlistAddress("rtsp://camera.test/live"))
        assertNull(PlaylistParser.httpUrl("udp://239.1.1.1:1234"))
        val channels=PlaylistParser.parse("相机,rtsp://camera.test/live\n组播,udp://239.1.1.1:1234")
        assertEquals(2,channels.size)
    }
    @Test fun audioFallbackDoesNotForceVideoIntoSoftware(){
        val attempts=PlaybackAttempts();attempts.reset("https://test/live")
        assertTrue(attempts.softwareFallback(true,1));assertEquals(1,attempts.softwareTracks)
        assertFalse(attempts.softwareFallback(true,1))
        assertTrue(attempts.softwareFallback(true,2));assertEquals(3,attempts.softwareTracks)
        assertFalse(attempts.softwareFallback(true,3))
        attempts.reset("https://test/next");assertEquals(0,attempts.softwareTracks)
    }
}
