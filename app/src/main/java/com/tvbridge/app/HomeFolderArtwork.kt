package com.tvbridge.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

internal class HomeFolderArtwork(private val previews:List<Pair<Drawable,Boolean>>):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    // The folder's base is the tile's own frosted-glass background (GlassUi.homeGlass,
    // set by the home view); this artwork only draws the mini icons on top of it.
    private val sheen=LinearGradient(0f,0f,0f,180f*0.55f,0x59ffffff,0x00ffffff,Shader.TileMode.CLAMP)
    private val rimGrad=LinearGradient(0f,0f,0f,180f,intArrayOf(0xe6ffffff.toInt(),0x40ffffff,0x2effffff,0x8cffffff.toInt()),floatArrayOf(0f,0.4f,0.6f,1f),Shader.TileMode.CLAMP)
    // Each row of minis is centred horizontally on its own: a folder holding
    // three icons shows one centred row, not a row hugging the left edge.
    private val spots=previews.indices.map{index->
        val row=index/3
        val inRow=minOf(3,previews.size-row*3)
        val startX=(180f-(inRow*46f+(inRow-1)*8f))/2f
        (startX+(index%3)*54f) to (17f+row*54f)
    }
    private val outlines=spots.map{(x,y)->roundedRectPath(x,y,x+46f,y+46f,10f)}

    override fun draw(canvas:Canvas){
        val save=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/180f,bounds.height()/180f)
        previews.forEachIndexed{index,(art,isBanner)->
            val (fx,fy)=spots[index]
            val x=fx.toInt();val y=fy.toInt()
            paint.color=0x38ffffff
            val outline=outlines[index]
            canvas.drawPath(outline,paint)
            art.setBounds(x,y,x+46,y+46)
            val clipped=canvas.save()
            canvas.clipPath(outline)
            art.draw(canvas)
            // Each mini icon carries the same gloss as a full tile.
            paint.style=Paint.Style.FILL
            paint.shader=sheen
            canvas.drawRect(x.toFloat(),y.toFloat(),(x+46).toFloat(),(y+46).toFloat(),paint)
            paint.shader=null
            canvas.restoreToCount(clipped)
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=1.6f
            paint.shader=rimGrad
            canvas.drawPath(outline,paint)
            paint.shader=null
            paint.style=Paint.Style.FILL
        }
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth()=180
    override fun getIntrinsicHeight()=180
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    override fun getOpacity()=PixelFormat.TRANSLUCENT
}
