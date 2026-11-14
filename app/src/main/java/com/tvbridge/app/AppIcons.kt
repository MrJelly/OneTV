package com.tvbridge.app

import android.graphics.*
import android.graphics.drawable.Drawable
import android.widget.TextView

object AppIcons {
    fun forLabel(label:String):String?=when{
        label=="设置"->"settings";label.contains("直播源")->"layers";label.contains("直播")->"tv";label.contains("文件")->"folder";label.contains("遥控")->"remote"
        label.contains("收藏")->"star";label.contains("频道列表")->"list";label.contains("返回")||label.contains("退出全屏")->"back"
        label.contains("重新加载")||label.contains("刷新")||label.contains("线路")->"refresh";label.contains("全屏")->"expand"
        label.contains("添加")->"plus";label.contains("桌面")->"home";label=="应用"->"grid";else->null
    }
    fun bind(view:TextView,key:String?=forLabel(view.text.toString()),size:Float=28f){
        if(key==null)return
        val icon=Vector(key){view.currentTextColor};val n=(size*NativeUi.scale(view.context)).toInt();icon.setBounds(0,0,n,n)
        view.setCompoundDrawablesRelative(icon,null,null,null);view.compoundDrawablePadding=(12*NativeUi.scale(view.context)).toInt()
    }
    class Vector(private val key:String,private val tint:()->Int):Drawable(){
        override fun getIntrinsicWidth()=24
        override fun getIntrinsicHeight()=24
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        override fun isStateful()=true
        override fun onStateChange(state:IntArray):Boolean{invalidateSelf();return true}
        override fun draw(c:Canvas){c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f);paint.color=tint()
            fun line(vararg xy:Float){val p=Path();p.moveTo(xy[0],xy[1]);for(i in 2 until xy.size step 2)p.lineTo(xy[i],xy[i+1]);c.drawPath(p,paint)}
            fun box(l:Float,t:Float,r:Float,b:Float,rad:Float=3f){c.drawPath(roundedRectPath(l,t,r,b,rad),paint)}
            fun path(x:Float,y:Float,a:Float,b:Float,d:Float,e:Float,f:Float,g:Float){val p=Path();p.moveTo(x,y);p.cubicTo(a,b,d,e,f,g);c.drawPath(p,paint)}
            when(key){
                "move"->{line(12f,3f,12f,21f);line(3f,12f,21f,12f);line(9f,6f,12f,3f,15f,6f);line(9f,18f,12f,21f,15f,18f);line(6f,9f,3f,12f,6f,15f);line(18f,9f,21f,12f,18f,15f)}
                "hidden"->{path(3f,12f,8f,5f,16f,5f,21f,12f);path(3f,12f,8f,19f,16f,19f,21f,12f);line(4f,4f,20f,20f)}
                "info"->{c.drawCircle(12f,12f,9f,paint);line(12f,11f,12f,16f);c.drawPoint(12f,8f,paint)}
                "power"->{c.drawArc(RectF(4f,4f,20f,21f),-55f,290f,false,paint);line(12f,2f,12f,11f)}
                "trash"->{line(4f,7f,20f,7f);line(9f,7f,9f,4f,15f,4f,15f,7f);line(6f,8f,7f,20f,17f,20f,18f,8f);line(10f,11f,10.5f,17f);line(14f,11f,13.5f,17f)}
                "wifi","wifi0","wifi1","wifi2","wifi3"->{
                    val level=key.last().digitToIntOrNull()?:3
                    val color=paint.color
                    for(i in 0..2){
                        paint.alpha=if(i<level)Color.alpha(color)else Color.alpha(color)/3
                        val radius=4f+i*4f
                        c.drawArc(RectF(12f-radius,20f-radius,12f+radius,20f+radius),222f,96f,false,paint)
                    }
                    paint.color=color;paint.style=Paint.Style.FILL;c.drawCircle(12f,20f,1.35f,paint);paint.style=Paint.Style.STROKE
                }
                "ethernet"->{
                    line(12f,8f,12f,13f);line(5f,16f,5f,13f,19f,13f,19f,16f)
                    paint.style=Paint.Style.FILL
                    c.drawRoundRect(RectF(8.5f,2f,15.5f,9f),1.5f,1.5f,paint)
                    c.drawRoundRect(RectF(1.5f,15f,8.5f,22f),1.5f,1.5f,paint)
                    c.drawRoundRect(RectF(15.5f,15f,22.5f,22f),1.5f,1.5f,paint)
                    paint.style=Paint.Style.STROKE
                }
                "network_off"->{c.drawArc(RectF(0f,6f,24f,30f),225f,90f,false,paint);line(4f,4f,20f,20f);c.drawCircle(12f,20f,1f,paint)}
                "input_hdmi"->{line(4f,5f,20f,5f,20f,13f,17f,18f,7f,18f,4f,13f,4f,5f);for(x in 8..16 step 4)line(x.toFloat(),6f,x.toFloat(),9f)}
                "input_tuner"->{c.drawArc(RectF(2f,3f,22f,23f),180f,180f,false,paint);c.drawArc(RectF(6f,7f,18f,19f),180f,180f,false,paint);line(8f,21f,12f,16f,16f,21f);line(12f,12f,12f,16f)}
                "input_av"->{for(x in listOf(5f,12f,19f)){line(x,3f,x,21f);c.drawRoundRect(RectF(x-1.5f,7f,x+1.5f,16f),.6f,.6f,paint)}}
                "sliders"->{line(3f,6f,8f,6f);line(12f,6f,21f,6f);c.drawCircle(10f,6f,2f,paint);line(3f,12f,14f,12f);line(18f,12f,21f,12f);c.drawCircle(16f,12f,2f,paint);line(3f,18f,6f,18f);line(10f,18f,21f,18f);c.drawCircle(8f,18f,2f,paint)}
                "sun"->{c.drawCircle(12f,12f,4f,paint);for(i in 0..7){val a=Math.toRadians(i*45.0);line((12+7*kotlin.math.cos(a)).toFloat(),(12+7*kotlin.math.sin(a)).toFloat(),(12+9*kotlin.math.cos(a)).toFloat(),(12+9*kotlin.math.sin(a)).toFloat())}}

                "globe"->{c.drawCircle(12f,12f,9f,paint);c.drawOval(RectF(7f,3f,17f,21f),paint);line(3f,12f,21f,12f)}
                "settings"->{c.drawCircle(12f,12f,6.5f,paint);c.drawCircle(12f,12f,2.5f,paint);for(i in 0..7){val a=Math.toRadians(i*45.0);line((12+6.5*kotlin.math.cos(a)).toFloat(),(12+6.5*kotlin.math.sin(a)).toFloat(),(12+9*kotlin.math.cos(a)).toFloat(),(12+9*kotlin.math.sin(a)).toFloat())}}
                "tv"->{box(3f,5f,21f,18f);line(8f,22f,16f,22f);line(12f,18f,12f,22f);line(10f,9f,15f,11.5f,10f,14f,10f,9f)}
                "folder"->{val p=Path();p.moveTo(3f,8f);p.lineTo(3f,5f);p.quadTo(3f,3f,5f,3f);p.lineTo(9f,3f);p.lineTo(12f,6f);p.lineTo(19f,6f);p.quadTo(21f,6f,21f,8f);p.lineTo(21f,19f);p.quadTo(21f,21f,19f,21f);p.lineTo(5f,21f);p.quadTo(3f,21f,3f,19f);p.close();c.drawPath(p,paint);line(3f,9f,21f,9f)}
                "remote"->{box(6f,2f,18f,22f);c.drawCircle(12f,9f,3f,paint);line(9f,16f,10f,16f);line(14f,16f,15f,16f);line(9f,19f,10f,19f);line(14f,19f,15f,19f)}
                "star"->{val p=Path();for(i in 0..9){val angle=Math.toRadians((i*36-90).toDouble());val radius=if(i%2==0)9.0 else 4.3;val x=(12+radius*kotlin.math.cos(angle)).toFloat();val y=(12+radius*kotlin.math.sin(angle)).toFloat();if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};p.close();c.drawPath(p,paint)}
                "back"->line(14f,5f,7f,12f,14f,19f)
                "check"->line(5f,12f,10f,17f,20f,7f)
                "chevron-right"->line(9f,5f,16f,12f,9f,19f)
                "history-back"->{line(10f,5f,3f,12f,10f,19f);line(3f,12f,21f,12f)}
                "history-forward"->{line(14f,5f,21f,12f,14f,19f);line(3f,12f,21f,12f)}
                "search"->{c.drawCircle(10.5f,10.5f,6.5f,paint);line(15.5f,15.5f,21f,21f)}
                "collapse"->line(5f,15f,12f,8f,19f,15f)
                "menu"->{line(4f,6f,20f,6f);line(4f,12f,20f,12f);line(4f,18f,20f,18f)}
                "monitor"->{box(3f,4f,21f,17f,2f);line(12f,17f,12f,21f);line(7f,21f,17f,21f)}
                "phone"->{box(7f,2f,17f,22f,2f);line(10f,18f,14f,18f)}
                "exit"->{line(14f,4f,21f,4f,21f,20f,14f,20f);line(3f,12f,15f,12f);line(8f,7f,3f,12f,8f,17f)}
                "refresh"->{c.drawArc(RectF(4f,4f,20f,20f),45f,275f,false,paint);line(20f,4f,20f,10f,14f,10f)}
                "expand"->{line(9f,3f,3f,3f,3f,9f);line(15f,3f,21f,3f,21f,9f);line(3f,15f,3f,21f,9f,21f);line(15f,21f,21f,21f,21f,15f)}
                "plus"->{line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
                "minus"->line(5f,12f,19f,12f)
                "grid"->{for(x in listOf(3f,14f))for(y in listOf(3f,14f))box(x,y,x+7,y+7,2f)}
                "home"->{line(3f,11f,12f,3f,21f,11f);line(5f,10f,5f,21f,19f,21f,19f,10f);line(10f,21f,10f,15f,14f,15f,14f,21f)}
                "layers"->{line(3f,7f,12f,2f,21f,7f,12f,12f,3f,7f);line(3f,12f,12f,17f,21f,12f);line(3f,17f,12f,22f,21f,17f)}
                "list"->{for(y in listOf(6f,12f,18f)){line(9f,y,21f,y);c.drawCircle(4f,y,.7f,paint)}}
                "play"->line(8f,5f,19f,12f,8f,19f,8f,5f)
            };c.restore()
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha};override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter};override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
