package com.tvbridge.app

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import android.util.AtomicFile
import java.io.File
import java.io.InputStream

object HomeAppearance {
    const val MAX_IMAGE_BYTES=20*1024*1024
    private val importLock=Any()
    @Volatile private var writingImage=false
    private var cachedKey=""
    private var cached:Bitmap?=null
    private class PhotoShade(val height:Int){
        val top=Paint().apply{shader=LinearGradient(0f,0f,0f,height*0.28f,intArrayOf(0x66000000,0),null,Shader.TileMode.CLAMP)}
        val bottom=Paint().apply{shader=LinearGradient(0f,height*0.62f,0f,height.toFloat(),intArrayOf(0,0x73000000),null,Shader.TileMode.CLAMP)}
    }
    @Volatile private var photoShade:PhotoShade?=null
    fun drawBackdrop(context:Context,canvas:Canvas,width:Int,height:Int,dark:Boolean,paint:Paint){
        if(mode(context)!="custom"||!hasCustom(context)){
            val w=width.coerceIn(1,1280)
            val h=(height.toFloat()*w/width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            canvas.drawBitmap(backdrop(context,w,h,dark),null,Rect(0,0,width,height),paint)
        }else canvas.drawBitmap(backdrop(context,width,height,dark),null,Rect(0,0,width,height),paint)
        if(mode(context)=="bing"&&hasBing(context)){
            val shade=photoShade?.takeIf{it.height==height}?:PhotoShade(height).also{photoShade=it}
            canvas.drawRect(0f,0f,width.toFloat(),height*0.28f,shade.top)
            canvas.drawRect(0f,height*0.62f,width.toFloat(),height.toFloat(),shade.bottom)
        }
    }
    fun isLowEnd(context:Context):Boolean {
        return try{(context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice||android.os.Build.VERSION.SDK_INT<=21}catch(_:Exception){false}
    }
    private fun modeRaw(context:Context):String{
        val prefs=context.getSharedPreferences("appearance",0)
        return when(val raw=prefs.getString("wallpaper_mode",null)){
            null,"light","dark"->"gradient";else->raw
        }
    }
    fun setEngineDark(context:Context,value:Boolean){context.getSharedPreferences("appearance",0).edit().putBoolean("engine_dark_override",value).apply()}
    fun dark(context:Context):Boolean{
        val prefs=context.getSharedPreferences("appearance",0)
        if(prefs.contains("engine_dark_override"))return prefs.getBoolean("engine_dark_override",true)
        return when(modeRaw(context)){
            "bing"->true
            "custom"->customDark(context,prefs.getBoolean("custom_dark",prefs.getBoolean("dark_home",true)))
            else->true
        }
    }
    fun mode(context:Context)=modeRaw(context)
    fun customFile(context:Context)=File(context.filesDir,"wallpapers/custom.jpg")
    fun hasCustom(context:Context)=customFile(context).isFile||File(customFile(context).path+".bak").isFile
    fun bingFile(context:Context)=File(context.filesDir,"wallpapers/bing.jpg")
    fun hasBing(context:Context)=bingFile(context).isFile
    fun bingThumbnail(context:Context):Bitmap? = try{BitmapFactory.Options().apply{inSampleSize=8}.let{BitmapFactory.decodeFile(bingFile(context).path,it)}}catch(_:Exception){null}
    fun bingDate(context:Context)=context.getSharedPreferences("appearance",0).getString("bing_date","").orEmpty()
    fun bingStale(context:Context):Boolean= !hasBing(context)
    fun setBing(context:Context){val prefs=context.getSharedPreferences("appearance",0);prefs.edit().putString("wallpaper_mode","bing").putLong("wallpaper_revision",System.nanoTime()).apply()}
    @Volatile private var bingAttemptMs=0L
    fun refreshBingIfStale(context:Context,changed:()->Unit){
        if(!bingStale(context))return
        val now=System.currentTimeMillis();if(now-bingAttemptMs<3600*1000)return;bingAttemptMs=now
        Thread({
            val error=downloadBing(context)
            if(error==null)(context as? android.app.Activity)?.runOnUiThread{changed()}
        },"bing-download").start()
    }
    fun bingIdx(context:Context)=context.getSharedPreferences("appearance",0).getInt("bing_idx",0)
    fun downloadBing(context:Context,random:Boolean=false):String? {
        return try{
            val picture=OnlineWallpapers.select(context,random)
            val bytes=OnlineWallpapers.read(picture.url)
            if(bytes.isEmpty())return "今日壁纸下载为空"
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth<1||bounds.outHeight<1)return "今日壁纸无法读取"
            val opts=BitmapFactory.Options().apply{inSampleSize=1;while(bounds.outWidth/inSampleSize>1920||bounds.outHeight/inSampleSize>1080)inSampleSize*=2}
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)?:return "今日壁纸无法读取"
            var luminance=0.0;var count=0
            for(y in 0 until decoded.height step 32)for(x in 0 until decoded.width step 32){val c=decoded.getPixel(x,y);luminance+=(Color.red(c)*.2126+Color.green(c)*.7152+Color.blue(c)*.0722)/255;count++}
            val directory=File(context.filesDir,"wallpapers").apply{check(isDirectory||mkdirs())}
            val file=AtomicFile(bingFile(context));val output=file.startWrite()
            try{check(decoded.compress(Bitmap.CompressFormat.JPEG,90,output));file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}finally{decoded.recycle()}
            val today=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())
            context.getSharedPreferences("appearance",0).edit().putBoolean("dark_home",luminance/count<.48).putBoolean("bing_dark",luminance/count<.48).putString("bing_date",today).putLong("wallpaper_revision",System.nanoTime()).apply()
            OnlineWallpapers.used(context,picture)
            null
        }catch(e:Exception){e.message?:"今日壁纸下载失败"}
    }
    fun customThumbnail(context:Context):Bitmap? = try {
        val options=BitmapFactory.Options().apply{inSampleSize=8}
        if(writingImage)BitmapFactory.decodeFile(customFile(context).path+".bak",options)else AtomicFile(customFile(context)).openRead().use{BitmapFactory.decodeStream(it,null,options)}
    }catch(_:Exception){null}
    internal fun applyBingDraft(context:Context,draft:Context){
        val directory=File(context.filesDir,"wallpapers").apply{check(isDirectory||mkdirs())}
        val file=AtomicFile(File(directory,"bing.jpg"));val output=file.startWrite()
        try{bingFile(draft).inputStream().use{it.copyTo(output)};file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
        val prefs=draft.getSharedPreferences("appearance",0)
        context.getSharedPreferences("appearance",0).edit()
            .putBoolean("bing_dark",prefs.getBoolean("bing_dark",true))
            .putString("bing_date",bingDate(draft)).putInt("bing_idx",bingIdx(draft)).apply()
    }
    fun label(context:Context)=when(mode(context)){"custom"->"自定义";"bing"->"壁纸";else->"默认"}
    fun key(context:Context)="${context.packageName}:${mode(context)}:${dark(context)}:${context.getSharedPreferences("appearance",0).getLong("wallpaper_revision",0)}"
    fun setGradient(context:Context){context.getSharedPreferences("appearance",0).edit().putString("wallpaper_mode","gradient").putLong("wallpaper_revision",System.nanoTime()).apply()}
    fun setDark(context:Context,value:Boolean)=setGradient(context)
    fun customTheme(context:Context)=context.getSharedPreferences("appearance",0).getString("custom_theme","auto").orEmpty().takeIf{it in listOf("auto","light","dark")}?:"auto"
    fun customThemeLabel(context:Context)=when(customTheme(context)){"light"->"浅色";"dark"->"深色";else->"自动"}
    private fun customDark(context:Context,automatic:Boolean)=when(customTheme(context)){"light"->false;"dark"->true;else->automatic}
    fun setCustomTheme(context:Context,value:String){
        require(value in listOf("auto","light","dark"));require(hasCustom(context))
        val prefs=context.getSharedPreferences("appearance",0)
        val resolved=when(value){"light"->false;"dark"->true;else->prefs.getBoolean("custom_dark",dark(context))}
        prefs.edit().putString("custom_theme",value).putBoolean("dark_home",resolved).putString("wallpaper_mode","custom").putLong("wallpaper_revision",System.nanoTime()).apply()
    }
    fun useCustom(context:Context){require(hasCustom(context));val prefs=context.getSharedPreferences("appearance",0);prefs.edit().putBoolean("dark_home",customDark(context,prefs.getBoolean("custom_dark",dark(context)))).putString("wallpaper_mode","custom").putLong("wallpaper_revision",System.nanoTime()).apply()}
    fun importImage(context:Context,input:InputStream)=synchronized(importLock){
        val directory=File(context.filesDir,"wallpapers").apply{check(isDirectory||mkdirs())}
        val temp=File.createTempFile("import-",".image",directory)
        var decoded:Bitmap?=null;var oriented:Bitmap?=null;var target:Bitmap?=null
        try{
            input.use{source->temp.outputStream().use{out->val bytes=ByteArray(65536);var total=0;while(true){val n=source.read(bytes);if(n<0)break;total+=n;require(total<=MAX_IMAGE_BYTES){"图片最大 20 MB"};out.write(bytes,0,n)}}}
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(temp.path,options)
            require(options.outWidth in 1..24000&&options.outHeight in 1..24000){"无法读取图片"}
            options.inSampleSize=1;while(options.outWidth/options.inSampleSize>3840||options.outHeight/options.inSampleSize>2160)options.inSampleSize*=2
            options.inJustDecodeBounds=false
            decoded=BitmapFactory.decodeFile(temp.path,options)?:error("无法读取图片")
            val orientation=try{ExifInterface(temp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}catch(_:Throwable){ExifInterface.ORIENTATION_NORMAL}
            val matrix=Matrix().apply{when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)}}
            oriented=if(matrix.isIdentity)decoded else Bitmap.createBitmap(decoded!!,0,0,decoded!!.width,decoded!!.height,matrix,true)
            target=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
            val scale=maxOf(1920f/oriented!!.width,1080f/oriented!!.height);val w=oriented!!.width*scale;val h=oriented!!.height*scale
            Canvas(target!!).drawBitmap(oriented!!,null,RectF((1920-w)/2,(1080-h)/2,(1920+w)/2,(1080+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))
            var luminance=0.0;var count=0
            for(y in 0 until target!!.height step 32)for(x in 0 until target!!.width step 32){val c=target!!.getPixel(x,y);luminance+=(Color.red(c)*.2126+Color.green(c)*.7152+Color.blue(c)*.0722)/255;count++}
            val file=AtomicFile(customFile(context));writingImage=true
            try{val output=file.startWrite();try{check(target!!.compress(Bitmap.CompressFormat.JPEG,90,output));file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}}finally{writingImage=false}
            context.getSharedPreferences("appearance",0).edit().putBoolean("dark_home",customDark(context,luminance/count<.48)).putBoolean("custom_dark",luminance/count<.48).putString("wallpaper_mode","custom").putLong("wallpaper_revision",System.nanoTime()).apply()
        }finally{target?.recycle();if(oriented!==decoded)oriented?.recycle();decoded?.recycle();temp.delete()}
    }
    fun prewarm(context:Context){
        Thread({
            try{
                val appContext=context.applicationContext
                val size=appContext.resources.displayMetrics
                val w=size.widthPixels.coerceAtLeast(1)
                val h=size.heightPixels.coerceAtLeast(1)
                val dark=dark(appContext)
                val glassH=(h*320f/w).toInt().coerceAtLeast(1)
                backdrop(appContext,320,glassH,dark)
                val cappedH=(h*1280f/w).toInt().coerceAtLeast(1)
                backdrop(appContext,1280,cappedH,dark)
                if(mode(appContext)=="custom")backdrop(appContext,w,h,dark)
            }catch(_:Throwable){}
        },"wallpaper-prewarm").start()
    }
    @Synchronized fun backdrop(context:Context,width:Int,height:Int,dark:Boolean):Bitmap {
        val requested="${key(context)}:$width:$height:$dark"
        if(cachedKey==requested)cached?.takeUnless{it.isRecycled}?.let{return it}
        val target=Bitmap.createBitmap(width.coerceAtLeast(1),height.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        if(mode(context)=="bing"&&hasBing(context)){
            val source=try{BitmapFactory.decodeFile(bingFile(context).path)}catch(_:Exception){null}
            if(source!=null){
                val canvas=Canvas(target);val scale=maxOf(width/source.width.toFloat(),height/source.height.toFloat())
                val w=source.width*scale;val h=source.height*scale
                canvas.drawBitmap(source,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                source.recycle();cached=target;cachedKey=requested;return target
            }
        }
        if(mode(context)!="custom"||!hasCustom(context)){
            ProceduralWallpaper.draw(Canvas(target),target.width,target.height,dark);cached=target;cachedKey=requested;return target
        }
        val source=try{if(writingImage)BitmapFactory.decodeFile(customFile(context).path+".bak")else AtomicFile(customFile(context)).openRead().use{BitmapFactory.decodeStream(it)}}catch(_:Exception){null}
        if(source==null){Canvas(target).drawColor(if(dark)0xff14263c.toInt()else 0xffe4edf5.toInt());cached=target;cachedKey=requested;return target}
        val canvas=Canvas(target);val scale=maxOf(width/source.width.toFloat(),height/source.height.toFloat())
        val w=source.width*scale;val h=source.height*scale
        canvas.drawBitmap(source,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        source.recycle();cached=target;cachedKey=requested;return target
    }
}
