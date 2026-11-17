package com.tvbridge.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.os.SystemClock

class LoadingSpinner(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
    private val bounds=RectF()
    private var running=false
    private var started=0L
    private val frame=object:Runnable{override fun run(){if(running){rotation=((SystemClock.uptimeMillis()-started)%900)*360f/900;postOnAnimation(this)}}}
    init{isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(canvas:Canvas){
        val stroke=(3*NativeUi.scale(context)).coerceAtLeast(2f);val inset=stroke/2+2
        paint.strokeWidth=stroke;bounds.set(inset,inset,width-inset,height-inset)
        paint.color=0x26ffffff;canvas.drawOval(bounds,paint)
        paint.color=0xffe4e8ed.toInt();canvas.drawArc(bounds,-90f,260f,false,paint)
    }
    private fun updateAnimation(){
        if(isAttachedToWindow&&isShown&&windowVisibility==VISIBLE){
            if(!running){running=true;started=SystemClock.uptimeMillis();postOnAnimation(frame)}
        }else if(running){running=false;removeCallbacks(frame)}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateAnimation()}
    override fun onDetachedFromWindow(){running=false;removeCallbacks(frame);super.onDetachedFromWindow()}
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);updateAnimation()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);updateAnimation()}
}
