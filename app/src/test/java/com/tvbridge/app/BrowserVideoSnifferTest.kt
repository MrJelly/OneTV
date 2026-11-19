package com.tvbridge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserVideoSnifferTest {

    @Test
    fun classifyRecognizesExtensionsQueryHintsAndSecFetchDest() {
        val sniffer = BrowserVideoSniffer()
        assertEquals(
            BrowserVideoSniffer.StreamKind.HLS_MASTER,
            sniffer.classify("https://cdn.example.com/hls/master.m3u8?token=abc")
        )
        assertEquals(
            BrowserVideoSniffer.StreamKind.HLS,
            sniffer.classify("https://cdn.example.com/hls/720p.m3u8")
        )
        assertEquals(
            BrowserVideoSniffer.StreamKind.DASH,
            sniffer.classify("https://cdn.example.com/vod/stream.mpd")
        )
        assertEquals(
            BrowserVideoSniffer.StreamKind.DIRECT_VIDEO,
            sniffer.classify("https://cdn.example.com/vod/movie.mp4")
        )
        // Extensionless stream identified by Sec-Fetch-Dest: video
        assertEquals(
            BrowserVideoSniffer.StreamKind.VIDEO_DEST,
            sniffer.classify(
                "https://cdn.example.com/api/play?id=12345",
                mapOf("Sec-Fetch-Dest" to "video")
            )
        )
        // Extensionless stream identified by query parameter
        assertEquals(
            BrowserVideoSniffer.StreamKind.HLS,
            sniffer.classify("https://cdn.example.com/play?format=m3u8&id=9")
        )
        assertEquals(
            BrowserVideoSniffer.StreamKind.DIRECT_VIDEO,
            sniffer.classify("https://cdn.example.com/download?response-content-type=video%2Fmp4")
        )
    }

    @Test
    fun classifyFiltersAdsStaticAssetsAndSegments() {
        val sniffer = BrowserVideoSniffer()
        assertNull(sniffer.classify("https://doubleclick.net/video/ad.mp4"))
        assertNull(sniffer.classify("https://cdn.example.com/app.js"))
        assertNull(sniffer.classify("https://cdn.example.com/hls/seg001.ts"))
        assertNull(sniffer.classify("https://cdn.example.com/dash/chunk01.m4s"))
        assertNull(sniffer.classify("https://cdn.example.com/dash/seg001.mp4"))
        assertNull(sniffer.classify("https://cdn.example.com/dash/00001.mp4"))
        assertNull(sniffer.classify("https://cdn.example.com/dash/init.mp4"))
    }

    @Test
    fun mergeCandidatesOrdersByConfidenceAndRespectsGeneration() {
        val sniffer = BrowserVideoSniffer()
        val gen = sniffer.generation
        assertTrue(sniffer.record("https://cdn.example.com/a.mp4", emptyMap(), gen, 1000L))
        assertTrue(sniffer.record("https://cdn.example.com/live/master.m3u8", emptyMap(), gen, 1100L))
        assertTrue(
            sniffer.record(
                "https://cdn.example.com/stream?id=7",
                mapOf("Sec-Fetch-Dest" to "video"),
                gen,
                1200L
            )
        )

        val candidates = sniffer.mergeCandidates(
            domActiveUrl = "https://cdn.example.com/active.mp4",
            domFallbackUrl = "https://cdn.example.com/fallback.mp4",
            jsUrls = listOf("https://cdn.example.com/xhr/list.mpd"),
            nowMs = 2000L
        )

        assertEquals(
            listOf(
                "https://cdn.example.com/active.mp4",
                "https://cdn.example.com/live/master.m3u8",
                "https://cdn.example.com/stream?id=7",
                "https://cdn.example.com/a.mp4",
                "https://cdn.example.com/xhr/list.mpd",
                "https://cdn.example.com/fallback.mp4"
            ),
            candidates
        )

        // Generation changes on clear(); stale callbacks must be ignored
        sniffer.clear()
        assertFalse(sniffer.record("https://cdn.example.com/late.m3u8", emptyMap(), gen, 3000L))
        assertTrue(sniffer.mergeCandidates(null, null, emptyList(), 3000L).isEmpty())
    }

    @Test
    fun playbackHeadersPreservesAuthAndStripsHopByHopHeaders() {
        val sniffer = BrowserVideoSniffer()
        val url = "https://media.cdn.com/hls/master.m3u8"
        sniffer.record(
            url,
            mapOf(
                "Host" to "media.cdn.com",
                "Range" to "bytes=0-1023",
                "Cookie" to "sid=secret",
                "Sec-Fetch-Dest" to "video",
                "Authorization" to "Bearer tok123",
                "X-Custom-Sign" to "sig999"
            ),
            sniffer.generation,
            1000L
        )

        val headers = sniffer.playbackHeaders(url, pageUrl = "https://www.site.com/watch/1")
        assertEquals("Bearer tok123", headers["Authorization"])
        assertEquals("sig999", headers["X-Custom-Sign"])
        assertEquals("https://www.site.com/watch/1", headers["Referer"])
        assertEquals("https://www.site.com", headers["Origin"])
        assertNull(headers["Host"])
        assertNull(headers["Range"])
        assertNull(headers["Cookie"])
        assertNull(headers["Sec-Fetch-Dest"])
    }

    @Test
    fun sitePolicyDetectsWebOnlyCloudDrives() {
        val sniffer = BrowserVideoSniffer()
        assertEquals(BrowserVideoSniffer.SitePolicy.WEB_ONLY, sniffer.sitePolicy("https://pan.quark.cn/s/abc"))
        assertEquals(BrowserVideoSniffer.SitePolicy.NATIVE_FIRST, sniffer.sitePolicy("https://example.com/video"))
    }
}
