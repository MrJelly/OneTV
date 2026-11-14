package com.tvbridge.app

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
class BrowserActivity : Activity() {
    companion object { @Volatile var active: BrowserActivity? = null; private set }
    private lateinit var root: FrameLayout
    @Volatile private lateinit var web: WebView
    private lateinit var cursor: Cursor
    private lateinit var toolbar: LinearLayout
    private lateinit var address: EditText
    private var editingAddress=false
    private lateinit var progress: ProgressBar
    private lateinit var homepage: ScrollView
    private lateinit var homeContent: LinearLayout
    private var custom: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pagePlayer: ExoPlayer? = null
    private var videoBar: LinearLayout? = null
    private var speedRow: LinearLayout? = null
    private var speedButton: Button? = null
    private var loopButton: Button? = null
    private var barPlay: Button? = null
    private var barSeek: SeekBar? = null
    private var barTime: TextView? = null
    private var barSeeking = false
    private var barPolling = false
    private var webVideoActive = false
    private var pageGestureAt = -1000L
    private var webExpanded = false
    private var seekEditing = false
    private var webVideoPositionMs = 0L
    private var webVideoDurationMs = 0L
    private var videoSpeed = 1.0f
    private val videoSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var loopVideo = false
    private class SniffedStream(val at:Long,val headers:Map<String,String>)
    private val sniffedVideos = LinkedHashMap<String,SniffedStream>()
    private val mediaSuffix = Regex("\\.(m3u8|mpd|mp4|webm|mov|m4v|mkv|flv)$",RegexOption.IGNORE_CASE)
    private val adHosts = setOf("doubleclick.net","googlesyndication.com","google-analytics.com","googletagmanager.com","googleadservices.com","adservice.google.com","imasdk.googleapis.com","amazon-adsystem.com","adnxs.com","scorecardresearch.com")
    private val segmentWords = setOf("init","dashinit","seg","segment","chunk","frag","fragment")
    private val segmentPrefix = Regex("^(init|seg|segment|chunk|frag|fragment)\\d+$")
    private val segmentSplit = Regex("[^a-z0-9]+")
    @Volatile private var sniffGeneration = 0L
    private var videoGeneration = 0L
    private var videoDeadline: Runnable? = null
    private var webPollPending=false
    private var playbackPhase = "idle"
    private var sessionSources=emptyList<String>()
    private var sourceIndex=0
    @Volatile private var playbackState = "{}"
    fun remotePlaybackState(): String = playbackState

    private fun publishPlayback(phase:String,position:Long=0,duration:Long=0,seekable:Boolean=false) {
        playbackPhase=phase
        playbackState=org.json.JSONObject().put("phase",phase).put("title",web.title.orEmpty().take(120))
            .put("position",position).put("duration",duration).put("seekable",seekable)
            .put("speed",videoSpeed.toDouble()).put("loop",loopVideo).put("desktop",desktop)
            .put("fullscreen",custom!=null||webExpanded).put("sourceIndex",sourceIndex)
            .put("sources",org.json.JSONArray(sessionSources.indices.map(::sourceLabel))).toString()
    }

    private fun clearSniffing() = synchronized(sniffedVideos) {
        sniffGeneration++
        sniffedVideos.clear()
    }
    private fun sourceLabel(index:Int):String {
        val path=sessionSources[index].substringBefore('?').substringBefore('#')
        val type=when {
            path.endsWith(".m3u8",true)->"HLS"
            path.endsWith(".mpd",true)->"DASH"
            else->"视频"
        }
        return "$type ${index+1}"
    }
    private val barHide = Runnable { hideVideoBar() }
    private val barPoll = Runnable { pollVideoBar() }
    private var toolsCollapsed = false
    private var desktop = true
    private var destroyed = false
    private var swipeStartY=0f
    private var swipeOnPage=false
    private var scrolledAwayFromTop=false
    private var buttonHint:PopupWindow?=null
    private lateinit var bookmarkButton:ToolbarButton
    private lateinit var modeButton:ToolbarButton
    private val bookmarks by lazy{BrowserBookmarks(this)}
    @Volatile private var currentPageUrl=""
    private fun pageScrolled(y:Int,oldY:Int){
        if(custom!=null||!::homepage.isInitialized)return
        if(y>=dp(24)){
            scrolledAwayFromTop=true
            if(y>oldY)setToolsCollapsed(true)
        }else if(y<=0&&scrolledAwayFromTop){scrolledAwayFromTop=false;setToolsCollapsed(false)}
    }
    private fun setToolsCollapsed(collapsed:Boolean){
        if(toolsCollapsed==collapsed||custom!=null)return
        toolsCollapsed=collapsed
        toolbar.visibility=if(collapsed)View.GONE else View.VISIBLE
        updateInsets()
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.decorView.systemUiVisibility = 5894 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        root = FrameLayout(this).apply { background=GlassUi.backdrop(this);isFocusableInTouchMode=true }
        setContentView(root)
        desktop = getPreferences(0).getBoolean("desktop", true)
        cursor = Cursor()
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setPadding(0,0,0,0)
            background=null
        }
        address = EditText(this).apply {
            setSingleLine(); hint = "输入网址"; textSize = 13f
            minWidth=0;minimumWidth=0;setHorizontallyScrolling(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE); setHintTextColor(0xffe0e5ea.toInt());setSelectAllOnFocus(true)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        addButton(toolbar,"返回"){finish()}
        val addressGroup=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumWidth=0;background=GlassUi.surface(this,18f,video=true,dark=true,faint=true)}
        toolbar.addView(addressGroup,LinearLayout.LayoutParams(0,dp(40),1f))
        addressGroup.addView(address,LinearLayout.LayoutParams(0,dp(40),1f))
        val search=addButton(addressGroup,"打开"){navigate(address.text.toString())}
        search.layoutParams=LinearLayout.LayoutParams(dp(36),dp(40))
        search.setPadding(dp(8),dp(6),dp(8),dp(6))
        search.background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        address.onFocusChangeListener=View.OnFocusChangeListener{_,focused->if(focused)editingAddress=true;addressGroup.isActivated=focused||search.hasFocus()}
        search.onFocusChangeListener=View.OnFocusChangeListener{_,focused->addressGroup.isActivated=focused||address.hasFocus()}
        val actions = toolbar
        addButton(actions, "主页") { showHomepage() }
        bookmarkButton=addButton(actions,"收藏网页"){toggleBookmark()}
        bookmarkButton.setOnClickListener{toggleBookmark()}
        bookmarkButton.isEnabled=false;bookmarkButton.alpha=.35f
        addButton(actions, "后退") { if (web.canGoBack()) { homepage.visibility=View.GONE;web.visibility=View.VISIBLE;web.onResume();web.goBack();showTools(false) } }
        addButton(actions, "前进") { if (web.canGoForward()) { homepage.visibility=View.GONE;web.visibility=View.VISIBLE;web.onResume();web.goForward();showTools(false) } }
        addButton(actions, "刷新") { if(homepage.visibility!=View.VISIBLE){web.reload();showTools(false)} }
        addButton(actions,"缩小"){zoomPage(.8f)}
        addButton(actions,"放大"){zoomPage(1.25f)}
        modeButton=addButton(actions,if(desktop)"桌面网页"else"手机网页"){}
        modeButton.setOnClickListener {setDesktopMode(!desktop)}
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(8), dp(6), dp(8), 0) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        createPage()
        burnWebData(web)
        homeContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        homepage = object:ScrollView(this){
            override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int){super.onScrollChanged(l,t,oldl,oldt);pageScrolled(t,oldt)}
        }.apply { isFillViewport=true; background=GlassUi.backdrop(this); addView(homeContent) }
        root.addView(homepage, 2, FrameLayout.LayoutParams(-1, -1))
        homeContent.addOnLayoutChangeListener{_,left,_,right,_,_,_,_,_->
            val width=right-left-homeContent.paddingLeft-homeContent.paddingRight
            if(width>0&&width!=bookmarkLayoutWidth)renderBookmarks()
        }
        cursor.bringToFront()
        toolbar.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updateInsets();cursor.placeAtAddress()}
        address.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->cursor.placeAtAddress()}
        if (state != null && web.restoreState(state) != null) { homepage.visibility = View.GONE;currentPageUrl=web.url.orEmpty();updateBookmarkButton(); showTools(false) } else showHomepage()
        if(state==null)intent.getStringExtra("address")?.let(::navigate)
    }
    @Suppress("DEPRECATION")
    private fun burnWebData(target:WebView){
        try{target.stopLoading()}catch(_:Throwable){}
        try{target.clearHistory()}catch(_:Throwable){}
        try{target.clearFormData()}catch(_:Throwable){}
        try{target.clearCache(true)}catch(_:Throwable){}
    }
    private fun createPage(){
        if(::web.isInitialized){
            val old=web
            old.webChromeClient=null;old.webViewClient=WebViewClient()
            old.visibility=View.GONE;root.removeView(old)
            runCatching{old.stopLoading();old.settings.javaScriptEnabled=false;old.onPause()}
            old.destroy()
        }
        web = object:WebView(this){
            override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int){super.onScrollChanged(l,t,oldl,oldt);pageScrolled(t,oldt)}
        }
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true)
        root.addView(web,0, FrameLayout.LayoutParams(-1, -1))
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportZoom(true);builtInZoomControls=true;displayZoomControls=false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }
        setAgent()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?){
                if(view===web&&::homepage.isInitialized&&homepage.visibility!=View.VISIBLE){
                    exitVideo()
                    clearSniffing()
                    currentPageUrl=url
                    updateBookmarkButton()
                }
            }
            @android.annotation.TargetApi(21)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in listOf("http", "https")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = Uri.parse(url).scheme !in listOf("http", "https")
            @android.annotation.TargetApi(21)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if(!request.isForMainFrame)sniffVideoUrl(view,request.url.toString(),request.requestHeaders?:emptyMap())
                return super.shouldInterceptRequest(view,request)
            }
            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                sniffVideoUrl(view,url)
                return super.shouldInterceptRequest(view,url)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if(view===web&&homepage.visibility!=View.VISIBLE){currentPageUrl=url}
            }
            override fun onPageFinished(view: WebView, url: String) {
                if(view===web&&homepage.visibility!=View.VISIBLE){currentPageUrl=url;updateBookmarkButton()}
                if (view===web&&homepage.visibility!=View.VISIBLE&&url!="about:blank"&&!editingAddress) address.setText(url)
                if(view===web&&url.startsWith("http"))view.evaluateJavascript("""
                    (function(){var m=document.querySelector('meta[name="viewport"]');if(!m){m=document.createElement('meta');m.name='viewport';m.content='width=1280';document.head.appendChild(m)}var c=(m.content||'').split(',').filter(function(v){return !/^\s*(minimum-scale|maximum-scale|user-scalable)\s*=/i.test(v)});c.push('minimum-scale=0.25','maximum-scale=5','user-scalable=yes');m.content=c.join(',');})();
                """.trimIndent(),null)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if(view!==web)return true
                NativeUi.notice(this@BrowserActivity,"网页已停止，请重新打开")
                showHomepage(true); return true
            }
        }
        web.setOnTouchListener { _, event ->
            if(event.actionMasked==MotionEvent.ACTION_UP)pageGestureAt=SystemClock.uptimeMillis()
            false
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                if(view!==web)return
                progress.progress = value; progress.visibility = if (value >= 100) View.GONE else View.VISIBLE
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (custom != null) { callback.onCustomViewHidden(); return }
                if(SystemClock.uptimeMillis()-pageGestureAt>800){callback.onCustomViewHidden();return}
                webVideoActive=true
                showTools(false); custom = view; customCallback = callback; toolbar.visibility = View.GONE
                web.visibility = View.GONE; cursor.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(-1, -1)); view.requestFocus();cursor.visibility=if(GlobalPointer.enabled)View.GONE else View.VISIBLE;cursor.bringToFront()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showVideoBar()
            }
            override fun onHideCustomView() { exitVideo() }
        }
        if(android.os.Build.VERSION.SDK_INT>=29)web.setWebViewRenderProcessClient(object:WebViewRenderProcessClient(){
            override fun onRenderProcessUnresponsive(view:WebView,renderer:WebViewRenderProcess?){
                if(view!==web||destroyed)return
                if(renderer?.terminate()!=true)showHomepage(true)
            }
            override fun onRenderProcessResponsive(view:WebView,renderer:WebViewRenderProcess?){}
        })
    }
    private fun showHomepage(forceReset:Boolean=false) {
        exitVideo()
        if(forceReset||(::homepage.isInitialized&&homepage.visibility!=View.VISIBLE))createPage()
        web.visibility = View.GONE; homepage.visibility = View.VISIBLE
        currentPageUrl="";renderBookmarks();updateBookmarkButton()
        progress.visibility=View.GONE;scrolledAwayFromTop=false
        editingAddress=false;showTools(true); address.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(address.windowToken,0)
        address.clearFocus()
    }
    private fun updateBookmarkButton(){
        if(!::bookmarkButton.isInitialized)return
        val uri=Uri.parse(currentPageUrl)
        val usable=::homepage.isInitialized&&homepage.visibility!=View.VISIBLE&&uri.scheme in listOf("http","https")&&!uri.host.isNullOrEmpty()
        bookmarkButton.isEnabled=usable;bookmarkButton.alpha=if(usable)1f else .35f
        bookmarkButton.isSelected=usable&&bookmarks.contains(currentPageUrl);bookmarkButton.isActivated=bookmarkButton.isSelected
        bookmarkButton.contentDescription=if(bookmarkButton.isSelected)"取消收藏网页"else "收藏网页";bookmarkButton.invalidate()
    }
    private fun toggleBookmark(){
        if(!bookmarkButton.isEnabled||homepage.visibility==View.VISIBLE)return
        val title=web.title.orEmpty().ifBlank{Uri.parse(currentPageUrl).host.orEmpty()}
        val result=bookmarks.toggle(currentPageUrl,title)
        updateBookmarkButton()
        val message=when(result){BrowserBookmarks.Result.ADDED->"已收藏网页";BrowserBookmarks.Result.REMOVED->"已取消收藏";BrowserBookmarks.Result.FULL->"最多收藏 100 个网页";else->return}
        NativeUi.notice(this,message)
    }
    private var bookmarkLayoutWidth=0
    private fun renderBookmarks(){
        homeContent.removeAllViews()
        val available=(homeContent.width-homeContent.paddingLeft-homeContent.paddingRight).takeIf{it>0}
            ?: (resources.displayMetrics.widthPixels-dp(48))
        bookmarkLayoutWidth=available
        val columns=(available/dp(216)).coerceAtLeast(1)
        val cardWidth=minOf(dp(248),(available-dp(12)*(columns-1))/columns)
        bookmarks.all().chunked(columns).forEach{items->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP}
            homeContent.addView(row,LinearLayout.LayoutParams(-1,dp(76)).apply{bottomMargin=dp(12)})
            items.forEachIndexed{index,item->
                val card=LinearLayout(this).apply{
                    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                    background=GlassUi.surface(this,faint=true)
                    setPadding(dp(16),0,dp(4),0)
                }
                val label=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL
                    contentDescription=item.title+"，"+item.url
                    setOnClickListener{navigate(item.url)};NativeUi.touchClicks(this)
                }
                label.addView(TextView(this).apply{
                    text=item.title;textSize=16f;setTextColor(GlassUi.ink(this@BrowserActivity))
                    setTypeface(null,android.graphics.Typeface.BOLD);setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END
                },LinearLayout.LayoutParams(-1,-2))
                label.addView(TextView(this).apply{
                    text=Uri.parse(item.url).host.orEmpty().removePrefix("www.");textSize=13f;setTextColor(GlassUi.ink(this@BrowserActivity))
                    setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END
                },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})
                card.addView(label,LinearLayout.LayoutParams(0,-1,1f))
                val remove=Button(this).apply{
                    text="×";textSize=22f;contentDescription="删除收藏："+item.title
                    isFocusable=false;isFocusableInTouchMode=false;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
                    if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null}
                    setPadding(0,0,0,0);setTextColor(GlassUi.ink(this@BrowserActivity));background=GlassUi.surface(this,GlassUi.CIRCLE_RADIUS,faint=true)
                    setOnClickListener{bookmarks.remove(item.url);renderBookmarks();NativeUi.notice(this@BrowserActivity,"已删除收藏")}
                    NativeUi.touchClicks(this)
                }
                card.addView(remove,LinearLayout.LayoutParams(dp(40),dp(44)))
                row.addView(card,LinearLayout.LayoutParams(cardWidth,-1).apply{if(index>0)leftMargin=dp(12)})
            }
        }
    }
    private fun setAgent() {
        val actual=WebSettings.getDefaultUserAgent(this)
        val engine=Regex("Chrome/[0-9.]+").find(actual)?.value?:"Chrome/83.0.4103.120"
        web.settings.userAgentString = if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $engine Safari/537.36" else actual
    }
    private fun setDesktopMode(value: Boolean) {
        desktop=value
        getPreferences(0).edit().putBoolean("desktop",desktop).apply()
        if(::modeButton.isInitialized){
            modeButton.contentDescription=if(desktop)"桌面网页" else "手机网页"
            modeButton.iconKey=if(desktop)"monitor" else "phone"
            modeButton.invalidate()
        }
        setAgent()
        if(homepage.visibility!=View.VISIBLE){
            web.reload()
            showTools(false)
        }
    }
    private fun zoomPage(factor:Float){
        if(homepage.visibility==View.VISIBLE)return
        web.evaluateJavascript("(function(){var z=Math.max(.35,Math.min(3,(window.__onePageZoom||1)*$factor));window.__onePageZoom=z;document.documentElement.style.zoom=String(z)})()",null)
    }
    private inner class ToolbarButton(key:String):ImageButton(this@BrowserActivity){
        var iconKey=key
            set(value){field=value;setImageDrawable(AppIcons.Vector(value){if(isSelected)0xffb17b35.toInt()else Color.WHITE})}
        init{
            scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(11),dp(11),dp(11),dp(11))
            isFocusable=false;isFocusableInTouchMode=false;minimumWidth=0;minimumHeight=0
            NativeUi.touchClicks(this)
            if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null}
            if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
            background=GlassUi.surface(this,18f,video=true,dark=true,faint=true)
            setImageDrawable(AppIcons.Vector(key){if(isSelected)0xffb17b35.toInt()else Color.WHITE})
        }
    }
    private fun showButtonHint(anchor:View,title:String){
        buttonHint?.dismiss()
        if(!anchor.isShown||isFinishing)return
        val label=TextView(this).apply{text=title;setTextColor(Color.WHITE);textSize=13f;setPadding(dp(10),dp(5),dp(10),dp(5));background=GlassUi.surface(this,12f,dense=true)}
        buttonHint=PopupWindow(label,-2,-2,false).apply{isTouchable=false;showAsDropDown(anchor,0,dp(4))}
        label.postDelayed({buttonHint?.takeIf{it.contentView===label}?.dismiss()},1000)
    }
    private fun addButton(row:LinearLayout,title:String,action:()->Unit):ToolbarButton=ToolbarButton(when(title){"返回"->"exit";"打开"->"search";"主页"->"home";"收藏网页"->"star";"后退"->"history-back";"前进"->"history-forward";"刷新"->"refresh";"缩小"->"minus";"放大"->"plus";"桌面网页"->"monitor";"手机网页"->"phone";else->"collapse"}).apply{
        contentDescription=title;if(android.os.Build.VERSION.SDK_INT>=26)tooltipText=null
        setOnLongClickListener{showButtonHint(this,title);true}
        setOnClickListener{action();showButtonHint(this,title)};row.addView(this,LinearLayout.LayoutParams(dp(40),dp(40)).apply{setMargins(dp(2),0,dp(2),0)})
    }
    private fun navigate(value: String) {
        val candidate = if (value.contains("://")) value.trim() else "https://${value.trim()}"
        val uri = Uri.parse(candidate)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()) {
            NativeUi.notice(this,"请输入有效的网址"); return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        webVideoActive=false;hideVideoBar()
        clearSniffing()
        web.stopLoading();editingAddress=false;address.setText(candidate)
        homepage.visibility = View.GONE; web.visibility = View.VISIBLE; web.onResume()
        currentPageUrl=candidate;updateBookmarkButton()
        web.loadUrl(candidate); showTools(false)
    }
    private fun showTools(show: Boolean) {
        val homeVisible = ::homepage.isInitialized && homepage.visibility == View.VISIBLE
        if(show)toolsCollapsed=false
        toolbar.visibility = if(custom==null&&!webExpanded&&!toolsCollapsed)View.VISIBLE else View.GONE
        updateInsets()
        cursor.visibility = if(GlobalPointer.enabled)View.GONE else View.VISIBLE;cursor.bringToFront()
        if (show) { if(!editingAddress)address.setText(web.url.orEmpty());root.requestFocus() } else if(!homeVisible)web.requestFocus()
    }
    private fun updateInsets(){
        for(view in listOfNotNull(if(::web.isInitialized)web else null,if(::homepage.isInitialized)homepage else null)){
            val params=view.layoutParams as FrameLayout.LayoutParams
            if(params.topMargin!=0){params.topMargin=0;view.layoutParams=params}
        }
        if(::homeContent.isInitialized){
            val top=(toolbar.bottom.takeIf{it>0}?:dp(46))+dp(20)
            if(homeContent.paddingTop!=top)homeContent.setPadding(dp(24),top,dp(24),dp(28))
        }
    }
    private fun exitVideo() {
        videoGeneration++
        clearVideoDeadline()
        webPollPending=false
        pageGestureAt=-1000L
        if(webExpanded)web.evaluateJavascript(restoreVideoJs,null)
        webExpanded=false
        webVideoActive=false;hideVideoBar()
        sessionSources=emptyList()
        sourceIndex=0
        pagePlayer?.release()
        pagePlayer=null
        stopBarPoll()
        custom?.let { root.removeView(it) }; custom = null
        publishPlayback("idle")
        val callback = customCallback; customCallback = null; callback?.onCustomViewHidden()
        web.visibility = View.VISIBLE; cursor.visibility = View.VISIBLE; toolbar.visibility = if(toolsCollapsed)View.GONE else View.VISIBLE; web.requestFocus()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun isAdHost(url:String):Boolean {
        val host=Uri.parse(url).host?.lowercase()?:return false
        return adHosts.any{host==it||host.endsWith(".$it")}
    }
    private fun isSegmentLike(path:String):Boolean {
        val name=path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        if(name.isEmpty())return false
        if(name.all{it.isDigit()})return true
        val words=name.split(segmentSplit).filter{it.isNotEmpty()}
        if(words.any{it in segmentWords||segmentPrefix.matches(it)})return true
        val last=words.lastOrNull()?:return false
        return words.size>1&&last.length>1&&last.startsWith("0")&&last.all{it.isDigit()}
    }
    private fun sniffVideoUrl(view:WebView,url:String,requestHeaders:Map<String,String> = emptyMap()) {
        val generation=sniffGeneration
        val path=url.substringBefore('?').substringBefore('#')
        if(!mediaSuffix.containsMatchIn(path)||PlaylistParser.httpUrl(url)==null)return
        if(isAdHost(url))return
        val lower=path.lowercase()
        if((lower.endsWith(".mp4")||lower.endsWith(".m4v")||lower.endsWith(".webm")||lower.endsWith(".mkv"))&&isSegmentLike(path))return
        synchronized(sniffedVideos) {
            if(view!==web||generation!=sniffGeneration)return
            sniffedVideos.remove(url)
            sniffedVideos[url]=SniffedStream(SystemClock.uptimeMillis(),HashMap(requestHeaders))
            if(sniffedVideos.size>20)sniffedVideos.remove(sniffedVideos.keys.first())
        }
    }

    private fun sniffKind(url:String):Int {
        val path=url.substringBefore('?').substringBefore('#').lowercase()
        return when{
            path.endsWith(".m3u8")->if(path.contains("master"))0 else 1
            path.endsWith(".mpd")->2
            else->3
        }
    }
    private fun sniffedCandidates():List<String> = synchronized(sniffedVideos) {
        val cutoff=SystemClock.uptimeMillis()-90_000
        sniffedVideos.entries.filter{it.value.at>=cutoff}
            .map{Triple(it.key,sniffKind(it.key),it.value.at)}
            .sortedWith(compareBy<Triple<String,Int,Long>>{it.second}.thenBy{row->
                if(row.second==0)-row.third else row.third
            })
            .map{it.first}
    }
    private fun sniffedHeaders(url:String):Map<String,String> = synchronized(sniffedVideos) {
        sniffedVideos[url]?.headers?:emptyMap()
    }
    private fun originOf(url:String):String? {
        val uri=runCatching{java.net.URI(url)}.getOrNull()?:return null
        val scheme=uri.scheme?.lowercase()?:return null
        if(scheme!="http"&&scheme!="https")return null
        val host=uri.host?.lowercase()?:return null
        val defaultPort=if(scheme=="https")443 else 80
        val port=if(uri.port==-1||uri.port==defaultPort)"" else ":${uri.port}"
        return "$scheme://$host$port"
    }
    private fun playbackHeaders(url:String):Map<String,String> {
        val sniffed=sniffedHeaders(url)
        fun header(name:String):String? = sniffed.entries.firstOrNull{it.key.equals(name,true)}?.value?.ifEmpty{null}
        val sniffedReferer=header("Referer")
        val origin=header("Origin")?:sniffedReferer?.let{originOf(it)}?.takeIf{it!=originOf(url)}
        val referer=sniffedReferer?:web.url?.ifEmpty{null}
        return buildMap{
            referer?.let{put("Referer",it)}
            origin?.let{put("Origin",it)}
        }
    }

    private fun clearVideoDeadline() {
        videoDeadline?.let(root::removeCallbacks)
        videoDeadline=null
    }

    fun playDetectedVideo(done:(Boolean)->Unit) {
        if(playbackPhase=="searching"||playbackPhase=="loading"){done(true);return}
        pagePlayer?.let{player->
            if(player.playWhenReady)player.pause()else player.play()
            showVideoBar()
            done(true)
            return
        }
        if(custom!=null||webVideoActive){
            web.evaluateJavascript(jsVideo("if(v.paused){v.play();}else{v.pause();}return true;"),null)
            showVideoBar()
            done(true)
            return
        }
        if(homepage.visibility==View.VISIBLE){done(false);return}
        val page=web
        val address=page.url
        val generation=++videoGeneration
        publishPlayback("searching")
        done(true)
        if(Uri.parse(address.orEmpty()).host.equals("pan.quark.cn",ignoreCase=true)){
            fallbackWebVideo{if(!it)publishPlayback("failed")}
            return
        }
        videoDeadline=Runnable{
            if(videoGeneration==generation&&playbackPhase=="searching"){
                videoGeneration++
                publishPlayback("failed")
            }
        }.also{root.postDelayed(it,3500)}
        page.evaluateJavascript("""
            (function(){
              var videos=document.querySelectorAll('video');
              var fallback='';
              for(var i=0;i<videos.length;i++){
                var url=videos[i].currentSrc||videos[i].src;
                if(/^https?:\/\//i.test(url)){
                  if(!videos[i].paused)return [url,true];
                  if(!fallback)fallback=url;
                }
              }
              return [fallback,false];
            })()
        """.trimIndent()){result->
            if(destroyed||active!==this||page!==web||page.url!=address||custom!=null||videoGeneration!=generation)return@evaluateJavascript
            clearVideoDeadline()
            val detected=runCatching{org.json.JSONArray(result)}.getOrNull()
            val domUrl=detected?.optString(0)?.let(PlaylistParser::httpUrl)
            val activeVideo=detected?.optBoolean(1)==true
            val candidates=buildList{
                if(domUrl!=null&&activeVideo)add(domUrl)
                for(url in sniffedCandidates()){
                    if(url!=domUrl||!activeVideo)add(url)
                }
                if(domUrl!=null&&!contains(domUrl))add(domUrl)
            }
            val playable=candidates.filterNot{it.substringBefore('?').substringBefore('#').endsWith(".ts",true)}.take(3)
            if(playable.isEmpty()){fallbackWebVideo{if(!it)publishPlayback("failed")};return@evaluateJavascript}
            playNativeCandidates(playable,generation)
        }
    }
    private fun playNativeCandidates(urls:List<String>,generation:Long,startAt:Int=0) {
        videoSpeed=1f
        sessionSources=urls
        var surface:PlayerView?=null
        fun attempt(index:Int){
            if(destroyed||active!==this||videoGeneration!=generation)return
            clearVideoDeadline()
            val previous=pagePlayer
            pagePlayer=null
            surface?.player=null
            previous?.release()
            if(index>=urls.size){
                exitVideo()
                fallbackWebVideo{ok->
                    if(!ok){
                        publishPlayback("failed")
                        NativeUi.notice(this,"这个网页视频无法直接播放")
                    }
                }
                return
            }
            sourceIndex=index
            publishPlayback("loading")
            try{
                val player=NativePlayback.player(this)
                pagePlayer=player
                val view=surface?:PlayerView(this).apply{
                    setBackgroundColor(Color.BLACK)
                    setShutterBackgroundColor(Color.BLACK)
                    useController=false
                    surface=this
                    custom=this
                    root.addView(this,FrameLayout.LayoutParams(-1,-1))
                    showTools(false)
                    web.visibility=View.GONE
                    cursor.visibility=View.GONE
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                view.player=player
                var rendered=false
                player.repeatMode=if(loopVideo)Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.addListener(object:Player.Listener{
                    override fun onRenderedFirstFrame(){
                        if(pagePlayer!==player)return
                        rendered=true
                        clearVideoDeadline()
                        web.evaluateJavascript("Array.prototype.forEach.call(document.querySelectorAll('video'),function(v){v.pause();})",null)
                        showVideoBar()
                    }
                    override fun onPlaybackStateChanged(state:Int){if(rendered&&pagePlayer===player)refreshVideoBar()}
                    override fun onIsPlayingChanged(isPlaying:Boolean){if(rendered&&pagePlayer===player)refreshVideoBar()}
                    override fun onPlayerError(error:PlaybackException){
                        if(pagePlayer===player)attempt(index+1)
                    }
                })
                videoDeadline=Runnable{if(pagePlayer===player&&!rendered)attempt(index+1)}
                    .also{root.postDelayed(it,4500)}
                player.setMediaSource(NativePlayback.browserSource(this,urls[index],web.settings.userAgentString,playbackHeaders(urls[index])))
                player.prepare()
                player.play()
            }catch(_:RuntimeException){attempt(index+1)}
        }
        attempt(startAt)
    }
    private val pickVideoJs = "var videos=[];function collect(doc,depth){if(!doc||depth>3)return;" +
        "var list=doc.querySelectorAll('video');for(var i=0;i<list.length;i++)videos.push(list[i]);" +
        "var frames=doc.querySelectorAll('iframe');for(var j=0;j<frames.length;j++){try{collect(frames[j].contentDocument,depth+1);}catch(e){}}}" +
        "collect(document,0);var v=window.__oneExpandedVideo;var fe=document.fullscreenElement||document.webkitFullscreenElement;" +
        "if(!v&&fe&&fe.tagName==='VIDEO')v=fe;" +
        "if(!v){for(var i=0;i<videos.length;i++){if(!videos[i].paused){v=videos[i];break;}}}" +
        "if(!v&&videos.length>0)v=videos[0];if(!v)return null;"
    private fun jsVideo(body: String): String =
        "(function(){try{" + pickVideoJs + body + "}catch(e){return null;}})()"
    private val expandVideoJs = "if(!window.__oneExpandedVideo){" +
        "var nodes=[v];var player=v.closest&&v.closest('.video-js');if(player)nodes.push(player);" +
        "var frame=v.ownerDocument.defaultView.frameElement;" +
        "while(frame){nodes.push(frame);frame=frame.ownerDocument.defaultView.frameElement;}" +
        "window.__oneExpandedStyles=nodes.map(function(n){return [n,n.getAttribute('style')];});" +
        "nodes.forEach(function(n){n.style.cssText+=';position:fixed!important;inset:0!important;top:0!important;left:0!important;bottom:0!important;right:0!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important;margin:0!important;padding:0!important;';});" +
        "window.__oneExpandedVideo=v;}return true;"
    private val restoreVideoJs = "(function(){(window.__oneExpandedStyles||[]).forEach(function(p){if(p[1]===null)p[0].removeAttribute('style');else p[0].setAttribute('style',p[1]);});delete window.__oneExpandedStyles;delete window.__oneExpandedVideo;})()"

    private fun showExpandedVideo() {
        webExpanded=true
        webVideoActive=true
        toolbar.visibility=View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showVideoBar()
    }

    private fun fallbackWebVideo(done:(Boolean)->Unit) {
        val generation=videoGeneration
        val page=web
        val url=page.url
        publishPlayback("loading")
        videoDeadline=Runnable{
            if(videoGeneration==generation&&playbackPhase=="loading"){
                exitVideoBar()
                publishPlayback("failed")
            }
        }.also{root.postDelayed(it,4500)}
        web.evaluateJavascript(jsVideo("v.loop=$loopVideo;v.playbackRate=1;var p=v.play();if(p&&p.catch)p.catch(function(){});"+expandVideoJs)){ result ->
            if(destroyed||active!==this||page!==web||page.url!=url||generation!=videoGeneration)return@evaluateJavascript
            if(result?.trim()!="true"){clearVideoDeadline();done(false);return@evaluateJavascript}
            videoSpeed=1f
            showExpandedVideo()
            done(true)
        }
    }
    private fun videoBarSize(value:Int)=(value*2*NativeUi.scale(this)).toInt()

    private fun videoBarButton(label:String,run:()->Unit):Button {

        return Button(this).apply{
            text=label
            isAllCaps=false
            includeFontPadding=false
            minWidth=0
            minimumWidth=0
            minHeight=0
            minimumHeight=0
            if(android.os.Build.VERSION.SDK_INT>=21){backgroundTintList=null;stateListAnimator=null;elevation=0f}
            if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
            NativeUi.textSize(this,28f)
            maxLines=1
            setPadding(videoBarSize(14),0,videoBarSize(14),0)
            background=GlassUi.surface(this,24f,video=true,dark=true,faint=true)
            setTextColor(Color.WHITE)
            isFocusable=true
            isFocusableInTouchMode=true
            NativeUi.touchClicks(this)
            setOnClickListener{run();showVideoBar()}
        }
    }
    private fun createVideoBar() {
        if(videoBar!=null)return
        val bar=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            background=GlassUi.surface(this,0f,video=true,dark=true,dense=true,border=false)
            setPadding(videoBarSize(12),videoBarSize(10),videoBarSize(12),videoBarSize(12))
            visibility=View.GONE
            isFocusable=false
            isFocusableInTouchMode=false
        }
        val rewindButton=videoBarButton("快退"){seekVideoBy(-10_000)}.apply{contentDescription="快退 10 秒"}
        val playButton=videoBarButton("播放"){toggleVideoPlay()}
        val forwardButton=videoBarButton("快进"){seekVideoBy(10_000)}.apply{contentDescription="快进 10 秒"}
        val exitButton=videoBarButton("退出全屏"){exitVideoBar()}
        val barButtons=listOf(rewindButton,playButton,forwardButton,exitButton)
        val widest=barButtons.maxOf { it.paint.measureText(it.text.toString()).toInt()+1+it.paddingLeft+it.paddingRight }
        bar.addView(rewindButton,LinearLayout.LayoutParams(widest,videoBarSize(40)).apply{marginEnd=videoBarSize(8)})
        barPlay=playButton.also{
            bar.addView(it,LinearLayout.LayoutParams(widest,videoBarSize(40)).apply{marginEnd=videoBarSize(8)})
        }
        bar.addView(forwardButton,LinearLayout.LayoutParams(widest,videoBarSize(40)).apply{marginEnd=videoBarSize(8)})
        barSeek=SeekBar(this).apply{
            max=0
            progress=0
            isFocusable=true
            isFocusableInTouchMode=true
            contentDescription="播放进度，按确认调整"
            background=GlassUi.surface(this,10f,video=true,dark=true,faint=true)
            setOnFocusChangeListener{_,focused->if(!focused){seekEditing=false;isActivated=false}}
            setOnKeyListener{_,key,event->
                when(key){
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{
                        if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0){
                            seekEditing=!seekEditing
                            isActivated=seekEditing
                            if(seekEditing)NativeUi.notice(this@BrowserActivity,"左右键调整进度，再按确认结束")
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->{
                        if(event.action==KeyEvent.ACTION_DOWN){
                            val left=key==KeyEvent.KEYCODE_DPAD_LEFT
                            if(seekEditing)seekVideoBy(if(left)-10_000 else 10_000)
                            else focusSearch(if(left)View.FOCUS_LEFT else View.FOCUS_RIGHT)?.requestFocus()
                        }
                        true
                    }
                    else->false
                }
            }
            if(android.os.Build.VERSION.SDK_INT>=21){
                progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
                thumbTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList=android.content.res.ColorStateList.valueOf(0x80ffffff.toInt())
            }
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar:SeekBar,progress:Int,fromUser:Boolean){
                    if(fromUser)updateBarTime(progress.toLong(),barDurationMs())
                }
                override fun onStartTrackingTouch(seekBar:SeekBar){
                    barSeeking=true
                    showVideoBar()
                }
                override fun onStopTrackingTouch(seekBar:SeekBar){
                    barSeeking=false
                    seekVideoTo(seekBar.progress.toLong())
                    showVideoBar()
                }
            })
        }.also{
            bar.addView(it,LinearLayout.LayoutParams(0,videoBarSize(40),1f).apply{marginEnd=videoBarSize(8)})
        }
        barTime=TextView(this).apply{
            text="0:00 / --:--"
            setTextColor(Color.WHITE)
            NativeUi.textSize(this,28f)
            gravity=Gravity.CENTER_VERTICAL
            isFocusable=false
            setSingleLine()
        }.also{
            bar.addView(it,LinearLayout.LayoutParams(-2,videoBarSize(40)).apply{marginEnd=videoBarSize(8)})
        }
        bar.addView(exitButton,LinearLayout.LayoutParams(widest,videoBarSize(40)))
        fun addOption(button:Button){
            bar.addView(button,LinearLayout.LayoutParams(widest,videoBarSize(40)).apply{marginStart=videoBarSize(8)})
        }
        speedButton=videoBarButton("倍速"){toggleSpeedRow()}.also(::addOption)
        loopButton=videoBarButton("循环播放"){remoteCommand("browserLoop")}.also(::addOption)
        addOption(videoBarButton("换源"){
            if(sessionSources.size>1)remoteCommand("browserSelectSource",((sourceIndex+1)%sessionSources.size).toString())
        }.apply{tag="video-source"})
        root.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        videoBar=bar
    }
    private fun showVideoBar() {
        if(custom==null&&pagePlayer==null&&!webVideoActive)return
        createVideoBar()
        val bar=videoBar?:return
        val opening=bar.visibility!=View.VISIBLE
        bar.bringToFront()
        speedRow?.bringToFront()
        speedButton?.text=speedLabel(videoSpeed)
        loopButton?.isActivated=loopVideo
        bar.findViewWithTag<View>("video-source")?.visibility=if(sessionSources.size>1)View.VISIBLE else View.GONE
        bar.visibility=View.VISIBLE
        if(opening)barPlay?.requestFocus()
        root.removeCallbacks(barHide)
        if(speedRow==null)root.postDelayed(barHide,5000)
        startBarPoll()
        refreshVideoBar()
    }
    private fun hideVideoBar() {
        closeSpeedRow(false)
        seekEditing=false
        barSeek?.isActivated=false
        root.removeCallbacks(barHide)
        if(pagePlayer==null&&!webVideoActive)stopBarPoll()
        videoBar?.visibility=View.GONE
    }
    private fun startBarPoll() {
        if(barPolling)return
        barPolling=true
        root.removeCallbacks(barPoll)
        root.postDelayed(barPoll,500)
    }
    private fun stopBarPoll() {
        barPolling=false
        root.removeCallbacks(barPoll)
    }
    private fun pollVideoBar() {
        if(!barPolling)return
        refreshVideoBar()
        root.postDelayed(barPoll,if(videoBar?.visibility==View.VISIBLE)500 else 1000)
    }
    private fun refreshVideoBar() {
        pagePlayer?.let{player->
            val phase=when{
                player.playbackState==Player.STATE_ENDED->"ended"
                player.playbackState==Player.STATE_BUFFERING->"loading"
                player.isPlaying->"playing"
                else->"paused"
            }
            publishPlayback(phase,player.currentPosition,player.duration.coerceAtLeast(0),player.isCurrentMediaItemSeekable)
            barSeek?.isEnabled=player.isCurrentMediaItemSeekable
            barPlay?.text=if(player.isPlaying)"暂停" else "播放"
            val duration=player.duration.coerceAtLeast(0)
            val position=player.currentPosition.coerceAtLeast(0)
            updateBarProgress(position,duration)
            return
        }
        if(!webVideoActive||webPollPending)return
        val generation=videoGeneration
        webPollPending=true
        web.evaluateJavascript(jsVideo("var d=v.duration;if(!isFinite(d))d=0;return (v.paused?'1':'0')+'|'+v.currentTime+'|'+d+'|'+v.readyState;")){ result ->
            if(generation==videoGeneration)webPollPending=false
            if(destroyed||active!==this||generation!=videoGeneration||!webVideoActive)return@evaluateJavascript
            val text=runCatching{org.json.JSONTokener(result).nextValue() as? String}.getOrNull()?:return@evaluateJavascript
            val parts=text.split('|')
            if(parts.size!=4)return@evaluateJavascript
            val paused=parts[0]=="1"
            val current=parts[1].toDoubleOrNull()?:return@evaluateJavascript
            val duration=parts[2].toDoubleOrNull()?:return@evaluateJavascript
            val positionMs=(current*1000).toLong().coerceAtLeast(0)
            val durationMs=(duration*1000).toLong().coerceAtLeast(0)
            webVideoPositionMs=positionMs
            webVideoDurationMs=durationMs
            val ready=(parts[3].toIntOrNull()?:0)>=2
            if(ready&&!paused)clearVideoDeadline()
            val phase=if(playbackPhase=="loading"&&(!ready||paused))"loading" else if(paused)"paused" else "playing"
            publishPlayback(phase,positionMs,durationMs,durationMs>0)
            barSeek?.isEnabled=durationMs>0
            if(videoBar?.visibility!=View.VISIBLE)return@evaluateJavascript
            barPlay?.text=if(paused)"播放" else "暂停"
            updateBarProgress(positionMs,durationMs)
        }
    }
    private fun updateBarProgress(positionMs:Long,durationMs:Long) {
        val seek=barSeek?:return
        if(durationMs>0){
            val max=durationMs.coerceIn(0,Int.MAX_VALUE.toLong()).toInt()
            if(seek.max!=max)seek.max=max
            if(!barSeeking)seek.progress=positionMs.coerceIn(0,seek.max.toLong()).toInt()
        }else{
            if(seek.max!=0)seek.max=0
            if(!barSeeking)seek.progress=0
        }
        if(!barSeeking)updateBarTime(positionMs,durationMs)
    }
    private fun updateBarTime(positionMs:Long,durationMs:Long) {
        barTime?.text=if(durationMs>0){
            formatVideoTime(positionMs)+" / "+formatVideoTime(durationMs)
        }else{
            formatVideoTime(positionMs)+" / --:--"
        }
    }
    private fun formatVideoTime(ms:Long):String {
        val totalSeconds=(ms/1000).toInt().coerceAtLeast(0)
        val hours=totalSeconds/3600
        val minutes=(totalSeconds%3600)/60
        val seconds=totalSeconds%60
        return if(hours>0){
            "$hours:${minutes.toString().padStart(2,'0')}:${seconds.toString().padStart(2,'0')}"
        }else{
            "$minutes:${seconds.toString().padStart(2,'0')}"
        }
    }
    private fun barDurationMs():Long {
        pagePlayer?.let{return it.duration.coerceAtLeast(0)}
        return webVideoDurationMs
    }
    private fun seekVideoBy(offsetMs:Long) {
        pagePlayer?.let{player->
            val position=(player.currentPosition+offsetMs).coerceAtLeast(0)
            player.seekTo(if(player.duration>0)position.coerceAtMost(player.duration)else position)
            showVideoBar()
            return
        }
        if(homepage.visibility==View.VISIBLE)return
        val offsetSec=offsetMs/1000
        web.evaluateJavascript(jsVideo("var d=v.duration;var t=v.currentTime+($offsetSec);if(isFinite(d)){t=Math.max(0,Math.min(d,t));}else{t=Math.max(0,t);}v.currentTime=t;return true;"),null)
        showVideoBar()
    }
    private fun seekVideoTo(positionMs:Long) {
        pagePlayer?.let{player->
            player.seekTo(if(player.duration>0)positionMs.coerceIn(0,player.duration)else positionMs.coerceAtLeast(0))
            showVideoBar()
            return
        }
        if(homepage.visibility==View.VISIBLE)return
        val seconds=positionMs/1000.0
        web.evaluateJavascript(jsVideo("var d=v.duration;var t=($seconds);if(isFinite(d)){t=Math.max(0,Math.min(d,t));}else{t=Math.max(0,t);}v.currentTime=t;return true;"),null)
        showVideoBar()
    }
    private fun toggleVideoPlay() {
        pagePlayer?.let{player->
            if(player.playWhenReady){
                player.pause()
            }else{
                if(player.playbackState==Player.STATE_ENDED)player.seekTo(0)
                player.play()
            }
            showVideoBar()
            return
        }
        if(custom!=null||webVideoActive){
            web.evaluateJavascript(jsVideo("if(v.paused){v.play();}else{v.pause();}return true;"),null)
            showVideoBar()
            return
        }
        if(homepage.visibility==View.VISIBLE)return
        playDetectedVideo{}
    }
    private fun speedLabel(rate:Float)=rate.toString().removeSuffix(".0")+"×"

    private fun toggleSpeedRow() {
        if(speedRow!=null){closeSpeedRow();return}
        val bar=videoBar?:return
        val row=LinearLayout(this).apply{
            background=GlassUi.surface(this,0f,video=true,dark=true,dense=true,border=false)
            setPadding(videoBarSize(8),videoBarSize(6),videoBarSize(8),videoBarSize(6))
        }
        for(rate in videoSpeeds){
            val choice=videoBarButton(speedLabel(rate)){closeSpeedRow();setVideoSpeed(rate)}
            choice.isActivated=rate==videoSpeed
            choice.id=View.generateViewId()
            choice.nextFocusDownId=choice.id
            choice.nextFocusUpId=choice.id
            row.addView(choice,LinearLayout.LayoutParams(-2,videoBarSize(40)))
        }
        speedRow=row
        root.addView(row,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply{bottomMargin=bar.height})
        row.getChildAt(videoSpeeds.indexOf(videoSpeed).coerceAtLeast(0)).requestFocus()
    }

    private fun closeSpeedRow(restoreFocus:Boolean=true) {
        val row=speedRow?:return
        speedRow=null
        root.removeView(row)
        if(restoreFocus)speedButton?.requestFocus()
    }

    private fun setVideoSpeed(rate:Float):Boolean {
        if(rate !in videoSpeeds||homepage.visibility==View.VISIBLE)return false
        if(pagePlayer==null&&custom==null&&!webVideoActive)return false
        videoSpeed=rate
        pagePlayer?.setPlaybackSpeed(rate)
        if(pagePlayer==null)web.evaluateJavascript(jsVideo("v.playbackRate=($rate);return true;"),null)
        showVideoBar()
        return true
    }
    private fun exitVideoBar() {
        if(custom!=null||pagePlayer!=null||playbackPhase=="searching"){
            exitVideo()
            return
        }
        if(webVideoActive){
            web.evaluateJavascript(jsVideo("v.pause();return true;"),null)
            webVideoActive=false
        }
        exitVideo()
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_DOWN&&(custom!=null||pagePlayer!=null||webVideoActive)){
            showVideoBar()
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            swipeStartY=event.y
            swipeOnPage=custom==null&&(toolsCollapsed||event.y>toolbar.bottom)
        }else if(event.actionMasked==MotionEvent.ACTION_MOVE&&swipeOnPage){
            if(event.y-swipeStartY < -dp(20)){setToolsCollapsed(true);swipeOnPage=false}
            else if(event.y-swipeStartY>dp(20)&&swipeStartY<dp(48)){setToolsCollapsed(false);swipeOnPage=false}
        }
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if((custom!=null||pagePlayer!=null||webVideoActive)&&event.keyCode in listOf(19,20,21,22,23,66)){
            if(videoBar?.visibility!=View.VISIBLE){
                if(event.action==KeyEvent.ACTION_DOWN)showVideoBar()
                return true
            }
            root.removeCallbacks(barHide)
            if(speedRow==null)root.postDelayed(barHide,5000)
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if(custom!=null||pagePlayer!=null||webVideoActive){
                    if(videoBar?.visibility==View.VISIBLE)hideVideoBar()else showVideoBar()
                    return true
                }
                if(toolsCollapsed)showTools(true) else {setToolsCollapsed(true);showTools(false)}
            }; return true
        }
        if(event.keyCode in listOf(19,20,21,22,23,66)){
            if(event.action==KeyEvent.ACTION_DOWN&&(event.keyCode !in listOf(23,66)||event.repeatCount==0)){
                if(event.keyCode in listOf(23,66)){
                    if(GlobalPointer.enabled){val point=GlobalPointer.position();pointerClick(point.first,point.second)}else{cursor.visibility=View.VISIBLE;cursor.bringToFront();cursor.click()}
                }else if(GlobalPointer.enabled){
                    val step=if(event.repeatCount>6)28f else 12f
                    GlobalPointer.move(if(event.keyCode==21)-step else if(event.keyCode==22)step else 0f,if(event.keyCode==19)-step else if(event.keyCode==20)step else 0f)
                }else{cursor.visibility=View.VISIBLE;cursor.bringToFront();cursor.move(event.keyCode,event.repeatCount)}
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onBackPressed() {
        if(speedRow!=null){closeSpeedRow();showVideoBar();return}
        if(seekEditing){seekEditing=false;barSeek?.isActivated=false;showVideoBar();return}
        when {
            custom!=null||webVideoActive||playbackPhase in listOf("searching","loading")->exitVideoBar()
            homepage.visibility==View.VISIBLE->finish()
            web.canGoBack()->web.goBack()
            else->showHomepage()
        }
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
    }
    fun globalPointerChanged(enabled:Boolean){
        cursor.visibility=if(enabled)View.GONE else View.VISIBLE
        if(enabled)cursor.placeAtAddress(false)
    }
    fun pointerClick(x:Int,y:Int){val origin=IntArray(2);root.getLocationOnScreen(origin);cursor.clickAt((x-origin[0]).toFloat(),(y-origin[1]).toFloat())}
    internal fun returnToOneHome(): Boolean {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("show_home", true))
        return true
    }
    fun remoteCommand(command: String, value: String = ""): Boolean {
        if(command.startsWith("browserTouch")&&GlobalPointer.enabled)GlobalPointer.hide()
        when(command){
            "browserSelectSource"->{
                val sources=sessionSources
                val index=value.toIntOrNull()?.takeIf{it in sources.indices}?:return false
                exitVideo()
                playNativeCandidates(sources,videoGeneration,index)
                return true
            }
            "browserTouchMove"->return remoteCommand("touchMove",value)
            "browserTouchScroll"->return remoteCommand("touchScroll",value)
            "browserTouchClick"->return remoteCommand("touchClick")
            "browserStop"->{if(custom==null&&!webVideoActive&&playbackPhase=="idle")return false;exitVideoBar();return true}
            "browserSeek"->{
                val ms=value.toLongOrNull()?.takeIf{it>=0}?:return false
                if(pagePlayer?.isCurrentMediaItemSeekable!=true&&(!webVideoActive||webVideoDurationMs<=0))return false
                seekVideoTo(ms)
                return true
            }
            "browserRewind","browserFastForward"->{
                if(pagePlayer?.isCurrentMediaItemSeekable!=true&&(!webVideoActive||webVideoDurationMs<=0))return false
                val offset=if(command=="browserRewind")-10_000L else 10_000L
                seekVideoBy(offset)
                return true
            }
            "browserFullscreen"->{
                if(custom!=null||webExpanded){exitVideoBar();return true}
                if(homepage.visibility==View.VISIBLE)return false
                val generation=videoGeneration
                web.evaluateJavascript(jsVideo(expandVideoJs)){ result ->
                    if(destroyed||active!==this||generation!=videoGeneration)return@evaluateJavascript
                    if(result?.trim()=="true")showExpandedVideo()
                    else NativeUi.notice(this,"当前网页没有可全屏的视频")
                }
                return true
            }
            "browserSpeed"->{
                return setVideoSpeed(value.toFloatOrNull()?:return false)
            }
            "browserDesktopUa"->{
                setDesktopMode(!desktop)
                NativeUi.notice(this,if(desktop)"已切换到电脑版" else "已切换到手机版")
                return true
            }
            "browserLoop"->{
                loopVideo=!loopVideo
                loopButton?.isActivated=loopVideo
                pagePlayer?.let{
                    it.repeatMode=if(loopVideo)Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                }
                if(pagePlayer==null&&homepage.visibility!=View.VISIBLE){
                    val loop=if(loopVideo)"true" else "false"
                    web.evaluateJavascript(jsVideo("v.loop=$loop;return true;"),null)
                }
                NativeUi.notice(this,if(loopVideo)"已开启单曲循环" else "已关闭单曲循环")
                return true
            }
            "browserNavigate"->{val url=PlaylistParser.httpUrl(value)?:return false;exitVideo();navigate(url);return true}
            "browserBookmark"->{if(homepage.visibility==View.VISIBLE)return false;toggleBookmark();return true}
            "browserBack"->{if(custom!=null||webVideoActive||playbackPhase in listOf("searching","loading")){exitVideoBar();return true};if(!web.canGoBack())return false;homepage.visibility=View.GONE;web.visibility=View.VISIBLE;web.onResume();web.goBack();return true}
            "browserForward"->{if(!web.canGoForward())return false;exitVideo();homepage.visibility=View.GONE;web.goForward();return true}
            "browserReload"->{if(homepage.visibility==View.VISIBLE)return false;exitVideo();web.reload();return true}
            "browserHome"->{exitVideo();showHomepage();return true}
        }
        if(command=="inputText"){
            if(!RemoteText.valid(value))return false
            if(RemoteText.insert(currentFocus,value)){
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(address.windowToken,0)
                return true
            }
            if(homepage.visibility==View.VISIBLE||!web.hasFocus()||!web.onCheckIsTextEditor())return false
            val connection=web.onCreateInputConnection(android.view.inputmethod.EditorInfo())?:return false
            connection.beginBatchEdit()
            return try{connection.finishComposingText();connection.commitText(value,1)}finally{connection.endBatchEdit()}
        }
        if(command=="touchMove"||command=="touchScroll") {
            val parts=value.split(',');if(parts.size!=2)return false
            val dx=parts[0].toFloatOrNull()?:return false;val dy=parts[1].toFloatOrNull()?:return false
            if(!dx.isFinite()||!dy.isFinite())return false
            if(custom!=null)return false
            if(command=="touchMove") {
                if(!GlobalPointer.enabled){cursor.visibility=View.VISIBLE;cursor.bringToFront()}
                cursor.moveBy(dp(1)*dx.coerceIn(-240f,240f),dp(1)*dy.coerceIn(-240f,240f))
            } else if(homepage.visibility==View.VISIBLE)homepage.scrollBy((dp(1)*dx.coerceIn(-600f,600f)).toInt(),(dp(1)*dy.coerceIn(-600f,600f)).toInt())
            else cursor.scroll(dp(1)*dx.coerceIn(-600f,600f),dp(1)*dy.coerceIn(-600f,600f))
            return true
        }
        if(command=="touchClick") {
            if(custom!=null){
                val bar=videoBar
                if(bar!=null&&bar.visibility==View.VISIBLE&&cursor.isOver(bar)){cursor.click();return true}
                return remoteCommand("ok")
            }
            cursor.click();return true
        }
        val code = when (command) {
            "up" -> 19; "down" -> 20; "left" -> 21; "right" -> 22; "ok" -> 23
            "menu" -> 82; "back" -> 4; else -> return false
        }
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)); return true
    }
    override fun onPause() { buttonHint?.dismiss();hideVideoBar();if (active === this) active = null; if(pagePlayer!=null||webVideoActive||playbackPhase!="idle")exitVideoBar();web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); active = this; if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        if (active === this) active = null
        if (!destroyed && ::web.isInitialized) {
            destroyed = true; exitVideo(); root.removeView(web); burnWebData(web); web.destroy()
        }; super.onDestroy()
    }
    private inner class Cursor : View(this@BrowserActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var px = -1f; private var py = -1f
        private var initiallyPlaced=false
        private var awake=true
        private val idle=Runnable{awake=false;invalidate()}
        private fun wake(){awake=true;invalidate();removeCallbacks(idle);postDelayed(idle,3000)}
        override fun onDetachedFromWindow(){removeCallbacks(idle);super.onDetachedFromWindow()}
        fun placeAtAddress(initialOnly:Boolean=true){
            if(initialOnly&&initiallyPlaced||address.width<=0||address.height<=0)return
            val field=IntArray(2);val origin=IntArray(2)
            address.getLocationOnScreen(field);root.getLocationOnScreen(origin)
            px=field[0]+address.width/2f-origin[0];py=field[1]+address.height/2f-origin[1]
            initiallyPlaced=true;wake()
            if(GlobalPointer.enabled)GlobalPointer.moveTo(field[0]+address.width/2f,field[1]+address.height/2f)
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            px = if (px < 0) w / 2f else px.coerceIn(0f, w.toFloat())
            py = if (py < 0) h / 2f else py.coerceIn(0f, h.toFloat())
        }
        override fun onDraw(canvas: Canvas) {
            if(GlobalPointer.enabled||!awake)return
            paint.style = Paint.Style.FILL; paint.color = 0x80000000.toInt(); canvas.drawCircle(px, py, dp(11).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.WHITE
            canvas.drawCircle(px, py, dp(8).toFloat(), paint)
            paint.style = Paint.Style.FILL; canvas.drawCircle(px, py, dp(2).toFloat(), paint)
        }
        fun move(code: Int, repeat: Int) {
            wake()
            val step = dp(if (repeat > 6) 28 else 12).toFloat()
            val dx = if (code == 21) -step else if (code == 22) step else 0f
            val dy = if (code == 19) -step else if (code == 20) step else 0f
            val margin = dp(14).toFloat()
            if (custom==null&&py>=web.top&&dy != 0f && (py + dy < margin || py + dy > height - margin)) scroll(0f,dy*4)
            if (custom==null&&dx != 0f && (px + dx < margin || px + dx > width - margin)) scroll(dx*4,0f)
            px = (px + dx).coerceIn(margin, (width - margin).coerceAtLeast(margin))
            py = (py + dy).coerceIn(margin, (height - margin).coerceAtLeast(margin)); invalidate()
        }
        fun moveBy(dx:Float,dy:Float) {
            wake()
            if(width<=0||height<=0){if(root.width<=0||root.height<=0)return;layout(0,0,root.width,root.height)}
            px=(px+dx).coerceIn(0f,(width-1).toFloat());py=(py+dy).coerceIn(0f,(height-1).toFloat());invalidate()
        }
        fun scroll(dx:Float,dy:Float){
            wake()
            if(homepage.visibility==View.VISIBLE){homepage.scrollBy(dx.toInt(),dy.toInt());return}
            if(px<0||py<0)moveBy(0f,0f)
            val pointer=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_MOUSE}
            val point=MotionEvent.PointerCoords().apply{
                x=(px-web.left).coerceIn(0f,(web.width-1).coerceAtLeast(0).toFloat())
                y=(py-web.top).coerceIn(0f,(web.height-1).coerceAtLeast(0).toFloat())
                setAxisValue(MotionEvent.AXIS_HSCROLL,-dx/dp(48).coerceAtLeast(1))
                setAxisValue(MotionEvent.AXIS_VSCROLL,-dy/dp(48).coerceAtLeast(1))
            }
            val time=SystemClock.uptimeMillis()
            val event=MotionEvent.obtain(time,time,MotionEvent.ACTION_SCROLL,1,arrayOf(pointer),arrayOf(point),0,0,1f,1f,0,0,InputDevice.SOURCE_MOUSE,0)
            try{web.dispatchGenericMotionEvent(event)}finally{event.recycle()}
        }
        fun isOver(view:View):Boolean{
            if(view.visibility!=View.VISIBLE)return false
            val loc=IntArray(2)
            view.getLocationOnScreen(loc)
            val origin=IntArray(2)
            root.getLocationOnScreen(origin)
            val x=px+origin[0]-loc[0]
            val y=py+origin[1]-loc[1]
            return x>=0&&y>=0&&x<view.width&&y<view.height
        }
        fun click() {
            videoBar?.let{bar->
                if(isOver(bar)){
                    val time=SystemClock.uptimeMillis()
                    for(action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                        val event=MotionEvent.obtain(time,time+if(action==MotionEvent.ACTION_UP)40 else 0,action,px,py,0)
                        root.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    return
                }
            }
            custom?.let{video->
                val time=SystemClock.uptimeMillis()
                val origin=IntArray(2);video.getLocationOnScreen(origin);val rootOrigin=IntArray(2);root.getLocationOnScreen(rootOrigin)
                for(action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                    val event=MotionEvent.obtain(time,time+if(action==MotionEvent.ACTION_UP)16 else 0,action,px+rootOrigin[0]-origin[0],py+rootOrigin[1]-origin[1],0)
                    try{video.dispatchTouchEvent(event)}finally{event.recycle()}
                }
                return
            }
            if(px<0||py<0)moveBy(0f,0f)
            fun hit(view:View,x:Float,y:Float):View?{
                if(view.visibility!=View.VISIBLE||!view.isEnabled||x<0||y<0||x>=view.width||y>=view.height)return null
                if(view is WebView)return view
                if(view is ViewGroup)for(i in view.childCount-1 downTo 0){
                    val child=view.getChildAt(i);val point=floatArrayOf(x+view.scrollX-child.left,y+view.scrollY-child.top)
                    if(!child.matrix.isIdentity){val inverse=android.graphics.Matrix();if(!child.matrix.invert(inverse))continue;inverse.mapPoints(point)}
                    hit(child,point[0],point[1])?.let{return it}
                }
                return view.takeIf{it is EditText||it.isClickable}
            }
            val overToolbar=toolbar.visibility==View.VISIBLE&&px>=toolbar.left&&px<toolbar.right&&py>=toolbar.top&&py<toolbar.bottom
            if(homepage.visibility!=View.VISIBLE&&!overToolbar){tapPage();return}
            when(val target=hit(root,px,py)){
                is EditText->{target.requestFocusFromTouch();target.selectAll();(getSystemService(INPUT_METHOD_SERVICE)as android.view.inputmethod.InputMethodManager).showSoftInput(target,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);return}
                is Button->{target.requestFocusFromTouch();target.performClick();return}
                is ImageButton->{target.requestFocusFromTouch();target.performClick();return}
                is WebView->{tapPage();return}
                null->Unit
                else->if(target !is WebView&&target.performClick())return
            }
            val time = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 40 else 0, action, px, py, 0)
                root.dispatchTouchEvent(event); event.recycle()
            }
        }
        private fun tapPage(){
            web.requestFocusFromTouch()
                    val time=SystemClock.uptimeMillis()
                    val pointer=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}
                    val point=MotionEvent.PointerCoords().apply{x=px-web.left;y=py-web.top;pressure=1f;size=1f}
                    for(action in intArrayOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                        point.pressure=if(action==MotionEvent.ACTION_DOWN)1f else 0f
                        val event=MotionEvent.obtain(time,time+if(action==MotionEvent.ACTION_UP)16 else 0,action,1,arrayOf(pointer),arrayOf(point),0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                        try{web.dispatchTouchEvent(event)}finally{event.recycle()}
                    }
        }
        fun clickAt(x:Float,y:Float){px=x;py=y;click()}
    }
}
