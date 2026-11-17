package com.tvbridge.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class LiveChannel(val id:String,val name:String,val group:String,val streams:List<String>) {
    fun json()=JSONObject().put("id",id).put("name",name).put("group",group).put("streams",JSONArray(streams))
}

/** Port of notation/src/shared/iptv/playlist.ts. No browser/relay dependency on TV. */
object PlaylistParser {
    const val MAX_BYTES=20*1024*1024
    const val MAX_CHANNELS=20000
    fun streamUrl(value:String):String?=try {
        val uri=URI(value.trim())
        val scheme=uri.scheme?.lowercase(Locale.ROOT)
        if(scheme !in listOf("http","https","rtsp","udp") || uri.host.isNullOrEmpty() || (uri.userInfo!=null&&scheme!="rtsp"&&!(scheme=="udp"&&uri.userInfo.isEmpty())) || (scheme=="udp"&&uri.port !in 1..65535)) null else uri.toASCIIString().let{if(scheme=="udp")it.replaceFirst(Regex("(?i)^udp://@"),"udp://")else it}
    }catch(_:Exception){null}
    fun httpUrl(value:String):String?=streamUrl(value)?.takeIf{URI(it).scheme.lowercase(Locale.ROOT) in listOf("http","https")}
    fun playlistAddress(value:String):String? {
        val url=httpUrl(value)?:return null
        val uri=URI(url)
        return if(uri.host=="github.com" && Regex("^/[^/]+/[^/]+/blob/").containsMatchIn(uri.path)) "https://raw.githubusercontent.com"+uri.path.replaceFirst("/blob/","/")
        else url
    }
    fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
    @JvmStatic fun parse(input:String,scope:String="local",baseUrl:String?=streamUrl(scope)):List<LiveChannel> {
        require(input.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"节目单超过 20 MB，请使用精简版订阅。"}
        val text=input.removePrefix("\uFEFF").trim()
        require(!Regex("#EXT-X-(TARGETDURATION|STREAM-INF|MEDIA-SEQUENCE)").containsMatchIn(text)){"这是视频流清单，请使用在线地址添加为直播直链。"}
        val channels=linkedMapOf<String,LiveChannel>()
        fun add(nameValue:String,groupValue:String,urls:List<String>,keyValue:String=nameValue) {
            val streams=urls.mapNotNull {value->
                val clean=value.trim().replace(Regex("\\$(?:[0-9]{3,4}[x×*][0-9]{3,4}|超清|高清|标清|蓝光|4K|1080P|720P).*$",RegexOption.IGNORE_CASE),"")
                streamUrl(clean)?:if(baseUrl!=null)try{streamUrl(URI(baseUrl).resolve(clean).toString())}catch(_:Exception){null}else null
            }.distinct().take(100)
            if(streams.isEmpty())return
            val group=groupValue.trim().take(60).ifEmpty{"未分组"}
            if(Regex("更新时间|更新日期|^(说明|公告)$").containsMatchIn(group))return
            val name=nameValue.trim().take(160).ifEmpty{"频道 ${channels.size+1}"}
            val id=digest("$scope:${keyValue.ifEmpty{name}}")
            val previous=channels[id]
            if(previous!=null)channels[id]=previous.copy(streams=(previous.streams+streams).distinct().take(100))
            else if(channels.size<MAX_CHANNELS)channels[id]=LiveChannel(id,name,group,streams)
        }
        when {
            text.startsWith("{")||text.startsWith("[")&&!text.startsWith("[playlist]",true)->{
                val list=if(text.startsWith("["))JSONArray(text) else JSONObject(text).optJSONArray("channels")?:error("JSON 需要 channels 数组")
                for(i in 0 until minOf(list.length(),MAX_CHANNELS)){
                    val item=list.optJSONObject(i)?:continue
                    val streams=item.optJSONArray("streams")?.let{a->(0 until a.length()).map{a.optString(it)}}?:listOf(item.optString("url",item.optString("location")))
                    add(item.optString("name",item.optString("title")),item.optString("group"),streams)
                }
            }
            Regex("<(?:\\w+:)?playlist\\b").containsMatchIn(text)->{
                require(!Regex("<!DOCTYPE|<!ENTITY",RegexOption.IGNORE_CASE).containsMatchIn(text)){"不支持含外部实体的 XML 节目单"}
                val factory=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true;isExpandEntityReferences=false}
                val doc=factory.newDocumentBuilder().parse(text.byteInputStream())
                val tracks=doc.getElementsByTagNameNS("*","track")
                for(i in 0 until minOf(tracks.length,MAX_CHANNELS)){
                    val track=tracks.item(i) as org.w3c.dom.Element
                    val locations=track.getElementsByTagNameNS("*","location")
                    add(track.getElementsByTagNameNS("*","title").item(0)?.textContent?:"","未分组",(0 until locations.length).map{locations.item(it).textContent})
                }
            }
            text.startsWith("[playlist]",true)->{
                val entries=linkedMapOf<String,Pair<String,MutableList<String>>>()
                text.lineSequence().forEach{line->val m=Regex("^(File|Title)(\\d+)=(.*)$",RegexOption.IGNORE_CASE).find(line.trim());if(m!=null){val key=m.groupValues[2];val entry=entries.getOrPut(key){"" to mutableListOf()};if(m.groupValues[1].equals("File",true))entry.second.add(m.groupValues[3].trim())else entries[key]=m.groupValues[3].trim() to entry.second}}
                entries.values.forEach{add(it.first,"未分组",it.second)}
            }
            text.contains("#EXTINF:")->{
                var name="";var group="未分组";var key=""
                text.lineSequence().forEach {raw->val line=raw.trim()
                    if(line.startsWith("#EXTINF:")){
                        val comma=Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)").find(line)?.range?.first
                        name=if(comma!=null)line.substring(comma+1).trim() else ""
                        group=Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?:"未分组"
                        key=(Regex("tvg-id=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?:name).replace(Regex("@(SD|HD|FHD|UHD|4K)$",RegexOption.IGNORE_CASE),"")
                        if(key.matches(Regex("\\d+")))key=name
                    }else if(line.isNotEmpty()&&!line.startsWith("#")){
                        if(name.isNotEmpty())add(name,group,listOf(line),key)
                        name="";group="未分组";key=""
                    }
                }
            }
            else->{var group="未分组";text.lineSequence().forEach{line->val comma=line.indexOf(',');if(comma>=0){val name=line.substring(0,comma).trim().trim('"');val location=line.substring(comma+1).trim().trim('"');if(location=="#genre#")group=name else add(name,group,location.split('#'))}}}
        }
        return channels.values.toList()
    }
}
