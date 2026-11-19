package com.tvbridge.app

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch

class LiveRepository(private val context:Context) {
    data class Source(val url:String,val name:String,val channels:List<LiveChannel>,val updated:Long)
    private val file=AtomicFile(java.io.File(context.filesDir,"live-sources.json"))
    private val loaded=CountDownLatch(1)
    @Volatile private var persistSeq=0L
    private val playback=context.getSharedPreferences("live-resume",Context.MODE_PRIVATE)
    private val sources=mutableListOf<Source>()
    private val favorites=mutableSetOf<String>()
    private var active="local"
    private var lastChannel=""
    private var lastPlayedAt=0L
    @Volatile var lastMessage="可使用手机添加订阅或导入节目单"
        private set

    init {
        AppThreads.onPersist {
            try {
                loadFromDisk()
            } catch (t: Throwable) {
                Log.w("LiveRepository", "异步加载 live-sources.json 失败", t)
            } finally {
                loaded.countDown()
            }
        }
    }

    private fun awaitLoaded() {
        if (loaded.count == 0L) return
        try {
            loaded.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun loadFromDisk() {
        val loadedSources = mutableListOf<Source>()
        val loadedFavorites = mutableSetOf<String>()
        var loadedActive = "local"
        var loadedLastChannel = ""
        var loadedLastPlayedAt = 0L
        try {
            val stored = file.readFully()
            val root = JSONObject(String(stored, Charsets.UTF_8))
            loadedActive = root.optString("active", "local")
            loadedLastChannel = root.optString("lastChannel")
            loadedLastPlayedAt = root.optLong("lastPlayedAt")
            val list = root.optJSONArray("sources")
            val retireBuiltIn = list != null && (0 until minOf(list.length(), 11)).any { i ->
                list.optJSONObject(i)?.let { isRetiredBuiltIn(it) } == true
            } && backupBeforeRetiringBuiltIn(stored)
            list?.let { entries ->
                var total = 0
                for (i in 0 until minOf(entries.length(), 11)) {
                    try {
                        val s = entries.getJSONObject(i)
                        if (retireBuiltIn && isRetiredBuiltIn(s)) continue
                        val saved = s.getJSONArray("channels")
                        val limit = minOf(saved.length(), 20000, (20000 - total).coerceAtLeast(0))
                        val channels = (0 until limit).mapNotNull { index ->
                            try {
                                val c = saved.getJSONObject(index)
                                val urls = c.getJSONArray("streams")
                                val streams = (0 until urls.length()).mapNotNull { savedStream(urls.getString(it)) }
                                if (streams.isEmpty()) null else LiveChannel(c.getString("id"), c.getString("name"), c.getString("group"), streams)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        total += channels.size
                        val url = s.getString("url")
                        loadedSources.add(Source(url, s.getString("name"), channels, s.optLong("updated")))
                    } catch (e: Exception) {
                        Log.w("LiveRepository", "跳过损坏的直播源 #$i: ${e.javaClass.simpleName} ${e.message}")
                    }
                }
            }
            root.optJSONArray("favorites")?.let { a -> for (i in 0 until a.length()) loadedFavorites.add(a.getString(i)) }
        } catch (_: Exception) {
        }
        val playedAt = playback.getLong("time", 0)
        val playedId = playback.getString("channel", "").orEmpty()
        val playedSource = loadedSources.firstOrNull { source -> source.channels.any { it.id == playedId } }
        if (playedAt > loadedLastPlayedAt && playedSource != null) {
            loadedLastPlayedAt = playedAt
            loadedLastChannel = playedId
            loadedActive = playedSource.url
        }
        synchronized(this) {
            sources.clear()
            sources.addAll(loadedSources)
            favorites.clear()
            favorites.addAll(loadedFavorites)
            active = loadedActive
            lastChannel = loadedLastChannel
            lastPlayedAt = loadedLastPlayedAt
            snapshotKey = null
        }
    }
    private fun isRetiredBuiltIn(source:JSONObject):Boolean {
        val url=source.optString("url")
        val plugin="one-plugin://live"
        val page="https://www.yangshipin.cn/tv/home"
        if(url!=plugin&&url!=page)return false
        val channels=source.optJSONArray("channels")?:return false
        if(channels.length()==0)return url==plugin
        val legacyId=Regex("^yangshipin-([0-9]{6,12})$")
        return (0 until channels.length()).all { index->
            val channel=channels.optJSONObject(index)?:return@all false
            val pid=legacyId.matchEntire(channel.optString("id"))
                ?.groupValues?.get(1) ?: return@all false
            val streams=channel.optJSONArray("streams")?:return@all false
            val expected=if(url==plugin)"$plugin/$pid" else "$page?pid=$pid"
            streams.length()==1 && streams.optString(0)==expected
        }
    }

    private fun backupBeforeRetiringBuiltIn(stored:ByteArray):Boolean {
        val backup=AtomicFile(java.io.File(context.filesDir,"live-sources.pre-v1.3.json"))
        return try {
            if(!backup.baseFile.isFile){
                val out=backup.startWrite()
                try{out.write(stored);backup.finishWrite(out)}catch(e:Exception){backup.failWrite(out);throw e}
            }
            true
        }catch(e:Exception){
            Log.w("LiveRepository","未能备份旧版直播源",e)
            false
        }
    }

    private fun savedStream(value:String)=PlaylistParser.streamUrl(value)

    fun allChannels(): List<LiveChannel> {
        awaitLoaded()
        return synchronized(this) {
            val allowed = sources.toList()
            if (allowed.none { it.url == active }) active = allowed.firstOrNull()?.url ?: "local"
            allowed.firstOrNull { it.url == active }?.channels.orEmpty()
        }
    }

    fun sourceList(): List<Source> {
        awaitLoaded()
        return synchronized(this) { sources.toList() }
    }

    fun findChannel(id: String?): LiveChannel? {
        awaitLoaded()
        return synchronized(this) {
            sources.asSequence().flatMap { it.channels.asSequence() }.firstOrNull { it.id == id }
        }
    }

    fun favoriteChannels(): List<LiveChannel> {
        awaitLoaded()
        return synchronized(this) {
            sources.flatMap { it.channels }.filter { favorites.contains(it.id) }.distinctBy { it.id }
        }
    }

    fun activeSource(): String {
        awaitLoaded()
        return synchronized(this) {
            if (sources.none { it.url == active }) active = sources.firstOrNull()?.url ?: "local"
            active
        }
    }

    fun rememberChannel(id: String) {
        awaitLoaded()
        synchronized(this) {
            val source = sources.firstOrNull { s -> s.channels.any { it.id == id } } ?: return
            if (id == "cast") return
            if (lastChannel == id && active == source.url) return
            lastChannel = id
            lastPlayedAt = maxOf(System.currentTimeMillis(), lastPlayedAt + 1)
            active = source.url
            playback.edit().putString("channel", id).putLong("time", lastPlayedAt).apply()
        }
    }

    fun resumeChannel(): LiveChannel? {
        awaitLoaded()
        return synchronized(this) {
            val available = sources.toList()
            val source = available.firstOrNull { s -> s.channels.any { it.id == lastChannel } }
            if (source != null) {
                if (active != source.url) {
                    active = source.url
                    saveAsync()
                }
                return@synchronized source.channels.first { it.id == lastChannel }
            }
            val currentChannels = available.firstOrNull { it.url == active }?.channels.orEmpty()
            val fallback = currentChannels.firstOrNull()
                ?: available.firstOrNull { it.channels.isNotEmpty() }?.channels?.firstOrNull()
            if (fallback != null) {
                val next = available.first { s -> s.channels.any { it.id == fallback.id } }
                if (active != next.url) {
                    active = next.url
                    saveAsync()
                }
            }
            fallback
        }
    }

    fun favorite(id: String): Boolean {
        awaitLoaded()
        return synchronized(this) { favorites.contains(id) }
    }

    fun toggleFavorite(id: String) {
        awaitLoaded()
        synchronized(this) {
            if (!favorites.add(id)) favorites.remove(id)
            saveAsync()
        }
    }

    fun selectSource(url: String) {
        awaitLoaded()
        synchronized(this) {
            if (sources.any { it.url == url }) {
                active = url
                saveAsync()
            }
        }
    }

    fun removeSource(url: String) {
        awaitLoaded()
        synchronized(this) {
            sources.removeAll { it.url == url }
            if (active == url) active = sources.firstOrNull()?.url ?: "local"
            saveAsync()
        }
    }

    private data class SnapshotKey(val revision: Long, val source: String, val message: String)
    private var snapshotKey: SnapshotKey? = null
    private var snapshotJson = ""
    private var snapshotVersion = 0L

    fun snapshot(knownVersion: String = ""): String {
        awaitLoaded()
        return synchronized(this) {
            val key = SnapshotKey(revision, active, lastMessage)
            if (key == snapshotKey) return@synchronized if (knownVersion == snapshotVersion.toString()) "" else snapshotJson
            if (sources.none { it.url == active }) active = sources.firstOrNull()?.url ?: "local"
            val activeChannels = sources.firstOrNull { it.url == active }?.channels.orEmpty()
            val channels = JSONArray()
            activeChannels.forEach { channels.put(it.json().put("favorite", favorites.contains(it.id))) }
            val list = JSONArray()
            sources.forEach { list.put(JSONObject().put("url", it.url).put("name", it.name).put("count", it.channels.size)) }
            val favs = sources.flatMap { it.channels }.filter { favorites.contains(it.id) }.distinctBy { it.id }
            snapshotVersion = maxOf(snapshotVersion + 1, System.currentTimeMillis())
            snapshotJson = JSONObject()
                .put("version", snapshotVersion.toString())
                .put("channels", channels)
                .put("favoriteChannels", JSONArray(favs.map { it.json().put("favorite", true) }))
                .put("sources", list)
                .put("active", active)
                .put("message", lastMessage)
                .toString()
            snapshotKey = key.copy(revision = revision, source = active)
            snapshotJson
        }
    }

    @Volatile var revision = 0L
        private set
    var onPersist: ((String) -> Unit)? = null

    fun exportStore(): String {
        awaitLoaded()
        return synchronized(this) { exportStoreLocked() }
    }

    private fun exportStoreLocked(): String {
        val list = JSONArray()
        sources.forEach { s ->
            list.put(
                JSONObject()
                    .put("url", s.url)
                    .put("name", s.name)
                    .put("updated", s.updated)
                    .put("channels", JSONArray(s.channels.map { it.json() }))
            )
        }
        return JSONObject()
            .put("active", active)
            .put("sources", list)
            .put("favorites", JSONArray(favorites.toList()))
            .put("lastChannel", lastChannel)
            .put("lastPlayedAt", lastPlayedAt)
            .toString()
    }

    private fun saveLocked() {
        persistSeq++
        val text = exportStoreLocked()
        writeStore(text)
        revision++
        onPersist?.invoke(text)
    }

    private fun writeStore(text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val out = file.startWrite()
        try {
            out.write(data)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun saveAsync() {
        val snapshot = synchronized(this) { exportStoreLocked().also { revision++ } to ++persistSeq }
        val text = snapshot.first
        val seq = snapshot.second
        AppThreads.onPersist {
            try {
                if (seq == persistSeq) {
                    writeStore(text)
                    onPersist?.invoke(text)
                }
            } catch (e: Throwable) {
                Log.w("LiveRepository", "live-sources.json 异步写盘失败", e)
            }
        }
    }

    private fun decodedSources(root: JSONObject): List<Source> {
        val entries = root.getJSONArray("sources")
        require(entries.length() <= 11)
        return (0 until entries.length()).mapNotNull { i ->
            val item = entries.getJSONObject(i)
            if (isRetiredBuiltIn(item)) return@mapNotNull null
            val url = item.getString("url")
            require(url == "local" || PlaylistParser.playlistAddress(url) != null)
            val channels = item.getJSONArray("channels")
            require(channels.length() <= 20000)
            Source(
                url,
                item.getString("name").take(100),
                (0 until channels.length()).map { j ->
                    val c = channels.getJSONObject(j)
                    val streams = c.getJSONArray("streams")
                    require(streams.length() in 1..100)
                    LiveChannel(
                        c.getString("id"),
                        c.getString("name").take(200),
                        c.optString("group").take(100),
                        (0 until streams.length()).map { k -> savedStream(streams.getString(k)) ?: error("无效频道地址") }
                    )
                },
                item.optLong("updated")
            )
        }
    }

    fun restoreSnapshot(text: String) {
        awaitLoaded()
        synchronized(this) {
            val root = JSONObject(text)
            val next = decodedSources(root)
            require(next.sumOf { it.channels.size } <= 20000)
            sources.clear()
            sources.addAll(next)
            favorites.clear()
            root.optJSONArray("favorites")?.let { a ->
                for (i in 0 until minOf(a.length(), 20000)) favorites.add(a.getString(i))
            }
            active = root.getString("active")
            lastChannel = root.optString("lastChannel")
            lastPlayedAt = root.optLong("lastPlayedAt")
            snapshotKey = null
        }
    }

    fun mergeSnapshot(before: String, after: String) {
        awaitLoaded()
        synchronized(this) {
            val old = JSONObject(before)
            val next = JSONObject(after)
            val previous = decodedSources(old).associateBy { it.url }
            val incoming = decodedSources(next).associateBy { it.url }
            val result = sources.filter { it.url !in previous || it.url in incoming }.associateBy { it.url }.toMutableMap()
            incoming.forEach { (key, value) -> if (previous[key] != value) result[key] = value }
            require(result.size <= 11 && result.values.sumOf { it.channels.size } <= 20000)
            fun ids(root: JSONObject): Set<String> {
                val a = root.optJSONArray("favorites") ?: JSONArray()
                require(a.length() <= 20000)
                return (0 until a.length()).map { a.getString(it) }.toSet()
            }
            val oldIds = ids(old)
            val newIds = ids(next)
            sources.clear()
            sources.addAll(result.values)
            favorites.removeAll(oldIds - newIds)
            favorites.addAll(newIds - oldIds)
            if (old.optString("active") != next.optString("active")) active = next.getString("active")
            if (old.optString("lastChannel") != next.optString("lastChannel") && next.optLong("lastPlayedAt") > lastPlayedAt) {
                lastChannel = next.optString("lastChannel")
                lastPlayedAt = next.optLong("lastPlayedAt")
            }
            saveAsync()
        }
    }

    private fun replace(source: Source) {
        awaitLoaded()
        synchronized(this) {
            require(sources.size < 10 || sources.any { it.url == source.url }) { "最多保存 10 个订阅源" }
            require(source.channels.size + sources.filter { it.url != source.url }.sumOf { it.channels.size } <= 20000) { "总频道数最多 20000，请先删除不需要的源" }
            val old = sources.toList()
            val previousActive = active
            sources.removeAll { it.url == source.url }
            sources.add(source)
            active = source.url
            try {
                saveLocked()
            } catch (e: Exception) {
                sources.clear()
                sources.addAll(old)
                active = previousActive
                throw e
            }
            lastMessage = "已保存 ${source.channels.size} 个频道"
        }
    }

    fun importText(text: String): Boolean = attempt {
        awaitLoaded()
        val channels = PlaylistParser.parse(text, "local")
        require(channels.isNotEmpty()) { "没有找到可用频道" }
        val old = synchronized(this) { sources.firstOrNull { it.url == "local" }?.channels.orEmpty() }
        replace(Source("local", "本地导入", (old + channels).associateBy { it.id }.values.toList(), System.currentTimeMillis()))
    }
    fun addDirect(name:String,url:String):Boolean=attempt {
        val stream=PlaylistParser.streamUrl(url)?:error("请输入 HTTP、HTTPS、RTSP 或 UDP 直播地址")
        val channel=JSONObject().put("name",name.ifBlank{"自定义直播"}).put("url",stream)
        if(!importText(JSONArray().put(channel).toString()))error(lastMessage)
    }
    fun subscribe(address:String):Boolean=attempt {
        awaitLoaded()
        var next=PlaylistParser.playlistAddress(address)?:error("请输入 HTTP / HTTPS 节目单链接")
        val original=next
        var text:String?=null
        for(attempt in 0..5){
            val connection=(URL(next).openConnection() as HttpURLConnection).also{LegacyTls.configure(it)}
            try {
                connection.connectTimeout=10000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
                connection.setRequestProperty("User-Agent","TVBridge/0.2 AndroidTV")
                if(connection.responseCode in 300..399){require(attempt<5){"重定向过多"};next=PlaylistParser.httpUrl(URL(URL(next),connection.getHeaderField("Location")?:error("重定向地址缺失")).toString())?:error("订阅跳转到不支持的地址");continue}
                require(connection.responseCode in 200..299){"订阅更新失败 (${connection.responseCode})，已保留原频道"}
                require((connection.getHeaderField("Content-Length")?.toLongOrNull()?:-1L)<=PlaylistParser.MAX_BYTES){"节目单超过 20 MB"}
                connection.inputStream.use{input->val out=ByteArrayOutputStream();val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=PlaylistParser.MAX_BYTES){"节目单超过 20 MB"};out.write(buffer,0,n)};text=out.toString("UTF-8")}
                break
            }finally{connection.disconnect()}
        }
        val body=text?:error("订阅读取失败")
        val digests=context.getSharedPreferences("live-subscriptions",Context.MODE_PRIVATE)
        val digest=PlaylistParser.digest(body)
        if(digests.getString(original,null)==digest){
            val refreshed=synchronized(this){
                val existing=sources.firstOrNull{it.url==original && it.channels.isNotEmpty()}
                if(existing!=null)replace(existing.copy(updated=System.currentTimeMillis()))
                existing!=null
            }
            if(refreshed){lastMessage="订阅内容未变化";return@attempt}
        }
        val channels=PlaylistParser.parse(body,original,next)
        require(channels.isNotEmpty()){"节目单没有可用频道，已保留原频道"}
        val name=synchronized(this){sources.firstOrNull{it.url==original}?.name}?:URL(original).host
        replace(Source(original,name,channels,System.currentTimeMillis()))
        digests.edit().putString(original,digest).apply()
    }
    private fun attempt(block:()->Unit):Boolean=try{block();true}catch(e:Exception){lastMessage=LegacyTls.message(e);false}
}
