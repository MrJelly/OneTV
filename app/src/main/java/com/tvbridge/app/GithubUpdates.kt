package com.tvbridge.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object GithubUpdates {
    const val REPOSITORY="MrJelly/OneTV"
    const val RELEASES="https://github.com/$REPOSITORY/releases"
    const val LATEST="https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK=64L*1024*1024
    private val proxies=listOf("https://gh-proxy.org/","https://ghproxy.net/")
    private val officialHosts=setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com")
    data class Release(val version:String,val name:String,val url:String,val size:Long,val digest:String?,val checksums:String?,val notes:String)
    private val versionPattern=Regex("""^(?:download-)?v?([0-9]+)\.([0-9]+)(?:\.([0-9]+))?$""")
    private val downloadLock=Any()
    fun version(value:String):List<Int>?{
        val match=versionPattern.matchEntire(value)?:return null
        return match.groupValues.drop(1).map{if(it.isEmpty())0 else it.toIntOrNull()?:return null}
    }
    fun newer(candidate:String,current:String):Boolean {
        val a=version(candidate)?:return false;val b=version(current)?:return false
        for(i in a.indices)if(a[i]!=b[i])return a[i]>b[i]
        return false
    }
    private fun assetUrl(url:String):Boolean=try{val u=URI(url);u.scheme=="https"&&u.host=="github.com"&&u.userInfo==null&&u.port==-1&&u.rawPath.startsWith("/$REPOSITORY/releases/download/")}catch(_:Exception){false}
    fun parse(text:String):Release? {
        val root=JSONObject(text)
        if(root.optBoolean("draft")||root.optBoolean("prerelease"))return null
        val tag=root.optString("tag_name")
        val numbers=version(tag)?:return null
        val v=numbers.take(if(tag.count{it=='.'}==1)2 else 3).joinToString(".")
        val list=root.optJSONArray("assets")?:return null
        val assets=(0 until list.length()).mapNotNull{list.optJSONObject(it)}
        val apk=assets.firstOrNull{asset->
            val name=asset.optString("name")
            name.startsWith("One-")&&name.endsWith(".apk")&&
                version(name.removePrefix("One-").removeSuffix(".apk"))==numbers&&asset.optString("state")=="uploaded"
        }?:return null
        val url=apk.optString("browser_download_url");if(!assetUrl(url))return null
        val size=apk.optLong("size");if(size !in 1..MAX_APK)return null
        val digest=apk.optString("digest").takeIf{it.matches(Regex("sha256:[a-fA-F0-9]{64}"))}?.substringAfter(':')?.lowercase()
        val sums=assets.firstOrNull{it.optString("name") in listOf("SHA256SUMS.txt","SHA256SUMS-$v.txt")}?.optString("browser_download_url")?.takeIf(::assetUrl)
        if(digest==null&&sums==null)return null
        return Release(v,apk.getString("name"),url,size,digest,sums,root.optString("body").take(700))
    }
    fun checksum(text:String,name:String):String?=text.lineSequence().mapNotNull{Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(it.trim())}.firstOrNull{it.groupValues[2]==name}?.groupValues?.get(1)?.lowercase()
    class Request(private val nanoTime:()->Long={System.nanoTime()},private val open:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}) {
        private val cancelled=AtomicBoolean(false)
        @Volatile private var connection:HttpURLConnection?=null
        fun cancel(){
            cancelled.set(true)
            val current=connection?:return;connection=null
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())Thread({close(current)},"cancel-github-connection").start()else close(current)
        }
        private fun close(value:HttpURLConnection){try{value.disconnect()}catch(_:RuntimeException){};if(connection===value)connection=null}
        fun cancelled()=cancelled.get()
        private fun ensureActive(){if(cancelled.get())throw InterruptedIOException("已取消")}
        private fun <T> routes(url:String,read:(String,Boolean)->T):T {
            require(url==LATEST||assetUrl(url)){"更新地址无效"}
            val candidates=listOf(url)+(if(url==LATEST)proxies.take(1)else proxies).map{it+url}
            var failure:Exception?=null
            for((index,address) in candidates.withIndex()){
                ensureActive()
                try{return read(address,index<candidates.lastIndex)}catch(e:Exception){
                    ensureActive()
                    if(e !is IOException&&e !is org.json.JSONException)throw e
                    failure=e
                }
            }
            throw failure?:IOException("更新线路暂不可用")
        }
        private fun connection(url:String,offset:Long=0):HttpURLConnection {
            var current=URL(url)
            repeat(6){
                ensureActive()
                require(current.protocol=="https"&&current.userInfo==null&&current.port==-1){"更新地址无效"}
                val proxied=proxies.firstOrNull{current.toString().startsWith(it)}?.let{current.toString().removePrefix(it)}
                require(current.host in officialHosts||proxied!=null&&(proxied==LATEST||assetUrl(proxied))){"更新地址无效"}
                val c=open(current).apply{connectTimeout=6000;readTimeout=8000;instanceFollowRedirects=false;setRequestProperty("User-Agent","OneTV/${BuildConfig.VERSION_NAME}");setRequestProperty("Accept",if(url.contains("api.github.com/"))"application/vnd.github+json"else "application/octet-stream")}
                if(offset>0)c.setRequestProperty("Range","bytes=$offset-")
                if(offset>0)c.setRequestProperty("Accept-Encoding","identity")
                LegacyTls.configure(c)
                connection=c
                val code=try{ensureActive();c.responseCode}catch(e:Exception){close(c);throw e}
                if(code in 300..399){val location=c.getHeaderField("Location");close(c);current=URL(current,location?:error("更新下载地址失效"))}
                else{
                    if(code !in 200..299){close(c);throw IOException(when(code){404->"尚未发布可用的正式版本";403,429->"更新线路请求受限，请稍后重试";else->"更新线路连接失败（$code）"})}
                    return c
                }
            }
            error("更新下载重定向过多")
        }
        private fun readBytes(url:String,max:Long):ByteArray {
            val c=connection(url)
            val started=nanoTime()
            try{require(c.getHeaderField("Content-Length")?.toLongOrNull()?.let{it<=max}!=false){"更新信息过大"};return c.inputStream.use{input->val out=ByteArrayOutputStream();val buffer=ByteArray(32768);while(true){ensureActive();val n=input.read(buffer);if(n<0)break;if(nanoTime()-started>12_000_000_000L)throw SocketTimeoutException("读取更新信息超时");require(out.size()+n<=max){"更新信息过大"};out.write(buffer,0,n)};out.toByteArray()}}
            finally{close(c)}
        }
        private fun bytes(url:String,max:Long)=routes(url){address,_->readBytes(address,max)}
        fun latest():Release?=routes(LATEST){address,canSwitch->
            val release=parse(readBytes(address,2L*1024*1024).toString(Charsets.UTF_8))
            if(release==null&&canSwitch)throw IOException("更新线路未返回可校验的安装包")
            release
        }
        fun download(context:Context,release:Release,progress:(Int)->Unit):File=synchronized(downloadLock){
            require(assetUrl(release.url)&&release.size in 1..MAX_APK&&
                release.version.matches(Regex("""[0-9]+\.[0-9]+(?:\.[0-9]+)?"""))){"更新信息无效"}
            val directory=File(context.filesDir,"received").apply{check(isDirectory||mkdirs())}
            val expected=release.digest?:checksum(bytes(release.checksums?:error("缺少更新校验文件"),65536)
                .toString(Charsets.UTF_8),release.name)?:error("未找到此安装包的校验信息")
            require(expected.matches(Regex("[a-f0-9]{64}"))){"更新包校验信息无效"}
            val partial=File(directory,"one-update-$expected-${release.size}.part")
            directory.listFiles{file->file!=partial&&file.name.matches(Regex("""one-update-[a-f0-9]{64}-[0-9]+\.part"""))}
                ?.forEach{it.delete()}
            if(partial.length()>release.size)partial.delete()

            try{
                routes(release.url){address,canSwitch->
                    if(partial.length()<release.size)transfer(address,partial,release.size,canSwitch,progress)
                    ensureActive()
                    if(fileDigest(partial)!=expected){
                        partial.delete()
                        throw IOException("更新包校验失败，请重新下载")
                    }
                }
                progress(100)
                ensureActive()
                try{
                    validate(context,partial)
                }catch(e:Exception){
                    partial.delete()
                    throw e
                }
                val target=File(directory,"One-update-${release.version}.apk")
                require(partial.renameTo(target)){"无法保存更新包"}
                target
            }finally{
                if(cancelled())partial.delete()
            }
        }

        private fun transfer(address:String,partial:File,size:Long,canSwitch:Boolean,progress:(Int)->Unit){
            val offset=partial.length()
            val c=connection(address,offset)
            try{
                val append=c.responseCode==HttpURLConnection.HTTP_PARTIAL
                if(append&&c.getHeaderField("Content-Range")!="bytes $offset-${size-1}/$size")
                    throw IOException("更新线路返回的下载范围无效")
                val start=if(append)offset else 0L
                require(partial.parentFile!!.usableSpace+(offset-start)>size-start+32L*1024*1024){"存储空间不足"}
                val started=nanoTime()
                var total=start
                var reported=(100*start/size).toInt().coerceAtMost(99)
                progress(reported)
                ensureActive()
                c.inputStream.use{input->FileOutputStream(partial,append).use{out->
                    val buffer=ByteArray(65536)
                    while(true){
                        ensureActive()
                        val count=input.read(buffer)
                        if(count<0)break
                        if(total+count>size)throw IOException("更新包大小异常")
                        out.write(buffer,0,count)
                        total+=count
                        val elapsed=(nanoTime()-started)/1_000_000
                        if(canSwitch&&elapsed>8000&&total-start<elapsed*64)throw SocketTimeoutException("正在切换较快的更新线路")
                        val percent=(100*total/size).toInt().coerceAtMost(99)
                        if(percent!=reported){reported=percent;progress(percent)}
                    }
                    if(total!=size)throw IOException("更新包下载不完整")
                }}
            }finally{
                close(c)
            }
        }

        private fun fileDigest(file:File):String{
            val sha=MessageDigest.getInstance("SHA-256")
            file.inputStream().use{input->
                val buffer=ByteArray(65536)
                while(true){
                    ensureActive()
                    val count=input.read(buffer)
                    if(count<0)break
                    sha.update(buffer,0,count)
                }
            }
            return sha.digest().joinToString(""){"%02x".format(it)}
        }
    }
    @Suppress("DEPRECATION") fun validate(context:Context,file:File){
        val pm=context.packageManager;val archive=pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNATURES)?:error("更新包无效")
        val host=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES)
        require(archive.packageName==context.packageName){"更新包不属于 One"}
        val signatures=archive.signatures
        require(!signatures.isNullOrEmpty()&&signatures.toSet()==host.signatures?.toSet()){"更新包签名不一致"}
        val candidate=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
        val installed=if(Build.VERSION.SDK_INT>=28)host.longVersionCode else host.versionCode.toLong()
        require(candidate>installed){"更新包版本未高于当前版本"}
        if(Build.VERSION.SDK_INT>=24)require(archive.applicationInfo?.minSdkVersion?.let{it<=Build.VERSION.SDK_INT}!=false){"此更新不支持当前系统"}
    }
}
