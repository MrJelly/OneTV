package com.tvbridge.app

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ResolverRaceTest {
    private fun waitFor(condition:()->Boolean){
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!condition()&&System.nanoTime()<deadline){Thread.sleep(10);shadowOf(android.os.Looper.getMainLooper()).idle()}
        assertTrue("Resolver did not complete",condition())
    }
    @Test fun cctvIsOnlyUsedAfterThePreferredRouteFailsAndIsThenCached(){
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1);val requests=AtomicInteger()
        val resolver=YangshipinStreamResolver(RuntimeEnvironment.getApplication()){url,_,_,_->
            requests.incrementAndGet()
            if(url.contains("/auth")){entered.countDown();unblock.await(100,TimeUnit.MILLISECONDS);throw IOException("API unavailable")}
            check(entered.await(2,TimeUnit.SECONDS))
            """{"ack":"yes","manifest":{"hls_cdrm":"https://cdn.example/cdrmldcctv7_1/index.m3u8"}}"""
        }
        try{
            var result:Result<String>?=null
            resolver.resolve("one-plugin://live/600000492"){result=it}
            assertTrue(entered.await(2,TimeUnit.SECONDS));waitFor{result!=null}
            assertTrue(result!!.getOrThrow().contains("cdrmldcctv7_1"))
            val before=requests.get();resolver.resolve("one-plugin://live/600004092"){result=it}
            assertEquals(before,requests.get())
        }finally{unblock.countDown();resolver.release()}
    }
    @Test fun htmlErrorPageIsNotAcceptedOrCachedAsAPlayableManifest(){
        val resolver=YangshipinStreamResolver(RuntimeEnvironment.getApplication()){url,_,_,_->
            if(url.contains("/auth"))throw IOException("API offline")
            "<html>Unavailable</html>"
        }
        try{var result:Result<String>?=null;resolver.resolve("one-plugin://live/600000492"){result=it};waitFor{result!=null};assertTrue(result!!.isFailure)}finally{resolver.release()}
    }
    @Test fun cancelledChannelCannotDeliverALateResolution(){
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        val resolver=YangshipinStreamResolver(RuntimeEnvironment.getApplication()){url,_,_,_->
            if(url.contains("/auth"))throw IOException("API offline")
            entered.countDown();unblock.await(3,TimeUnit.SECONDS);"""{"ack":"yes","manifest":{"hls_cdrm":"https://cdn.example/cdrmldcctv7_1/index.m3u8"}}"""
        }
        try{
            var calls=0;resolver.resolve("one-plugin://live/600000492"){calls++}
            assertTrue(entered.await(2,TimeUnit.SECONDS));resolver.cancel();unblock.countDown()
            resolver.release();Thread.sleep(50);shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0,calls)
        }finally{unblock.countDown();resolver.release()}
    }
}
