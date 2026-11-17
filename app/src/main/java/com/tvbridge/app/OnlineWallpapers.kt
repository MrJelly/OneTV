package com.tvbridge.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal object OnlineWallpapers {
    private val keys=listOf("bing_pool","bing_pool_checked","bing_seen")
    data class Picture(val id:String,val url:String,val date:String)
    private fun prefs(context:Context)=context.getSharedPreferences("appearance",0)
    private fun pictures(text:String)=try{
        val array=JSONArray(text)
        (0 until array.length()).mapNotNull{i->
            val entry=array.getJSONObject(i)
            val url=entry.optString("url")
            if(valid(url))Picture(entry.getString("id"),url,entry.optString("date"))else null
        }
    }catch(_:Exception){emptyList()}
    private fun valid(url:String)=try{val u=URI(url);u.scheme=="https"&&u.userInfo==null&&u.host?.let{it=="bing.com"||it.endsWith(".bing.com")}==true}catch(_:Exception){false}
    private fun encode(items:List<Picture>)=JSONArray().apply{items.forEach{put(JSONObject().put("id",it.id).put("url",it.url).put("date",it.date))}}.toString()
    private fun history(context:Context):List<String> = try{
        val list=JSONArray(prefs(context).getString("bing_seen","[]"))
        (0 until list.length()).map{list.getString(it)}
    }catch(_:Exception){emptyList()}
    fun select(context:Context,random:Boolean,fetch:(String)->String={read(it,1024*1024).toString(Charsets.UTF_8)}):Picture {
        val p=prefs(context);val old=pictures(p.getString("bing_pool","[]").orEmpty())
        val age=System.currentTimeMillis()-p.getLong("bing_pool_checked",0)
        val pool=if(old.isNotEmpty()&&age in 0 until 6*60*60_000L)old else {
            val fresh=mutableListOf<Picture>()
            for(index in listOf(0,7))try{
                val images=JSONObject(fetch("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=$index&n=8&mkt=zh-CN")).getJSONArray("images")
                for(i in 0 until images.length()){
                    val image=images.getJSONObject(i);val path=image.getString("url")
                    val url=if(path.startsWith("/"))"https://cn.bing.com$path"else path
                    val id=Regex("OHR\\.([^_?]+)").find(image.optString("urlbase",url))?.groupValues?.get(1)?:image.optString("hsh")
                    if(id.isNotBlank()&&valid(url))fresh.add(Picture(id,url,image.optString("startdate")))
                }
            }catch(_:Exception){}
            val merged=(fresh+old).distinctBy{it.id}.take(96)
            if(merged.isEmpty())throw IOException("暂时无法获取在线壁纸，请稍后重试")
            if(fresh.isNotEmpty())p.edit().putString("bing_pool",encode(merged)).putLong("bing_pool_checked",System.currentTimeMillis()).apply()
            merged
        }
        if(!random)return pool.first()
        val seen=history(context)
        val unseen=pool.filter{it.id !in seen}
        val choices=unseen.ifEmpty{
            p.edit().putString("bing_seen",JSONArray(seen.takeLast(1)).toString()).apply()
            pool.filter{it.id!=seen.lastOrNull()}.ifEmpty{pool}
        }
        return choices[kotlin.random.Random.nextInt(choices.size)]
    }
    fun used(context:Context,picture:Picture){
        val seen=(history(context).filter{it!=picture.id}+picture.id).takeLast(96)
        prefs(context).edit().putString("bing_seen",JSONArray(seen).toString()).apply()
    }
    fun copyMetadata(to:Context,from:Context){
        val source=prefs(from);val edit=prefs(to).edit()
        for(key in keys)if(key=="bing_pool_checked")edit.putLong(key,source.getLong(key,0))else edit.putString(key,source.getString(key,"[]"))
        edit.apply()
    }
    fun read(url:String,limit:Int=8*1024*1024):ByteArray {
        val connection=URL(url).openConnection() as HttpURLConnection
        try{
            LegacyTls.configure(connection);connection.connectTimeout=5000;connection.readTimeout=8000
            if(connection.responseCode !in 200..299)throw IOException("壁纸下载暂时不可用")
            if(connection.contentLength>limit)throw IOException("壁纸文件过大")
            return connection.inputStream.use{input->
                val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
                while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>limit)throw IOException("壁纸文件过大");out.write(buffer,0,n)}
                out.toByteArray()
            }
        }finally{connection.disconnect()}
    }
}
