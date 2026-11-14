package com.tvbridge.app

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YangshipinPlaybackCoordinatorTest {

    @Test fun obsoleteCctv7PidUsesTheCurrentOfficialChannelWithoutChangingStoredIds() {
        assertEquals("600004092", YangshipinStreamResolver.canonicalPid("600000492"))
        assertEquals("600004092", YangshipinStreamResolver.canonicalPid("600004092"))
        assertEquals("600001859", YangshipinStreamResolver.canonicalPid("600001859"))
        val old = YangshipinStreamResolver.extractPid("one-plugin://live/600000492")
        assertEquals("600000492", old)
        assertEquals("600004092", YangshipinStreamResolver.canonicalPid(old))
    }

    @Test
    fun testPidExtraction() {
        assertEquals("600001859", YangshipinStreamResolver.extractPid("one-plugin://live/600001859"))
        assertEquals("600001800", YangshipinStreamResolver.extractPid("https://www.yangshipin.cn/tv/home?pid=600001800"))
        assertEquals("600001818", YangshipinStreamResolver.extractPid("600001818"))
    }

    @Test fun siteTransformersCannotBeMixedOrSelectedByQueryStrings() {
        assertEquals("h5e", HlsProxyServer.transformFor("https://cdn.example/ldocctvwbcd/cdrmldcctv7_1/1.ts"))
        assertEquals("cmg", HlsProxyServer.transformFor("https://live.ysp.cctv.cn/channel/1.ts"))
        assertEquals("plain", HlsProxyServer.transformFor("https://example.com/video.ts?url=yangshipin.cn"))
        assertEquals("plain", HlsProxyServer.transformFor("https://yangshipin.cn.example.com/video.ts"))
    }

    @Test
    fun testYangshipinApiPayloadCreation() {
        val payload = YangshipinApiPayload.create("600001859", definition = "fhd", serverTimeMs = 1700000000000L)
        assertNotNull(payload.guid)
        assertTrue(payload.guid.isNotEmpty())
        assertTrue(payload.authBody.contains("pid=600001859"))
        assertTrue(payload.authBody.contains("guid="))
        assertTrue(payload.authBody.contains("signature="))

        val cKey = YangshipinApiPayload.createCKey("2024078201", payload.guid, 1700000000L)
        assertNotNull(cKey)
        assertTrue(cKey.startsWith("--01"))
        assertTrue(cKey.length > 32)
    }

    @Test
    fun testHlsProxyServerStartupAndShutdown() {
        val proxy = HlsProxyServer()
        try {
            val testOrigin = "https://example.com/live/index.m3u8"
            val proxied = proxy.proxyUrl(testOrigin)
            assertTrue(proxied.startsWith("http://127.0.0.1:"))
            assertTrue(proxied.contains("playlist.m3u8?url="))
        } finally {
            proxy.close()
        }
    }

    @Test
    fun testCoordinatorInstantiationAndLifecycle() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val states = mutableListOf<String>()
        val coordinator = YangshipinPlaybackCoordinator(activity, {}) { msg, _ -> states.add(msg) }

        try {
            assertNotNull(coordinator.view)
            assertEquals("fhd", coordinator.quality)

            coordinator.open("one-plugin://live/600001859")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(states.isNotEmpty())

            coordinator.pauseVideo()
            coordinator.resume()
            coordinator.stop()
        } finally {
            coordinator.release()
        }
    }
}
