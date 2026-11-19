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

/** iOS-style app tile: a square icon drawn full-bleed under a rounded mask, finished
 *  with a gloss sheen over the top and a bright hairline rim. Names are drawn by the
 *  home grid below the tile; module glyph tiles keep their name inside at the bottom. */
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
    /** Fires on every focus-animation frame so the parent can repaint the shadow. */
    var focusAnimHook:(()->Unit)?=null
    private var focusFrac=0f
    private var focusAnimator:android.animation.ValueAnimator?=null
    /** 0 = at rest, 1 = fully focused: drives scale, gloss strength and ring fade. */
    val focusFraction get()=focusFrac
    /** 1 = resting on the glass plate, 0 = fully lifted into the grid: on the
     *  plate, focus is a full-perimeter lit edge; lifted, it is the shadow. */
    var plateFrac=1f
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val rimPath = Path()
    private val glowPath = Path()
    private val badgeClipPath = Path()
    private var cachedW = 0
    private var cachedH = 0
    private var rimStroke = 1f
    private var glowStroke = 1.5f
    private var glossShader: LinearGradient? = null
    private var bottomShader: LinearGradient? = null
    private var rimShader: LinearGradient? = null
    private var fillShader: LinearGradient? = null
    private var fillShaderKey = 0
    private val starDrawable by lazy { AppIcons.Vector("star") { Color.WHITE } }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (android.os.Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        // Hardware-accelerated path clipping is supported since API 18 (minSdk is 19).
        // Keeping tiles on the hardware layer avoids per-tile CPU bitmap caches and
        // software re-rasterization on every focus-animation frame.
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
        focusHook?.invoke(focused)
        animateFocus(focused)
    }

    /** Focus motion: a 180ms swell to 1.05 with the gloss and ring fading up with
     *  it. A new animation always starts from the current fraction and cancels the
     *  old one, so fast D-pad repeats never stack or jump. Only the tile that owns
     *  or just lost focus ever animates; every other tile stays static. */
    private fun animateFocus(focused: Boolean) {
        focusAnimator?.cancel()
        val target = if (focused) 1f else 0f
        if (focusFrac == target) {
            applyFocusFrac()
            return
        }
        focusAnimator = android.animation.ValueAnimator.ofFloat(focusFrac, target).apply {
            duration = if (focused) 180L else 140L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                focusFrac = it.animatedValue as Float
                applyFocusFrac()
            }
            start()
        }
    }

    private fun applyFocusFrac() {
        val s = 1f + 0.05f * focusFrac
        scaleX = s
        scaleY = s
        invalidate()
        focusAnimHook?.invoke()
    }

    override fun onDetachedFromWindow() {
        focusAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private val cornerRadius get() = height * .225f

    private fun ensureGeometry(w: Float, h: Float) {
        if (cachedW == width && cachedH == height) return
        cachedW = width
        cachedH = height
        val radius = cornerRadius

        clipPath.reset()
        clipPath.addPath(roundedRectPath(0f, 0f, w, h, radius))

        rimStroke = (h * 0.009f).coerceIn(1f, 3f)
        val rim = rimStroke / 2f
        rimPath.reset()
        rimPath.addPath(roundedRectPath(rim, rim, w - rim, h - rim, (radius - rim).coerceAtLeast(0f)))

        glowStroke = (h * 0.014f).coerceIn(1.5f, 4f)
        val gi = glowStroke / 2f
        glowPath.reset()
        glowPath.addPath(roundedRectPath(gi, gi, w - gi, h - gi, (radius - gi).coerceAtLeast(0f)))

        val badgeSize = h * 0.62f
        val bl = w / 2f - badgeSize / 2f
        val bt = h * 0.50f - badgeSize / 2f
        badgeClipPath.reset()
        badgeClipPath.addPath(roundedRectPath(bl, bt, bl + badgeSize, bt + badgeSize, badgeSize * 0.24f))

        glossShader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x59ffffff, 0x00ffffff, Shader.TileMode.CLAMP)
        bottomShader = LinearGradient(0f, h, 0f, h * 0.5f, 0x30ffffff, 0x00ffffff, Shader.TileMode.CLAMP)
        rimShader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xe6ffffff.toInt(), 0x40ffffff, 0x2effffff, 0x8cffffff.toInt()),
            floatArrayOf(0f, 0.4f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        fillShader = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        ensureGeometry(w, h)

        canvas.save()
        canvas.clipPath(clipPath)
        val art = banner
        if (art != null) {
            val iw = art.intrinsicWidth.coerceAtLeast(1)
            val ih = art.intrinsicHeight.coerceAtLeast(1)
            val s = maxOf(w / iw, h / ih)
            val dw = iw * s
            val dh = ih * s
            art.setBounds(((w - dw) / 2).toInt(), ((h - dh) / 2).toInt(), ((w + dw) / 2).toInt(), ((h + dh) / 2).toInt())
            art.draw(canvas)
        } else {
            paint.style = Paint.Style.FILL
            val glyphArt = glyph
            val key = (tintTop * 31 + tintBottom) * 31 + (if (glyphArt != null) 1 else 0)
            if (fillShader == null || fillShaderKey != key) {
                fillShaderKey = key
                fillShader = if (glyphArt != null) {
                    LinearGradient(0f, 0f, w * 0.4f, h, tintTop, tintBottom, Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, 0f, 0f, h, tintTop, tintBottom, Shader.TileMode.CLAMP)
                }
            }
            paint.shader = fillShader
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            if (glyphArt != null) {
                val size = h * 0.87f
                val cx = w / 2f
                val cy = h * 0.40f
                glyphArt.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
                glyphArt.draw(canvas)
            } else icon?.let {
                if (badgeIcon) {
                    val size = h * 0.62f
                    val cx = w / 2f
                    val cy = h * 0.50f
                    canvas.save()
                    canvas.clipPath(badgeClipPath)
                    val iw = it.intrinsicWidth.coerceAtLeast(1)
                    val ih = it.intrinsicHeight.coerceAtLeast(1)
                    val s = maxOf(size / iw, size / ih)
                    val dw = iw * s
                    val dh = ih * s
                    it.setBounds((cx - dw / 2).toInt(), (cy - dh / 2).toInt(), (cx + dw / 2).toInt(), (cy + dh / 2).toInt())
                    it.draw(canvas)
                    canvas.restore()
                } else {
                    val size = h * 0.56f
                    val cx = w / 2f
                    val cy = h * 0.50f
                    it.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
                    it.draw(canvas)
                }
            }
        }
        if (art == null && glyph != null) {
            paint.color = Color.WHITE
            paint.textSize = maxOf(h * .15f, NativeUi.BODY_TEXT_SIZE * NativeUi.scale(context))
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.setShadowLayer(h * .03f, 0f, h * .012f, 0x99000000.toInt())
            val label = TextUtils.ellipsize(title, paint, w * .88f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, (w - paint.measureText(label)) / 2f, h * .90f, paint)
            paint.clearShadowLayer()
        }

        // iOS gloss, inside the rounded mask: top reflection, fainter bottom pool,
        // and a hairline rim lit at top and bottom.
        paint.style = Paint.Style.FILL
        paint.alpha = (255 * (0.70f + 0.30f * focusFrac)).toInt().coerceIn(0, 255)
        paint.shader = glossShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = bottomShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rimStroke
        paint.shader = rimShader
        canvas.drawPath(rimPath, paint)
        paint.shader = null

        // On the glass plate (not yet lifted) the focused tile's whole rim lights up;
        // lifted, this fades out as the home view's soft shadow fades in.
        val plateGlow = focusFrac * plateFrac
        if (plateGlow > 0.01f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = glowStroke
            paint.alpha = (plateGlow * 235).toInt().coerceIn(0, 255)
            paint.color = 0xffffffff.toInt()
            canvas.drawPath(glowPath, paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.restore()

        if (badge) {
            val size = (h * 0.17f).toInt().coerceAtLeast(1)
            val margin = (h * 0.07f).toInt()
            starDrawable.setBounds(width - margin - size, margin, width - margin, margin + size)
            starDrawable.draw(canvas)
        }
    }
}
