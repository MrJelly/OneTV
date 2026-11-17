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
    private val gradient=LinearGradient(0f,0f,320f,180f,0xff5373ac.toInt(),0xff303c57.toInt(),Shader.TileMode.CLAMP)
    private val outlines=previews.mapIndexed{index,_->
        val x=26f+(index%3)*94f
        val y=17f+(index/3)*50f
        roundedRectPath(x,y,x+80f,y+45f,6f)
    }

    override fun draw(canvas:Canvas){
        val save=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/320f,bounds.height()/180f)
        paint.shader=gradient
        canvas.drawRect(0f,0f,320f,180f,paint)
        paint.shader=null
        previews.forEachIndexed{index,(art,isBanner)->
            val x=26+(index%3)*94
            val y=17+(index/3)*50
            paint.color=0x38ffffff
            val outline=outlines[index]
            canvas.drawPath(outline,paint)
            val inset=if(isBanner)0 else 7
            val iconWidth=if(isBanner)80 else 31
            val left=x+(80-iconWidth)/2
            art.setBounds(left,y+inset,left+iconWidth,y+45-inset)
            val clipped=canvas.save()
            canvas.clipPath(outline)
            art.draw(canvas)
            canvas.restoreToCount(clipped)
        }
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth()=320
    override fun getIntrinsicHeight()=180
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    override fun getOpacity()=PixelFormat.OPAQUE
}
