package com.tvbridge.app

import org.json.JSONArray
import org.json.JSONObject

object PlaybackDiagnostics {
    private var state = JSONObject()
    private val loads = ArrayDeque<JSONObject>()

    @Synchronized fun start(name:String, url:String, line:Int) {
        state = JSONObject().put("channel",name).put("url",url).put("line",line)
        loads.clear()
    }
    @Synchronized fun update(key:String, value:Any) { state.put(key,value) }
    @Synchronized fun load(phase:String, request:String, response:String, durationMs:Long, bytes:Long,
                           status:Int?, headers:Map<out String?,List<String>?>, detail:String="") {
        val selected=JSONObject()
        headers.forEach{(key,value)->
            if(key!=null&&key.lowercase() in setOf("date","age","cache-control","content-type","server","x-cache","via"))
                selected.put(key,value.orEmpty().joinToString(", ").take(300))
        }
        loads.addLast(JSONObject().put("time",System.currentTimeMillis()).put("phase",phase)
            .put("request",request).put("response",response).put("durationMs",durationMs)
            .put("bytes",bytes).put("http",status?:JSONObject.NULL).put("headers",selected).put("detail",detail.take(500)))
        while(loads.size>24)loads.removeFirst()
    }
    @Synchronized fun snapshot():String = JSONObject(state.toString())
        .put("loads",JSONArray(loads.map{JSONObject(it.toString())})).toString()
}
