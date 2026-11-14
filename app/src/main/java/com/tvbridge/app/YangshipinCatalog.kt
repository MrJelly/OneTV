package com.tvbridge.app

import android.content.res.AssetManager
import android.net.Uri
import org.json.JSONArray

object YangshipinCatalog {
    const val SOURCE="https://www.yangshipin.cn/tv/home"
    fun resolve(url:String):String = if(LiveCatalog.isPage(url)) SOURCE+"?pid="+android.net.Uri.parse(url).lastPathSegment else url
    fun isPage(url:String):Boolean=try {
        val uri=Uri.parse(url)
        uri.scheme=="https"&&uri.host=="www.yangshipin.cn"&&uri.userInfo==null&&uri.port in listOf(-1,443)&&uri.path=="/tv/home"&&uri.getQueryParameter("pid")?.matches(Regex("[0-9]{6,12}"))==true
    }catch(_:Exception){false}
    fun channels(assets:AssetManager):List<LiveChannel> {
        val groups=JSONArray(assets.open("yangshipin-channels.json").bufferedReader().use{it.readText()})
        val result=buildList{
            for(i in 0 until groups.length()){
                val group=groups.getJSONObject(i);val channels=group.getJSONArray("channels")
                for(j in 0 until channels.length()){
                    val item=channels.getJSONObject(j);val pid=item.getString("pid")
                    if(pid.matches(Regex("[0-9]{6,12}")))add(LiveChannel("yangshipin-$pid",item.getString("name"),group.getString("group_name"),listOf("one-plugin://live/$pid")))
                }
            }
        }
        val order=listOf("600001859","600001800","600001814","600001818","600001817","600108442","600000492","600001803","600004078","600001805","600001806","600001807","600001811","600001809","600001815","600098637","600001810","600002264","600156816")
        return result.sortedBy{channel->val index=order.indexOf(channel.id.removePrefix("yangshipin-"));if(index>=0)index else 100+result.indexOf(channel)}
    }
}
