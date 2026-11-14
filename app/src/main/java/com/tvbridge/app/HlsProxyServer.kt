package com.tvbridge.app

import android.net.Uri
import android.os.SystemClock
import com.bu.cc.tv.NativeCmgDecryptor
import com.bu.cc.tv.NativeH5eDecryptor
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*

class HlsProxyServer(private val preferredQuality: String = "hd", private val lowRam: Boolean = false) : Closeable {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var prefetchEnabled = true
    fun setPrefetchEnabled(enabled: Boolean) { prefetchEnabled = enabled }
    private val port: Int
    private val clientWorkers = ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, ArrayBlockingQueue(12))
    private val prefetchWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    @Volatile private var h5eLoaded = false
    private val transformWorkers = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8), ThreadFactory { worker ->
        Thread({
            try { worker.run() }
            finally { if (h5eLoaded) try { NativeH5eDecryptor.releaseThreadContext() } catch (_: LinkageError) {} }
        }, "HlsProxy-Transform")
    })
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val upstream = Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection, Boolean>())

    internal class SegmentCache(private val maxEntries: Int = 6, private val maxBytes: Int = 32 * 1024 * 1024) {
        private val map = LinkedHashMap<String, ByteArray>(12, 0.75f, true)
        private var bytes = 0
        @Synchronized operator fun get(key: String): ByteArray? = map[key]
        @Synchronized operator fun set(key: String, value: ByteArray) {
            map.remove(key)?.let { bytes -= it.size }
            if (value.size > maxBytes) return
            map[key] = value; bytes += value.size
            val it = map.entries.iterator()
            while ((map.size > maxEntries || bytes > maxBytes) && it.hasNext()) {
                bytes -= it.next().value.size; it.remove()
            }
        }
        @Synchronized fun containsKey(key: String): Boolean = map.containsKey(key)
        @Synchronized fun clear() { map.clear(); bytes = 0 }
    }

    private val segmentCache = SegmentCache(if(lowRam)3 else 6, if(lowRam)16*1024*1024 else 32*1024*1024)
    private val activeTasks = ConcurrentHashMap<String, Transfer>()
    private inner class Transfer(val url:String) {
        val media = ProgressiveMediaBuffer(if(lowRam)8*1024*1024 else 16*1024*1024)
        val task = object : FutureTask<ByteArray?>(Callable { fetchProgressive(url, media) }) {
            override fun done() {
                if(isCancelled)media.fail(InterruptedIOException("Channel cancelled"))
                activeTasks.remove(url,this@Transfer)
            }
        }
    }
    private fun transfer(url:String):Transfer {
        val candidate=Transfer(url)
        val actual=activeTasks.putIfAbsent(url,candidate)?:candidate
        if(actual===candidate)try{prefetchWorkers.execute(actual.task)}catch(_:RejectedExecutionException){actual.task.cancel(true)}
        return actual
    }
    private val nextSegments = Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    })

    init {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 4000
        serverSocket = socket
        port = socket.localPort
        running = true
        Thread({ runServer(socket) }, "HlsProxy-Accept").start()
    }

    fun proxyUrl(originUrl: String): String {
        return "http://127.0.0.1:$port/playlist.m3u8?url=" + URLEncoder.encode(originUrl, "UTF-8")
    }

    private fun runServer(server: ServerSocket) {
        while (running && !server.isClosed) {
            try {
                val client = server.accept()
                clients.add(client)
                try { clientWorkers.execute { handleClient(client) } }
                catch (_: RejectedExecutionException) { clients.remove(client); client.close() }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10000
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val line = input.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 2) return
            val path = parts[1]
            var headerLine = input.readLine()
            while (!headerLine.isNullOrEmpty()) {
                headerLine = input.readLine()
            }

            val uri = Uri.parse(path)
            val originUrl = uri.getQueryParameter("url")
            if (originUrl.isNullOrEmpty()) {
                sendResponse(client, 400, "text/plain", "Missing url".toByteArray())
                return
            }

            if (uri.path?.endsWith(".m3u8") == true || path.startsWith("/playlist")) {
                handlePlaylist(client, originUrl)
            } else {
                handleSegment(client, originUrl)
            }
        } catch (e: Exception) {
            android.util.Log.w("HlsProxy", "handleClient failed: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            clients.remove(client)
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handlePlaylist(client: Socket, originUrl: String) {
        val (statusCode, body, responseUrl) = fetchText(originUrl)
        if (statusCode !in 200..299 || body == null) {
            sendResponse(client, if (statusCode > 0) statusCode else 502, "text/plain", "Upstream Error".toByteArray())
            return
        }

        val rewritten = rewritePlaylist(body, responseUrl ?: originUrl)
        sendResponse(client, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray(Charsets.UTF_8))
    }

    private fun handleSegment(client: Socket, originUrl: String) {
        segmentCache[originUrl]?.let{
            nextSegments[originUrl]?.let(::prefetchSegment)
            sendResponse(client,200,"video/mp2t",it)
            return
        }
        val job=transfer(originUrl)
        nextSegments[originUrl]?.let(::prefetchSegment)
        var sent=false
        try {
            job.media.open().use{input ->
                val block=ByteArray(32768)
                val first=input.read(block)
                if(first<0)throw IOException("Empty segment")
                val length=job.media.expectedLength()
                val header="HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\n"+
                    (if(length>=0)"Content-Length: $length\r\n"else "")+"Connection: close\r\n\r\n"
                val output=client.getOutputStream()
                output.write(header.toByteArray(Charsets.US_ASCII));sent=true
                output.write(block,0,first);output.flush()
                while(true){val count=input.read(block);if(count<0)break;output.write(block,0,count);output.flush()}
            }
        } catch(_:Exception){if(!sent)sendResponse(client,502,"text/plain","Segment unavailable".toByteArray())}
    }

    private data class Variant(val info: String, val url: String, val bandwidth: Int, val width: Int, val height: Int)

    private fun fetchProgressive(originUrl:String,media:ProgressiveMediaBuffer):ByteArray? {
        var connection:HttpURLConnection?=null
        var pipe:CmgBytePipe?=null
        var decoding:Future<*>?=null
        val started=SystemClock.elapsedRealtime()
        try {
            if(!running||Thread.currentThread().isInterrupted)throw InterruptedIOException()
            connection=(URL(originUrl).openConnection() as HttpURLConnection).also{upstream.add(it)}.apply{
                LegacyTls.configure(this);connectTimeout=8000;readTimeout=10000;instanceFollowRedirects=true
                setRequestProperty("User-Agent",USER_AGENT);setRequestProperty("Accept-Encoding","identity")
            }
            if(connection.responseCode !in 200..299)throw IOException("Upstream HTTP ${connection.responseCode}")
            val expected=connection.contentLength
            if(expected>16*1024*1024)throw IOException("Segment exceeds limit")
            if(expected>=0)media.expectLength(expected)
            val mode=transformFor(connection.url.toString()).let{if(it=="plain")transformFor(originUrl)else it}
            if(mode!="plain") {
                val encrypted=CmgBytePipe(64*1024);pipe=encrypted
                decoding=transformWorkers.submit {
                    try {
                        fun checkActive(){if(!running||Thread.currentThread().isInterrupted)throw InterruptedIOException("Channel cancelled")}
                        checkActive()
                        if(mode=="h5e"){
                            if(!h5eLoaded){NativeH5eDecryptor.setSpsCompatibilityMode(true);h5eLoaded=true}
                            if(!NativeH5eDecryptor.supportsStreamingTransportStream())throw IOException("Streaming runtime missing")
                        }else synchronized(CMG_DECRYPT_LOCK){checkActive();restartCmgRuntime("Progressive segment")}
                        var first=true
                        val stream=CmgTsStream(object:CmgTsStream.Decoder {
                            override fun videoPid(prefix:ByteArray)=findVideoPid(prefix,mode=="h5e")
                            override fun decode(packets:ByteArray,pid:Int):ByteArray {
                                checkActive()
                                val clear=if(mode=="h5e")NativeH5eDecryptor.decryptTransportStreamPart(packets,first)
                                    ?:throw IOException("CCTV PES conversion failed")
                                else synchronized(CMG_DECRYPT_LOCK){checkActive();decryptYangshipinTransportStream(packets,true,pid)}
                                first=false
                                return clear
                            }
                        },if(lowRam)4*1024*1024 else 8*1024*1024)
                        stream.copy(BufferedInputStream(encrypted,32768)){media.append(it)}
                    }finally{encrypted.close()}
                }
            }
            var received=0
            val output=pipe?.output()
            try{
                connection.inputStream.use{input ->
                    val block=ByteArray(16384)
                    while(true){
                        if(!running||Thread.currentThread().isInterrupted||SystemClock.elapsedRealtime()-started>25000)throw InterruptedIOException("Segment cancelled or timed out")
                        val count=input.read(block);if(count<0)break
                        received+=count
                        if(received>16*1024*1024)throw IOException("Segment exceeds limit")
                        if(output!=null)output.write(block,0,count)else media.append(block.copyOf(count))
                    }
                }
            }finally{output?.close()}
            if(expected>=0&&received!=expected)throw IOException("Truncated segment $received/$expected")
            decoding?.get(25,TimeUnit.SECONDS)
            if(!running||Thread.currentThread().isInterrupted)throw InterruptedIOException()
            val body=media.finish()
            segmentCache[originUrl]=body
            return body
        }catch(e:Exception){
            media.fail(if(e is IOException)e else IOException("Segment failed",e))
            if(running&&e !is InterruptedException&&!Thread.currentThread().isInterrupted)android.util.Log.w("HlsProxy","Segment failed: ${e.message}")
            return null
        }finally{
            pipe?.close();if(decoding?.isDone==false)decoding.cancel(true)
            connection?.let{upstream.remove(it);it.disconnect()}
        }
    }

    private fun prefetchSegment(segmentUrl:String){
        if(running&&prefetchEnabled&&!segmentCache.containsKey(segmentUrl))transfer(segmentUrl)
    }

    private fun rewritePlaylist(body: String, baseUrl: String): String {
        val baseUri = URI.create(baseUrl)
        val lines = body.lines()

        if (body.contains("#EXT-X-STREAM-INF")) {
            val variants = mutableListOf<Variant>()
            var currentInfo = ""
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    currentInfo = trimmed
                } else if (!trimmed.startsWith("#") && currentInfo.isNotEmpty()) {
                    val absUrl = resolveUri(baseUri, trimmed).toString()
                    val bwMatch = Regex("BANDWIDTH=([0-9]+)").find(currentInfo)
                    val resMatch = Regex("RESOLUTION=([0-9]+)x([0-9]+)").find(currentInfo)
                    val bw = bwMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val w = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val h = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    variants.add(Variant(currentInfo, absUrl, bw, w, h))
                    currentInfo = ""
                }
            }

            if (variants.isNotEmpty()) {
                variants.sortWith(compareBy({ it.height }, { it.bandwidth }))
                val selected = when (preferredQuality) {
                    "hd" -> variants.firstOrNull { it.height in 720..1079 }
                        ?: variants.getOrNull(variants.size / 2)
                        ?: variants.last()
                    "fhd" -> {
                        if (android.os.Build.VERSION.SDK_INT <= 19) {
                            variants.firstOrNull { it.height in 720..1079 } ?: variants.last()
                        } else {
                            variants.last()
                        }
                    }
                    else -> variants.firstOrNull { it.height in 720..1079 } ?: variants.last()
                }

                android.util.Log.i("HlsProxy", "Selected variant: ${selected.width}x${selected.height} bw=${selected.bandwidth}")
                val proxiedUrl = if (selected.url.contains(".m3u8")) {
                    "http://127.0.0.1:$port/playlist.m3u8?url=" + URLEncoder.encode(selected.url, "UTF-8")
                } else {
                    selected.url
                }
                return "#EXTM3U\n${selected.info}\n$proxiedUrl\n"
            }
        }

        val result = StringBuilder(body.length + 512)
        val segmentsToPrefetch = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#")) {
                result.append(trimmed).append("\n")
            } else {
                val absoluteUri = resolveUri(baseUri, trimmed)
                val absoluteUrl = absoluteUri.toString()
                if (trimmed.contains(".m3u8")) {
                    val proxied = "http://127.0.0.1:$port/playlist.m3u8?url=" + URLEncoder.encode(absoluteUrl, "UTF-8")
                    result.append(proxied).append("\n")
                } else {
                    val proxied = "http://127.0.0.1:$port/segment.ts?url=" + URLEncoder.encode(absoluteUrl, "UTF-8")
                    result.append(proxied).append("\n")
                    segmentsToPrefetch.add(absoluteUrl)
                }
            }
        }

        if (segmentsToPrefetch.isNotEmpty()) {
            for (i in 0 until segmentsToPrefetch.size - 1) {
                nextSegments[segmentsToPrefetch[i]] = segmentsToPrefetch[i + 1]
            }
            val liveEdge = segmentsToPrefetch.takeLast(2).take(1)
            liveEdge.forEach { prefetchSegment(it) }
        }

        return result.toString()
    }

    private fun resolveUri(base: URI, relative: String): URI {
        return try {
            base.resolve(relative)
        } catch (_: Exception) {
            URI.create(relative)
        }
    }

    private fun fetchText(url: String): Triple<Int, String?, String?> {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).also { upstream.add(it) }.apply {
                LegacyTls.configure(this)
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            val code = connection.responseCode
            val effectiveUrl = connection.url.toString()
            if (code in 200..299) {
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        builder.append(buffer, 0, n)
                        if (builder.length > 2 * 1024 * 1024) {
                            android.util.Log.w("HlsProxy", "fetchText too large for $url")
                            return Triple(-1, null, null)
                        }
                    }
                    builder.toString()
                }
                return Triple(code, text, effectiveUrl)
            }
            return Triple(code, null, effectiveUrl)
        } catch (e: Exception) {
            android.util.Log.e("HlsProxy", "fetchText failed for $url: " + e.message, e)
            return Triple(-1, null, null)
        } finally {
            connection?.let { upstream.remove(it); it.disconnect() }
        }
    }

    private fun sendResponse(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val out = socket.getOutputStream()
        val statusText = if (status == 200) "OK" else if (status == 404) "Not Found" else "Error"
        val header = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    @Synchronized override fun close() {
        if(!running)return
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        clientWorkers.shutdownNow()
        prefetchWorkers.shutdownNow()
        if (h5eLoaded) try { NativeH5eDecryptor.cancelPendingDecrypts() } catch (_: LinkageError) {}
        transformWorkers.shutdownNow()
        upstream.forEach { it.disconnect() }; upstream.clear()
        activeTasks.values.forEach { it.task.cancel(true) }
        clients.forEach { try { it.close() } catch (_: IOException) {} }
        clients.clear()
        segmentCache.clear()
        activeTasks.clear()
        nextSegments.clear()
    }


    private class PesBuffer {
        var bytes = ByteArray(64 * 1024)
        var size = 0
        var packetOffsets = IntArray(512)
        var transportOffsets = IntArray(512)
        var slotCount = 0
        var pesHeaderOffset = -1
        var pesHeaderLength = 0
        var pesPacketLength = 0

        fun add(ts: ByteArray, packetOffset: Int, offset: Int, length: Int) {
            ensureByteCapacity(size + length)
            ensureSlotCapacity(slotCount + 1)
            packetOffsets[slotCount] = packetOffset
            transportOffsets[slotCount] = offset
            slotCount++
            System.arraycopy(ts, offset, bytes, size, length)
            size += length
        }

        fun data(): ByteArray = bytes

        fun payloadSize(): Int {
            val payloadLength = expectedPayloadLength()
            if (payloadLength in 0 until size) {
                return payloadLength
            }
            return size
        }

        fun copyPayloadToTransportStream(ts: ByteArray, data: ByteArray, dataLength: Int) {
            updatePesLength(ts, dataLength)
            var dataOffset = 0
            for (slot in 0 until slotCount) {
                val packetOffset = packetOffsets[slot]
                val transportOffset = transportOffsets[slot]
                val packetEnd = packetOffset + 188
                val capacity = packetEnd - transportOffset
                val remaining = dataLength - dataOffset
                val count = Math.min(capacity, Math.max(remaining, 0))
                if (count == capacity) {
                    System.arraycopy(data, dataOffset, ts, transportOffset, count)
                    dataOffset += count
                    continue
                }
                Arrays.fill(ts, packetOffset + 4, packetEnd, 0xff.toByte())
                val header = ts[packetOffset + 3].toInt() and 0xff
                if (count <= 0) {
                    ts[packetOffset + 1] = (ts[packetOffset + 1].toInt() and 0x40.inv()).toByte()
                    ts[packetOffset + 3] = ((header and 0xcf) or 0x20).toByte()
                    ts[packetOffset + 4] = 183.toByte()
                    ts[packetOffset + 5] = 0.toByte()
                } else {
                    val payloadOffset = packetEnd - count
                    val adaptationLength = payloadOffset - packetOffset - 5
                    ts[packetOffset + 3] = ((header and 0xcf) or 0x30).toByte()
                    ts[packetOffset + 4] = adaptationLength.toByte()
                    if (adaptationLength > 0) {
                        ts[packetOffset + 5] = 0.toByte()
                    }
                    System.arraycopy(data, dataOffset, ts, payloadOffset, count)
                    dataOffset += count
                }
            }
            if (dataOffset < dataLength) {
                android.util.Log.w("HlsProxy", "Rebuilt PES payload did not fit original TS packets before=$size after=$dataLength")
            }
        }

        fun reset() {
            size = 0
            slotCount = 0
            pesHeaderOffset = -1
            pesHeaderLength = 0
            pesPacketLength = 0
        }

        fun setHeader(offset: Int, length: Int, packetLength: Int) {
            pesHeaderOffset = offset
            pesHeaderLength = length
            pesPacketLength = packetLength
        }

        private fun updatePesLength(ts: ByteArray, payloadLength: Int) {
            if (pesHeaderOffset < 0 || pesPacketLength == 0) return
            val updatedLength = payloadLength + pesHeaderLength - 6
            if (updatedLength > 0xffff) return
            ts[pesHeaderOffset + 4] = ((updatedLength shr 8) and 0xff).toByte()
            ts[pesHeaderOffset + 5] = (updatedLength and 0xff).toByte()
        }

        private fun expectedPayloadLength(): Int {
            if (pesPacketLength <= 0 || pesHeaderLength <= 0) return -1
            return Math.max(0, pesPacketLength - (pesHeaderLength - 6))
        }

        private fun ensureByteCapacity(required: Int) {
            if (required <= bytes.size) return
            var capacity = bytes.size
            while (capacity < required) {
                capacity = if (capacity < 1024 * 1024) capacity shl 1 else required
            }
            bytes = Arrays.copyOf(bytes, capacity)
        }

        private fun ensureSlotCapacity(required: Int) {
            if (required <= packetOffsets.size) return
            val capacity = packetOffsets.size shl 1
            packetOffsets = Arrays.copyOf(packetOffsets, capacity)
            transportOffsets = Arrays.copyOf(transportOffsets, capacity)
        }
    }

    companion object {
        fun configureChannel(pid: String) {
            synchronized(CMG_DECRYPT_LOCK) {
                NativeCmgDecryptor.configureLocationForProbe("https://www.yangshipin.cn/tv/home?pid=$pid")
            }
        }
        internal fun transformFor(url: String): String {
            val uri = try { URI(url) } catch (_: Exception) { return "plain" }
            val host = uri.host?.lowercase(Locale.US) ?: return "plain"
            if (host == "yangshipin.cn" || host.endsWith(".yangshipin.cn") || host == "ysp.cctv.cn" || host.endsWith(".ysp.cctv.cn")) return "cmg"
            val path = uri.path.orEmpty().lowercase(Locale.US)
            return if (path.contains("cdrmld") || path.contains("cctvwbcd")) "h5e" else "plain"
        }
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val CMG_DECRYPT_LOCK = Any()
        private val CMG_PES_BUFFER = PesBuffer()
        @Volatile private var cmgLiveVideoDecodeEnabled = false
        @Volatile private var cmgVclSinceRuntimeRestart = 0
        @Volatile private var cmgPlayerTag = ""

        fun setPlayerTag(tag: String) {
            cmgPlayerTag = tag
        }

        private fun restartCmgRuntime(reason: String) {
            synchronized(CMG_DECRYPT_LOCK) {
                try {
                    syncCmgRuntimeClockForNative()
                    NativeCmgDecryptor.resetRuntimeForProbe()
                    NativeCmgDecryptor.initializeRuntimeForProbe()
                } catch (t: Throwable) {
                    android.util.Log.e("HlsProxy", "restartCmgRuntime failed ($reason): ${t.message}")
                }
                cmgLiveVideoDecodeEnabled = false
                cmgVclSinceRuntimeRestart = 0
            }
        }

        private fun syncCmgRuntimeClockForNative() {
            try {
                NativeCmgDecryptor.setClockForProbe(System.currentTimeMillis())
            } catch (_: Throwable) {}
        }

        private fun findVideoPid(ts: ByteArray, avcOnly: Boolean = false): Int {
            var pmtPid = -1
            var offset = 0
            while (offset + 188 <= ts.size) {
                if (ts[offset] != 0x47.toByte()) {
                    offset += 188
                    continue
                }
                val pid = ((ts[offset + 1].toInt() and 0x1f) shl 8) or (ts[offset + 2].toInt() and 0xff)
                val payloadStart = (ts[offset + 1].toInt() and 0x40) != 0
                val payloadOffset = payloadOffset(ts, offset)
                if (payloadOffset >= 0 && payloadStart) {
                    val section = psiSection(ts, payloadOffset, offset + 188)
                    if (section != null) {
                        if (pid == 0) {
                            var index = 8
                            while (index + 4 <= section.size - 4) {
                                val program = ((section[index].toInt() and 0xff) shl 8) or (section[index + 1].toInt() and 0xff)
                                if (program != 0) {
                                    pmtPid = ((section[index + 2].toInt() and 0x1f) shl 8) or (section[index + 3].toInt() and 0xff)
                                    break
                                }
                                index += 4
                            }
                        } else if (pid == pmtPid) {
                            val programInfoLength = ((section[10].toInt() and 0x0f) shl 8) or (section[11].toInt() and 0xff)
                            var index = 12 + programInfoLength
                            while (index + 5 <= section.size - 4) {
                                val streamType = section[index].toInt() and 0xff
                                val elementaryPid = ((section[index + 1].toInt() and 0x1f) shl 8) or (section[index + 2].toInt() and 0xff)
                                val infoLength = ((section[index + 3].toInt() and 0x0f) shl 8) or (section[index + 4].toInt() and 0xff)
                                if (streamType == 0x1b || (!avcOnly && streamType == 0x24)) {
                                    return elementaryPid
                                }
                                index += 5 + infoLength
                            }
                        }
                    }
                }
                offset += 188
            }
            return -1
        }

        private fun payloadOffset(ts: ByteArray, packetOffset: Int): Int {
            val adaptationControl = (ts[packetOffset + 3].toInt() shr 4) and 3
            if ((adaptationControl and 1) == 0) return -1
            var offset = packetOffset + 4
            if ((adaptationControl and 2) != 0) {
                offset += 1 + (ts[offset].toInt() and 0xff)
            }
            return if (offset < packetOffset + 188) offset else -1
        }

        private fun psiSection(ts: ByteArray, payloadOffset: Int, packetEnd: Int): ByteArray? {
            val pointer = ts[payloadOffset].toInt() and 0xff
            val sectionStart = payloadOffset + 1 + pointer
            if (sectionStart + 3 > packetEnd) return null
            val sectionLength = ((ts[sectionStart + 1].toInt() and 0x0f) shl 8) or (ts[sectionStart + 2].toInt() and 0xff)
            var sectionEnd = sectionStart + 3 + sectionLength
            if (sectionEnd > packetEnd) sectionEnd = packetEnd
            val section = ByteArray(sectionEnd - sectionStart)
            System.arraycopy(ts, sectionStart, section, 0, section.size)
            return section
        }

        private fun startCodeLength(data: ByteArray, offset: Int): Int {
            if (offset + 3 < data.size && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte()) {
                if (data[offset + 2] == 1.toByte()) return 3
                if (offset + 4 < data.size && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) return 4
            }
            return 0
        }

        private fun normalizeVideoContinuityCounters(ts: ByteArray, videoPid: Int) {
            var lastCounter = -1
            var packetOffset = 0
            while (packetOffset + 188 <= ts.size) {
                if (ts[packetOffset] == 0x47.toByte()) {
                    val pid = ((ts[packetOffset + 1].toInt() and 0x1f) shl 8) or (ts[packetOffset + 2].toInt() and 0xff)
                    if (pid == videoPid) {
                        val adaptationControl = (ts[packetOffset + 3].toInt() shr 4) and 3
                        val hasPayload = (adaptationControl and 1) != 0
                        val originalCounter = ts[packetOffset + 3].toInt() and 0x0f
                        val nextCounter = if (lastCounter < 0) {
                            originalCounter
                        } else if (hasPayload) {
                            (lastCounter + 1) and 0x0f
                        } else {
                            lastCounter
                        }
                        if (originalCounter != nextCounter) {
                            ts[packetOffset + 3] = ((ts[packetOffset + 3].toInt() and 0xf0) or nextCounter).toByte()
                        }
                        lastCounter = nextCounter
                    }
                }
                packetOffset += 188
            }
        }

        fun decryptYangshipinTransportStream(ts: ByteArray, spsCompatibility: Boolean = true, knownVideoPid: Int = -1): ByteArray {
            synchronized(CMG_DECRYPT_LOCK) {
                val startedAt = SystemClock.elapsedRealtime()
                val videoPid = if(knownVideoPid>=0)knownVideoPid else findVideoPid(ts)
                if (videoPid < 0) return ts

                val output = ts
                val currentPes = CMG_PES_BUFFER
                currentPes.reset()
                var pesActive = false
                var remainingPesPayload = -1

                var packetOffset = 0
                while (packetOffset + 188 <= output.size) {
                    if (output[packetOffset] == 0x47.toByte()) {
                        val pid = ((output[packetOffset + 1].toInt() and 0x1f) shl 8) or (output[packetOffset + 2].toInt() and 0xff)
                        if (pid == videoPid) {
                            var payloadOffset = payloadOffset(output, packetOffset)
                            if (payloadOffset >= 0) {
                                val payloadStart = (output[packetOffset + 1].toInt() and 0x40) != 0
                                if (payloadStart) {
                                    if (pesActive && currentPes.size > 0) {
                                        decryptPesNals(output, currentPes, spsCompatibility)
                                    }
                                    currentPes.reset()
                                    pesActive = false
                                    remainingPesPayload = -1
                                    if (payloadOffset + 9 < packetOffset + 188
                                        && output[payloadOffset] == 0.toByte() && output[payloadOffset + 1] == 0.toByte()
                                        && output[payloadOffset + 2] == 1.toByte()
                                    ) {
                                        val pesHeaderLength = 9 + (output[payloadOffset + 8].toInt() and 0xff)
                                        val pesPacketLength = ((output[payloadOffset + 4].toInt() and 0xff) shl 8) or (output[payloadOffset + 5].toInt() and 0xff)
                                        if (pesPacketLength > 0) {
                                            remainingPesPayload = Math.max(0, pesPacketLength - (pesHeaderLength - 6))
                                        }
                                        currentPes.setHeader(payloadOffset, pesHeaderLength, pesPacketLength)
                                        pesActive = true
                                        payloadOffset += pesHeaderLength
                                    }
                                }
                                if (pesActive && payloadOffset < packetOffset + 188) {
                                    var payloadLength = packetOffset + 188 - payloadOffset
                                    if (remainingPesPayload >= 0) {
                                        payloadLength = Math.min(payloadLength, remainingPesPayload)
                                        remainingPesPayload -= payloadLength
                                    }
                                    if (payloadLength > 0) {
                                        currentPes.add(output, packetOffset, payloadOffset, payloadLength)
                                    }
                                }
                            }
                        }
                    }
                    packetOffset += 188
                }
                if (pesActive && currentPes.size > 0) {
                    decryptPesNals(output, currentPes, spsCompatibility)
                }
                if(knownVideoPid<0)normalizeVideoContinuityCounters(output, videoPid)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                if (elapsedMs >= 500L) {
                    android.util.Log.i("HlsProxy", "CMG TS decrypt elapsed=${elapsedMs}ms bytes=${ts.size}")
                }
                return output
            }
        }

        private fun decryptPesNals(ts: ByteArray, pes: PesBuffer, spsCompatibility: Boolean) {
            val data = pes.data()
            val dataLength = pes.payloadSize()
            var rebuilt: ByteArrayOutputStream? = null
            var writeOffset = 0
            var offset = 0
            while (offset < dataLength - 4) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Channel cancelled")
                val prefix = startCodeLength(data, offset)
                if (prefix == 0) {
                    offset++
                    continue
                }
                val nalStart = offset + prefix
                if (nalStart >= dataLength) {
                    offset++
                    continue
                }
                var nalEnd = dataLength
                for (next in (nalStart + 1) until (dataLength - 4)) {
                    if (startCodeLength(data, next) > 0) {
                        nalEnd = next
                        break
                    }
                }
                val nalType = data[nalStart].toInt() and 0x1f
                var replacementLength = -1
                val replaceNal = cmgLiveVideoDecodeEnabled && (nalType == 1 || nalType == 5)
                val stateOnlyNal = (nalType == 7)

                if (nalType == 7 && cmgVclSinceRuntimeRestart >= 150) {
                    restartCmgRuntime("VCL budget $cmgVclSinceRuntimeRestart")
                }
                advanceCmgSessionForNal(nalType)

                if (stateOnlyNal) {
                    val nal = ByteArray(nalEnd - nalStart)
                    System.arraycopy(data, nalStart, nal, 0, nal.size)
                    updateCmgLiveVideoFlag(nalType, nal)
                    try {
                        NativeCmgDecryptor.decodeNalForProbe(nal, true, true)
                    } catch (t: Throwable) {
                        android.util.Log.w("HlsProxy", "decodeNalForProbe error: ${t.message}")
                    }
                    sanitizeH264SpsMarker(data, nalStart, nalEnd, spsCompatibility)
                } else if (replaceNal) {
                    val nalLength = nalEnd - nalStart
                    try {
                        val decodedLength = NativeCmgDecryptor.decodeNalRangeInPlace(data, nalStart, nalLength, true, true)
                        if (decodedLength >= 0) {
                            replacementLength = decodedLength
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w("HlsProxy", "decodeNalRangeInPlace error: ${t.message}")
                    }
                }
                if (nalType == 1 || nalType == 5) {
                    cmgVclSinceRuntimeRestart++
                }
                if (replacementLength in 0 until (nalEnd - nalStart) && rebuilt == null) {
                    rebuilt = ByteArrayOutputStream(dataLength)
                }
                if (rebuilt != null) {
                    rebuilt.write(data, writeOffset, nalStart - writeOffset)
                    if (replacementLength < 0) {
                        rebuilt.write(data, nalStart, nalEnd - nalStart)
                    } else {
                        rebuilt.write(data, nalStart, replacementLength)
                    }
                    writeOffset = nalEnd
                }
                offset = nalEnd - 1
                offset++
            }
            if (rebuilt == null) {
                pes.copyPayloadToTransportStream(ts, data, dataLength)
            } else {
                rebuilt.write(data, writeOffset, dataLength - writeOffset)
                val repacked = rebuilt.toByteArray()
                pes.copyPayloadToTransportStream(ts, repacked, repacked.size)
            }
        }

        private fun updateCmgLiveVideoFlag(nalType: Int, nal: ByteArray) {
            if (nalType != 7 || nal.size <= 2 || cmgLiveVideoDecodeEnabled) return
            val bits = nal[2].toInt() and 3
            cmgLiveVideoDecodeEnabled = (bits == 1 || bits == 2)
            if (cmgLiveVideoDecodeEnabled) {
                android.util.Log.i("HlsProxy", "CMG live video encryption detected in SPS (bits=$bits)")
            }
        }

        private fun sanitizeH264SpsMarker(data: ByteArray, nalStart: Int, nalEnd: Int, spsCompatibility: Boolean) {
            if (!spsCompatibility || nalEnd - nalStart < 3) return
            val before = data[nalStart + 2].toInt() and 0xff
            val after = before and 0xfc
            if (before != after) {
                data[nalStart + 2] = after.toByte()
            }
        }

        private fun advanceCmgSessionForNal(nalType: Int): Int {
            try {
                val mediaTag = cmgPlayerTag
                if (mediaTag.isNotEmpty()) {
                    NativeCmgDecryptor.setPlayerTagForProbe(mediaTag)
                }
                syncCmgRuntimeClockForNative()
                return NativeCmgDecryptor.updateSessionForProbe()
            } catch (_: Throwable) {
                return 0
            }
        }
    }
}
