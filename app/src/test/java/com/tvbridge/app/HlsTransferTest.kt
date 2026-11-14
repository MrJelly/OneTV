package com.tvbridge.app

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HlsTransferTest {
    private class Upstream(private val respond:(Int)->Pair<Int,ByteArray>):java.io.Closeable {
        private val socket=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        private val workers=Executors.newCachedThreadPool()
        val count=AtomicInteger()
        val port get()=socket.localPort
        init {
            Thread({
                while(!socket.isClosed){
                    val client=try{socket.accept()}catch(_:java.io.IOException){break}
                    workers.execute{
                        client.use{
                            val input=it.getInputStream().bufferedReader()
                            while(!input.readLine().isNullOrEmpty()){}
                            val (status,body)=respond(count.incrementAndGet())
                            val output=it.getOutputStream()
                            output.write("HTTP/1.1 $status Response\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(body);output.flush()
                        }
                    }
                }
            },"test-upstream").apply{isDaemon=true;start()}
        }
        override fun close(){socket.close();workers.shutdownNow()}
    }
    @Test fun concurrentConsumersShareOneTransferAndReuseCompletedBytes(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val body=ByteArray(188*8){(it%253).toByte()}
        val upstream=Upstream { entered.countDown();release.await(5,TimeUnit.SECONDS);200 to body }
        val proxy=HlsProxyServer();val pool=Executors.newFixedThreadPool(2)
        try{
            val address=proxy.proxyUrl("http://127.0.0.1:${upstream.port}/segment.ts").replace("/playlist.m3u8?","/segment.ts?")
            val first=pool.submit<ByteArray>{URL(address).readBytes()}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val second=pool.submit<ByteArray>{URL(address).readBytes()}
            release.countDown()
            assertArrayEquals(body,first.get(5,TimeUnit.SECONDS));assertArrayEquals(body,second.get(5,TimeUnit.SECONDS))
            assertArrayEquals(body,URL(address).readBytes());assertEquals(1,upstream.count.get())
        }finally{release.countDown();proxy.close();upstream.close();pool.shutdownNow()}
    }
    @Test fun rejectedTransferIsNotCachedAndNextRequestCanRecover(){
        val upstream=Upstream { (if(it==1)503 else 200) to byteArrayOf(1,2,3) }
        val proxy=HlsProxyServer()
        try{
            val address=proxy.proxyUrl("http://127.0.0.1:${upstream.port}/segment.ts").replace("/playlist.m3u8?","/segment.ts?")
            try{URL(address).readBytes();fail("Failure must reach the player")}catch(_:java.io.IOException){}
            assertArrayEquals(byteArrayOf(1,2,3),URL(address).readBytes());assertEquals(2,upstream.count.get())
        }finally{proxy.close();upstream.close()}
    }
}
