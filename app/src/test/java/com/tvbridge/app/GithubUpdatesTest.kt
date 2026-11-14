package com.tvbridge.app

import android.app.Activity
import android.content.pm.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.net.*
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GithubUpdatesTest {
    private fun metadata()=JSONObject().put("tag_name","download-v0.16.0").put("assets",JSONArray().put(JSONObject().put("name","One-0.16.0.apk").put("state","uploaded").put("size",100).put("digest","sha256:"+"a".repeat(64)).put("browser_download_url","https://github.com/MrJelly/OneTV/releases/download/v0.16.0/One-0.16.0.apk")))
    @Test fun numericVersionsAndPublicMainAssetSelection(){
        assertTrue(GithubUpdates.newer("0.15.10","0.15.9"));assertFalse(GithubUpdates.newer("0.14.14","0.15.1"));assertFalse(GithubUpdates.newer("0.15.1","0.15.1"))
        assertEquals("0.16.0",GithubUpdates.parse(metadata().toString())!!.version)
        assertTrue(GithubUpdates.newer("1.0","0.18.2"))
        assertFalse(GithubUpdates.newer("1.0.0","1.0"))
        val one=metadata().put("tag_name","v1.0")
        one.getJSONArray("assets").getJSONObject(0).put("name","One-1.0.apk")
        assertEquals("1.0",GithubUpdates.parse(one.toString())!!.version)
        val m=metadata();m.getJSONArray("assets").getJSONObject(0).put("name","One-Live-modern-0.16.0.apk");assertNull(GithubUpdates.parse(m.toString()))
        for(flag in listOf("draft","prerelease"))assertNull(GithubUpdates.parse(metadata().put(flag,true).toString()))
        for(url in listOf("http://github.com/MrJelly/OneTV/releases/download/v/x.apk","https://evil.test/One.apk","https://github.com/other/repo/releases/download/v/One.apk")){
            val invalid=metadata();invalid.getJSONArray("assets").getJSONObject(0).put("browser_download_url",url);assertNull(GithubUpdates.parse(invalid.toString()))
        }
    }
    @Test fun releaseTagCanIncludeTrailingZeroMissingFromApkName(){
        val release=metadata().put("tag_name","v1.1.0")
        release.getJSONArray("assets").getJSONObject(0).put("name","One-1.1.apk")
            .put("browser_download_url","https://github.com/MrJelly/OneTV/releases/download/v1.1.0/One-1.1.apk")
        val candidate=GithubUpdates.parse(release.toString())!!
        assertEquals("One-1.1.apk",candidate.name)
        assertFalse(GithubUpdates.newer(candidate.version,"1.1"))
    }
    @Test fun checksumFallbackMatchesExactFileName(){
        val m=metadata();m.getJSONArray("assets").getJSONObject(0).remove("digest");assertNull(GithubUpdates.parse(m.toString()))
        m.getJSONArray("assets").put(JSONObject().put("name","SHA256SUMS.txt").put("browser_download_url","https://github.com/MrJelly/OneTV/releases/download/v/SHA256SUMS.txt"))
        assertNotNull(GithubUpdates.parse(m.toString()))
        val sha="a".repeat(64)
        assertEquals(sha,GithubUpdates.checksum("$sha  One-0.16.0.apk\n${"b".repeat(64)}  One-Live.apk","One-0.16.0.apk"))
        assertNull(GithubUpdates.checksum("$sha  Old-One-0.16.0.apk","One-0.16.0.apk"))
    }
    private fun future(a:Activity)=PackageInfo().apply{packageName=a.packageName;versionCode=48;signatures=arrayOf(Signature("0102"));applicationInfo=ApplicationInfo().apply{packageName=a.packageName;minSdkVersion=19}}
    private fun host(a:Activity){Shadows.shadowOf(a.packageManager).getInternalMutablePackageInfo(a.packageName).apply{versionCode=47;signatures=arrayOf(Signature("0102"))}}
    private fun connection(url:URL,bytes:ByteArray,status:Int=200,location:String?=null)=object:HttpURLConnection(url){
        override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
        override fun getResponseCode()=status
        override fun getInputStream()=bytes.inputStream()
        override fun getHeaderField(name:String)=when(name){"Location"->location;"Content-Length"->bytes.size.toString();else->null}
    }
    @Test fun downloadVerifiesBeforeSavingAndRejectsPartialOrWrongHash(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();host(a)
        try{
            val bytes="future apk test fixture".toByteArray();val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val release=GithubUpdates.parse(metadata().toString())!!.copy(size=bytes.size.toLong(),digest=hash)
            val folder=File(a.filesDir,"received")
            val saved=GithubUpdates.Request{connection(it,bytes)}.download(a,release){if(it==100){val part=folder.listFiles()!!.single{f->f.extension=="part"};Shadows.shadowOf(a.packageManager).setPackageArchiveInfo(part.path,future(a))}}
            assertArrayEquals(bytes,saved.readBytes());assertFalse(folder.listFiles()!!.any{it.extension=="part"})
            for(bad in listOf(release.copy(digest="0".repeat(64)),release.copy(size=bytes.size+1L))){
                try{GithubUpdates.Request{connection(it,bytes)}.download(a,bad){};fail("Invalid update accepted")}catch(_:java.io.IOException){}
                assertArrayEquals(bytes,saved.readBytes())
            }
        }finally{c.pause().stop().destroy()}
    }
    @Test fun rejectsForeignSignatureDowngradeAndUnsafeRedirect(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();host(a)
        try{
            val file=File(a.cacheDir,"fixture.apk").apply{writeText("fixture")};val pm=Shadows.shadowOf(a.packageManager)
            for(info in listOf(future(a).apply{packageName="another.app"},future(a).apply{signatures=arrayOf(Signature("0304"))},future(a).apply{versionCode=47})){
                pm.setPackageArchiveInfo(file.path,info)
                try{GithubUpdates.validate(a,file);fail("Invalid package accepted")}catch(_:IllegalArgumentException){}
            }
            var opens=0
            try{GithubUpdates.Request{opens++;connection(it,byteArrayOf(),302,"http://evil.test/file")}.latest();fail("Unsafe redirect accepted")}catch(_:IllegalArgumentException){}
            assertEquals(1,opens)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun cancellingDownloadRemovesPartialAndDoesNotProduceAnInstallableFile(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            val bytes=ByteArray(100){1};val release=GithubUpdates.parse(metadata().toString())!!
            val work=GithubUpdates.Request{connection(it,bytes)}
            try{work.download(a,release){work.cancel()};fail("Cancelled update accepted")}catch(_:java.io.InterruptedIOException){}
            assertTrue(work.cancelled());assertFalse(File(a.filesDir,"received").listFiles().orEmpty().any{it.extension in listOf("part","apk")})
        }finally{c.pause().stop().destroy()}
    }
    @Test fun cancellingAnActiveConnectionNeverClosesTlsOnTheUiThread(){
        val entered=java.util.concurrent.CountDownLatch(1);val closed=java.util.concurrent.CountDownLatch(1)
        val mainClose=java.util.concurrent.atomic.AtomicBoolean(false)
        val work=GithubUpdates.Request{url->object:HttpURLConnection(url){
            override fun connect(){};override fun usingProxy()=false;override fun getResponseCode()=200
            override fun disconnect(){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())mainClose.set(true);closed.countDown()}
            override fun getInputStream()=object:java.io.InputStream(){override fun read():Int{entered.countDown();closed.await(5,java.util.concurrent.TimeUnit.SECONDS);return -1}}
        }}
        val thread=Thread{try{work.latest()}catch(_:Exception){}}
        thread.start();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
        work.cancel();assertTrue(closed.await(5,java.util.concurrent.TimeUnit.SECONDS));thread.join(5000)
        assertFalse(mainClose.get());assertFalse(thread.isAlive)
    }
    @Test fun rateLimitedOrMalformedMetadataFallsBackToApiProxy(){
        for(malformed in listOf(false,true)){
            val opened=mutableListOf<URL>()
            val result=GithubUpdates.Request{url->
                opened.add(url)
                if(url.host=="api.github.com")connection(url,"<html>blocked</html>".toByteArray(),if(malformed)200 else 403)
                else connection(url,metadata().toString().toByteArray())
            }.latest()
            assertEquals("0.16.0",result!!.version)
            assertEquals(listOf("api.github.com","gh-proxy.org"),opened.map{it.host})
            assertTrue(opened.last().toString().endsWith(GithubUpdates.LATEST))
        }
    }
    @Test fun slowDownloadSwitchesRouteAndRestartsWhenRangeIsIgnored(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get();host(a)
        try{
            val bytes=ByteArray(1000){(it%251).toByte()}
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val release=GithubUpdates.parse(metadata().toString())!!.copy(size=bytes.size.toLong(),digest=hash)
            var clock=0L;val opened=mutableListOf<String>()
            val work=GithubUpdates.Request(nanoTime={clock}){url->
                opened.add(url.host)
                if(url.host!="github.com")connection(url,bytes)
                else object:HttpURLConnection(url){
                    override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
                    override fun getResponseCode()=200
                    override fun getInputStream()=object:java.io.ByteArrayInputStream(bytes){
                        override fun read(b:ByteArray,off:Int,len:Int):Int{clock=9_000_000_000L;return super.read(b,off,minOf(len,20))}
                    }
                }
            }
            val saved=work.download(a,release){percent->if(percent==100){val part=File(a.filesDir,"received").listFiles()!!.single{it.extension=="part"};Shadows.shadowOf(a.packageManager).setPackageArchiveInfo(part.path,future(a))}}
            assertEquals(listOf("github.com","gh-proxy.org"),opened)
            assertArrayEquals(bytes,saved.readBytes())
            assertFalse(File(a.filesDir,"received").listFiles()!!.any{it.extension=="part"})
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun interruptedDownloadResumesFromSavedOffsetOnTheNextRequest(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        host(activity)
        try{
            val bytes=ByteArray(1000){(it%251).toByte()}
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val release=GithubUpdates.parse(metadata().toString())!!.copy(size=1000,digest=hash)
            val folder=File(activity.filesDir,"received")
            try{
                GithubUpdates.Request{connection(it,bytes.copyOf(200))}.download(activity,release){}
                fail("Incomplete download accepted")
            }catch(_:java.io.IOException){}
            val partial=folder.listFiles()!!.single()
            assertEquals(200L,partial.length())
            val saved=GithubUpdates.Request{url->object:HttpURLConnection(url){
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode():Int{
                    assertEquals("bytes=200-",getRequestProperty("Range"))
                    return HTTP_PARTIAL
                }
                override fun getHeaderField(name:String)=if(name=="Content-Range")"bytes 200-999/1000" else null
                override fun getInputStream()=bytes.inputStream(200,800)
            }}.download(activity,release){if(it==100)Shadows.shadowOf(activity.packageManager).setPackageArchiveInfo(partial.path,future(activity))}
            assertArrayEquals(bytes,saved.readBytes())
            assertFalse(partial.exists())
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun proxyCannotRedirectUpdateToUnlistedHost(){
        val opened=mutableListOf<String>()
        try{
            GithubUpdates.Request{url->opened.add(url.host);if(url.host=="api.github.com")connection(url,byteArrayOf(),403)else connection(url,byteArrayOf(),302,"https://unlisted.example/file")}.latest()
            fail("Unlisted redirect accepted")
        }catch(_:IllegalArgumentException){}
        assertEquals(listOf("api.github.com","gh-proxy.org"),opened)
    }
    @Test fun slowMetadataReadHasDeadlineAndFallsBack(){
        var ticks=0;val opened=mutableListOf<String>()
        val work=GithubUpdates.Request(nanoTime={if(ticks++==0)0L else 13_000_000_000L}){url->
            opened.add(url.host);connection(url,metadata().toString().toByteArray())
        }
        assertNotNull(work.latest())
        assertEquals(listOf("api.github.com","gh-proxy.org"),opened)
    }
}
