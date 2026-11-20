package com.tvbridge.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class BrowserHistoryEntry(val url:String,val title:String,val time:Long)

class BrowserHistory(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("browser-history",Context.MODE_PRIVATE)
    private fun valid(url:String):Boolean=runCatching{val uri=Uri.parse(url);uri.scheme in listOf("http","https")&&!uri.host.isNullOrEmpty()}.getOrDefault(false)
    fun all():List<BrowserHistoryEntry> = runCatching{
        val data=JSONArray(prefs.getString("items","[]"));(0 until data.length()).mapNotNull{i->
            val item=data.optJSONObject(i)?:return@mapNotNull null
            val url=item.optString("url");if(!valid(url))null else BrowserHistoryEntry(url,item.optString("title").ifBlank{Uri.parse(url).host.orEmpty()}.take(100),item.optLong("time"))
        }.distinctBy{it.url}
    }.getOrDefault(emptyList())
    private fun save(items:List<BrowserHistoryEntry>){
        val data=JSONArray();items.forEach{data.put(JSONObject().put("url",it.url).put("title",it.title).put("time",it.time))}
        prefs.edit().putString("items",data.toString()).apply()
    }
    fun record(url:String,title:String){
        if(!valid(url))return
        val items=all().toMutableList()
        val top=items.firstOrNull()
        if(top!=null&&top.url==url){
            if(title.isNotBlank()&&title!=top.title){items[0]=top.copy(title=title.take(100));save(items)}
            return
        }
        items.removeAll{it.url==url}
        items.add(0,BrowserHistoryEntry(url,title.ifBlank{Uri.parse(url).host.orEmpty()}.take(100),System.currentTimeMillis()))
        save(items.take(100))
    }
    fun remove(url:String){save(all().filterNot{it.url==url})}
    fun clear(){prefs.edit().remove("items").apply()}
    fun toJson(limit:Int=50):JSONArray{val data=JSONArray();all().take(limit).forEach{data.put(JSONObject().put("url",it.url).put("title",it.title).put("time",it.time))};return data}
}
