package com.tvbridge.app

import android.content.*
import android.content.pm.PackageManager

/** Native-player contracts adapted from Aerial (MIT); resolve and check permission before use. */
object InputRoutes {
    private fun allowed(context:Context,intent:Intent):Boolean {
        val a=runCatching{context.packageManager.resolveActivity(intent,0)?.activityInfo}.getOrNull()?:return false
        return a.exported&&a.enabled&&(a.permission==null||androidx.core.content.ContextCompat.checkSelfPermission(context,a.permission)==PackageManager.PERMISSION_GRANTED)
    }
    fun route(context:Context,id:String,hardware:Int?,type:Int,label:String,profile:DeviceAdapters.Profile,generic:Intent):Intent {
        if(hardware!=null){
            val number=Regex("HDMI\\s*(\\d+)",RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
            val configured=number?.let{port->runCatching{val c=Class.forName("mitv.common.ConfigurationManager");val o=c.getMethod("getInstance").invoke(null);(c.getMethod("queryTvConfigByKey",String::class.java).invoke(o,"hdmi${port}_order") as? String)?.trim()?.toIntOrNull()?.takeIf{it>0}}.getOrNull()}
            val source=profile.sources[hardware]?:if(type==1007)configured?:profile.fallback[hardware]?:(hardware+18)else null
            if(source!=null){
                val intent=Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY").setComponent(ComponentName("com.xiaomi.mitv.tvplayer","com.xiaomi.mitv.tvplayer.ExternalSourceActivity")).putExtra("input",source)
                if(allowed(context,intent))return intent
            }
            if(type==1007&&number in 1..4){
                val tcl=Intent().setComponent(ComponentName("com.tcl.tv","com.tcl.tv.TVActivity")).putExtra("targetSourceId",7+number!!).putExtra("referer","com.tcl.settings.module_source")
                if(allowed(context,tcl))return tcl
            }
        }
        if(type==1007&&id.startsWith("com.mediatek.tvinput/.hdmi.HDMIInputService/")){
            val port=Regex("""^HDMI\s*([1-4])(?:\b|$)""",RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?:hardware?.minus(4)?.takeIf{it in 1..4}
            if(port!=null){
                val intent=Intent().setComponent(ComponentName("com.jmgo.tv","com.jmgo.tv.MainActivity")).putExtra("inputSrc","HDMI$port")
                if(allowed(context,intent))return intent
            }
        }
        if(!allowed(context,generic)){
            val droid=Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.droidlogic.tvsource","com.droidlogic.tvsource.DroidLogicTv")).putExtra("id",id)
            if(type==1007&&allowed(context,droid))return droid
            if(type==1007){
                val port=Regex("""\.Hdmi(\d)InputService/""").find(id)?.groupValues?.get(1)
                if(port!=null){
                    val jmgo=Intent().setComponent(ComponentName("com.jmgo.tv","com.jmgo.tv.AmlogicTvActivity")).putExtra("inputSrc","HDMI$port")
                    if(allowed(context,jmgo))return jmgo
                }
            }
        }
        return generic
    }
    fun skyworth(context:Context):List<SystemControls.Input> = runCatching{
        val player=ComponentName("com.skyworth.tv","com.skyworth.tv.activity.SkyTVMainActivity")
        if(!allowed(context,Intent().setComponent(player)))return emptyList()
        val result=ArrayList<SystemControls.Input>()
        context.contentResolver.query(android.net.Uri.parse("content://com.skyworth.tv.sdk.provider/system/source_list"),arrayOf("sourceName","sourceIndex","displayName","signalState"),null,null,null)?.use{c->
            while(result.size<64&&c.moveToNext()){
                val name=c.getString(c.getColumnIndexOrThrow("sourceName"))?:continue
                val index=c.getInt(c.getColumnIndexOrThrow("sourceIndex"));val label=c.getString(c.getColumnIndexOrThrow("displayName"))?.takeIf{it.isNotBlank()}?:name
                val data=android.os.Bundle().apply{putString("name",name);putString("displayname",label);putInt("index",index);putInt("signal",c.getInt(c.getColumnIndexOrThrow("signalState")))}
                result+=SystemControls.Input("skyworth:$name:$index",label,Intent().setComponent(player).putExtra("source",data))
            }
        };result
    }.getOrDefault(emptyList())
}
