package com.tvbridge.app

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*

object GlobalPointer {
    private var app:Context?=null
    private var manager:WindowManager?=null
    private var dot:View?=null
    private val idleHandler=Handler(Looper.getMainLooper())
    private val idle=Runnable{dot?.visibility=View.INVISIBLE}
    private fun wake(){dot?.visibility=View.VISIBLE;idleHandler.removeCallbacks(idle);idleHandler.postDelayed(idle,3000)}
    private var params:WindowManager.LayoutParams?=null
    private var x=0f;private var y=0f
    private var width=1;private var height=1;private var density=1f
    @Volatile var enabled=false;private set
    @Volatile var requested=false
    fun permitted(context:Context)=Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(context)
    private fun bounds(){val m=DisplayMetrics();manager?.defaultDisplay?.getRealMetrics(m);width=m.widthPixels.coerceAtLeast(1);height=m.heightPixels.coerceAtLeast(1);density=m.density}
    fun show(context:Context):Boolean {
        if(!permitted(context))return false
        requested=true
        if(dot!=null){enabled=true;wake();return true}
        app=context.applicationContext;manager=app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager;bounds()
        x=width/2f;y=height/2f
        val size=(28*density).toInt().coerceAtLeast(20)
        val view=object:View(app){val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas){val cx=this.width/2f;val cy=this.height/2f
                paint.style=Paint.Style.FILL;paint.color=0x90000000.toInt();c.drawCircle(cx,cy,11*density,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=Color.WHITE;c.drawCircle(cx,cy,8*density,paint)
                paint.style=Paint.Style.FILL;c.drawCircle(cx,cy,2*density,paint)
            }
        }
        val lp=WindowManager.LayoutParams(size,size,if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.LEFT;alpha=.65f}
        dot=view;params=lp
        return try{place();manager!!.addView(view,lp);enabled=true;wake();BrowserActivity.active?.globalPointerChanged(true);true}catch(_:RuntimeException){dot=null;params=null;enabled=false;false}
    }
    private fun place(){params?.let{it.x=(x-it.width/2f).toInt();it.y=(y-it.height/2f).toInt()}}
    fun move(dx:Float,dy:Float):Boolean {
        if(!enabled||!dx.isFinite()||!dy.isFinite())return false
        bounds();x=(x+dx.coerceIn(-240f,240f)*density).coerceIn(0f,(width-1).toFloat());y=(y+dy.coerceIn(-240f,240f)*density).coerceIn(0f,(height-1).toFloat())
        return try{place();manager?.updateViewLayout(dot,params);wake();true}catch(_:RuntimeException){hide();false}
    }
    fun position()=Pair(x.toInt().coerceIn(0,width-1),y.toInt().coerceIn(0,height-1))
    fun moveTo(screenX:Float,screenY:Float):Boolean {
        if(!enabled||!screenX.isFinite()||!screenY.isFinite())return false
        bounds();x=screenX.coerceIn(0f,(width-1).toFloat());y=screenY.coerceIn(0f,(height-1).toFloat())
        return try{place();manager?.updateViewLayout(dot,params);wake();true}catch(_:RuntimeException){hide();false}
    }
    fun scroll(dx:Float,dy:Float):IntArray {
        wake()
        val start=position();return intArrayOf(start.first,start.second,(x-dx.coerceIn(-600f,600f)*density).toInt().coerceIn(0,width-1),(y-dy.coerceIn(-600f,600f)*density).toInt().coerceIn(0,height-1))
    }
    fun hide(){idleHandler.removeCallbacks(idle);requested=false;dot?.let{try{manager?.removeView(it)}catch(_:RuntimeException){}};dot=null;params=null;enabled=false;BrowserActivity.active?.globalPointerChanged(false)}
}
