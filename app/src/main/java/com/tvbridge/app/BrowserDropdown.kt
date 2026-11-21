package com.tvbridge.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

internal object BrowserChromeStyle {
    const val TEXT_SP = 13f
    const val RADIUS_DP = 18f
    fun background(view: View) = GlassUi.surface(view, RADIUS_DP, video = true, dark = true, faint = true)
}

internal object BrowserDropdown {
    class Row(
        val label: String,
        val value: String? = null,
        val checked: Boolean = false,
        val indent: Boolean = false,
        val footer: Boolean = false,
        val keepOpen: Boolean = false,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
        val onClick: () -> Unit
    )

    private var window: PopupWindow? = null
    private var owner: View? = null
    private var panelView: View? = null
    private var contentScroll: ScrollView? = null
    private var frameView: android.widget.FrameLayout? = null
    private var dotView: View? = null
    private var dotSizePx = 0
    private var dotPos: Pair<Float, Float>? = null
    private var selfDismissing = false
    private var outsideAnchor = java.lang.ref.WeakReference<View>(null)
    private var outsideAt = 0L
    var visibilityListener: ((Boolean) -> Unit)? = null

    fun isShowing(): Boolean = window?.isShowing == true
    fun isShowingFor(anchor: View): Boolean = isShowing() && owner === anchor

    fun dismiss() {
        val current = window
        window = null
        owner = null
        panelView = null
        contentScroll = null
        frameView = null
        dotView = null
        dotPos = null
        if (current != null) {
            selfDismissing = true
            current.dismiss()
            selfDismissing = false
            visibilityListener?.invoke(false)
        }
    }

    fun dispatchTap(screenX: Float, screenY: Float): Boolean {
        val panel = panelView ?: return false
        if (!isShowing()) return false
        val loc = IntArray(2)
        panel.getLocationOnScreen(loc)
        val x = screenX - loc[0]
        val y = screenY - loc[1]
        if (x < 0 || y < 0 || x >= panel.width || y >= panel.height) return false
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 40 else 0, action, x, y, 0)
            try {
                panel.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        return true
    }

    fun scrollList(dy: Float): Boolean {
        val scroll = contentScroll ?: return false
        if (!isShowing()) return false
        scroll.smoothScrollBy(0, dy.toInt())
        return true
    }

    fun moveDot(screenX: Float, screenY: Float) {
        dotPos = screenX to screenY
        applyDot()
    }

    fun hideDot() {
        dotView?.visibility = View.GONE
    }

    private fun applyDot() {
        val frame = frameView ?: return
        val dot = dotView ?: return
        val pos = dotPos ?: return
        if (frame.width == 0) {
            frame.post { applyDot() }
            return
        }
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val lp = dot.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.leftMargin = (pos.first - loc[0] - dotSizePx / 2f).toInt()
        lp.topMargin = (pos.second - loc[1] - dotSizePx / 2f).toInt()
        dot.layoutParams = lp
        dot.visibility = View.VISIBLE
    }

    fun consumeOutsideDismiss(anchor: View): Boolean {
        val hit = outsideAnchor.get() === anchor && SystemClock.uptimeMillis() - outsideAt < 500
        if (hit) {
            outsideAnchor.clear()
            outsideAt = 0L
        }
        return hit
    }

    fun armToggle(anchor: View) {
        var closing = false
        anchor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    closing = isShowingFor(anchor) || consumeOutsideDismiss(anchor)
                    if (closing) dismiss()
                    closing
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val consumed = closing
                    closing = false
                    consumed
                }
                else -> closing
            }
        }
    }

    private fun buildPanel(activity: Activity, anchor: View, rows: List<Row>, availableHeight: Int): LinearLayout {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val items = rows.filterNot { it.footer }
        val actions = rows.filter { it.footer }
        val actionGap = if (items.isNotEmpty() && actions.isNotEmpty()) dp(8) else 0
        val actionHeight = actions.size * dp(34) + actionGap
        val maxH = minOf((activity.resources.displayMetrics.heightPixels * 0.6f).toInt(), availableHeight - dp(16)).coerceAtLeast(1)
        val contentH = (items.size * dp(34)).coerceAtMost((maxH - actionHeight).coerceAtLeast(0))
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEach { row ->
            column.addView(buildRow(activity, row, ::dp), LinearLayout.LayoutParams(-1, dp(34)))
        }
        val scroll = ScrollView(activity).apply {
            addView(column)
            isVerticalScrollBarEnabled = false
            isScrollbarFadingEnabled = true
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = BrowserChromeStyle.background(anchor)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            if (items.isNotEmpty()) addView(scroll, LinearLayout.LayoutParams(-1, contentH))
            actions.forEachIndexed { index, row ->
                addView(android.widget.Button(activity).apply {
                    text = row.label
                    NativeUi.styleDialogAction(this, video = true, dark = true)
                    background = GlassUi.settingsRow(this, video = true, dark = true, heightPx = dp(34))
                    textSize = BrowserChromeStyle.TEXT_SP
                    includeFontPadding = false
                    minWidth = dp(120)
                    gravity = Gravity.CENTER
                    NativeUi.touchClicks(this)
                    setOnClickListener { if (!row.keepOpen) dismiss(); row.onClick() }
                }, LinearLayout.LayoutParams(-1, dp(34)).apply {
                    if (index == 0) topMargin = actionGap
                })
            }
            tag = contentH + actionHeight + dp(16)
        }
    }

    fun show(activity: Activity, anchor: View, widthDp: Int, rows: List<Row>, outsideTouchable: Boolean = true, showAbove: Boolean = false): Boolean {
        if (isShowingFor(anchor)) {
            dismiss()
            return false
        }
        dismiss()
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val frame = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val available = if (showAbove) location[1] - frame.top - dp(8) else frame.bottom - location[1] - anchor.height - dp(8)
        val panel = buildPanel(activity, anchor, rows, available)
        val shell = android.widget.FrameLayout(activity)
        shell.systemUiVisibility = activity.window.decorView.systemUiVisibility
        shell.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -2))
        dotSizePx = dp(36)
        val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val dot = object : View(activity) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                dotPaint.style = android.graphics.Paint.Style.FILL
                dotPaint.color = 0x80000000.toInt()
                canvas.drawCircle(cx, cy, dp(13).toFloat(), dotPaint)
                dotPaint.style = android.graphics.Paint.Style.STROKE
                dotPaint.strokeWidth = dp(2).toFloat()
                dotPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, dp(10).toFloat(), dotPaint)
                dotPaint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(cx, cy, dp(2).toFloat(), dotPaint)
            }
        }
        dot.visibility = View.GONE
        dot.isClickable = false
        dot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        shell.addView(dot, android.widget.FrameLayout.LayoutParams(dotSizePx, dotSizePx))
        val popup = PopupWindow(shell, dp(widthDp.coerceAtLeast(176)).coerceAtMost(activity.resources.displayMetrics.widthPixels - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = outsideTouchable
        popup.setOnDismissListener {
            if (window === popup) {
                if (!selfDismissing) {
                    outsideAnchor = java.lang.ref.WeakReference(owner)
                    outsideAt = SystemClock.uptimeMillis()
                }
                window = null
                owner = null
                panelView = null
                contentScroll = null
                frameView = null
                dotView = null
                dotPos = null
                visibilityListener?.invoke(false)
            }
        }
        window = popup
        owner = anchor
        panelView = panel
        contentScroll = panel.getChildAt(0) as? ScrollView
        frameView = shell
        dotView = dot
        outsideAnchor.clear()
        outsideAt = 0L
        if (showAbove) {
            val totalH = panel.tag as Int
            popup.showAsDropDown(anchor, 0, -(totalH + anchor.height + dp(4)))
        } else {
            popup.showAsDropDown(anchor, 0, dp(4))
        }
        visibilityListener?.invoke(true)
        shell.post { applyDot() }
        (panel.getChildAt(0) as? ScrollView)?.getChildAt(0)?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() }
        return true
    }

    private fun buildRow(activity: Activity, row: Row, dp: (Int) -> Int): View {
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (row.indent) 26 else 10), 0, dp(10), 0)
            isFocusable = true
            isSelected = row.checked
            background = GlassUi.settingsRow(this, video = true, dark = true, heightPx = dp(34))
        }
        val label = TextView(activity).apply {
            text = row.label
            setTextColor(if (row.footer) 0xccffffff.toInt() else Color.WHITE)
            textSize = BrowserChromeStyle.TEXT_SP
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (row.footer) gravity = Gravity.CENTER
        }
        line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        if (row.value != null) line.addView(TextView(activity).apply {
            text = row.value
            setTextColor(0xccffffff.toInt())
            textSize = BrowserChromeStyle.TEXT_SP
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, 0, 0) })
        if (row.checked) line.addView(ImageView(activity).apply {
            setImageDrawable(AppIcons.Vector("check") { Color.WHITE })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(dp(8), 0, 0, 0) })
        if (row.actionLabel != null && row.onAction != null) {
            line.addView(TextView(activity).apply {
                text = row.actionLabel
                setTextColor(0xccffffff.toInt())
                textSize = 17f
                gravity = Gravity.CENTER
                isFocusable = true
                setPadding(dp(12), 0, dp(8), 0)
                setOnClickListener { row.onAction.invoke() }
            }, LinearLayout.LayoutParams(-2, -1))
        }
        line.setOnFocusChangeListener { _, focused -> label.setTypeface(null, if (focused) Typeface.BOLD else Typeface.NORMAL) }
        line.setOnClickListener { if (!row.keepOpen) dismiss(); row.onClick() }
        return line
    }
}
