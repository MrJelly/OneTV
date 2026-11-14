package com.tvbridge.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.Button

class ShortcutButton(context:Context):Button(context){
    var icon:Drawable?=null
    var iconPixels=92f
    override fun onDraw(canvas:Canvas){
        val scale=NativeUi.scale(context);val iconSize=iconPixels*scale;val gap=16*scale
        paint.textSize=26*scale;paint.color=currentTextColor
        val fm=paint.fontMetrics;val textHeight=fm.descent-fm.ascent
        val top=(height-iconSize-gap-textHeight)/2
        icon?.let{val left=(width-iconSize)/2;it.setBounds(left.toInt(),top.toInt(),(left+iconSize).toInt(),(top+iconSize).toInt());it.draw(canvas)}
        val label=TextUtils.ellipsize(text,paint,(width-16*scale).coerceAtLeast(1f),TextUtils.TruncateAt.END).toString()
        canvas.drawText(label,(width-paint.measureText(label))/2,top+iconSize+gap-fm.ascent,paint)
    }
}
