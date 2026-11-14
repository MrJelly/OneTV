package com.tvbridge.app

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class LiveRepository(private val context:Context) {
    data class Source(val url:String,val name:String,val channels:List<LiveChannel>,val updated:Long)
    companion object {const val DEFAULT_SOURCE=LiveCatalog.SOURCE}
    private var pluginHidden=false
    var pluginMessage="";private set
    private var officialCatalogVersion=0
    private val file=AtomicFile(java.io.File(context.filesDir,"live-sources.json"))
    private val persistExecutor=Executors.newSingleThreadExecutor()
    @Volatile private var persistSeq=0L
    private val playback=context.getSharedPreferences("live-resume",Context.MODE_PRIVATE)
    private val sources=mutableListOf<Source>()
    private val favorites=mutableSetOf<String>()
    private var active="local"
    private var lastChannel=""
    private var lastPlayedAt=0L
    @Volatile var lastMessage="可使用手机添加订阅或导入节目单"
        private set
    init {try {
        val root=JSONObject(String(file.readFully(),Charsets.UTF_8));active=root.optString("active","local");lastChannel=root.optString("lastChannel");lastPlayedAt=root.optLong("lastPlayedAt");officialCatalogVersion=root.optInt("officialCatalogVersion");pluginHidden=root.optBoolean("pluginHidden")
        root.optJSONArray("sources")?.let{list->var total=0;for(i in 0 until minOf(list.length(),11)){try{
            val s=list.getJSONObject(i);val saved=s.getJSONArray("channels");val limit=minOf(saved.length(),20000,(20000-total).coerceAtLeast(0))
            val channels=(0 until limit).mapNotNull{index->try{val c=saved.getJSONObject(index);val urls=c.getJSONArray("streams");val streams=(0 until urls.length()).mapNotNull{savedStream(urls.getString(it))};if(streams.isEmpty())null else LiveChannel(c.getString("id"),c.getString("name"),c.getString("group"),streams)}catch(_:Exception){null}}
            total+=channels.size;val url=s.getString("url");sources.add(Source(url,when{url==DEFAULT_SOURCE->"默认直播";s.getString("name") in listOf("默认订阅","notation")->"订阅";else->s.getString("name")},channels,s.optLong("updated")))
        }catch(e:Exception){android.util.Log.w("LiveRepository","跳过损坏的直播源 #$i: ${e.javaClass.simpleName} ${e.message}")}}}
        root.optJSONArray("favorites")?.let{a->for(i in 0 until a.length())favorites.add(a.getString(i))}
    }catch(_:Exception){}
        if(officialCatalogVersion<2){
            val original=java.io.File(context.filesDir,"live-sources.json")
            val backup=java.io.File(context.filesDir,"live-sources.pre-plugin.json")
            if(original.exists()&&!backup.exists())original.copyTo(backup)
            val old=sources.filter{it.channels.isNotEmpty()&&it.channels.all{c->c.id.startsWith("yangshipin-")}}
            old.forEach {source->
                sources.remove(source)
                val channels=source.channels.mapNotNull {c->
                    val pid=c.id.removePrefix("yangshipin-")
                    if(!pid.matches(Regex("[0-9]{6,12}")))null else c.copy(streams=listOf("$DEFAULT_SOURCE/$pid"))
                }
                if(sources.none{it.url==DEFAULT_SOURCE})sources.add(Source(DEFAULT_SOURCE,"默认直播",channels,source.updated))
                if(active==source.url)active=DEFAULT_SOURCE
            }
            officialCatalogVersion=2;save()
        }
        val playedAt=playback.getLong("time",0)
        val playedId=playback.getString("channel","").orEmpty()
        val playedSource=sources.firstOrNull{source->source.channels.any{it.id==playedId}}
        if(playedAt>lastPlayedAt&&playedSource!=null){
            lastPlayedAt=playedAt
            lastChannel=playedId
            active=playedSource.url
        }
        refreshPlugin()
    }
    private fun savedStream(value:String)=if(LiveCatalog.isPage(value))value else PlaylistParser.streamUrl(value)
    @Synchronized fun refreshPlugin(){
        if(!LiveCatalog.available(context))return
        val result=LiveCatalog.read(context)
        val channels=result.channels
        pluginMessage=if(pluginHidden)"插件频道已移除，可点击恢复插件频道"else result.error
        if(channels.isEmpty())return
        val budget=(20000-sources.filter{it.url!=DEFAULT_SOURCE}.sumOf{it.channels.size}).coerceAtLeast(0)
        val capped=channels.take(budget)
        if(capped.isEmpty())return
        val index=sources.indexOfFirst{it.url==DEFAULT_SOURCE}
        if(index<0&&!pluginHidden){sources.add(0,Source(DEFAULT_SOURCE,"默认直播",capped,System.currentTimeMillis()));if(active=="local"&&sources.size==1)active=DEFAULT_SOURCE;saveAsync()}
        else if(index>=0&&sources[index].channels!=capped){sources[index]=sources[index].copy(channels=capped);saveAsync()}
    }

    @Synchronized fun allChannels():List<LiveChannel> {
        val allowed=sourceList()
        if(allowed.none{it.url==active})active=allowed.firstOrNull()?.url?:"local"
        return allowed.firstOrNull{it.url==active}?.channels.orEmpty()
    }
    @Synchronized fun sourceList()=sources.filter{(it.url==DEFAULT_SOURCE)==LiveLaunch.builtIn(context)}
    @Synchronized fun findChannel(id:String?)=sourceList().asSequence().flatMap{it.channels.asSequence()}.firstOrNull{it.id==id}
    @Synchronized fun favoriteChannels()=sourceList().flatMap{it.channels}.filter{favorites.contains(it.id)}.distinctBy{it.id}
    @Synchronized fun activeSource():String {allChannels();return active}
    @Synchronized fun rememberChannel(id:String){
        val source=sources.firstOrNull{s->s.channels.any{it.id==id}}?:return
        if(id=="cast"||(source.url==DEFAULT_SOURCE&&!LiveCatalog.available(context)))return
        if(lastChannel==id&&active==source.url)return
        lastChannel=id
        lastPlayedAt=maxOf(System.currentTimeMillis(),lastPlayedAt+1)
        active=source.url
        playback.edit().putString("channel",id).putLong("time",lastPlayedAt).apply()
    }
    @Synchronized fun resumeChannel():LiveChannel? {
        val available=sourceList()
        val source=available.firstOrNull{s->s.channels.any{it.id==lastChannel}}
        if(source!=null){if(active!=source.url){active=source.url;saveAsync()};return source.channels.first{it.id==lastChannel}}
        val fallback=allChannels().firstOrNull()?:available.firstOrNull{it.channels.isNotEmpty()}?.channels?.firstOrNull()
        if(fallback!=null){val next=available.first{s->s.channels.any{it.id==fallback.id}};if(active!=next.url){active=next.url;saveAsync()}}
        return fallback
    }
    @Synchronized fun favorite(id:String)=favorites.contains(id)
    @Synchronized fun toggleFavorite(id:String){if(!favorites.add(id))favorites.remove(id);saveAsync()}
    @Synchronized fun selectSource(url:String){if(sources.any{it.url==url}){active=url;saveAsync()}}
    @Synchronized fun removeSource(url:String){if(url==DEFAULT_SOURCE)pluginHidden=true;sources.removeAll{it.url==url};if(active==url)active=sources.firstOrNull()?.url?:"local";saveAsync()}
    private data class SnapshotKey(val revision:Long,val mode:String,val player:String,val source:String,val message:String)
    private var snapshotKey:SnapshotKey?=null
    private var snapshotJson=""
    private var snapshotVersion=0L
    @Synchronized fun snapshot(knownVersion:String=""):String {
        val external=false
        val mode=if(external)"external"else if(LiveLaunch.builtIn(context))"one"else "default"
        val player=LiveLaunch.label(context)
        val key=SnapshotKey(revision,mode,player,active,lastMessage)
        if(key==snapshotKey)return if(knownVersion==snapshotVersion.toString())"" else snapshotJson
        val channels=JSONArray();if(!external)allChannels().forEach{channels.put(it.json().put("favorite",favorites.contains(it.id)))}
        val list=JSONArray();if(mode=="default")sourceList().forEach{list.put(JSONObject().put("url",it.url).put("name",it.name).put("count",it.channels.size))}
        snapshotVersion=maxOf(snapshotVersion+1,System.currentTimeMillis())
        snapshotJson=JSONObject().put("version",snapshotVersion.toString()).put("playerMode",mode).put("playerName",player).put("channels",channels).put("favoriteChannels",JSONArray(if(external)emptyList()else favoriteChannels().map{it.json().put("favorite",true)})).put("sources",list).put("active",active).put("message",lastMessage).toString()
        snapshotKey=key.copy(revision=revision,source=active)
        return snapshotJson
    }
    @Volatile var revision=0L;private set
    var onPersist:((String)->Unit)?=null
    @Synchronized fun exportStore():String {
        val list=JSONArray();sources.forEach{s->list.put(JSONObject().put("url",s.url).put("name",s.name).put("updated",s.updated).put("channels",JSONArray(s.channels.map{it.json()})))}
        return JSONObject().put("active",active).put("sources",list).put("favorites",JSONArray(favorites.toList())).put("officialCatalogVersion",officialCatalogVersion).put("pluginHidden",pluginHidden).put("lastChannel",lastChannel).put("lastPlayedAt",lastPlayedAt).toString()
    }
    @Synchronized private fun save() {
        persistSeq++
        val text=exportStore();writeStore(text);revision++;onPersist?.invoke(text)
    }
    private fun writeStore(text:String) {
        val data=text.toByteArray(Charsets.UTF_8)
        val out=file.startWrite();try{out.write(data);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    private fun saveAsync() {
        val snapshot=synchronized(this){exportStore().also{revision++} to ++persistSeq}
        val text=snapshot.first;val seq=snapshot.second
        persistExecutor.execute{synchronized(this){try{if(seq==persistSeq){writeStore(text);onPersist?.invoke(text)}}catch(e:Throwable){Log.w("LiveRepository","live-sources.json 异步写盘失败",e)}}}
    }
    private fun decodedSources(root:JSONObject):List<Source>{
        val entries=root.getJSONArray("sources");require(entries.length()<=11)
        return (0 until entries.length()).map {i->val item=entries.getJSONObject(i);val url=item.getString("url");require(url=="local"||url==DEFAULT_SOURCE||PlaylistParser.playlistAddress(url)!=null)
            val channels=item.getJSONArray("channels");require(channels.length()<=20000)
            Source(url,item.getString("name").take(100),(0 until channels.length()).map{j->val c=channels.getJSONObject(j);val streams=c.getJSONArray("streams");require(streams.length() in 1..100)
                LiveChannel(c.getString("id"),c.getString("name").take(200),c.optString("group").take(100),(0 until streams.length()).map{k->savedStream(streams.getString(k))?:error("无效频道地址")})},item.optLong("updated"))
        }
    }
    @Synchronized fun restoreSnapshot(text:String){
        val root=JSONObject(text);val next=decodedSources(root);require(next.sumOf{it.channels.size}<=20000)
        sources.clear();sources.addAll(next);favorites.clear();root.optJSONArray("favorites")?.let{a->for(i in 0 until minOf(a.length(),20000))favorites.add(a.getString(i))};active=root.getString("active");lastChannel=root.optString("lastChannel");lastPlayedAt=root.optLong("lastPlayedAt");pluginHidden=root.optBoolean("pluginHidden");officialCatalogVersion=2
        snapshotKey=null
    }
    @Synchronized fun mergeSnapshot(before:String,after:String){
        val old=JSONObject(before);val next=JSONObject(after);val previous=decodedSources(old).associateBy{it.url};val incoming=decodedSources(next).associateBy{it.url}
        val result=sources.filter{it.url !in previous||it.url in incoming}.associateBy{it.url}.toMutableMap()
        incoming.forEach{(key,value)->if(previous[key]!=value)result[key]=value};require(result.size<=11&&result.values.sumOf{it.channels.size}<=20000)
        fun ids(root:JSONObject):Set<String>{val a=root.optJSONArray("favorites")?:JSONArray();require(a.length()<=20000);return (0 until a.length()).map{a.getString(it)}.toSet()}
        val oldIds=ids(old);val newIds=ids(next);sources.clear();sources.addAll(result.values);favorites.removeAll(oldIds-newIds);favorites.addAll(newIds-oldIds)
        if(old.optString("active")!=next.optString("active"))active=next.getString("active");
        if(old.optString("lastChannel")!=next.optString("lastChannel")&&next.optLong("lastPlayedAt")>lastPlayedAt){lastChannel=next.optString("lastChannel");lastPlayedAt=next.optLong("lastPlayedAt")};if(old.optBoolean("pluginHidden")!=next.optBoolean("pluginHidden"))pluginHidden=next.optBoolean("pluginHidden");saveAsync()
    }
    @Synchronized private fun replace(source:Source) {
        require(source.url==DEFAULT_SOURCE||sources.count{it.url!=DEFAULT_SOURCE}<10 || sources.any{it.url==source.url}){"最多保存 10 个订阅源"}
        require(source.channels.size+sources.filter{it.url!=source.url}.sumOf{it.channels.size}<=20000){"总频道数最多 20000，请先删除不需要的源"}
        val old=sources.toList();val previousActive=active
        sources.removeAll{it.url==source.url};sources.add(source);active=source.url
        try{save()}catch(e:Exception){sources.clear();sources.addAll(old);active=previousActive;throw e}
        lastMessage="已保存 ${source.channels.size} 个频道"
    }
    fun importText(text:String):Boolean=attempt {
        val channels=PlaylistParser.parse(text,"local");require(channels.isNotEmpty()){"没有找到可用频道"}
        val old=synchronized(this){sources.firstOrNull{it.url=="local"}?.channels.orEmpty()}
        replace(Source("local","本地导入",(old+channels).associateBy{it.id}.values.toList(),System.currentTimeMillis()))
    }
    fun addDirect(name:String,url:String):Boolean=attempt {
        val stream=PlaylistParser.streamUrl(url)?:error("请输入 HTTP、HTTPS、RTSP 或 UDP 直播地址")
        val channel=JSONObject().put("name",name.ifBlank{"自定义直播"}).put("url",stream)
        if(!importText(JSONArray().put(channel).toString()))error(lastMessage)
    }
    fun subscribe(address:String):Boolean=attempt {
        if(address==DEFAULT_SOURCE){val result=LiveCatalog.read(context);require(result.channels.isNotEmpty()){result.error};val hidden=pluginHidden;pluginHidden=false;try{replace(Source(DEFAULT_SOURCE,"默认直播",result.channels,System.currentTimeMillis()));pluginMessage=""}catch(e:Exception){pluginHidden=hidden;throw e};return@attempt}
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
        if(digests.getString(original,null)==digest){synchronized(this){sources.firstOrNull{it.url==original}?.let{replace(it.copy(updated=System.currentTimeMillis()))}};lastMessage="订阅内容未变化";return@attempt}
        val channels=PlaylistParser.parse(body,original,next)
        require(channels.isNotEmpty()){"节目单没有可用频道，已保留原频道"}
        val name=synchronized(this){sources.firstOrNull{it.url==original}?.name}?:URL(original).host
        replace(Source(original,name,channels,System.currentTimeMillis()))
        digests.edit().putString(original,digest).apply()
    }
    private fun attempt(block:()->Unit):Boolean=try{block();true}catch(e:Exception){lastMessage=LegacyTls.message(e);false}
}
