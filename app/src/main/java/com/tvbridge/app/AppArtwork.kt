package com.tvbridge.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean

internal object AppArtwork {
    private const val BASE="https://aerial.qust.me/icons/"
    private val pathPattern=Regex("""p/[A-Za-z0-9._-]+\.(png|webp|jpg)""")
    private val refreshing=AtomicBoolean(false)
    @Volatile private var revision=0
    @Volatile private var paths=emptyMap<String,String>()
    @Volatile private var lastAttempt=0L

    fun version()=revision

    fun tile(context:Context,packageName:String):BitmapDrawable?{
        val path=paths[packageName]?:return null
        val file=File(context.filesDir,"app-art/"+path.substringAfterLast('/'))
        if(!file.isFile)return null
        return BitmapDrawable(context.resources,BitmapFactory.decodeFile(file.path)?:return null)
    }

    fun refresh(context:Context,changed:()->Unit){
        val now=android.os.SystemClock.elapsedRealtime()
        if(lastAttempt!=0L&&now-lastAttempt<600_000L)return
        if(!refreshing.compareAndSet(false,true))return
        lastAttempt=now
        val app=context.applicationContext
        Thread({
            try{
                val dir=File(app.filesDir,"app-art")
                if(!dir.isDirectory&&!dir.mkdirs())return@Thread
                val index=File(dir,"index.json")
                val prefs=app.getSharedPreferences("app-art",0)
                val clock=System.currentTimeMillis()
                val version=BuildConfig.VERSION_NAME
                if(!index.isFile||prefs.getString("version",null)!=version||clock-prefs.getLong("checked",0) !in 0..43_200_000L){
                    runCatching{
                        val data=fetch("index.json",2*1024*1024)
                        JSONObject(data.toString(Charsets.UTF_8))
                        save(index,data)
                        prefs.edit().putLong("checked",clock).putString("version",version).apply()
                    }
                }
                val root=JSONObject(index.readText())
                val choices=root.optJSONObject("choices")
                val legacy=root.optJSONObject("icons")
                val packages=LauncherApps(app).installed().map{it.component.packageName}.distinct()
                val selected=packages.mapNotNull{pkg->
                    val path=choices?.optJSONArray(pkg)?.optJSONObject(0)?.optString("p")
                        ?.takeIf{pathPattern.matches(it)}
                        ?:legacy?.optString(pkg)?.takeIf{pathPattern.matches(it)}
                    path?.let{pkg to it}
                }.toMap()
                var updated=selected!=paths
                paths=selected
                val keep=selected.values.map{it.substringAfterLast('/')}.toSet()
                dir.listFiles()?.filter{it.name!="index.json"&&it.name !in keep}?.forEach{it.delete()}
                for(path in selected.values.distinct()){
                    val file=File(dir,path.substringAfterLast('/'))
                    if(file.isFile)continue
                    runCatching{
                        val data=fetch(path,512*1024)
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        BitmapFactory.decodeByteArray(data,0,data.size,bounds)
                        require(bounds.outWidth in 1..960&&bounds.outHeight in 1..540)
                        save(file,data)
                        updated=true
                    }
                }
                if(updated){
                    revision++
                    android.os.Handler(android.os.Looper.getMainLooper()).post{changed()}
                }
            }catch(_:Exception){}finally{refreshing.set(false)}
        },"app-art").start()
    }

    private fun save(file:File,data:ByteArray){
        val atomic=AtomicFile(file)
        val out=atomic.startWrite()
        try{out.write(data);atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
    }

    private fun fetch(path:String,limit:Int):ByteArray{
        val connection=(URL(BASE+path).openConnection() as HttpsURLConnection).apply{
            connectTimeout=3500
            readTimeout=3500
            instanceFollowRedirects=false
        }
        try{
            require(connection.responseCode==200)
            return connection.inputStream.use{input->
                val output=ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true){
                    val size=input.read(buffer)
                    if(size<0)break
                    require(output.size()+size<=limit)
                    output.write(buffer,0,size)
                }
                output.toByteArray()
            }
        }finally{connection.disconnect()}
    }
}
