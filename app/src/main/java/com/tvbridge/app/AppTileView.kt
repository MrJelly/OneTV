package com.tvbridge.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** Rounded-rect path built from arcs only: Path.addRoundRect and Canvas.drawRoundRect are API 21+. */
internal fun roundedRectPath(l:Float,t:Float,r:Float,b:Float,radius:Float):Path{
    val rr=minOf(radius,(r-l)/2f,(b-t)/2f).coerceAtLeast(0f)
    val p=Path()
    p.moveTo(l+rr,t)
    p.lineTo(r-rr,t)
    p.arcTo(RectF(r-2*rr,t,r,t+2*rr),-90f,90f)
    p.lineTo(r,b-rr)
    p.arcTo(RectF(r-2*rr,b-2*rr,r,b),0f,90f)
    p.lineTo(l+rr,b)
    p.arcTo(RectF(l,b-2*rr,l+2*rr,b),90f,90f)
    p.lineTo(l,t+rr)
    p.arcTo(RectF(l,t,l+2*rr,t+2*rr),180f,90f)
    p.close()
    return p
}

/** Aerial-style app tile: a 16:9 card. A banner fills it edge to edge; otherwise a large icon
 *  sits on a gradient sampled from that icon (or a module accent), with the name inside. */
class AppTileView(context:Context):View(context) {
    var title=""
    var role=""
    var sortKey=0
    var banner:Drawable?=null
    var icon:Drawable?=null
    var tintTop=0xff3a3a3f.toInt()
    var tintBottom=0xff26262b.toInt()
    var badge=false
    var badgeIcon=false
    /** Aerial glyph-tile mode: a white glyph over the diagonal tint gradient, no icon art. */
    var glyph:Drawable?=null
    var focusHook:((Boolean)->Unit)?=null
    private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath=Path()
    private var clipW=0
    private var clipH=0

    init {
        isFocusable=true
        isFocusableInTouchMode=true
        if(android.os.Build.VERSION.SDK_INT>=21)stateListAnimator=null
        if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        setLayerType(LAYER_TYPE_SOFTWARE,null)
    }

    override fun onFocusChanged(focused:Boolean,direction:Int,previouslyFocusedRect:Rect?) {
        super.onFocusChanged(focused,direction,previouslyFocusedRect)
        invalidate()
        focusHook?.invoke(focused)
    }

    private val cornerRadius get()=height*(if(role=="module") .19f else .145f)

    override fun onDraw(canvas:Canvas) {
        val w=width.toFloat();val h=height.toFloat()
        if(w<=0f||h<=0f)return
        val radius=cornerRadius
        if(clipW!=width||clipH!=height){clipW=width;clipH=height;val fresh=roundedRectPath(0f,0f,w,h,radius);clipPath.reset();clipPath.addPath(fresh)}
        canvas.save()
        canvas.clipPath(clipPath)
        val art=banner
        if(art!=null){
            val iw=art.intrinsicWidth.coerceAtLeast(1);val ih=art.intrinsicHeight.coerceAtLeast(1)
            val s=maxOf(w/iw,h/ih);val dw=iw*s;val dh=ih*s
            art.setBounds(((w-dw)/2).toInt(),((h-dh)/2).toInt(),((w+dw)/2).toInt(),((h+dh)/2).toInt())
            art.draw(canvas)
        }else{
            paint.style=Paint.Style.FILL
            val glyphArt=glyph
            paint.shader=if(glyphArt!=null)LinearGradient(0f,0f,w*0.4f,h,tintTop,tintBottom,Shader.TileMode.CLAMP) else LinearGradient(0f,0f,0f,h,tintTop,tintBottom,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,w,h,paint)
            paint.shader=null
            if(glyphArt!=null){
                val size=h*0.87f;val cx=w/2f;val cy=h*0.40f
                glyphArt.setBounds((cx-size/2).toInt(),(cy-size/2).toInt(),(cx+size/2).toInt(),(cy+size/2).toInt())
                glyphArt.draw(canvas)
            }else icon?.let{
                if(badgeIcon){
                    val size=h*0.62f;val cx=w/2f;val cy=h*0.42f
                    val l=cx-size/2;val t=cy-size/2
                    canvas.save()
                    canvas.clipPath(roundedRectPath(l,t,l+size,t+size,size*0.24f))
                    val iw=it.intrinsicWidth.coerceAtLeast(1);val ih=it.intrinsicHeight.coerceAtLeast(1)
                    val s=maxOf(size/iw,size/ih);val dw=iw*s;val dh=ih*s
                    it.setBounds((cx-dw/2).toInt(),(cy-dh/2).toInt(),(cx+dw/2).toInt(),(cy+dh/2).toInt())
                    it.draw(canvas)
                    canvas.restore()
                }else{
                    val size=h*0.56f;val cx=w/2f;val cy=h*0.40f
                    it.setBounds((cx-size/2).toInt(),(cy-size/2).toInt(),(cx+size/2).toInt(),(cy+size/2).toInt())
                    it.draw(canvas)
                }
            }
        }
        if(art==null){
            paint.color=Color.WHITE
            paint.textSize=maxOf(h*.15f,NativeUi.BODY_TEXT_SIZE*NativeUi.scale(context))
            paint.typeface=Typeface.DEFAULT_BOLD
            paint.setShadowLayer(h*.03f,0f,h*.012f,0x99000000.toInt())
            val label=TextUtils.ellipsize(title,paint,w*.88f,TextUtils.TruncateAt.END).toString()
            canvas.drawText(label,(w-paint.measureText(label))/2f,h*.90f,paint)
            paint.clearShadowLayer()
        }
        canvas.restore()
        if(hasFocus()||isSelected){
            val inset=(h*.0055f).coerceAtLeast(.5f)
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=inset*2
            paint.color=0xff173958.toInt()
            canvas.drawPath(roundedRectPath(inset,inset,w-inset,h-inset,radius-inset),paint)
            val inner=inset*3
            paint.color=if(isSelected)0xffa6ddff.toInt() else 0xfff3faff.toInt()
            canvas.drawPath(roundedRectPath(inner,inner,w-inner,h-inner,(radius-inner).coerceAtLeast(0f)),paint)
            paint.style=Paint.Style.FILL
        }
        if(badge){
            val star=AppIcons.Vector("star"){Color.WHITE}
            val size=(h*0.17f).toInt().coerceAtLeast(1)
            val margin=(h*0.07f).toInt()
            star.setBounds(width-margin-size,margin,width-margin,margin+size)
            star.draw(canvas)
        }
    }
}
