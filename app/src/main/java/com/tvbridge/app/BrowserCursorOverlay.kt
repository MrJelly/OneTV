package com.tvbridge.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ScrollView

/**
 * Virtual pointer overlay for D-pad and phone touchpad navigation inside [BrowserActivity].
 */
internal class BrowserCursorOverlay(
    private val activity: Activity,
    private val root: FrameLayout,
    private val host: Host
) : View(activity) {

    interface Host {
        val webView: WebView
        val toolbar: View
        val homepage: ScrollView
        val address: EditText
        val customVideoView: View?
        val videoBarView: View?
        fun isHomepageVisible(): Boolean
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var px = -1f
    private var py = -1f
    private var initiallyPlaced = false
    private var awake = true
    private val idle = Runnable { awake = false; invalidate() }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    private fun wake() {
        awake = true
        invalidate()
        removeCallbacks(idle)
        postDelayed(idle, 3000L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(idle)
        super.onDetachedFromWindow()
    }

    fun placeAtAddress(initialOnly: Boolean = true) {
        val address = host.address
        if ((initialOnly && initiallyPlaced) || address.width <= 0 || address.height <= 0) return
        val field = IntArray(2)
        val origin = IntArray(2)
        address.getLocationOnScreen(field)
        root.getLocationOnScreen(origin)
        px = field[0] + address.width / 2f - origin[0]
        py = field[1] + address.height / 2f - origin[1]
        initiallyPlaced = true
        wake()
        if (GlobalPointer.enabled) {
            GlobalPointer.moveTo(field[0] + address.width / 2f, field[1] + address.height / 2f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        px = if (px < 0) w / 2f else px.coerceIn(0f, w.toFloat())
        py = if (py < 0) h / 2f else py.coerceIn(0f, h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (GlobalPointer.enabled || !awake) return
        paint.style = Paint.Style.FILL
        paint.color = 0x80000000.toInt()
        canvas.drawCircle(px, py, dp(11).toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = Color.WHITE
        canvas.drawCircle(px, py, dp(8).toFloat(), paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(px, py, dp(2).toFloat(), paint)
    }

    fun move(code: Int, repeat: Int) {
        wake()
        val step = dp(if (repeat > 6) 28 else 12).toFloat()
        val dx = when (code) {
            21 -> -step
            22 -> step
            else -> 0f
        }
        val dy = when (code) {
            19 -> -step
            20 -> step
            else -> 0f
        }
        val margin = dp(14).toFloat()
        val web = host.webView
        if (host.customVideoView == null && py >= web.top && dy != 0f && (py + dy < margin || py + dy > height - margin)) {
            scroll(0f, dy * 4)
        }
        if (host.customVideoView == null && dx != 0f && (px + dx < margin || px + dx > width - margin)) {
            scroll(dx * 4, 0f)
        }
        px = (px + dx).coerceIn(margin, (width - margin).coerceAtLeast(margin))
        py = (py + dy).coerceIn(margin, (height - margin).coerceAtLeast(margin))
        invalidate()
    }

    fun moveBy(dx: Float, dy: Float) {
        wake()
        if (width <= 0 || height <= 0) {
            if (root.width <= 0 || root.height <= 0) return
            layout(0, 0, root.width, root.height)
        }
        px = (px + dx).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        py = (py + dy).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    fun scroll(dx: Float, dy: Float) {
        wake()
        if (host.isHomepageVisible()) {
            host.homepage.scrollBy(dx.toInt(), dy.toInt())
            return
        }
        if (px < 0 || py < 0) moveBy(0f, 0f)
        val web = host.webView
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val point = MotionEvent.PointerCoords().apply {
            x = (px - web.left).coerceIn(0f, (web.width - 1).coerceAtLeast(0).toFloat())
            y = (py - web.top).coerceIn(0f, (web.height - 1).coerceAtLeast(0).toFloat())
            setAxisValue(MotionEvent.AXIS_HSCROLL, -dx / dp(48).coerceAtLeast(1))
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / dp(48).coerceAtLeast(1))
        }
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(pointer), arrayOf(point), 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        try {
            web.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    fun isOver(view: View): Boolean {
        if (view.visibility != VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        val x = px + origin[0] - loc[0]
        val y = py + origin[1] - loc[1]
        return x >= 0 && y >= 0 && x < view.width && y < view.height
    }

    fun click() {
        host.videoBarView?.let { bar ->
            if (isOver(bar)) {
                dispatchRootTap(px, py)
                return
            }
        }
        host.customVideoView?.let { video ->
            val time = SystemClock.uptimeMillis()
            val origin = IntArray(2)
            video.getLocationOnScreen(origin)
            val rootOrigin = IntArray(2)
            root.getLocationOnScreen(rootOrigin)
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(
                    time,
                    time + if (action == MotionEvent.ACTION_UP) 16 else 0,
                    action,
                    px + rootOrigin[0] - origin[0],
                    py + rootOrigin[1] - origin[1],
                    0
                )
                try {
                    video.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            return
        }
        if (px < 0 || py < 0) moveBy(0f, 0f)
        val toolbar = host.toolbar
        val overToolbar = toolbar.visibility == VISIBLE &&
            px >= toolbar.left && px < toolbar.right &&
            py >= toolbar.top && py < toolbar.bottom
        if (!host.isHomepageVisible() && !overToolbar) {
            tapPage()
            return
        }
        when (val target = hitTest(root, px, py)) {
            is EditText -> {
                target.requestFocusFromTouch()
                target.selectAll()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
                return
            }
            is Button -> {
                target.requestFocusFromTouch()
                target.performClick()
                return
            }
            is ImageButton -> {
                target.requestFocusFromTouch()
                target.performClick()
                return
            }
            is WebView -> {
                tapPage()
                return
            }
            null -> Unit
            else -> if (target.performClick()) return
        }
        dispatchRootTap(px, py)
    }

    fun clickAt(x: Float, y: Float) {
        px = x
        py = y
        click()
    }

    private fun dispatchRootTap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                time,
                time + if (action == MotionEvent.ACTION_UP) 40 else 0,
                action, x, y, 0
            )
            try {
                root.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun hitTest(view: View, x: Float, y: Float): View? {
        if (view.visibility != VISIBLE || !view.isEnabled || x < 0 || y < 0 || x >= view.width || y >= view.height) {
            return null
        }
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val point = floatArrayOf(x + view.scrollX - child.left, y + view.scrollY - child.top)
                if (!child.matrix.isIdentity) {
                    val inverse = Matrix()
                    if (!child.matrix.invert(inverse)) continue
                    inverse.mapPoints(point)
                }
                hitTest(child, point[0], point[1])?.let { return it }
            }
        }
        return view.takeIf { it is EditText || it.isClickable }
    }

    private fun tapPage() {
        val web = host.webView
        web.requestFocusFromTouch()
        val time = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val point = MotionEvent.PointerCoords().apply {
            x = px - web.left
            y = py - web.top
            pressure = 1f
            size = 1f
        }
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            point.pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
            val event = MotionEvent.obtain(
                time,
                time + if (action == MotionEvent.ACTION_UP) 16 else 0,
                action, 1, arrayOf(pointer), arrayOf(point),
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try {
                web.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
