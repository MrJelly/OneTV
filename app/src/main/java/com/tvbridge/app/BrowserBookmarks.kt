package com.tvbridge.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class BrowserBookmark(val url:String,val title:String)

class BrowserBookmarks(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("browser-bookmarks",Context.MODE_PRIVATE)
    enum class Result { ADDED,REMOVED,FULL,INVALID }
    private fun valid(url:String):Boolean=runCatching{val uri=Uri.parse(url);uri.scheme in listOf("http","https")&&!uri.host.isNullOrEmpty()}.getOrDefault(false)
    fun all():List<BrowserBookmark> = runCatching{
        val data=JSONArray(prefs.getString("items","[]"));(0 until data.length()).mapNotNull{i->
            val item=data.optJSONObject(i)?:return@mapNotNull null
            val url=item.optString("url");if(!valid(url))null else BrowserBookmark(url,item.optString("title").ifBlank{Uri.parse(url).host.orEmpty()}.take(100))
        }.distinctBy{it.url}
    }.getOrDefault(emptyList())
    fun remove(url:String){
        val data=JSONArray();all().filterNot{it.url==url}.forEach{data.put(JSONObject().put("url",it.url).put("title",it.title))}
        prefs.edit().putString("items",data.toString()).apply()
    }
    fun contains(url:String)=all().any{it.url==url}
    fun toggle(url:String,title:String):Result{
        if(!valid(url))return Result.INVALID
        val items=all().toMutableList();val old=items.indexOfFirst{it.url==url}
        val result=if(old>=0){items.removeAt(old);Result.REMOVED}else{
            if(items.size>=100)return Result.FULL
            items.add(BrowserBookmark(url,title.ifBlank{Uri.parse(url).host.orEmpty()}.take(100)));Result.ADDED
        }
        val data=JSONArray();items.forEach{data.put(JSONObject().put("url",it.url).put("title",it.title))}
        prefs.edit().putString("items",data.toString()).apply();return result
    }
}
