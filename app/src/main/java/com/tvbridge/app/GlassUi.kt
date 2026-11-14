package com.tvbridge.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import java.lang.ref.WeakReference

object GlassUi {
    const val PANEL_RADIUS = 28f
    const val BUTTON_RADIUS = 24f
    const val TILE_RADIUS = 20f
    const val CHIP_RADIUS = 18f
    const val CIRCLE_RADIUS = 100f

    data class Frost(val bitmap: Bitmap, val width: Int, val height: Int, val left: Int = 0, val top: Int = 0)

    private var frostKey = ""
    private var wallpaperFrost: Frost? = null
    private fun tint(gain: Float, lift: Float) = ColorMatrixColorFilter(floatArrayOf(
        gain, 0f, 0f, 0f, lift, 0f, gain, 0f, 0f, lift,
        0f, 0f, gain, 0f, lift, 0f, 0f, 0f, 1f, 0f
    ))
    private val plateTintDark = tint(0.80f, 30f)
    private val plateTintLight = tint(0.85f, 40f)

    fun ink(context: Context, dark: Boolean = HomeAppearance.dark(context)) =
        if (dark) 0xffedf4fc.toInt() else 0xff263c50.toInt()

    fun muted(context: Context, dark: Boolean = HomeAppearance.dark(context)) =
        if (dark) 0xffbac8d8.toInt() else 0xff5e6c76.toInt()

    fun colors(context: Context, dark: Boolean = HomeAppearance.dark(context)) =
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(if (dark) 0xff9aa9b9.toInt() else 0xff83909d.toInt(), ink(context, dark))
        )

    fun activity(context: Context): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }

    fun frost(b: Bitmap): Bitmap {
        val w = 320
        val h = (b.height * 320f / b.width).toInt().coerceIn(1, 320)
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== b) small.recycle()
        val tmp = IntArray(px.size)
        repeat(3) {
            pass(px, tmp, w, h, 7, horizontal = true)
            pass(tmp, px, w, h, 7, horizontal = false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val n = r * 2 + 1
        for (l in 0 until lines) {
            val base = if (horizontal) l * w else l
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = src[base + k.coerceIn(0, len - 1) * stride]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                val idx = base + i * stride
                dst[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                val old = src[base + (i - r).coerceIn(0, len - 1) * stride]
                val next = src[base + (i + r + 1).coerceIn(0, len - 1) * stride]
                a += (next ushr 24) - (old ushr 24)
                rr += ((next shr 16) and 0xFF) - ((old shr 16) and 0xFF)
                g += ((next shr 8) and 0xFF) - ((old shr 8) and 0xFF)
                b += (next and 0xFF) - (old and 0xFF)
            }
        }
    }

    @Synchronized
    private fun wallpaper(context: Context): Frost {
        val size = context.resources.displayMetrics
        val root = activity(context)?.window?.decorView
        val w = root?.width?.takeIf { it > 0 } ?: size.widthPixels
        val h = root?.height?.takeIf { it > 0 } ?: size.heightPixels
        val point = IntArray(2)
        root?.getLocationOnScreen(point)
        val key = "${HomeAppearance.key(context)}:$w:$h:${point[0]}:${point[1]}"
        if (key != frostKey || wallpaperFrost == null) {
            val targetH = (h * 320f / w).toInt().coerceAtLeast(1)
            val image = HomeAppearance.backdrop(context, 320, targetH, HomeAppearance.dark(context))
            wallpaperFrost = Frost(frost(image), w, h, point[0], point[1])
            frostKey = key
        }
        return wallpaperFrost!!
    }

    private var staticFrostBitmap: Bitmap? = null

    @Synchronized
    fun staticFrost(context: Context): Frost {
        if (staticFrostBitmap == null) {
            staticFrostBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff2d4257.toInt()) }
        }
        val size = context.resources.displayMetrics
        val root = activity(context)?.window?.decorView
        val w = root?.width?.takeIf { it > 0 } ?: size.widthPixels
        val h = root?.height?.takeIf { it > 0 } ?: size.heightPixels
        return Frost(staticFrostBitmap!!, w, h, 0, 0)
    }

    fun snapshot(context: Context): Frost? {
        return try {
            wallpaper(context)
        } catch (_: Exception) {
            staticFrost(context)
        }
    }

    fun video(context: Context): Boolean {
        if (activity(context)?.javaClass?.name == "com.tvbridge.app.BrowserActivity") return false
        if (context.packageName == "com.tvbridge.engine") return true
        fun contains(view: View): Boolean {
            if (!view.isShown) return false
            if (view is android.view.SurfaceView || view is android.webkit.WebView) return true
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (contains(view.getChildAt(i))) return true
                }
            }
            return false
        }
        return activity(context)?.window?.decorView?.let(::contains) ?: false
    }

    fun backdrop(view: View): Drawable = object : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            HomeAppearance.drawBackdrop(view.context, canvas, bounds.width(), bounds.height(), HomeAppearance.dark(view.context), paint)
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Legacy Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun surface(
        view: View,
        radius: Float = 24f,
        frost: Frost? = null,
        video: Boolean = video(view.context),
        dark: Boolean? = null,
        dense: Boolean = false,
        border: Boolean = true,
        faint: Boolean = false,
        alphaBoost: Int = 0,
        bottomCorners: Boolean = true
    ): Drawable = Material(view, radius, frost, video, dark, dense, border, faint, alphaBoost, bottomCorners)

    fun homeGlass(view:View,radius:Float,screenLeft:Int?=null,screenTop:Int?=null):Drawable =
        Material(view,radius,null,false,true,false,true,false,0,true,true,screenLeft,screenTop)

    fun selectedBorder(view: View, radius: Float = 18f): Drawable {
        val scale = NativeUi.scale(view.context)
        fun borderDrawable(stroke: Float, strokeColor: Int, fillColor: Int) = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius * scale
            if (stroke > 0f) setStroke(stroke.toInt(), strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), borderDrawable(2.0f * scale, 0xf0ffffff.toInt(), 0x33ffffff))
            addState(intArrayOf(android.R.attr.state_focused), borderDrawable(2.0f * scale, 0xf0ffffff.toInt(), 0x33ffffff))
            addState(intArrayOf(android.R.attr.state_selected), borderDrawable(2.0f * scale, 0xf0ffffff.toInt(), 0x33ffffff))
            addState(intArrayOf(), borderDrawable(0f, 0x00000000, 0x00000000))
        }
    }

    object SmoothCorners {
        private const val SMOOTHING = 0.6f

        fun smoothRect(rect: RectF, radius: Float, bottomRadius: Float = radius): Path = Path().apply {
            if (radius <= 0f && bottomRadius <= 0f) {
                addRect(rect, Path.Direction.CW)
                return@apply
            }
            val budget = Math.min(rect.width(), rect.height()) / 2f
            if (budget <= 0f) {
                addRect(rect, Path.Direction.CW)
                return@apply
            }
            val top = Corner(radius, SMOOTHING, budget)
            val bot = Corner(bottomRadius, SMOOTHING, budget)
            moveTo(rect.left + top.p, rect.top)
            top.addTo(this, rect.right, rect.top, 1f, 0f, 0f, 1f)
            bot.addTo(this, rect.right, rect.bottom, 0f, 1f, -1f, 0f)
            bot.addTo(this, rect.left, rect.bottom, -1f, 0f, 0f, -1f)
            top.addTo(this, rect.left, rect.top, 0f, -1f, 1f, 0f)
            close()
        }

        private class Corner(radius: Float, smoothing: Float, budget: Float) {
            val p: Float
            private val r: Float
            private val a: Float
            private val b: Float
            private val c: Float
            private val d: Float
            private val arc: Float

            init {
                var s = smoothing
                if (radius > 0f && (1f + s) * radius > budget) s = (budget / radius - 1f).coerceIn(0f, s)
                r = Math.min(radius, budget).coerceAtLeast(0f)
                p = Math.min((1f + s) * r, budget)
                arc = (Math.PI / 2.0 * (1.0 - s)).toFloat()
                val arcLen = Math.sin(arc / 2.0).toFloat() * r * Math.sqrt(2.0).toFloat()
                val alpha = (Math.PI.toFloat() / 2f - arc) / 2f
                val p3p4 = r * Math.tan(alpha / 2.0).toFloat()
                val beta = Math.PI.toFloat() / 4f * s
                c = p3p4 * Math.cos(beta.toDouble()).toFloat()
                d = c * Math.tan(beta.toDouble()).toFloat()
                b = (p - arcLen - c - d) / 3f
                a = 2f * b
            }

            fun addTo(path: Path, vx: Float, vy: Float, ux: Float, uy: Float, wx: Float, wy: Float) {
                fun x(i: Float, j: Float) = vx + ux * i + wx * j
                fun y(i: Float, j: Float) = vy + uy * i + wy * j
                if (r <= 0f) { path.lineTo(vx, vy); return }
                path.lineTo(x(-p, 0f), y(-p, 0f))
                path.cubicTo(x(-p + a, 0f), y(-p + a, 0f), x(-p + a + b, 0f), y(-p + a + b, 0f), x(-p + a + b + c, d), y(-p + a + b + c, d))
                val f0 = -Math.PI.toFloat() / 4f - arc / 2f
                val f1 = -Math.PI.toFloat() / 4f + arc / 2f
                val k = 4f / 3f * Math.tan(arc / 4.0).toFloat() * r
                val e0x = -r + r * Math.cos(f0.toDouble()).toFloat(); val e0y = r + r * Math.sin(f0.toDouble()).toFloat()
                val e1x = -r + r * Math.cos(f1.toDouble()).toFloat(); val e1y = r + r * Math.sin(f1.toDouble()).toFloat()
                path.cubicTo(
                    x(e0x - k * Math.sin(f0.toDouble()).toFloat(), e0y + k * Math.cos(f0.toDouble()).toFloat()), y(e0x - k * Math.sin(f0.toDouble()).toFloat(), e0y + k * Math.cos(f0.toDouble()).toFloat()),
                    x(e1x + k * Math.sin(f1.toDouble()).toFloat(), e1y - k * Math.cos(f1.toDouble()).toFloat()), y(e1x + k * Math.sin(f1.toDouble()).toFloat(), e1y - k * Math.cos(f1.toDouble()).toFloat()),
                    x(e1x, e1y), y(e1x, e1y),
                )
                path.cubicTo(x(0f, p - a - b), y(0f, p - a - b), x(0f, p - a), y(0f, p - a), x(0f, p), y(0f, p))
            }
        }
    }

    private class Material(
        view: View,
        private val radius: Float,
        private val captured: Frost?,
        private val video: Boolean,
        private val forceDark: Boolean?,
        private val dense: Boolean,
        private val border: Boolean = true,
        private val faint: Boolean = false,
        private val alphaBoost: Int = 0,
        private val bottomCorners: Boolean = true,
        private val homeGlass: Boolean = false,
        private val screenLeft: Int? = null,
        private val screenTop: Int? = null
    ) : Drawable() {
        private var sampled: Bitmap? = null
        private var sampledShader: BitmapShader? = null
        private val placement = Matrix()
        private val target = WeakReference(view)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val point = IntArray(2)
        private var isSelected = false
        private val path = Path()
        private val edgePath = Path()
        private val edgeRect = RectF()
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var geometryDirty = true
        private var geometryScale = -1f

        override fun onBoundsChange(bounds: Rect) { geometryDirty = true }

        private fun geometry(scale: Float) {
            if (!geometryDirty && geometryScale == scale) return
            geometryDirty = false; geometryScale = scale
            rect.set(bounds)
            val corner = radius * scale
            path.set(SmoothCorners.smoothRect(rect, corner, if (bottomCorners) corner else 0f))
            val rimWidth = (if(homeGlass) 2.4f else if (isSelected) 1.8f else 1.2f) * scale
            rimPaint.strokeWidth = rimWidth
            rimPaint.color = if(homeGlass) Color.WHITE else Color.argb(if (isSelected) 178 else 56, 255, 255, 255)
            if(homeGlass)rimPaint.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
                intArrayOf(0x20ffffff,0x1affffff,0x0fffffff,0x16ffffff),
                floatArrayOf(0f,.35f,.7f,1f),Shader.TileMode.CLAMP)
            edgeRect.set(rect); edgeRect.inset(rimWidth / 2f, rimWidth / 2f)
            val edgeCorner = (corner - rimWidth / 2f).coerceAtLeast(0f)
            edgePath.set(SmoothCorners.smoothRect(edgeRect, edgeCorner, if (bottomCorners) edgeCorner else 0f))
        }

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val sel = state.any { it == android.R.attr.state_focused || it == android.R.attr.state_pressed || it == android.R.attr.state_selected }
            if (sel != isSelected) {
                isSelected = sel
                geometryDirty = true
                invalidateSelf()
                return true
            }
            return false
        }

        private var bakedGlass:Bitmap?=null
        private var bakedSource:Bitmap?=null
        private var bakedX=0
        private var bakedY=0

        private fun drawHomeGlass(canvas:Canvas,view:View){
            val source=try{wallpaper(view.context)}catch(_:Exception){staticFrost(view.context)}
            view.getLocationOnScreen(point)
            val x=(screenLeft ?: (point[0]+(view.width*(view.scaleX-1f)/2f-view.translationX).toInt()))-source.left
            val y=(screenTop ?: (point[1]+(view.height*(view.scaleY-1f)/2f-view.translationY).toInt()))-source.top
            val w=bounds.width()
            val h=bounds.height()
            val cached=bakedGlass
            if(cached==null||cached.width!=w||cached.height!=h||bakedSource!==source.bitmap||kotlin.math.abs(bakedX-x)>1||kotlin.math.abs(bakedY-y)>1){
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                val target=Canvas(bitmap)
                target.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
                val saved=target.save()
                target.clipPath(path)
                val crop=RectF(rect.left-x,rect.top-y,rect.left-x+source.width,rect.top-y+source.height)
                paint.shader=null
                paint.alpha=255
                paint.colorFilter=plateTintDark
                target.drawBitmap(source.bitmap,null,crop,paint)
                paint.colorFilter=null
                paint.shader=LinearGradient(0f,rect.top,0f,rect.centerY(),0x1affffff,0x00ffffff,Shader.TileMode.CLAMP)
                target.drawRect(rect,paint)
                paint.shader=LinearGradient(rect.left,0f,rect.right,0f,
                    intArrayOf(0x00ffffff,0x17ffffff,0x17ffffff,0x00ffffff),
                    floatArrayOf(0f,.30f,.70f,1f),Shader.TileMode.CLAMP)
                target.drawRect(rect,paint)
                paint.shader=null
                target.restoreToCount(saved)
                target.drawPath(edgePath,rimPaint)
                bakedGlass=bitmap
                bakedSource=source.bitmap
                bakedX=x
                bakedY=y
            }
            bakedGlass?.let{canvas.drawBitmap(it,null,bounds,paint)}
        }

        override fun draw(canvas: Canvas) {
            val view = target.get() ?: return
            val dark = forceDark ?: HomeAppearance.dark(view.context)
            val scale = NativeUi.scale(view.context)
            geometry(scale)
            if (rect.width() <= 0 || rect.height() <= 0) return

            if(homeGlass){drawHomeGlass(canvas,view);return}
            val saved = canvas.save()
            canvas.clipPath(path)

            if (video) {
                paint.shader = null
                paint.colorFilter = null
                paint.style = Paint.Style.FILL
                val smokeAlpha = if (dense) 0xd4 else if (faint) 0x90 else 0xc8
                paint.color = if (dark) {
                    Color.argb(
                        (smokeAlpha + alphaBoost).coerceIn(0, 255),
                        if (dense) 32 else 20,
                        if (dense) 36 else 24,
                        if (dense) 40 else 28
                    )
                } else {
                    Color.argb((smokeAlpha + alphaBoost).coerceIn(0, 255), 235, 240, 245)
                }
                canvas.drawRect(rect, paint)

            } else {
                val source = captured ?: try { wallpaper(view.context) } catch (_: Exception) { staticFrost(view.context) }
                view.getLocationOnScreen(point)
                if (sampled !== source.bitmap) {
                    sampled = source.bitmap
                    sampledShader = BitmapShader(source.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
                placement.setScale(source.width.toFloat() / source.bitmap.width, source.height.toFloat() / source.bitmap.height)
                placement.postTranslate((source.left - point[0]).toFloat(), (source.top - point[1]).toFloat())
                sampledShader!!.setLocalMatrix(placement)
                paint.shader = sampledShader
                paint.colorFilter = if(dark)plateTintDark else plateTintLight
                paint.alpha = 255
                canvas.drawRect(rect, paint)
                paint.shader = null
                paint.colorFilter = null

                if (dense || alphaBoost != 0) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(((if (dense) 18 else 0) + alphaBoost).coerceIn(0, 100), if (dark) 0 else 255, if (dark) 0 else 255, if (dark) 0 else 255)
                    canvas.drawRect(rect, paint)
                }
            }

            if (isSelected) {
                paint.style = Paint.Style.FILL
                paint.color = if (dark) 0x36ffffff else 0x42ffffff
                canvas.drawRect(rect, paint)
            }

            canvas.restoreToCount(saved)

            if (radius <= 0 || !border) return

            canvas.drawPath(edgePath, rimPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Legacy Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
