package com.tvbridge.app

import android.app.Activity
import android.app.AlertDialog
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

internal class BrowserPageTools(private val activity: Activity, private val host: Host) {
    interface Host {
        val page: WebView
        fun valid(): Boolean
        fun playSelectedVideo()
        fun beforePageAction()
        fun pageActionFinished()
        fun fitPage()
        fun originalScale()
        fun desktopMode(): Boolean
        fun setDesktopMode(value: Boolean)
        fun settingsChanged()
    }
    private var dialogRef = java.lang.ref.WeakReference<AlertDialog>(null)
    private var dialog: AlertDialog?
        get() = dialogRef.get()
        set(value) { dialogRef = java.lang.ref.WeakReference(value) }
    private var request = 0
    @Volatile var remoteSnapshot: String = "{}"
        private set
    private var revision = 0
    private var playlistScanAt = 0L
    private var remoteSelection = false
    private val expireRemote = Runnable { clearRemote() }
    private val script by lazy { activity.assets.open("browser-page-tools.js").bufferedReader().use { it.readText() } }
    private var injectedPage=java.lang.ref.WeakReference<WebView>(null)
    private var injectionEpoch=0
    fun onNavigation(){injectionEpoch++;injectedPage.clear();dismiss()}
    private fun evaluate(page:WebView,expression:String,done:(String)->Unit){
        val epoch=injectionEpoch
        val run={if(host.valid()&&host.page===page&&epoch==injectionEpoch)page.evaluateJavascript(expression){result->if(result!=null)done(result)}}
        if(injectedPage.get()===page){run();return}
        page.evaluateJavascript(script){
            if(host.valid()&&host.page===page&&epoch==injectionEpoch){injectedPage=java.lang.ref.WeakReference(page);run()}
        }
    }

    private fun clearRefs() { if (host.valid()) host.page.evaluateJavascript("if(window.__onePageTools)window.__onePageTools.clear();", null) }
    fun dismiss() { request++; BrowserDropdown.dismiss(); dialog?.dismiss(); dialog = null; clearRemote() }

    fun menu(anchor: android.view.View) {
        if (BrowserDropdown.consumeOutsideDismiss(anchor)) return
        if (BrowserDropdown.isShowingFor(anchor)) { BrowserDropdown.dismiss(); return }
        dismiss()
        val rows = mutableListOf<BrowserDropdown.Row>()
        fun pageRow(label: String, run: () -> Unit) = BrowserDropdown.Row(label) { if (!host.valid()) NativeUi.notice(activity, "请先打开网页") else run() }
        rows += pageRow("适配屏幕宽度") { host.fitPage() }
        rows += pageRow("原始比例") { host.originalScale() }
        rows += BrowserDropdown.Row("桌面网页", value = if (host.desktopMode()) "开" else "关") { host.setDesktopMode(!host.desktopMode()) }
        rows += BrowserDropdown.Row("自动全屏", value = if (BrowserSettings.autoFullscreen(activity)) "开" else "关") { BrowserSettings.setAutoFullscreen(activity, !BrowserSettings.autoFullscreen(activity)); host.settingsChanged() }
        rows += BrowserDropdown.Row("搜索引擎", value = BrowserNavigation.engines[BrowserSettings.engine(activity)] + " ›") {
            val current = BrowserSettings.engine(activity)
            val choices = BrowserNavigation.engines.map { (key, name) ->
                BrowserDropdown.Row(name, checked = key == current) { BrowserSettings.setEngine(activity, key); host.settingsChanged() }
            }
            BrowserDropdown.show(activity, anchor, 176, choices)
        }
        BrowserDropdown.show(activity, anchor, 176, rows)
    }

    private fun scan(done: (JSONObject) -> Unit) {
        dismiss()
        if (!host.valid()) return
        val page = host.page; val url = page.url; val ticket = request
        val version = ++revision
        remoteSnapshot = JSONObject().put("revision", version).put("loading", true).toString()
        evaluate(page,"window.__onePageTools.scan();") { result ->
            if (ticket != request || !host.valid() || host.page !== page || page.url != url) return@evaluate
            val data = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (data == null) {
                remoteSnapshot = JSONObject().put("revision", version).put("loading", false).put("error", "无法读取页面").toString()
                NativeUi.notice(activity, "无法读取页面，请稍后再试")
            } else {
                remoteSnapshot = data.put("revision", version).put("loading", false).toString()
                done(data)
            }
        }
    }

    fun inspect() {
        scan { remoteSelection = true }
        remoteSelection = true
        AppThreads.main.removeCallbacks(expireRemote)
        AppThreads.main.postDelayed(expireRemote, 60_000L)
    }

    fun clearRemote() { AppThreads.main.removeCallbacks(expireRemote); remoteSelection = false; remoteSnapshot = "{}"; clearRefs() }

    fun selectRemote(id: Int, label: String?, video: Boolean, version: Int): Boolean {
        val snapshot = runCatching { JSONObject(remoteSnapshot) }.getOrNull() ?: return false
        if (!host.valid() || snapshot.optInt("revision", -1) != version) return false
        val page = host.page
        if (!video) host.beforePageAction()
        val action = if (video) "selectVideo($id)" else "activate($id,${JSONObject.quote(label.orEmpty())})"
        page.evaluateJavascript("window.__onePageTools.$action;") { answer ->
            if (!host.valid() || host.page !== page) return@evaluateJavascript
            clearRemote()
            if (answer != "true") NativeUi.notice(activity, "页面已变化，请重新读取列表")
            else if (video) host.playSelectedVideo() else host.pageActionFinished()
        }
        return true
    }

    fun autoVideoProbe(done: (Boolean) -> Unit) {
        if (!host.valid() || dialog?.isShowing == true || BrowserDropdown.isShowing() || remoteSelection) { done(true); return }
        val page = host.page; val url = page.url
        evaluate(page,"window.__onePageTools.probeVideos();") { raw ->
            if (!host.valid() || host.page !== page || page.url != url) return@evaluate
            val data = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
            if ((data?.optInt("playing", 0) ?: 0) > 0) { done(true); takePlayingVideo() } else done(false)
        }
    }

    private fun takePlayingVideo() = scan { data ->
        val rows = data.optJSONArray("videos") ?: return@scan
        if (rows.length() == 0) return@scan
        var pick = 0
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).optBoolean("playing")) { pick = i; break }
        val id = rows.getJSONObject(pick).getInt("id")
        val page = host.page; val url = page.url
        page.evaluateJavascript("window.__onePageTools.selectVideo($id);") { answer ->
            if (!host.valid() || host.page !== page || page.url != url) return@evaluateJavascript
            if (answer == "true") host.playSelectedVideo()
        }
    }

    fun playlist(anchor: android.view.View) {
        if (BrowserDropdown.consumeOutsideDismiss(anchor)) return
        if (BrowserDropdown.isShowingFor(anchor)) { BrowserDropdown.dismiss(); return }
        if (android.os.SystemClock.uptimeMillis() - playlistScanAt < 3000L) return
        playlistScanAt = android.os.SystemClock.uptimeMillis()
        scan { data ->
            playlistScanAt = 0L
            val groups = data.optJSONArray("groups") ?: return@scan
            if (groups.length() == 0) { NativeUi.notice(activity, "尚未识别到频道或选集，请在原网页展开列表后重试"); return@scan }
            fun showGroup(index: Int, start: Int = 0) {
                val group = groups.getJSONObject(index); val items = group.getJSONArray("items")
                fun activate(i: Int) {
                    val row = items.getJSONObject(i); val page = host.page; val url = page.url
                    host.beforePageAction()
                    page.evaluateJavascript("window.__onePageTools.activate(${row.getInt("id")},${JSONObject.quote(row.getString("label"))});") { answer ->
                        if (!host.valid() || host.page !== page) return@evaluateJavascript
                        if (answer != "true" && page.url == url) NativeUi.notice(activity, "列表已变化，请刷新后再选")
                        host.pageActionFinished()
                    }
                }
                val rows = mutableListOf<BrowserDropdown.Row>()
                if (groups.length() > 1) rows += BrowserDropdown.Row(group.optString("label"), value = "${index + 1}/${groups.length()}") { showGroup((index + 1) % groups.length()) }
                val current = (0 until items.length()).firstOrNull { items.getJSONObject(it).optBoolean("selected") }
                if (current != null && current > 0) rows += BrowserDropdown.Row("上一个") { activate(current - 1) }
                if (current != null && current < items.length() - 1) rows += BrowserDropdown.Row("下一个") { activate(current + 1) }
                for (i in start until minOf(start + 40, items.length())) {
                    val row = items.getJSONObject(i)
                    rows += BrowserDropdown.Row((if (row.optBoolean("selected")) "● " else "") + row.getString("label")) { activate(i) }
                }
                if (start > 0) rows += BrowserDropdown.Row("上一页") { showGroup(index, (start - 40).coerceAtLeast(0)) }
                if (start + 40 < items.length()) rows += BrowserDropdown.Row("下一页") { showGroup(index, start + 40) }
                rows += BrowserDropdown.Row("重新识别") { playlist(anchor) }
                if (!anchor.isAttachedToWindow) return
                BrowserDropdown.show(activity, anchor, 232, rows, outsideTouchable = false, showAbove = true)
            }
            showGroup(0)
        }
    }
}
