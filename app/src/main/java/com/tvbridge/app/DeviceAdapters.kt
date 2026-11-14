package com.tvbridge.app

import android.content.*
import android.os.Build
import org.json.JSONObject

/** Small declarative corrections, adapted from Aerial's device data (MIT notice in assets). */
object DeviceAdapters {
    data class Profile(val hidden:Set<Int> = emptySet(),val labels:Map<Int,String> = emptyMap(),val sources:Map<Int,Int> = emptyMap(),val fallback:Map<Int,Int> = emptyMap(),val bootStockMs:Int = 0,val neverFilterKeys:Boolean = false)
    private var data:JSONObject?=null
    private var lastKey=""
    private var lastProfile=Profile()
    private val hardwarePattern=Regex("/HW(\\d+)$")
    fun hardware(id:String)=hardwarePattern.find(id)?.groupValues?.get(1)?.toIntOrNull()
    @Synchronized fun profile(context:Context,model:String=Build.MODEL,display:String=Build.DISPLAY):Profile {
        val key=model+"/"+display
        if(lastKey==key)return lastProfile
        val document=data?:runCatching{context.assets.open("device-adapters.json").bufferedReader().use{JSONObject(it.readText())}}.getOrDefault(JSONObject()).also{data=it}
        return select(document,model,display).also{lastProfile=it;lastKey=key}
    }
    fun select(document:JSONObject,model:String,display:String):Profile {
        val rows=document.optJSONArray("profiles")?:return Profile()
        val version=Regex("\\d+").findAll(display).map{it.value.toIntOrNull()?:0}.toList()
        fun atLeast(raw:String):Boolean {
            val wanted=raw.split('.').map{it.toIntOrNull()?:0}
            if(version.isEmpty())return false
            for(i in 0 until maxOf(version.size,wanted.size)){val diff=(version.getOrNull(i)?:0)-(wanted.getOrNull(i)?:0);if(diff!=0)return diff>0}
            return true
        }
        fun <T> map(row:JSONObject,key:String,value:(JSONObject,String)->T):Map<Int,T>{val obj=row.optJSONObject(key)?:return emptyMap();return obj.keys().asSequence().mapNotNull{k->k.toIntOrNull()?.let{it to value(obj,k)}}.toMap()}
        for(i in 0 until rows.length()){
            val row=rows.getJSONObject(i);val models=row.optJSONArray("models")?:continue
            if((0 until models.length()).none{models.getString(it).replace('_','-').equals(model.replace('_','-'),true)})continue
            if(row.has("buildAtLeast")&&!atLeast(row.getString("buildAtLeast")))continue
            if(row.has("buildMajorBelow")&&(version.isEmpty()||version.first()>=row.getInt("buildMajorBelow")))continue
            val hidden=row.optJSONArray("hidden")
            return Profile(if(hidden==null)emptySet()else (0 until hidden.length()).map{hidden.getInt(it)}.toSet(),map(row,"labels"){o,k->o.getString(k)},map(row,"xiaomiSources"){o,k->o.getInt(k)},map(row,"xiaomiFallbackSources"){o,k->o.getInt(k)},row.optInt("bootStockMs",0).coerceIn(0,45000),row.optBoolean("neverFilterKeys",false))
        }
        return Profile()
    }
    fun xgimiPage(context:Context,page:String):Boolean {
        val component=ComponentName("com.android.newsettings","com.xgimi.newsettings.SettingService")
        val info=runCatching{context.packageManager.getServiceInfo(component,0)}.getOrNull()?:return false
        if(!info.exported||!info.enabled)return false
        return runCatching{context.startService(Intent("com.xgimi.settings.SETTINGS").setComponent(component).putExtra("data",page).putExtra("packageName","com.xgimi.misckey").putExtra("unlocked",false))!=null}.getOrDefault(false)
    }
}
