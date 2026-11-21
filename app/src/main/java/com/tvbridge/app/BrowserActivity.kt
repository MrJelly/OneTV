package com.tvbridge.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(UnstableApi::class)
class BrowserActivity : Activity() {
    companion object {
        @Volatile var active: BrowserActivity? = null
            private set

        private val DPAD_KEYS = setOf(19, 20, 21, 22, 23, 66)
        private val OK_KEYS = setOf(23, 66)
        private const val VIEWPORT_ZOOM_JS =
            "(function(){var m=document.querySelector('meta[name=\"viewport\"]');if(!m){m=document.createElement('meta');m.name='viewport';m.content='width=1280';document.head.appendChild(m)}var c=(m.content||'').split(',').filter(function(v){return !/^\\s*(minimum-scale|maximum-scale|user-scalable)\\s*=/i.test(v)});c.push('minimum-scale=0.25','maximum-scale=5','user-scalable=yes');m.content=c.join(',');})();"
    }

    private lateinit var root: FrameLayout
    @Volatile private var webInstance: WebView? = null
    private val web: WebView get() = checkNotNull(webInstance)
    private lateinit var cursor: BrowserCursorOverlay
    private lateinit var videoController: BrowserVideoController
    private lateinit var pageTools: BrowserPageTools
    private lateinit var toolbar: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var homepage: ScrollView
    private lateinit var homeContent: LinearLayout
    private lateinit var bookmarkButton: ToolbarButton
    private lateinit var playButton: ToolbarButton
    private lateinit var toolsButton: ToolbarButton
    private lateinit var historyButton: ToolbarButton

    private val sniffer = BrowserVideoSniffer()
    private val stores by lazy { BrowserStores(this) }

    private var editingAddress = false
    private var toolsCollapsed = false
    private var desktop = true
    private var destroyed = false
    private var swipeStartY = 0f
    private var swipeOnPage = false
    private var scrolledAwayFromTop = false
    private var buttonHint: PopupWindow? = null
    private var bookmarkLayoutWidth = 0
    @Volatile private var currentPageUrl = ""
    private var lastSniffedCount = 0
    private var parkedHistory: Bundle? = null
    private var parkedHistoryIndex = 0
    private var parkedHistorySize = 0
    private var pageEpoch = 0
    private var pendingManualZoom = false
    private val zoomPreferences by lazy { getSharedPreferences("browser-page-zoom", MODE_PRIVATE) }
    private var fitTask: Runnable? = null
    private var autoFullscreenTask: Runnable? = null
    private var autoFullscreenEpoch = -1
    private var observedAutoFullscreen = false
    private var unresponsiveTask:Runnable?=null
    private fun cancelUnresponsiveCheck(){unresponsiveTask?.let(root::removeCallbacks);unresponsiveTask=null}

    fun remotePlaybackState(): String {
        val state = runCatching { org.json.JSONObject(if (::videoController.isInitialized) videoController.playbackState else "{}") }.getOrElse { org.json.JSONObject() }
        return state.put("desktop", desktop).put("pageOpen", webInstance != null && ::homepage.isInitialized && homepage.visibility != View.VISIBLE).put("settings", stores.settings.snapshot())
            .put("history", stores.history.toJson())
            .put("discovery", org.json.JSONObject(if (::pageTools.isInitialized) pageTools.remoteSnapshot else "{}")).toString()
    }

    fun playDetectedVideo(done: (Boolean) -> Unit) {
        if (!::videoController.isInitialized) {
            done(false)
            return
        }
        videoController.playDetectedVideo(done)
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    private fun pageScrolled(y: Int, oldY: Int) {
        if (videoController.mode == BrowserVideoController.Mode.CUSTOM || !::homepage.isInitialized) return
        if (y >= dp(24)) {
            scrolledAwayFromTop = true
            if (y > oldY) setToolsCollapsed(true)
        } else if (y <= 0 && scrolledAwayFromTop) {
            scrolledAwayFromTop = false
            setToolsCollapsed(false)
        }
    }

    private fun setToolsCollapsed(collapsed: Boolean) {
        if (toolsCollapsed == collapsed || videoController.mode == BrowserVideoController.Mode.CUSTOM) return
        toolsCollapsed = collapsed
        toolbar.visibility = if (collapsed) View.GONE else View.VISIBLE
        updateInsets()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        observedAutoFullscreen = stores.settings.autoFullscreen()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.decorView.systemUiVisibility = 5894 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        root = FrameLayout(this).apply {
            background = GlassUi.backdrop(this)
            isFocusableInTouchMode = true
        }
        setContentView(root)
        desktop = getPreferences(0).getBoolean("desktop", true)

        cursor = BrowserCursorOverlay(this, root, object : BrowserCursorOverlay.Host {
            override val webView: WebView get() = web
            override val toolbar: View get() = this@BrowserActivity.toolbar
            override val homepage: ScrollView get() = this@BrowserActivity.homepage
            override val address: EditText get() = this@BrowserActivity.address
            override val hasCustomVideo: Boolean get() = videoController.mode == BrowserVideoController.Mode.CUSTOM
            override val videoBarView: View? get() = videoController.videoBarView
            override val videoActive: Boolean get() = videoController.isVideoActive
            override fun onVideoTap(x: Float, y: Float) = videoController.handleTouchDown(x, y)
            override fun isHomepageVisible(): Boolean = this@BrowserActivity::homepage.isInitialized && homepage.visibility == View.VISIBLE
        })
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        BrowserDropdown.visibilityListener = { showing ->
            if (showing) cursor.screenPosition()?.let { BrowserDropdown.moveDot(it.first, it.second) }
        }

        videoController = BrowserVideoController(this, root, sniffer, object : BrowserVideoController.Host {
            override val webView: WebView get() = web
            override val toolbar: View get() = this@BrowserActivity.toolbar
            override val cursor: View get() = this@BrowserActivity.cursor
            override fun isHomepageVisible(): Boolean = ::homepage.isInitialized && homepage.visibility == View.VISIBLE
            override fun isDesktopMode(): Boolean = desktop
            override fun isToolsCollapsed(): Boolean = toolsCollapsed
            override fun isDestroyed(): Boolean = destroyed
            override fun isActive(): Boolean = active === this@BrowserActivity
            override fun showTools(show: Boolean) = this@BrowserActivity.showTools(show)
            override fun onVideoAvailabilityChanged(sniffedCount: Int) = updateVideoAvailability(sniffedCount)
            override fun showPageTools(anchor: View) = pageTools.playlist(anchor)
            override fun selectPageVideo() = playDetectedVideo {}
        })
        pageTools = BrowserPageTools(this, object : BrowserPageTools.Host {
            override val page: WebView get() = web
            override fun valid() = !destroyed && webInstance != null && active === this@BrowserActivity && ::homepage.isInitialized && homepage.visibility != View.VISIBLE
            override fun playSelectedVideo() = videoController.playSelectedWebVideo()
            override fun beforePageAction() { videoController.exitVideo(); sniffer.clear(); updateVideoAvailability(0) }
            override fun pageActionFinished() { showTools(false); schedulePageScale(); autoFullscreenEpoch = -1; scheduleAutoFullscreen() }
            override fun fitPage() = fitPageWidth()
            override fun originalScale() = originalPageScale()
            override fun desktopMode() = desktop
            override fun setDesktopMode(value: Boolean) = this@BrowserActivity.setDesktopMode(value)
            override fun settingsChanged() = onBrowserSettingsChanged()
        })
        sniffer.onSniffed = { _, _, total ->
            AppThreads.main.post {
                if (!destroyed && ::videoController.isInitialized) {
                    videoController.onSniffedMedia(total)
                    scheduleAutoFullscreen()
                }
            }
        }

        buildToolbar()
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
        }
        homepage = object : ScrollView(this) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                pageScrolled(t, oldt)
            }
        }.apply {
            isFillViewport = true
            background = GlassUi.backdrop(this)
            addView(homeContent)
        }
        val insertIndex = root.indexOfChild(toolbar).coerceAtLeast(0)
        root.addView(homepage, insertIndex, FrameLayout.LayoutParams(-1, -1))
        homeContent.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left - homeContent.paddingLeft - homeContent.paddingRight
            if (width > 0 && width != bookmarkLayoutWidth) renderBookmarks()
        }
        cursor.bringToFront()
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateInsets()
            cursor.placeAtAddress()
        }
        address.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cursor.placeAtAddress() }

        if (state?.getBundle("one.parkedHistory") != null) {
            parkedHistory = state.getBundle("one.parkedHistory")
            parkedHistoryIndex = state.getInt("one.parkedHistoryIndex")
            parkedHistorySize = state.getInt("one.parkedHistorySize")
            showHomepage()
        } else if (state != null && restorePageState(state)) {
            homepage.visibility = View.GONE
            currentPageUrl = web.url.orEmpty()
            updateBookmarkButton()
            showTools(false)
        } else {
            showHomepage()
        }
        if (state == null) intent.getStringExtra("address")?.let(::navigate)
    }

    private fun updateVideoAvailability(sniffedCount: Int) {
        lastSniffedCount = sniffedCount
        val onPage = ::homepage.isInitialized && homepage.visibility != View.VISIBLE
        val hasVideo = onPage && sniffedCount > 0
        if (::playButton.isInitialized) {
            playButton.isEnabled = onPage
            playButton.alpha = if (onPage) 1f else 0.35f
            playButton.contentDescription = if (hasVideo) "接管播放（已发现 $sniffedCount 路视频）" else "接管网页视频"
            playButton.invalidate()
        }
    }

    private fun buildToolbar() {
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            background = null
        }
        address = object:EditText(this){
            override fun onKeyPreIme(keyCode:Int,event:KeyEvent):Boolean{
                if(keyCode==KeyEvent.KEYCODE_BACK&&hasFocus()){
                    if(event.action==KeyEvent.ACTION_UP&&!event.isCanceled){
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0)
                        editingAddress=false;clearFocus();root.requestFocus()
                    }
                    return true
                }
                return super.onKeyPreIme(keyCode,event)
            }
        }.apply {
            setSingleLine()
            hint = "网址或关键词 · ${BrowserNavigation.engines[stores.settings.engine()]}"
            textSize = BrowserChromeStyle.TEXT_SP
            minWidth = 0
            minimumWidth = 0
            setHorizontallyScrolling(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(0xffe0e5ea.toInt())
            setSelectAllOnFocus(true)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        addButton(toolbar, "返回") { finish() }
        val addressGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = 0
            background = BrowserChromeStyle.background(this)
        }
        toolbar.addView(addressGroup, LinearLayout.LayoutParams(0, dp(40), 1f))
        addressGroup.addView(address, LinearLayout.LayoutParams(0, dp(40), 1f))
        val search = addButton(addressGroup, "打开") { navigate(address.text.toString()) }
        search.layoutParams = LinearLayout.LayoutParams(dp(36), dp(40))
        search.setPadding(dp(8), dp(6), dp(8), dp(6))
        search.background = ColorDrawable(Color.TRANSPARENT)
        address.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) editingAddress = true
            addressGroup.isActivated = focused || search.hasFocus()
        }
        search.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            addressGroup.isActivated = focused || address.hasFocus()
        }
        addButton(toolbar, "主页") { showHomepage() }
        playButton = addButton(toolbar, "接管播放") {
            if (homepage.visibility != View.VISIBLE) {
                playDetectedVideo { ok ->
                    if (!ok) NativeUi.notice(this, "当前网页未检测到可播放视频")
                }
            }
        }.apply {
            isEnabled = false
            alpha = 0.35f
            setOnLongClickListener {
                pageTools.menu(toolsButton)
                true
            }
        }
        bookmarkButton = addButton(toolbar, "收藏网页") { toggleBookmark() }.apply {
            isEnabled = false
            alpha = 0.35f
        }
        addButton(toolbar, "后退") {
            navigateHistory(-1)
        }
        addButton(toolbar, "前进") {
            navigateHistory(1)
        }
        addButton(toolbar, "刷新") {
            if (homepage.visibility != View.VISIBLE) {
                web.reload()
                showTools(false)
            }
        }
        historyButton = addButton(toolbar, "历史") { showHistory() }
        toolsButton = addButton(toolbar, "网页工具") { pageTools.menu(toolsButton) }
        BrowserDropdown.armToggle(historyButton)
        BrowserDropdown.armToggle(toolsButton)
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(8), dp(6), dp(8), 0) })
    }

    private fun disposePage() {
        cancelUnresponsiveCheck()
        val old = webInstance ?: return
        webInstance = null
        old.webChromeClient = null
        old.webViewClient = WebViewClient()
        old.visibility = View.GONE
        root.removeView(old)
        runCatching { old.stopLoading(); old.settings.javaScriptEnabled = false; old.onPause() }
        old.destroy()
    }

    private fun restorePageState(state: Bundle): Boolean {
        if (webInstance == null) createPage()
        return web.restoreState(state) != null
    }

    private fun createPage() {
        disposePage()
        webInstance = object : WebView(this) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                pageScrolled(t, oldt)
            }
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        root.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }
        setAgent()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view === webInstance && ::homepage.isInitialized && homepage.visibility != View.VISIBLE) {
                    pageEpoch++
                    pageTools.onNavigation()
                    cancelPageScale()
                    cancelAutoFullscreen()
                    videoController.onNavigationStarted()
                    sniffer.clear()
                    currentPageUrl = url
                    updateBookmarkButton()
                    if (url.startsWith("http")) {
                        view.evaluateJavascript(BrowserVideoSniffer.INJECT_SNIFF_JS, null)
                    }
                }
            }

            @android.annotation.TargetApi(21)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in listOf("http", "https")

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                Uri.parse(url).scheme !in listOf("http", "https")

            @android.annotation.TargetApi(21)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame && view === webInstance) {
                    sniffer.record(
                        url = request.url.toString(),
                        requestHeaders = request.requestHeaders ?: emptyMap(),
                        expectedGeneration = sniffer.generation,
                        nowMs = SystemClock.uptimeMillis()
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                if (view === webInstance) {
                    sniffer.record(
                        url = url,
                        requestHeaders = emptyMap(),
                        expectedGeneration = sniffer.generation,
                        nowMs = SystemClock.uptimeMillis()
                    )
                }
                return super.shouldInterceptRequest(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (view === webInstance && homepage.visibility != View.VISIBLE) {
                    if(sniffer.clearForUrlChange(currentPageUrl,url)){
                        pageTools.dismiss()
                        videoController.onSpaUrlChanged()
                        updateVideoAvailability(0)
                    }
                    currentPageUrl = url
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view === webInstance && homepage.visibility != View.VISIBLE) {
                    currentPageUrl = url
                    updateBookmarkButton()
                }
                if (view === webInstance && homepage.visibility != View.VISIBLE && url != "about:blank" && !editingAddress) {
                    address.setText(url)
                }
                if (view === webInstance && url.startsWith("http")) {
                    if (homepage.visibility != View.VISIBLE) stores.history.record(url, view.title.orEmpty())
                    view.evaluateJavascript(BrowserVideoSniffer.INJECT_SNIFF_JS, null)
                    view.evaluateJavascript(VIEWPORT_ZOOM_JS, null)
                    if (view.url == url) schedulePageScale()
                    if (view.url == url) scheduleAutoFullscreen()
                }
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                if (view === webInstance && pendingManualZoom) {
                    pendingManualZoom = false
                    zoomPreferences.edit().putString(zoomKey("mode"), "custom")
                        .putFloat(zoomKey("scale"), newScale / resources.displayMetrics.density).apply()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== webInstance) return true
                NativeUi.notice(this@BrowserActivity, "网页已停止，请重新打开")
                showHomepage(forceReset = true)
                return true
            }
        }
        web.setOnTouchListener { _, event ->
            if (event.pointerCount > 1) { cancelPageScale(); pendingManualZoom = true }
            if (event.actionMasked == MotionEvent.ACTION_UP) videoController.notePageGesture()
            false
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                if (view !== webInstance) return
                progress.progress = value
                progress.visibility = if (value >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) =
                videoController.onShowCustomView(view, callback)

            override fun onHideCustomView() = videoController.onHideCustomView()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            web.setWebViewRenderProcessClient(object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    if (view !== webInstance || destroyed) return
                    if(unresponsiveTask!=null)return
                    unresponsiveTask=Runnable{
                        unresponsiveTask=null
                        if(view===webInstance&&!destroyed&&active===this@BrowserActivity){
                            if(renderer?.terminate()!=true)showHomepage(forceReset=true)
                        }
                    }.also{root.postDelayed(it,12_000)}
                }
                override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {if(view===webInstance)cancelUnresponsiveCheck()}
            })
        }
    }

    private fun showHomepage(forceReset: Boolean = false) {
        pageTools.dismiss()
        cancelPageScale()
        cancelAutoFullscreen()
        if (webInstance != null) videoController.exitVideo()
        if (forceReset) {
            parkedHistory = null
        } else if (webInstance != null && ::homepage.isInitialized && homepage.visibility != View.VISIBLE) {
            val state = Bundle()
            val history = runCatching { web.saveState(state) }.getOrNull()
            parkedHistory = state.takeIf { history != null }
            parkedHistoryIndex = history?.currentIndex ?: 0
            parkedHistorySize = history?.size ?: 0
            sniffer.clear()
            updateVideoAvailability(0)
        }
        disposePage()
        homepage.visibility = View.VISIBLE
        currentPageUrl = ""
        renderBookmarks()
        updateBookmarkButton()
        progress.visibility = View.GONE
        scrolledAwayFromTop = false
        editingAddress = false
        showTools(true)
        address.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun navigateHistory(offset: Int): Boolean {
        parkedHistory?.let { state ->
            if (parkedHistoryIndex + offset !in 0 until parkedHistorySize) return false
            parkedHistory = null
            if (!restorePageState(state)) return false
        }
        if (webInstance == null) return false
        if (!web.canGoBackOrForward(offset)) return false
        videoController.exitVideo()
        homepage.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.onResume()
        web.goBackOrForward(offset)
        showTools(false)
        return true
    }

    private fun updateBookmarkButton() {
        if (!::bookmarkButton.isInitialized) return
        val uri = Uri.parse(currentPageUrl)
        val usable = ::homepage.isInitialized && homepage.visibility != View.VISIBLE &&
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty()
        bookmarkButton.isEnabled = usable
        bookmarkButton.alpha = if (usable) 1f else 0.35f
        bookmarkButton.isSelected = usable && stores.bookmarks.contains(currentPageUrl)
        bookmarkButton.isActivated = bookmarkButton.isSelected
        bookmarkButton.contentDescription = if (bookmarkButton.isSelected) "取消收藏网页" else "收藏网页"
        bookmarkButton.invalidate()
    }

    private fun toggleBookmark() {
        if (!bookmarkButton.isEnabled || homepage.visibility == View.VISIBLE) return
        val title = web.title.orEmpty().ifBlank { Uri.parse(currentPageUrl).host.orEmpty() }
        val result = stores.bookmarks.toggle(currentPageUrl, title)
        updateBookmarkButton()
        val message = when (result) {
            BrowserBookmarks.Result.ADDED -> "已收藏网页"
            BrowserBookmarks.Result.REMOVED -> "已取消收藏"
            BrowserBookmarks.Result.FULL -> "最多收藏 100 个网页"
            else -> return
        }
        NativeUi.notice(this, message)
    }

    private fun showHistory() {
        if (BrowserDropdown.consumeOutsideDismiss(historyButton)) return
        if (BrowserDropdown.isShowingFor(historyButton)) { BrowserDropdown.dismiss(); return }
        val entries = stores.history.all()
        if (entries.isEmpty()) { NativeUi.notice(this, "暂无历史记录"); return }
        val rows = entries.take(50).map { entry ->
            BrowserDropdown.Row(
                entry.title.ifBlank { entry.url },
                actionLabel = "×",
                onAction = { stores.history.remove(entry.url); BrowserDropdown.dismiss(); showHistory() }
            ) { navigate(entry.url) }
        } + BrowserDropdown.Row("清空全部", footer = true) { stores.history.clear(); NativeUi.notice(this, "已清空历史记录") }
        BrowserDropdown.show(this, historyButton, 176, rows)
    }

    private fun renderBookmarks() {
        homeContent.removeAllViews()
        val available = (homeContent.width - homeContent.paddingLeft - homeContent.paddingRight)
            .takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(48))
        bookmarkLayoutWidth = available
        val columns = (available / dp(216)).coerceAtLeast(1)
        val cardWidth = minOf(dp(248), (available - dp(12) * (columns - 1)) / columns)
        stores.bookmarks.all().chunked(columns).forEach { items ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            homeContent.addView(row, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(12) })
            items.forEachIndexed { index, item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GlassUi.surface(this, faint = true, plain = true)
                    setPadding(dp(16), 0, dp(4), 0)
                }
                val label = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    contentDescription = "${item.title}，${item.url}"
                    setOnClickListener { navigate(item.url) }
                    NativeUi.touchClicks(this)
                }
                label.addView(TextView(this).apply {
                    text = item.title
                    textSize = 16f
                    setTextColor(GlassUi.ink(this@BrowserActivity))
                    setTypeface(null, Typeface.BOLD)
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2))
                label.addView(TextView(this).apply {
                    text = Uri.parse(item.url).host.orEmpty().removePrefix("www.")
                    textSize = 13f
                    setTextColor(GlassUi.ink(this@BrowserActivity))
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                card.addView(label, LinearLayout.LayoutParams(0, -1, 1f))
                val remove = Button(this).apply {
                    text = "×"
                    textSize = 22f
                    contentDescription = "删除收藏：${item.title}"
                    isFocusable = false
                    isFocusableInTouchMode = false
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    if (Build.VERSION.SDK_INT >= 21) {
                        backgroundTintList = null
                        stateListAnimator = null
                    }
                    setPadding(0, 0, 0, 0)
                    setTextColor(GlassUi.ink(this@BrowserActivity))
                    background = GlassUi.surface(this, GlassUi.CIRCLE_RADIUS, faint = true)
                    setOnClickListener {
                        stores.bookmarks.remove(item.url)
                        renderBookmarks()
                        NativeUi.notice(this@BrowserActivity, "已删除收藏")
                    }
                    NativeUi.touchClicks(this)
                }
                card.addView(remove, LinearLayout.LayoutParams(dp(40), dp(44)))
                row.addView(card, LinearLayout.LayoutParams(cardWidth, -1).apply { if (index > 0) leftMargin = dp(12) })
            }
        }
    }

    private fun setAgent() {
        if (webInstance == null) return
        val actual = WebSettings.getDefaultUserAgent(this)
        val engine = Regex("Chrome/[0-9.]+").find(actual)?.value ?: "Chrome/83.0.4103.120"
        web.settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $engine Safari/537.36"
        } else {
            actual
        }
    }

    private fun setDesktopMode(value: Boolean) {
        desktop = value
        getPreferences(0).edit().putBoolean("desktop", desktop).apply()
        setAgent()
        if (homepage.visibility != View.VISIBLE) {
            web.reload()
            showTools(false)
        }
    }

    private fun zoomKey(suffix: String) = "${Uri.parse(web.url.orEmpty()).host.orEmpty()}:$suffix"

    private fun cancelPageScale() { fitTask?.let(root::removeCallbacks); fitTask = null; pendingManualZoom = false }

    private fun schedulePageScale() {
        cancelPageScale()
        val epoch = pageEpoch
        fitTask = Runnable {
            if (destroyed || active !== this || epoch != pageEpoch || homepage.visibility == View.VISIBLE || videoController.isVideoActive) return@Runnable
            when (zoomPreferences.getString(zoomKey("mode"), "fit")) {
                "custom" -> setPageScale(zoomPreferences.getFloat(zoomKey("scale"), 1f))
                "original" -> setPageScale(1f)
                else -> fitPageWidth(false)
            }
        }.also { root.postDelayed(it, 500L) }
    }

    @Suppress("DEPRECATION")
    private fun setPageScale(relative: Float) {
        val factor = (resources.displayMetrics.density * relative / web.scale).coerceIn(0.1f, 10f)
        if (Build.VERSION.SDK_INT >= 21) web.zoomBy(factor)
        else if (factor > 1.05f) web.zoomIn() else if (factor < .95f) web.zoomOut()
    }

    private fun originalPageScale() {
        if (webInstance == null) return
        cancelPageScale()
        zoomPreferences.edit().putString(zoomKey("mode"), "original").apply()
        setPageScale(1f)
    }

    private fun fitPageWidth(remember: Boolean = true) {
        if (homepage.visibility == View.VISIBLE || videoController.isVideoActive) return
        cancelPageScale()
        if (remember) zoomPreferences.edit().putString(zoomKey("mode"), "fit").apply()
        val page = web; val epoch = pageEpoch; val url = page.url
        page.evaluateJavascript("(function(){var d=document.documentElement,b=document.body,q=window.visualViewport;return [Math.max(d.clientWidth,d.scrollWidth,b?b.scrollWidth:0),q?q.width:innerWidth];})()") { result ->
            if (destroyed || active !== this || webInstance !== page || page.url != url || epoch != pageEpoch || videoController.isVideoActive) return@evaluateJavascript
            val values = runCatching { org.json.JSONArray(result) }.getOrNull() ?: return@evaluateJavascript
            val width = values.optDouble(0); val visible = values.optDouble(1)
            if (width > visible + 4 && visible > 0 && Build.VERSION.SDK_INT >= 21) web.zoomBy((visible / width).toFloat().coerceIn(.1f, 1f))
        }
    }

    fun onBrowserSettingsChanged() {
        if (::address.isInitialized) address.hint = "网址或关键词 · ${BrowserNavigation.engines[stores.settings.engine()]}"
        val enabled = stores.settings.autoFullscreen()
        if (enabled == observedAutoFullscreen) return
        observedAutoFullscreen = enabled
        cancelAutoFullscreen()
        autoFullscreenEpoch = -1
        scheduleAutoFullscreen()
    }

    private fun cancelAutoFullscreen() { autoFullscreenTask?.let(root::removeCallbacks); autoFullscreenTask = null }

    private fun scheduleAutoFullscreen(attempt: Int = 0) {
        if (!stores.settings.autoFullscreen() || autoFullscreenEpoch == pageEpoch || webInstance == null ||
            active !== this || homepage.visibility == View.VISIBLE || videoController.isBusyOrActive) return
        cancelAutoFullscreen()
        val epoch = pageEpoch
        autoFullscreenTask = Runnable {
            if (epoch != pageEpoch || active !== this || webInstance == null || !stores.settings.autoFullscreen() || videoController.isBusyOrActive) return@Runnable
            pageTools.autoVideoProbe { found ->
                if (epoch != pageEpoch) return@autoVideoProbe
                if (found) autoFullscreenEpoch = epoch else scheduleAutoFullscreen((attempt + 1).coerceAtMost(5))
            }
        }.also { root.postDelayed(it, if (attempt == 0) 800L else if (attempt < 5) 1500L else 2500L) }
    }

    private inner class ToolbarButton(key: String) : ImageButton(this@BrowserActivity) {
        var iconKey = key
            set(value) {
                field = value
                setImageDrawable(AppIcons.Vector(value) { if (isSelected) 0xffb17b35.toInt() else Color.WHITE })
            }

        init {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isFocusable = false
            isFocusableInTouchMode = false
            minimumWidth = 0
            minimumHeight = 0
            NativeUi.touchClicks(this)
            if (Build.VERSION.SDK_INT >= 21) {
                backgroundTintList = null
                stateListAnimator = null
            }
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            background = BrowserChromeStyle.background(this)
            setImageDrawable(AppIcons.Vector(key) { if (isSelected) 0xffb17b35.toInt() else Color.WHITE })
        }
    }

    private fun showButtonHint(anchor: View, title: String) {
        buttonHint?.dismiss()
        if (!anchor.isShown || isFinishing) return
        val label = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GlassUi.surface(this, 12f, video = true, dark = true, dense = true)
        }
        buttonHint = PopupWindow(label, -2, -2, false).apply {
            isTouchable = false
            showAsDropDown(anchor, 0, dp(4))
        }
        label.postDelayed({ buttonHint?.takeIf { it.contentView === label }?.dismiss() }, 1000L)
    }

    private fun addButton(row: LinearLayout, title: String, action: () -> Unit): ToolbarButton =
        ToolbarButton(
            when (title) {
                "返回" -> "exit"
                "打开" -> "search"
                "主页" -> "home"
                "接管播放" -> "play"
                "收藏网页" -> "star"
                "后退" -> "history-back"
                "前进" -> "history-forward"
                "刷新" -> "refresh"
                "缩小" -> "minus"
                "放大" -> "plus"
                "网页工具" -> "list"
                "历史" -> "history"
                else -> "collapse"
            }
        ).apply {
            contentDescription = title
            if (Build.VERSION.SDK_INT >= 26) tooltipText = null
            setOnLongClickListener { showButtonHint(this, title); true }
            setOnClickListener { action() }
            row.addView(this, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }

    private fun navigate(value: String) {
        val candidate = BrowserNavigation.resolve(value, stores.settings.engine())
        if (candidate == null) { NativeUi.notice(this, "请输入有效的网址或搜索关键词"); return }
        val uri = Uri.parse(candidate)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()) {
            NativeUi.notice(this, "请输入有效的网址")
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        if (webInstance == null) createPage()
        videoController.exitVideo()
        parkedHistory = null
        sniffer.clear()
        updateVideoAvailability(0)
        web.stopLoading()
        editingAddress = false
        address.setText(candidate)
        homepage.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.onResume()
        currentPageUrl = candidate
        updateBookmarkButton()
        web.loadUrl(candidate)
        showTools(false)
    }

    private fun showTools(show: Boolean) {
        val homeVisible = ::homepage.isInitialized && homepage.visibility == View.VISIBLE
        if (show) toolsCollapsed = false
        toolbar.visibility = if (!videoController.isVideoActive && !toolsCollapsed) View.VISIBLE else View.GONE
        updateVideoAvailability(if (homeVisible) 0 else lastSniffedCount)
        updateInsets()
        cursor.visibility = if (GlobalPointer.enabled) View.GONE else View.VISIBLE
        cursor.bringToFront()
        if (show) {
            if (!editingAddress) address.setText(webInstance?.url.orEmpty())
            root.requestFocus()
        } else if (!homeVisible) {
            web.requestFocus()
        }
    }

    private fun updateInsets() {
        for (view in listOfNotNull(webInstance, if (::homepage.isInitialized) homepage else null)) {
            val params = view.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin != 0) {
                params.topMargin = 0
                view.layoutParams = params
            }
        }
        if (::homeContent.isInitialized) {
            val top = (toolbar.bottom.takeIf { it > 0 } ?: dp(46)) + dp(20)
            if (homeContent.paddingTop != top) homeContent.setPadding(dp(24), top, dp(24), dp(28))
        }
    }

    private var swallowVideoTouch = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            videoController.handleTouchDown(event.rawX, event.rawY)
            swallowVideoTouch = false
            if (videoController.isVideoActive) {
                val bar = videoController.videoBarView
                val rect = android.graphics.Rect()
                val overBar = bar != null && bar.visibility == View.VISIBLE && bar.getGlobalVisibleRect(rect) && rect.contains(event.rawX.toInt(), event.rawY.toInt())
                if (!overBar) swallowVideoTouch = true
            }
            swipeStartY = event.y
            swipeOnPage = !videoController.isVideoActive && (toolsCollapsed || event.y > toolbar.bottom)
            if (swallowVideoTouch) return true
        } else if (swallowVideoTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowVideoTouch = false
            return true
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && swipeOnPage) {
            if (event.y - swipeStartY < -dp(20)) {
                setToolsCollapsed(true)
                swipeOnPage = false
            } else if (event.y - swipeStartY > dp(20) && swipeStartY < dp(48)) {
                setToolsCollapsed(false)
                swipeOnPage = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event:KeyEvent):Boolean = dispatchBrowserKey(event)
    private fun dispatchBrowserKey(event: KeyEvent): Boolean {
        if (videoController.handleKeyEvent(event)) return true
        if(event.keyCode in DPAD_KEYS&&address.hasFocus())return super.dispatchKeyEvent(event)
        if (videoController.isVideoActive && event.keyCode in DPAD_KEYS) {
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (toolsCollapsed) showTools(true) else {
                    setToolsCollapsed(true)
                    showTools(false)
                }
            }
            return true
        }
        if (event.keyCode in DPAD_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode !in OK_KEYS || event.repeatCount == 0)) {
                if (event.keyCode in OK_KEYS) {
                    if (GlobalPointer.enabled) {
                        val point = GlobalPointer.position()
                        pointerClick(point.first, point.second)
                    } else {
                        cursor.visibility = View.VISIBLE
                        cursor.bringToFront()
                        cursor.click()
                    }
                } else if (GlobalPointer.enabled) {
                    val step = if (event.repeatCount > 6) 28f else 12f
                    GlobalPointer.move(
                        if (event.keyCode == 21) -step else if (event.keyCode == 22) step else 0f,
                        if (event.keyCode == 19) -step else if (event.keyCode == 20) step else 0f
                    )
                } else {
                    cursor.visibility = View.VISIBLE
                    cursor.bringToFront()
                    cursor.move(event.keyCode, event.repeatCount)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (videoController.handleBackPressed()) return
        when {
            homepage.visibility == View.VISIBLE -> finish()
            web.canGoBack() -> web.goBack()
            else -> showHomepage()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        if (parkedHistory != null) {
            state.putBundle("one.parkedHistory", parkedHistory)
            state.putInt("one.parkedHistoryIndex", parkedHistoryIndex)
            state.putInt("one.parkedHistorySize", parkedHistorySize)
        } else if (webInstance != null && ::homepage.isInitialized && homepage.visibility != View.VISIBLE) {
            runCatching { web.saveState(state) }
        }
    }

    fun globalPointerChanged(enabled: Boolean) {
        cursor.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled) cursor.placeAtAddress(false)
    }

    fun pointerClick(x: Int, y: Int) {
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        cursor.clickAt((x - origin[0]).toFloat(), (y - origin[1]).toFloat())
    }

    internal fun returnToOneHome(): Boolean {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("show_home", true)
        )
        return true
    }

    fun remoteCommand(command: String, value: String = ""): Boolean {
        if (command.startsWith("browserTouch") && GlobalPointer.enabled) GlobalPointer.hide()
        if (command == "browserFullscreen" && !videoController.isVideoActive) { playDetectedVideo {}; return true }
        videoController.handleRemoteCommand(command, value)?.let { return it }
        when (command) {
            "browserInspect" -> { pageTools.inspect(); return true }
            "browserClearSelection" -> { pageTools.clearRemote(); return true }
            "browserChooseVideo", "browserChooseItem" -> {
                val data = runCatching { org.json.JSONObject(value) }.getOrNull() ?: return false
                return pageTools.selectRemote(data.optInt("id", -1), data.optString("label"), command == "browserChooseVideo", data.optInt("revision", -1))
            }
            "browserPageTools" -> { pageTools.menu(toolsButton); return true }
            "browserPlay" -> { playDetectedVideo {}; return true }
            "browserHistoryDelete" -> { stores.history.remove(value); return true }
            "browserHistoryClear" -> { stores.history.clear(); return true }
            "browserPlaylist" -> { pageTools.playlist(toolsButton); return true }
            "browserFit" -> { fitPageWidth(); return true }
            "browserOriginal" -> { originalPageScale(); return true }
            "browserTouchMove" -> return remoteCommand("touchMove", value)
            "browserTouchScroll" -> return remoteCommand("touchScroll", value)
            "browserTouchClick" -> return remoteCommand("touchClick")
            "browserDesktopUa" -> {
                setDesktopMode(!desktop)
                NativeUi.notice(this, if (desktop) "已切换到电脑版" else "已切换到手机版")
                return true
            }
            "browserNavigate" -> {
                val url = BrowserNavigation.resolve(value, stores.settings.engine()) ?: return false
                navigate(url)
                return true
            }
            "browserBookmark" -> {
                if (homepage.visibility == View.VISIBLE) return false
                toggleBookmark()
                return true
            }
            "browserBack" -> {
                if (videoController.isBusyOrActive) {
                    videoController.exitVideoBar()
                    return true
                }
                return navigateHistory(-1)
            }
            "browserForward" -> {
                return navigateHistory(1)
            }
            "browserReload" -> {
                if (homepage.visibility == View.VISIBLE) return false
                videoController.exitVideo()
                web.reload()
                return true
            }
            "browserHome" -> {
                showHomepage()
                return true
            }
        }
        if (command == "inputText") {
            if (!RemoteText.valid(value)) return false
            if (RemoteText.insert(currentFocus, value)) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
                return true
            }
            if (homepage.visibility == View.VISIBLE || !web.hasFocus() || !web.onCheckIsTextEditor()) return false
            val connection = web.onCreateInputConnection(EditorInfo()) ?: return false
            connection.beginBatchEdit()
            return try {
                connection.finishComposingText()
                connection.commitText(value, 1)
            } finally {
                connection.endBatchEdit()
            }
        }
        if (command == "touchMove" || command == "touchScroll") {
            val parts = value.split(',')
            if (parts.size != 2) return false
            val dx = parts[0].toFloatOrNull() ?: return false
            val dy = parts[1].toFloatOrNull() ?: return false
            if (!dx.isFinite() || !dy.isFinite()) return false
            if (videoController.mode == BrowserVideoController.Mode.CUSTOM) return false
            if (command == "touchMove") {
                if (!GlobalPointer.enabled) {
                    cursor.visibility = View.VISIBLE
                    cursor.bringToFront()
                }
                cursor.moveBy(dp(1) * dx.coerceIn(-240f, 240f), dp(1) * dy.coerceIn(-240f, 240f))
            } else if (homepage.visibility == View.VISIBLE) {
                homepage.scrollBy((dp(1) * dx.coerceIn(-600f, 600f)).toInt(), (dp(1) * dy.coerceIn(-600f, 600f)).toInt())
            } else {
                cursor.scroll(dp(1) * dx.coerceIn(-600f, 600f), dp(1) * dy.coerceIn(-600f, 600f))
            }
            return true
        }
        if (command == "touchClick") {
            if (videoController.mode == BrowserVideoController.Mode.CUSTOM) {
                val bar = videoController.videoBarView
                if (bar != null && bar.visibility == View.VISIBLE && cursor.isOver(bar)) {
                    cursor.click()
                    return true
                }
                return remoteCommand("ok")
            }
            cursor.click()
            return true
        }
        val code = when (command) {
            "up" -> 19
            "down" -> 20
            "left" -> 21
            "right" -> 22
            "ok" -> 23
            "menu" -> 82
            "back" -> 4
            else -> return false
        }
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    override fun onPause() {
        cancelUnresponsiveCheck()
        pageTools.dismiss()
        cancelPageScale()
        cancelAutoFullscreen()
        buttonHint?.dismiss()
        if (active === this) active = null
        if (::videoController.isInitialized) videoController.onActivityPause()
        webInstance?.onPause()
        super.onPause()
    }

    override fun onStop(){if(::videoController.isInitialized)videoController.onActivityStop();super.onStop()}

    override fun onResume() {
        super.onResume()
        active = this
        if (webInstance != null && homepage.visibility != View.VISIBLE) web.onResume()
        if (::videoController.isInitialized) videoController.onActivityResume()
        if (webInstance != null && homepage.visibility != View.VISIBLE) scheduleAutoFullscreen()
    }

    override fun onDestroy() {
        if (active === this) active = null
        BrowserDropdown.visibilityListener = null
        if (!destroyed) {
            pageTools.dismiss()
            cancelPageScale()
            cancelAutoFullscreen()
            destroyed = true
            if (webInstance != null && ::videoController.isInitialized) videoController.exitVideo()
            disposePage()
        }
        super.onDestroy()
    }
}
