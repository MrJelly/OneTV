package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
class HlsResourcesTest {
    @Test fun cacheBoundsBytesAndKeepsRecentlyUsedSegments(){
        val cache=HlsProxyServer.SegmentCache(6,6)
        cache["a"]=ByteArray(3);cache["b"]=ByteArray(3)
        assertNotNull(cache["a"])
        cache["c"]=ByteArray(3)
        assertNotNull(cache["a"]);assertNull(cache["b"]);assertNotNull(cache["c"])
        cache["large"]=ByteArray(7);assertNull(cache["large"])
        cache.clear();cache["d"]=ByteArray(6);assertNotNull(cache["d"])
    }
    @Test fun closingProxyDisconnectsClientsWaitingForRequestHeaders(){
        val proxy=HlsProxyServer()
        val port=java.net.URI(proxy.proxyUrl("https://example.org/live.m3u8")).port
        val clients=(0 until 5).map{Socket("127.0.0.1",port).apply{soTimeout=2000}}
        try{
            proxy.close()
            for(client in clients){
                val closed=try{client.getInputStream().read()==-1}catch(_:java.net.SocketException){true}
                assertTrue("Client socket must close with proxy",closed)
            }
        }finally{proxy.close();clients.forEach{it.close()}}
    }
}
