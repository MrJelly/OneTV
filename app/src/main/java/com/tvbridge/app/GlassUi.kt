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
        val scaled = Bitmap.createScaledBitmap(b, w, h, true)
        val small = if(scaled===b)b.copy(Bitmap.Config.ARGB_8888,true) else scaled
        blurInPlace(small)
        return small
    }

    private fun blurPasses(px: IntArray, tmp: IntArray, w: Int, h: Int) {
        repeat(3) {
            pass(px, tmp, w, h, 7, horizontal = true)
            pass(tmp, px, w, h, 7, horizontal = false)
        }
    }

    private fun blurInPlace(bitmap: Bitmap) {
        val w = bitmap.width; val h = bitmap.height
        if (w < 2 || h < 2) return
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(px.size)
        blurPasses(px, tmp, w, h)
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
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

    private data class CachedWallpaperFrost(val key: String, val frost: Frost)
    @Volatile private var cachedWallpaperFrost: CachedWallpaperFrost? = null
    private val wallpaperPending=mutableSetOf<String>()

    fun prewarmWallpaperFrost(context: Context) {
        val app = context.applicationContext
        val size = app.resources.displayMetrics
        val w = size.widthPixels.coerceAtLeast(1)
        val h = size.heightPixels.coerceAtLeast(1)
        val key = "${HomeAppearance.key(app)}:$w:$h:0:0"
        if (cachedWallpaperFrost?.key == key) return
        val targetH = (h * 320f / w).toInt().coerceAtLeast(1)
        val image = HomeAppearance.backdrop(app, 320, targetH, HomeAppearance.dark(app))
        val blurred = Frost(frost(image), w, h, 0, 0)
        synchronized(this) {
            wallpaperFrost = blurred
            frostKey = key
            cachedWallpaperFrost = CachedWallpaperFrost(key, blurred)
        }
    }

    private fun wallpaper(context: Context): Frost {
        val size = context.resources.displayMetrics
        val root = activity(context)?.window?.decorView
        val w = root?.width?.takeIf { it > 0 } ?: size.widthPixels
        val h = root?.height?.takeIf { it > 0 } ?: size.heightPixels
        val point = IntArray(2)
        root?.getLocationOnScreen(point)
        val appearance=HomeAppearance.key(context)
        val key = "$appearance:$w:$h:${point[0]}:${point[1]}"
        cachedWallpaperFrost?.let { if (it.key == key) return it.frost }
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){
            if(synchronized(this){wallpaperPending.add(key)}){
                val app=context.applicationContext
                val host=WeakReference(root)
                AppThreads.onIo{
                    val result=runCatching{buildWallpaper(app,w,h,point[0],point[1])}.getOrNull()
                    AppThreads.main.post{
                        synchronized(this){wallpaperPending.remove(key)}
                        if(result!=null&&HomeAppearance.key(app)==appearance){
                            synchronized(this){wallpaperFrost=result;frostKey=key;cachedWallpaperFrost=CachedWallpaperFrost(key,result)}
                            synchronized(sceneMaterials){sceneMaterials.toList()}.forEach{it.invalidateSelf()}
                            host.get()?.invalidate()
                        }
                    }
                }
            }
            return cachedWallpaperFrost?.frost?:staticFrost(context)
        }
        val blurred=buildWallpaper(context,w,h,point[0],point[1])
        synchronized(this) {
            if (key == frostKey && wallpaperFrost != null) return wallpaperFrost!!
            wallpaperFrost = blurred
            frostKey = key
            cachedWallpaperFrost = CachedWallpaperFrost(key, blurred)
            return blurred
        }
    }

    private fun buildWallpaper(context:Context,w:Int,h:Int,left:Int,top:Int):Frost{
        val targetH = (h * 320f / w).toInt().coerceAtLeast(1)
        val image = HomeAppearance.backdrop(context, 320, targetH, HomeAppearance.dark(context))
        return Frost(frost(image),w,h,left,top)
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

    private var sceneFrostCache: Frost? = null
    @Volatile private var sceneCaptureGen = 0
    @Volatile private var sceneCaptureRunning = false
    private var sceneBitmap: Bitmap? = null
    private var scenePx: IntArray? = null
    private var sceneTmp: IntArray? = null
    private val sceneMaterials = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Material, Boolean>())

    private fun registerSceneMaterial(material: Material) {
        synchronized(sceneMaterials) { sceneMaterials.add(material) }
    }

    fun invalidateScene() {
        synchronized(this) { sceneFrostCache = null; sceneCaptureGen++ }
    }

    @Synchronized
    fun trimMemory() {
        sceneCaptureGen++
        sceneFrostCache = null
        detailFrostCache = null
        detailFrostKey = ""
        cachedWallpaperFrost = null
        wallpaperFrost = null
        frostKey = ""
        if (!sceneCaptureRunning) {
            sceneBitmap = null
            scenePx = null
            sceneTmp = null
        }
    }

    @Synchronized
    fun sceneFrost(context: Context): Frost {
        sceneFrostCache?.let { return it }
        return try { wallpaper(context) } catch (_: Exception) { staticFrost(context) }
    }

    fun prepareScene(context: Context) {
        val root = activity(context)?.window?.decorView ?: return
        val w = root.width.takeIf { it > 0 } ?: return
        val h = root.height.takeIf { it > 0 } ?: return
        synchronized(this) {
            if (sceneCaptureRunning) return
            sceneCaptureRunning = true
        }
        val gen = sceneCaptureGen
        val bw = (w / 10).coerceAtLeast(1); val bh = (h / 10).coerceAtLeast(1)
        val point = IntArray(2)
        try {
            val bitmap = sceneBitmap?.takeIf { it.width == bw && it.height == bh && !it.isRecycled }
                ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { sceneBitmap = it }
            root.getLocationOnScreen(point)
            if(android.os.Build.VERSION.SDK_INT>=26){
                val window=activity(context)!!.window
                AppThreads.onIo{
                    try{
                        android.view.PixelCopy.request(window,bitmap,{result->
                            if(result==android.view.PixelCopy.SUCCESS)blurSceneCapture(bitmap,w,h,point,gen)
                            else synchronized(this){sceneCaptureRunning=false}
                        },AppThreads.main)
                    }catch(_:Throwable){synchronized(this){sceneCaptureRunning=false}}
                }
            }else{
                val canvas=android.graphics.Canvas(bitmap)
                canvas.scale(bw.toFloat()/w,bh.toFloat()/h)
                root.draw(canvas)
                blurSceneCapture(bitmap,w,h,point,gen)
            }
        } catch (_: Throwable) {
            synchronized(this) { sceneCaptureRunning = false }
            return
        }
    }

    private fun blurSceneCapture(bitmap:Bitmap,w:Int,h:Int,point:IntArray,gen:Int){
        val bw=bitmap.width;val bh=bitmap.height
        try {
            AppThreads.io.execute {
                val frost = try {
                    val px=scenePx?.takeIf{it.size==bw*bh}?:IntArray(bw*bh).also{scenePx=it}
                    bitmap.getPixels(px,0,bw,0,0,bw,bh)
                    check(SceneFrame.hasContent(px))
                    val tmp = sceneTmp?.takeIf { it.size == px.size } ?: IntArray(px.size).also { sceneTmp = it }
                    blurPasses(px, tmp, bw, bh)
                    val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    out.setPixels(px, 0, bw, 0, 0, bw, bh)
                    Frost(out, w, h, point[0], point[1])
                } catch (_: Throwable) {
                    null
                }
                AppThreads.main.post {
                    synchronized(this@GlassUi) { sceneCaptureRunning = false }
                    if (frost != null && gen == sceneCaptureGen) {
                        synchronized(this@GlassUi) { sceneFrostCache = frost }
                        val targets = synchronized(sceneMaterials) { sceneMaterials.toList() }
                        targets.forEach { it.invalidateSelf() }
                    }
                }
            }
        } catch (_: Throwable) {
            synchronized(this) { sceneCaptureRunning = false }
        }
    }

    private var detailFrostCache: Frost? = null
    private var detailFrostKey = ""

    private fun detailKey(detail: View): String {
        val loc = IntArray(2); detail.getLocationOnScreen(loc)
        return "${HomeAppearance.key(detail.context)}:${loc[0]},${loc[1]}:${detail.width}x${detail.height}"
    }

    fun detailFrost(detail: View): Frost? {
        if (detail.width <= 0 || detail.height <= 0) return null
        val key = detailKey(detail)
        return synchronized(this) { detailFrostCache?.takeIf { detailFrostKey == key } }
    }

    @Suppress("UNUSED_PARAMETER")
    fun prepareDetailRegion(detail: View, qrBox: View?) {
        if (!detail.isAttachedToWindow) return
        val decor = activity(detail.context)?.window?.decorView ?: return
        val w = detail.width; val h = detail.height
        if (w <= 0 || h <= 0) return
        val loc = IntArray(2); detail.getLocationOnScreen(loc)
        val key = detailKey(detail)
        val bw = (w / 10).coerceAtLeast(1); val bh = (h / 10).coerceAtLeast(1)
        val rootW=decor.width.coerceAtLeast(1);val rootH=decor.height.coerceAtLeast(1)
        val origin=IntArray(2);decor.getLocationOnScreen(origin)
        val app=detail.context.applicationContext
        AppThreads.io.execute {
            val frost=runCatching{
            val wallpaper=HomeAppearance.backdrop(app,320,(rootH*320f/rootW).toInt().coerceAtLeast(1),HomeAppearance.dark(app))
            val bitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap)
            canvas.scale(bw.toFloat()/w,bh.toFloat()/h)
            canvas.translate(-(loc[0]-origin[0]).toFloat(),-(loc[1]-origin[1]).toFloat())
            canvas.drawBitmap(wallpaper,null,Rect(0,0,rootW,rootH),Paint(Paint.FILTER_BITMAP_FLAG))
            val px=IntArray(bw*bh);bitmap.getPixels(px,0,bw,0,0,bw,bh)
            val tmp = IntArray(px.size)
            blurPasses(px, tmp, bw, bh)
            val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, bw, 0, 0, bw, bh)
            Frost(out, w, h, loc[0], loc[1])
            }.getOrNull()?:return@execute
            AppThreads.main.post {
                if(detail.isAttachedToWindow&&detailKey(detail)==key){
                    synchronized(this@GlassUi) { detailFrostCache = frost; detailFrostKey = key }
                    synchronized(sceneMaterials){sceneMaterials.toList()}.forEach{it.invalidateSelf()}
                }
            }
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
            HomeAppearance.drawBackdrop(view.context, canvas, bounds.width(), bounds.height(), HomeAppearance.dark(view.context), paint){view.invalidate()}
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
        bottomCorners: Boolean = true,
        scene: Boolean = false,
        plain: Boolean = false,
        marked: Boolean = false,
        detail: View? = null
    ): Drawable = Material(view, radius, frost, video, dark, dense, border, faint, alphaBoost, bottomCorners, false, null, null, scene, plain, marked,detail?.let{WeakReference(it)})

    fun homeGlass(view:View,radius:Float,screenLeft:Int?=null,screenTop:Int?=null):Drawable =
        Material(view,radius,null,false,true,false,true,false,0,true,true,screenLeft,screenTop)

    fun sceneGlass(view:View,radius:Float,screenLeft:Int?=null,screenTop:Int?=null):Drawable =
        Material(view,radius,null,false,true,false,true,false,0,true,true,screenLeft,screenTop,true)

    fun settingsRow(view:View,video:Boolean=video(view.context),dark:Boolean?=null,heightPx:Int=NativeUi.settingsRowHeight(view.context)):Drawable =
        surface(view,24f*heightPx/NativeUi.settingsRowHeight(view.context),video=video,dark=dark,faint=true,plain=true)

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
        private val screenTop: Int? = null,
        private val scene: Boolean = false,
        private val plain: Boolean = false,
        private val marked: Boolean = false,
        private val detail: WeakReference<View>? = null
    ) : Drawable() {
        private var sampled: Bitmap? = null
        private var sampledShader: BitmapShader? = null
        private val placement = Matrix()
        private val target = WeakReference(view)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val point = IntArray(2)
        private var isSelected = false
        private fun selected() = marked || isSelected
        private val path = Path()
        private val edgePath = Path()
        private val edgeRect = RectF()
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var geometryDirty = true
        private var geometryScale = -1f
        private var topHighlight: Shader? = null
        private var bottomHighlight: Shader? = null

        init { registerSceneMaterial(this) }

        override fun onBoundsChange(bounds: Rect) { geometryDirty = true }

        private fun geometry(scale: Float) {
            if (!geometryDirty && geometryScale == scale) return
            geometryDirty = false; geometryScale = scale
            rect.set(bounds)
            val corner = radius * scale
            path.set(SmoothCorners.smoothRect(rect, corner, if (bottomCorners) corner else 0f))
            val rimWidth = (if(homeGlass) 2.4f else if (scene) 2.0f else if (selected() && !plain) 2.2f else if (selected()) 1.8f else 1.2f) * scale
            rimPaint.strokeWidth = rimWidth
            if (plain) {
                rimPaint.shader = null
                rimPaint.color = Color.argb(if (selected()) 178 else 56, 255, 255, 255)
                topHighlight = null
                bottomHighlight = null
            } else {
                rimPaint.color = Color.WHITE
                rimPaint.shader = if (selected()) LinearGradient(rect.left,rect.top,rect.left,rect.bottom,
                    intArrayOf(0xf2ffffff.toInt(),0x99ffffff.toInt(),0x99ffffff.toInt(),0xf2ffffff.toInt()),
                    floatArrayOf(0f,.4f,.6f,1f),Shader.TileMode.CLAMP)
                else LinearGradient(rect.left,rect.top,rect.left,rect.bottom,
                    intArrayOf(0x8cffffff.toInt(),0x14ffffff,0x14ffffff,0x57ffffff),
                    floatArrayOf(0f,.4f,.6f,1f),Shader.TileMode.CLAMP)
                if (rect.width() > 0 && rect.height() > 0) {
                    topHighlight = LinearGradient(0f, rect.top, 0f, rect.centerY(), 0x1affffff, 0x00ffffff, Shader.TileMode.CLAMP)
                    bottomHighlight = LinearGradient(0f, rect.bottom, 0f, rect.top+rect.height()*0.55f, 0x12ffffff, 0x00ffffff, Shader.TileMode.CLAMP)
                }
            }
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

        private fun drawHomeGlass(canvas:Canvas,view:View){
            val source=try{if(scene)sceneFrost(view.context) else wallpaper(view.context)}catch(_:Exception){staticFrost(view.context)}
            view.getLocationOnScreen(point)
            val x=(screenLeft ?: (point[0]+(view.width*(view.scaleX-1f)/2f-view.translationX).toInt()))-source.left
            val y=(screenTop ?: (point[1]+(view.height*(view.scaleY-1f)/2f-view.translationY).toInt()))-source.top
            if (sampled !== source.bitmap) {
                sampled = source.bitmap
                sampledShader = BitmapShader(source.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            placement.setScale(source.width.toFloat() / source.bitmap.width, source.height.toFloat() / source.bitmap.height)
            placement.postTranslate(rect.left - x, rect.top - y)
            sampledShader?.setLocalMatrix(placement)
            paint.alpha = 255
            paint.shader = sampledShader
            paint.colorFilter = plateTintDark
            canvas.drawPath(path, paint)
            paint.colorFilter = null
            paint.shader = topHighlight
            canvas.drawPath(path, paint)
            paint.shader = bottomHighlight
            canvas.drawPath(path, paint)
            paint.shader = null
            canvas.drawPath(edgePath, rimPaint)
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
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.colorFilter = null
                val alpha = ((if (dark) 178 else 205) + (if (dense) 22 else 0) + alphaBoost).coerceIn(0, 242)
                paint.color = if (dark) Color.argb(alpha, 18, 20, 24) else Color.argb(alpha, 233, 237, 240)
                canvas.drawRect(rect, paint)
            } else {
                val source = detail?.get()?.let{detailFrost(it)} ?: captured ?: if (scene) sceneFrost(view.context) else try { wallpaper(view.context) } catch (_: Exception) { staticFrost(view.context) }
                view.getLocationOnScreen(point)
                if (sampled !== source.bitmap) {
                    sampled = source.bitmap
                    sampledShader = BitmapShader(source.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
                placement.setScale(source.width.toFloat() / source.bitmap.width, source.height.toFloat() / source.bitmap.height)
                placement.postTranslate((source.left - point[0]).toFloat(), (source.top - point[1]).toFloat())
                sampledShader!!.setLocalMatrix(placement)
                paint.shader = sampledShader
                paint.colorFilter = if (scene) null else if(dark)plateTintDark else plateTintLight
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

            if (!plain) {
                paint.style = Paint.Style.FILL
                paint.shader = topHighlight
                canvas.drawRect(rect, paint)
                paint.shader = bottomHighlight
                canvas.drawRect(rect, paint)
                paint.shader = null
            }

            if (selected()) {
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
