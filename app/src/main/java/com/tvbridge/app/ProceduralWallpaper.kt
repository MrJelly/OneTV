package com.tvbridge.app

import android.graphics.*

object ProceduralWallpaper {
    fun bitmap(width:Int,height:Int,dark:Boolean)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        .also{draw(Canvas(it),width,height,dark)}

    @Suppress("UNUSED_PARAMETER")
    fun draw(canvas:Canvas,width:Int,height:Int,dark:Boolean){
        val w=width.toFloat()
        val h=height.toFloat()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader=LinearGradient(0f,h*.3f,w,h*.8f,
            intArrayOf(0xff071027.toInt(),0xff101935.toInt(),0xff241b43.toInt()),
            null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w,h,paint)

        val upper=Path().apply{
            moveTo(-.06f*w,.27f*h)
            cubicTo(.16f*w,.32f*h,.27f*w,.05f*h,.49f*w,.12f*h)
            cubicTo(.68f*w,.1805f*h,.76f*w,.25f*h,1.06f*w,-.025f*h)
        }
        val upperFill=Path(upper).apply{lineTo(w,0f);lineTo(0f,0f);close()}
        paint.shader=LinearGradient(0f,h*.33f,w,0f,
            intArrayOf(0xff0a1834.toInt(),0xff183365.toInt(),0xff40245c.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawPath(upperFill,paint)

        val lower=Path().apply{
            moveTo(-.06f*w,.83f*h)
            cubicTo(.18f*w,.68f*h,.28f*w,.90f*h,.53f*w,.904f*h)
            cubicTo(.78f*w,.908f*h,.88f*w,.68f*h,1.06f*w,.64f*h)
        }
        val lowerFill=Path(lower).apply{lineTo(w,h);lineTo(0f,h);close()}
        paint.shader=LinearGradient(0f,h,w,h*.70f,
            intArrayOf(0xff07182f.toInt(),0xff172c65.toInt(),0xff592d87.toInt()),
            null,Shader.TileMode.CLAMP)
        canvas.drawPath(lowerFill,paint)

        fun glow(path:Path,x:Float,y:Float,radius:Float,color:Int){
            canvas.save()
            canvas.clipPath(path)
            paint.shader=RadialGradient(w*x,h*y,w*radius,color,color and 0xffffff,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,w,h,paint)
            canvas.restore()
        }
        glow(upperFill,.62f,.19f,.29f,0x702675d7)
        glow(lowerFill,.12f,.80f,.32f,0x902a6ca7.toInt())
        glow(lowerFill,.99f,.73f,.27f,0x82bd74ee.toInt())

        fun edge(path:Path,colors:IntArray){
            paint.style=Paint.Style.STROKE
            paint.shader=LinearGradient(0f,0f,w,0f,colors,floatArrayOf(0f,.34f,.62f,1f),Shader.TileMode.CLAMP)
            paint.strokeWidth=(h*.0035f).coerceAtLeast(.8f)
            paint.maskFilter=BlurMaskFilter((h*.024f).coerceAtLeast(.8f),BlurMaskFilter.Blur.NORMAL)
            paint.alpha=185
            canvas.drawPath(path,paint)
            paint.maskFilter=BlurMaskFilter((h*.008f).coerceAtLeast(.6f),BlurMaskFilter.Blur.NORMAL)
            paint.strokeWidth=(h*.009f).coerceAtLeast(.9f)
            paint.alpha=125
            canvas.drawPath(path,paint)
            paint.maskFilter=null
            paint.strokeWidth=(h*.00155f).coerceAtLeast(.65f)
            paint.alpha=225
            canvas.drawPath(path,paint)
            paint.alpha=255
            paint.style=Paint.Style.FILL
        }
        edge(upper,intArrayOf(0x00172a50,0x70527fc3,0xff7dc8f6.toInt(),0xaaa987df.toInt()))
        edge(lower,intArrayOf(0xaa558aca.toInt(),0xff6ab9f1.toInt(),0xff7664c0.toInt(),0xffda93e8.toInt()))
    }
}
