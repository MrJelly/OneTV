package com.tvbridge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistParserTest {

    @Test
    fun streamUrlAcceptsSupportedSchemesAndNormalizesUdp() {
        assertEquals("http://192.168.1.2:8080/live.m3u8", PlaylistParser.streamUrl(" http://192.168.1.2:8080/live.m3u8 "))
        assertEquals("https://example.com/a.m3u8", PlaylistParser.streamUrl("https://example.com/a.m3u8"))
        assertEquals("rtsp://user:pass@10.0.0.1:554/stream", PlaylistParser.streamUrl("rtsp://user:pass@10.0.0.1:554/stream"))
        assertEquals("udp://239.0.0.1:1234", PlaylistParser.streamUrl("udp://@239.0.0.1:1234"))
    }

    @Test
    fun streamUrlRejectsInvalidOrUnsafeUrls() {
        assertNull(PlaylistParser.streamUrl("file:///etc/passwd"))
        assertNull(PlaylistParser.streamUrl("http://user:pass@example.com/a.m3u8"))
        assertNull(PlaylistParser.streamUrl("udp://239.0.0.1"))
        assertNull(PlaylistParser.streamUrl("not a url"))
    }

    @Test
    fun playlistAddressRewritesGitHubBlobUrls() {
        assertEquals(
            "https://raw.githubusercontent.com/owner/repo/main/list.m3u",
            PlaylistParser.playlistAddress("https://github.com/owner/repo/blob/main/list.m3u")
        )
        assertEquals(
            "https://example.com/list.m3u",
            PlaylistParser.playlistAddress("https://example.com/list.m3u")
        )
        assertNull(PlaylistParser.playlistAddress("rtsp://10.0.0.1/live"))
    }

    @Test
    fun parseM3uMergesSameChannelStreamsAndStripsQualitySuffix() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1@HD" group-title="央视",CCTV-1
            http://example.com/cctv1-hd.m3u8${'$'}1080P
            #EXTINF:-1 tvg-id="cctv1@4K" group-title="央视",CCTV-1
            http://example.com/cctv1-4k.m3u8${'$'}4K
            #EXTINF:-1 group-title="更新时间",2026-01-01
            http://example.com/notice.m3u8
        """.trimIndent()

        val channels = PlaylistParser.parse(m3u, scope = "test")
        assertEquals(1, channels.size)
        assertEquals("CCTV-1", channels[0].name)
        assertEquals("央视", channels[0].group)
        assertEquals(
            listOf("http://example.com/cctv1-hd.m3u8", "http://example.com/cctv1-4k.m3u8"),
            channels[0].streams
        )
    }

    @Test
    fun parseTxtGenreFormatAndMultiSourceHashSplit() {
        val txt = """
            卫视,#genre#
            湖南卫视,http://a.example.com/hn.m3u8#http://b.example.com/hn.m3u8
            公告,#genre#
            免责声明,http://a.example.com/info.m3u8
        """.trimIndent()

        val channels = PlaylistParser.parse(txt, scope = "local")
        assertEquals(1, channels.size)
        assertEquals("湖南卫视", channels[0].name)
        assertEquals("卫视", channels[0].group)
        assertEquals(2, channels[0].streams.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsHlsMediaPlaylist() {
        val hls = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            seg0.ts
        """.trimIndent()
        PlaylistParser.parse(hls)
    }

    @Test
    fun digestIsDeterministicAnd24HexChars() {
        val a = PlaylistParser.digest("scope:CCTV-1")
        val b = PlaylistParser.digest("scope:CCTV-1")
        val c = PlaylistParser.digest("scope:CCTV-2")
        assertEquals(24, a.length)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
