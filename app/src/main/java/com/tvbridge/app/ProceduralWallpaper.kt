package com.tvbridge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object ProceduralWallpaper {
    private val colors=arrayOf(
        intArrayOf(0x0b6554,0x107c67,0x25567e,0x3c2d94,0x3f2c98,0x3e2b95,0x2d2072),
        intArrayOf(0x11957e,0x16a08b,0x3278b4,0x4f42d3,0x543ed5,0x593cd0,0x4f31aa),
        intArrayOf(0x17a98a,0x17a596,0x297ec3,0x3d4edf,0x4648df,0x6841d6,0x753bb5),
        intArrayOf(0x139a84,0x129aab,0x187cda,0x2060ef,0x2f59ec,0x744bdc,0x913eb6),
        intArrayOf(0x0c696d,0x0d7cb7,0x0e73f0,0x106bfb,0x1b66f6,0x6354e1,0x8c3dad),
        intArrayOf(0x062842,0x084895,0x0956c3,0x0a55c6,0x0d54c5,0x2d47b1,0x4b2872),
        intArrayOf(0x04132b,0x052e6a,0x073d8c,0x073d8d,0x083c8c,0x10337c,0x1b163e)
    )

    private fun cubic(a:Float,b:Float,c:Float,d:Float,t:Float):Float=
        b+.5f*t*(c-a+t*(2*a-5*b+4*c-d+t*(3*(b-c)+d-a)))

    private fun channel(x:Float,y:Float,shift:Int):Int{
        val column=x.toInt()
        val row=y.toInt()
        fun at(r:Int,c:Int)=((colors[r.coerceIn(0,6)][c.coerceIn(0,6)] shr shift) and 255).toFloat()
        fun across(r:Int)=cubic(at(r,column-1),at(r,column),at(r,column+1),at(r,column+2),x-column)
        return cubic(across(row-1),across(row),across(row+1),across(row+2),y-row).toInt().coerceIn(0,255)
    }

    private val palette by lazy {
        val width=192
        val height=108
        val pixels=IntArray(width*height){index->
            val x=(index%width)*6f/(width-1)
            val y=(index/width)*6f/(height-1)
            Color.rgb(channel(x,y,16),channel(x,y,8),channel(x,y,0))
        }
        Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun bitmap(width:Int,height:Int,dark:Boolean)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        .also{draw(Canvas(it),width,height,dark)}

    @Suppress("UNUSED_PARAMETER")
    fun draw(canvas:Canvas,width:Int,height:Int,dark:Boolean){
        canvas.drawBitmap(palette,null,Rect(0,0,width,height),paint)
    }
}
