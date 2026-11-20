package com.tvbridge.app

import android.graphics.*

internal object AppIconGlass {
    private val views=java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.view.View,Boolean>())
    fun observe(view:android.view.View){synchronized(views){views.add(view)}}
    @Volatile private var bitmap:Bitmap?=null
    fun ready():Bitmap?=bitmap
    @Synchronized fun prepare():Bitmap {
        bitmap?.let{return it}
        val result=Bitmap.createBitmap(144,144,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(0x66787878)
        paint.shader=LinearGradient(0f,0f,144f,144f,0x44ffffff,0x08ffffff,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,144f,144f,paint)
        bitmap=result
        AppThreads.main.post{synchronized(views){views.forEach{it.invalidate()}}}
        return result
    }
}
