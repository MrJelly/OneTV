package com.tvbridge.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.bu.cc.tv.NativeYspSigner
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class YangshipinStreamResolver(private val context: Context, private val transport: ((String, String, Map<String,String>, String?) -> String)? = null) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("official_stream_cache_v3", Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<String, CachedUrl>()
    private val executor = Executors.newFixedThreadPool(2)
    @Volatile private var currentEpoch = 0L
    @Volatile private var resolvedEpoch = -1L
    private val connections = ConcurrentHashMap<HttpURLConnection, Long>()

    data class CachedUrl(val url: String, val timestamp: Long)

    companion object {
        private const val TAG = "YangshipinResolver"
        private const val CACHE_EXPIRATION_MS = 10L * 60L * 1000L
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

        private val PID_TO_CCTV_SLUG = mapOf(
            "600001859" to "cctv1",
            "600001800" to "cctv2",
            "600001801" to "cctv3",
            "600001814" to "cctv4",
            "600001818" to "cctv5",
            "600001817" to "cctv5plus",
            "600108442" to "cctv6",
            "600000492" to "cctv7",
            "600004092" to "cctv7",
            "600001803" to "cctv8",
            "600004078" to "cctvjilu",
            "600001805" to "cctv10",
            "600001806" to "cctv11",
            "600001807" to "cctv12",
            "600001811" to "cctv13",
            "600001809" to "cctvchild",
            "600001815" to "cctv15",
            "600098637" to "cctv16",
            "600001810" to "cctv17"
        )

        fun canonicalPid(pid: String): String = if (pid == "600000492") "600004092" else pid

        fun extractPid(address: String): String {
            val uri = Uri.parse(address)
            return uri.getQueryParameter("pid") ?: uri.lastPathSegment ?: ""
        }
    }

    fun cancel() {
        currentEpoch++
        connections.entries.forEach { it.key.disconnect() }
    }

    fun resolve(address: String, quality: String = "fhd", forceReload: Boolean = false, preferCctv: Boolean = false, callback: (Result<String>) -> Unit) {
        cancel()
        val epoch = currentEpoch

        if (address.startsWith("http://") || address.startsWith("https://")) {
            val uri = Uri.parse(address)
            if (uri.path?.endsWith(".m3u8") == true && !address.contains("yangshipin.cn")) {
                callback(Result.success(address))
                return
            }
        }

        val pid = canonicalPid(extractPid(address))
        if (pid.isEmpty() || !pid.matches(Regex("[0-9]+"))) {
            callback(Result.failure(IllegalArgumentException("无效的频道 PID: $address")))
            return
        }

        val cacheKey = "${pid}_$quality" + if (preferCctv) "_cctv" else ""
        if (forceReload) {
            memoryCache.remove(cacheKey)
            prefs.edit().remove("url_$cacheKey").remove("time_$cacheKey").apply()
        }
        val cached = getValidCachedUrl(cacheKey)
        if (cached != null) {
            reportRoute(cached)
            callback(Result.success(cached))
            return
        }

        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val slug = PID_TO_CCTV_SLUG[pid]
        fun complete(url: String?) {
            if (epoch != currentEpoch || finished.get()) return
            if (url != null && finished.compareAndSet(false, true)) {
                resolvedEpoch = epoch
                connections.entries.filter { it.value == epoch }.forEach { it.key.disconnect() }
                handler.post {
                    if (epoch == currentEpoch) {
                        putCache(cacheKey, url)
                        reportRoute(url)
                        callback(Result.success(url))
                    }
                }
            } else if (finished.compareAndSet(false, true)) {
                handler.post { if (epoch == currentEpoch) callback(Result.failure(IOException("未能获取频道直播流，请重试或换台"))) }
            }
        }
        executor.execute {
            val stream = if (preferCctv && slug != null) {
                tryFetchCctvLiveUrl(slug, epoch) ?: tryFetchYangshipinLiveUrl(pid, quality, epoch)
            } else {
                tryFetchYangshipinLiveUrl(pid, quality, epoch) ?: slug?.let { tryFetchCctvLiveUrl(it, epoch) }
            }
            complete(stream)
        }
    }

    private fun tryFetchCctvLiveUrl(slug: String, epoch: Long): String? {
        fun md5(value: String) = java.security.MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val uid = md5(java.util.UUID.randomUUID().toString())
        for (host in listOf("vdnx.live.cntv.cn", "vdnxbk.live.cntv.cn")) {
            if (epoch != currentEpoch || epoch == resolvedEpoch) return null
            try {
                val time = System.currentTimeMillis()
                val nonce = 100 + java.util.Random().nextInt(901)
                val auth = "$time-$nonce-${md5("$slug$time${nonce}a4220a71b31746908fa3e7fdd7a6852a") }"
                val url = "https://$host/api/v3/vdn/live?channel=$slug&vn=1&pdrm=1&uid=$uid&hbss=$time"
                val response = JSONObject(request(url, "GET", mapOf("Referer" to "https://tv.cctv.com/", "auth-key" to auth), null, epoch))
                if (response.optString("ack") != "yes") continue
                val stream = response.optJSONObject("manifest")?.optString("hls_cdrm")?.takeIf { it.isNotBlank() }
                    ?: response.optJSONObject("backup")?.optString("hls_cdrm")?.takeIf { it.isNotBlank() } ?: continue
                val uri = URIForStream(stream)
                if (uri && HlsProxyServer.transformFor(stream) == "h5e") return stream
            } catch (_: Exception) { }
        }
        return null
    }

    private fun URIForStream(url: String): Boolean = try {
        val uri = java.net.URI(url)
        uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.path.endsWith(".m3u8")
    } catch (_: Exception) { false }

    private fun getValidCachedUrl(key: String): String? {
        val now = System.currentTimeMillis()
        val mem = memoryCache[key]
        if (mem != null && now - mem.timestamp in 0 until CACHE_EXPIRATION_MS) {
            return mem.url
        }
        val diskUrl = prefs.getString("url_$key", null)
        val diskTime = prefs.getLong("time_$key", 0L)
        if (diskUrl != null && now - diskTime in 0 until CACHE_EXPIRATION_MS) {
            memoryCache[key] = CachedUrl(diskUrl, diskTime)
            return diskUrl
        }
        return null
    }

    private fun putCache(key: String, url: String) {
        val now = System.currentTimeMillis()
        memoryCache[key] = CachedUrl(url, now)
        prefs.edit().putString("url_$key", url).putLong("time_$key", now).apply()
    }

    private fun reportRoute(url:String){
        val route=if(HlsProxyServer.transformFor(url)=="h5e")"cctv-fallback"else "yangshipin"
        android.util.Log.i(TAG,"Selected route: $route")
        PlaybackDiagnostics.update("officialRoute",route)
    }

    private fun request(url: String, method: String, headers: Map<String, String>, body: String?, epoch: Long): String {
        if (epoch != currentEpoch || epoch == resolvedEpoch || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        transport?.let { return it(url, method, headers, body) }
        val conn = URL(url).openConnection() as HttpURLConnection
        connections[conn] = epoch
        try {
            LegacyTls.configure(conn)
            conn.requestMethod = method
            conn.connectTimeout = 3500
            conn.readTimeout = 4500
            conn.setRequestProperty("User-Agent", DESKTOP_UA)
            conn.setRequestProperty("Referer", "https://www.yangshipin.cn/")
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val deadline = android.os.SystemClock.elapsedRealtime() + 6000
                while (true) {
                    if (epoch != currentEpoch || epoch == resolvedEpoch || Thread.currentThread().isInterrupted || android.os.SystemClock.elapsedRealtime() > deadline) throw java.io.InterruptedIOException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 1024 * 1024) throw IOException("Response exceeds limit")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8").trimStart('\uFEFF', ' ', '\n', '\r')
            }
            return text
        } finally { connections.remove(conn); conn.disconnect() }
    }

    private fun tryFetchYangshipinLiveUrl(pid: String, quality: String, epoch: Long): String? {
        return try {
            var payload = YangshipinApiPayload.create(pid = pid, definition = quality)
            fun headers(json: Boolean = false) = mapOf(
                "Content-Type" to if (json) "application/json;charset=UTF-8" else "application/x-www-form-urlencoded;charset=UTF-8",
                "Accept" to "application/json, text/plain, */*",
                "yspappid" to YangshipinApiPayload.APP_ID,
                "Origin" to "https://www.yangshipin.cn",
                "Cookie" to "guid=${payload.guid}; versionName=99.99.99; versionCode=999999; vplatform=109; platformVersion=Chrome; deviceModel=148; newLogin=1; pc_version=1.1.16; ysp_uinfo_pc=; nseqId=1; nrequest-id=${payload.requestId}"
            )
            fun auth() = JSONObject(request("https://player-api.yangshipin.cn/v1/player/auth", "POST", headers(), payload.authBody, epoch)).let {
                require(it.optInt("code", -1) == 0) { "Authentication rejected" }; it.getJSONObject("data")
            }
            var authData = auth()
            var seconds = authData.getLong("ts")
            if (kotlin.math.abs(seconds * 1000 - System.currentTimeMillis()) > 60_000) {
                payload = YangshipinApiPayload.create(pid = pid, definition = quality, serverTimeMs = seconds * 1000)
                authData = auth(); seconds = authData.getLong("ts")
            }
            val guid = payload.guid
            val rnd = NativeYspSigner.tokenRnd(guid, (seconds * 1000).toString())
            require(!rnd.isNullOrEmpty())
            val tokenUrl = "https://h5access.yangshipin.cn/web/open/token?yspappid=${YangshipinApiPayload.APP_ID}&guid=${URLEncoder.encode(guid, "UTF-8")}&vappid=59306155&vsecret=b42702bf7309a179d102f3d51b1add2fda0bc7ada64cb801&raw=1&version=v1&ts=${seconds * 1000}&rnd=${URLEncoder.encode(rnd, "UTF-8")}"
            val tokenResponse = JSONObject(request(tokenUrl, "GET", headers(), null, epoch))
            require(tokenResponse.optInt("ret", -1) == 0)
            val sdkToken = tokenResponse.getJSONObject("data").getString("token")
            require(sdkToken.isNotEmpty())
            val body = YangshipinApiPayload.createLiveBody(pid, payload.streamId, guid, payload.liveRandom, seconds, quality)
            val sdkInput = YangshipinApiPayload.createSdkInput(body)
            val fullInput = "$sdkInput-$guid-1-${payload.requestId}"
            val signature = NativeYspSigner.signature(guid, sdkToken, fullInput)
            require(!signature.isNullOrEmpty())
            val liveHeaders = headers(true) + mapOf(
                "yspplayertoken" to authData.getString("token"),
                "yspticket" to YangshipinApiPayload.computeTicket(pid, seconds, guid, payload.ticketRandom),
                "seqid" to "1", "request-id" to payload.requestId,
                "yspsdkinput" to sdkInput, "yspsdksign" to "$signature-$fullInput"
            )
            val response = JSONObject(request("https://player-api.yangshipin.cn/v1/player/get_live_info", "POST", liveHeaders, body, epoch))
            require(response.optInt("code", -1) == 0) { "Live API code ${response.optInt("code")}" }
            val data = response.getJSONObject("data")
            val url = data.getString("playurl")
            val uri = Uri.parse(url)
            require(uri.scheme in listOf("http", "https") && uri.userInfo == null && uri.host?.let { it == "ysp.cctv.cn" || it.endsWith(".ysp.cctv.cn") } == true && uri.path?.contains(".m3u8") == true)
            val ext = data.optString("extended_param")
            if (ext.isNotEmpty() && !url.contains(ext)) "$url$ext" else url
        } catch (e: Exception) {
            if (epoch == currentEpoch) android.util.Log.w(TAG, "Official route unavailable: ${e.message}")
            null
        } catch (e: LinkageError) {
            android.util.Log.w(TAG, "Native signer unavailable")
            null
        }
    }

    fun release() {
        cancel()
        executor.shutdownNow()
        memoryCache.clear()
    }
}
