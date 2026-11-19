package com.tvbridge.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Owns browser video playback takeover across three seamless modes:
 * 1. Native [ExoPlayer] (with software-decoder & format fallback via [PlaybackAttempts],
 *    manual SW/HW decode switch, aspect ratio control, and one-click fallback to web player).
 * 2. WebView `onShowCustomView` fullscreen (automatically upgraded to native ExoPlayer when
 *    a high-confidence stream is sniffed and not opted out).
 * 3. In-page expanded `<video>` fullscreen fallback.
 *
 * Also manages automatic video takeover with per-page opt-out, the TV overlay control bar,
 * speed selector, background progress telemetry for the phone remote, and pause/resume
 * preservation across transient Activity pauses.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BrowserVideoController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val sniffer: BrowserVideoSniffer,
    private val host: Host
) {

    interface Host {
        val webView: WebView
        val toolbar: View
        val cursor: View
        fun isHomepageVisible(): Boolean
        fun isDesktopMode(): Boolean
        fun isToolsCollapsed(): Boolean
        fun isDestroyed(): Boolean
        fun isActive(): Boolean
        fun showTools(show: Boolean)
        fun onVideoAvailabilityChanged(sniffedCount: Int)
    }

    var customView: View? = null
        private set
    var videoBarView: LinearLayout? = null
        private set
    @Volatile var playbackState: String = "{}"
        private set

    val isVideoActive: Boolean
        get() = customView != null || pagePlayer != null || webVideoActive

    val isBusyOrActive: Boolean
        get() = isVideoActive || playbackPhase == "searching" || playbackPhase == "loading"

    private val prefs = activity.getSharedPreferences("browser-video-prefs", Activity.MODE_PRIVATE)
    var autoTakeover: Boolean = prefs.getBoolean("auto_takeover", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("auto_takeover", value).apply()
        }
    private var manualSoftwareDecode: Boolean = prefs.getBoolean("software_decode", false)
    private var aspectMode: Int = prefs.getInt("aspect_mode", 0).coerceIn(0, 2)

    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pagePlayer: ExoPlayer? = null
    private var nativeSurface: PlayerView? = null
    private var webPausedForNative = false
    private var speedRow: LinearLayout? = null
    private var speedButton: Button? = null
    private var loopButton: Button? = null
    private var sourceButton: Button? = null
    private var decodeButton: Button? = null
    private var aspectButton: Button? = null
    private var webFallbackButton: Button? = null
    private var barPlay: Button? = null
    private var barSeek: SeekBar? = null
    private var barTime: TextView? = null
    private var barSeeking = false
    private var barPolling = false
    private var webVideoActive = false
    private var webExpanded = false
    private var seekEditing = false
    private var pageGestureAt = -10_000L
    private var webVideoPositionMs = 0L
    private var webVideoDurationMs = 0L
    private var videoSpeed = 1.0f
    private val videoSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var loopVideo = false
    private var videoGeneration = 0L
    private var videoDeadline: Runnable? = null
    private var webPollPending = false
    private var playbackPhase = "idle"
    private var sessionSources = emptyList<String>()
    private var sourceIndex = 0
    private var dismissedAutoPageUrl: String? = null
    private var preferWebPageUrl: String? = null

    // Preserves playback across transient onPause/onResume (e.g. system dialogs).
    private var resumeNativeOnForeground = false
    private var resumeWebOnForeground = false

    private val barHide = Runnable { hideVideoBar() }
    private val barPoll = Runnable { pollVideoBar() }
    private val autoProbeRunnable = Runnable { runAutoProbe() }

    fun notePageGesture() {
        pageGestureAt = SystemClock.uptimeMillis()
    }

    fun onNavigationStarted() {
        root.removeCallbacks(autoProbeRunnable)
        dismissedAutoPageUrl = null
        preferWebPageUrl = null
        exitVideo()
        host.onVideoAvailabilityChanged(0)
    }

    fun onSpaUrlChanged() {
        root.removeCallbacks(autoProbeRunnable)
        dismissedAutoPageUrl = null
        preferWebPageUrl = null
        host.onVideoAvailabilityChanged(0)
    }

    fun onSniffedMedia(total: Int) {
        if (host.isDestroyed() || host.isHomepageVisible()) return
        host.onVideoAvailabilityChanged(total)
        if (!isBusyOrActive && autoTakeover) {
            scheduleAutoProbe(AUTO_PROBE_SNIFF_DELAY_MS)
        }
    }

    fun onPageFinished(url: String) {
        if (host.isDestroyed() || host.isHomepageVisible() || !url.startsWith("http")) return
        val count = sniffer.count(SystemClock.uptimeMillis())
        if (count > 0) host.onVideoAvailabilityChanged(count)
        if (!isBusyOrActive && autoTakeover) {
            scheduleAutoProbe(AUTO_PROBE_PAGE_DELAY_MS)
        }
    }

    private fun scheduleAutoProbe(delayMs: Long) {
        root.removeCallbacks(autoProbeRunnable)
        root.postDelayed(autoProbeRunnable, delayMs)
    }

    private fun runAutoProbe() {
        if (host.isDestroyed() || !host.isActive() || host.isHomepageVisible() || isBusyOrActive) return
        if (!autoTakeover) return
        val page = host.webView
        val address = page.url ?: return
        if (!address.startsWith("http")) return
        if (address == dismissedAutoPageUrl || address == preferWebPageUrl) return
        if (sniffer.sitePolicy(address) == BrowserVideoSniffer.SitePolicy.WEB_ONLY) return

        val now = SystemClock.uptimeMillis()
        val hasHigh = sniffer.hasHighConfidence(now)
        val generation = videoGeneration
        page.evaluateJavascript(BrowserVideoSniffer.DOM_DETECT_JS) { result ->
            if (host.isDestroyed() || !host.isActive() || host.isHomepageVisible() ||
                isBusyOrActive || page !== host.webView || page.url != address ||
                videoGeneration != generation || address == dismissedAutoPageUrl || address == preferWebPageUrl
            ) {
                return@evaluateJavascript
            }
            val payload = parseDomPayload(result)
            val domActiveUrl = payload?.optString("activeUrl")?.takeIf { it.isNotBlank() }
            val domFallbackUrl = payload?.optString("fallbackUrl")?.takeIf { it.isNotBlank() }
            val playingLarge = payload?.optBoolean("playingLarge", false) == true
            val jsUrls = payload?.optJSONArray("jsUrls")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            } ?: emptyList()

            val playable = sniffer.mergeCandidates(
                domActiveUrl = domActiveUrl,
                domFallbackUrl = domFallbackUrl,
                jsUrls = jsUrls,
                nowMs = SystemClock.uptimeMillis()
            )
            if (playable.isEmpty()) return@evaluateJavascript
            host.onVideoAvailabilityChanged(playable.size)
            // Auto-takeover when a high-confidence stream (HLS/DASH/direct) is present or a large video is actively playing.
            if (hasHigh || playingLarge || domActiveUrl != null || jsUrls.isNotEmpty()) {
                val nextGen = ++videoGeneration
                playNativeCandidates(playable, nextGen)
            }
        }
    }

    fun onActivityPause() {
        root.removeCallbacks(autoProbeRunnable)
        hideVideoBar()
        stopBarPoll()
        pagePlayer?.let { player ->
            resumeNativeOnForeground = player.playWhenReady
            player.pause()
            return
        }
        if (webVideoActive) {
            resumeWebOnForeground = playbackPhase == "playing"
            host.webView.evaluateJavascript(jsVideo("v.pause();return true;"), null)
        } else if (playbackPhase == "searching" || playbackPhase == "loading") {
            exitVideoBar()
        }
    }

    fun onActivityResume() {
        pagePlayer?.let { player ->
            if (resumeNativeOnForeground) player.play()
            resumeNativeOnForeground = false
            startBarPoll()
            refreshVideoBar()
            return
        }
        if (webVideoActive) {
            if (resumeWebOnForeground) {
                host.webView.evaluateJavascript(jsVideo("var p=v.play();if(p&&p.catch)p.catch(function(){});return true;"), null)
            }
            resumeWebOnForeground = false
            startBarPoll()
            refreshVideoBar()
        }
    }

    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        val withinGesture = SystemClock.uptimeMillis() - pageGestureAt <= CUSTOM_VIEW_GESTURE_WINDOW_MS
        if (!withinGesture && !webVideoActive && playbackPhase != "searching") {
            callback.onCustomViewHidden()
            return
        }
        val pageUrl = host.webView.url
        val canUpgradeToNative = pageUrl != null &&
            pageUrl != preferWebPageUrl &&
            sniffer.sitePolicy(pageUrl) == BrowserVideoSniffer.SitePolicy.NATIVE_FIRST &&
            sniffer.hasHighConfidence(SystemClock.uptimeMillis())
        if (canUpgradeToNative) {
            callback.onCustomViewHidden()
            playDetectedVideo {}
            return
        }
        webVideoActive = true
        host.showTools(false)
        customView = view
        customCallback = callback
        host.toolbar.visibility = View.GONE
        host.webView.visibility = View.GONE
        host.cursor.visibility = View.GONE
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.requestFocus()
        host.cursor.visibility = if (GlobalPointer.enabled) View.GONE else View.VISIBLE
        host.cursor.bringToFront()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showVideoBar()
    }

    fun onHideCustomView() {
        exitVideo()
    }

    fun playDetectedVideo(done: (Boolean) -> Unit) {
        if (playbackPhase == "searching" || playbackPhase == "loading") {
            done(true)
            return
        }
        pagePlayer?.let { player ->
            if (player.playWhenReady) player.pause() else player.play()
            showVideoBar()
            done(true)
            return
        }
        if (customView != null || webVideoActive) {
            host.webView.evaluateJavascript(jsVideo("if(v.paused){v.play();}else{v.pause();}return true;"), null)
            showVideoBar()
            done(true)
            return
        }
        if (host.isHomepageVisible()) {
            done(false)
            return
        }
        val page = host.webView
        val address = page.url
        preferWebPageUrl = null
        val generation = ++videoGeneration
        publishPlayback("searching")
        done(true)

        if (sniffer.sitePolicy(address) == BrowserVideoSniffer.SitePolicy.WEB_ONLY) {
            fallbackWebVideo { if (!it) publishPlayback("failed") }
            return
        }

        videoDeadline = Runnable {
            if (videoGeneration == generation && playbackPhase == "searching") {
                val sniffedOnly = sniffer.mergeCandidates(null, null, emptyList(), SystemClock.uptimeMillis())
                if (sniffedOnly.isNotEmpty()) {
                    playNativeCandidates(sniffedOnly, generation)
                } else {
                    videoGeneration++
                    publishPlayback("failed")
                }
            }
        }.also { root.postDelayed(it, 4000L) }

        page.evaluateJavascript(BrowserVideoSniffer.DOM_DETECT_JS) { result ->
            if (host.isDestroyed() || !host.isActive() || page !== host.webView ||
                page.url != address || customView != null || videoGeneration != generation
            ) {
                return@evaluateJavascript
            }
            clearVideoDeadline()
            val payload = parseDomPayload(result)

            val domActiveUrl = payload?.optString("activeUrl")?.takeIf { it.isNotBlank() }
            val domFallbackUrl = payload?.optString("fallbackUrl")?.takeIf { it.isNotBlank() }
            val jsUrls = payload?.optJSONArray("jsUrls")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            } ?: emptyList()

            val playable = sniffer.mergeCandidates(
                domActiveUrl = domActiveUrl,
                domFallbackUrl = domFallbackUrl,
                jsUrls = jsUrls,
                nowMs = SystemClock.uptimeMillis()
            )
            if (playable.isEmpty()) {
                fallbackWebVideo { if (!it) publishPlayback("failed") }
                return@evaluateJavascript
            }
            playNativeCandidates(playable, generation)
        }
    }

    private fun parseDomPayload(result: String?): JSONObject? = runCatching {
        val raw = JSONTokener(result).nextValue()
        when (raw) {
            is String -> JSONObject(raw)
            is JSONObject -> raw
            else -> null
        }
    }.getOrNull()

    private fun playNativeCandidates(
        urls: List<String>,
        generation: Long,
        startAt: Int = 0,
        seekToMs: Long = 0L
    ) {
        videoSpeed = 1f
        sessionSources = urls
        var surface: PlayerView? = null
        val attempts = PlaybackAttempts()

        fun startAttempt(index: Int, resetAttempts: Boolean) {
            if (host.isDestroyed() || !host.isActive() || videoGeneration != generation) return
            clearVideoDeadline()
            val previous = pagePlayer
            pagePlayer = null
            surface?.player = null
            previous?.release()
            if (index >= urls.size) {
                exitVideo()
                fallbackWebVideo { ok ->
                    if (!ok) {
                        publishPlayback("failed")
                        NativeUi.notice(activity, "这个网页视频无法直接播放")
                    }
                }
                return
            }
            val url = urls[index]
            if (resetAttempts) attempts.reset(url)
            sourceIndex = index
            publishPlayback("loading")
            try {
                val swTracks = if (manualSoftwareDecode && NativePlayback.softwareAvailable()) 3 else attempts.softwareTracks
                val player = NativePlayback.player(activity, softwareTracks = swTracks)
                pagePlayer = player
                val view = surface ?: PlayerView(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    setShutterBackgroundColor(Color.BLACK)
                    useController = false
                    surface = this
                    nativeSurface = this
                    customView = this
                    applyAspectMode(this)
                    root.addView(this, FrameLayout.LayoutParams(-1, -1))
                    host.showTools(false)
                    host.webView.visibility = View.GONE
                    host.cursor.visibility = View.GONE
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                view.player = player
                var rendered = false
                player.repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.setPlaybackSpeed(videoSpeed)
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (pagePlayer !== player) return
                        rendered = true
                        clearVideoDeadline()
                        host.webView.evaluateJavascript(
                            "Array.prototype.forEach.call(document.querySelectorAll('video'),function(v){v.pause();})",
                            null
                        )
                        // Pause WebView timers and cross-origin iframe media decoders to prevent double audio.
                        if (!webPausedForNative) {
                            runCatching { host.webView.onPause() }
                            webPausedForNative = true
                        }
                        showVideoBar()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (rendered && pagePlayer === player) refreshVideoBar()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (rendered && pagePlayer === player) refreshVideoBar()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (pagePlayer !== player) return
                        // 1. Hardware decoder failure -> retry same URL with software decoder
                        if (isDecoderError(error)) {
                            val mime = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType.orEmpty()
                            val track = when {
                                mime.startsWith("audio/") -> 1
                                mime.startsWith("video/") -> 2
                                else -> 3
                            }
                            if (attempts.softwareFallback(NativePlayback.softwareAvailable(), track)) {
                                startAttempt(index, resetAttempts = false)
                                return
                            }
                        }
                        // 2. Parsing/container error on extensionless URL -> try HLS/DASH/progressive probe
                        if (isParsingError(error) && attempts.nextFormat()) {
                            startAttempt(index, resetAttempts = false)
                            return
                        }
                        // 3. Otherwise advance to next candidate URL
                        startAttempt(index + 1, resetAttempts = true)
                    }
                })
                videoDeadline = Runnable {
                    if (pagePlayer === player && !rendered) startAttempt(index + 1, resetAttempts = true)
                }.also { root.postDelayed(it, NATIVE_FIRST_FRAME_TIMEOUT_MS) }

                val headers = sniffer.playbackHeaders(url, host.webView.url)
                player.setMediaSource(
                    NativePlayback.browserSource(
                        activity,
                        url,
                        host.webView.settings.userAgentString,
                        headers,
                        attempts,
                        sniffer.mimeHint(url)
                    )
                )
                if (seekToMs > 0L && index == startAt) {
                    player.seekTo(seekToMs)
                }
                player.prepare()
                player.play()
            } catch (_: RuntimeException) {
                startAttempt(index + 1, resetAttempts = true)
            }
        }
        startAttempt(startAt, resetAttempts = true)
    }

    private fun applyAspectMode(view: PlayerView? = nativeSurface) {
        val target = view ?: return
        target.resizeMode = when (aspectMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun aspectLabel(): String = when (aspectMode) {
        1 -> "画面 铺满"
        2 -> "画面 裁切"
        else -> "画面 自适应"
    }

    private fun cycleAspectMode() {
        aspectMode = (aspectMode + 1) % 3
        prefs.edit().putInt("aspect_mode", aspectMode).apply()
        applyAspectMode()
        aspectButton?.text = aspectLabel()
        NativeUi.notice(activity, "已切换为${aspectLabel()}")
    }

    private fun toggleSoftwareDecode() {
        if (!NativePlayback.softwareAvailable()) return
        val player = pagePlayer ?: return
        val sources = sessionSources
        if (sources.isEmpty()) return
        val currentIndex = sourceIndex.coerceIn(0, sources.lastIndex)
        val resumePos = player.currentPosition.coerceAtLeast(0L)
        manualSoftwareDecode = !manualSoftwareDecode
        prefs.edit().putBoolean("software_decode", manualSoftwareDecode).apply()
        NativeUi.notice(activity, if (manualSoftwareDecode) "已切换软解播放" else "已切换硬解播放")
        exitVideo()
        playNativeCandidates(sources, videoGeneration, currentIndex, resumePos)
    }

    private fun switchToWebPlayer() {
        val pageUrl = host.webView.url
        preferWebPageUrl = pageUrl
        dismissedAutoPageUrl = pageUrl
        exitVideo()
        fallbackWebVideo { ok ->
            if (!ok) {
                publishPlayback("idle")
                NativeUi.notice(activity, "已切回网页，请点击页面播放")
            }
        }
    }

    private fun isDecoderError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }

    private fun isParsingError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> true
        else -> false
    }

    private fun fallbackWebVideo(done: (Boolean) -> Unit) {
        val generation = videoGeneration
        val page = host.webView
        val url = page.url
        if (webPausedForNative) {
            runCatching { page.onResume() }
            webPausedForNative = false
        }
        publishPlayback("loading")
        videoDeadline = Runnable {
            if (videoGeneration == generation && playbackPhase == "loading") {
                exitVideoBar()
                publishPlayback("failed")
            }
        }.also { root.postDelayed(it, 6000L) }
        page.evaluateJavascript(
            jsVideo("v.loop=$loopVideo;v.playbackRate=1;var p=v.play();if(p&&p.catch)p.catch(function(){});" + EXPAND_VIDEO_JS)
        ) { result ->
            if (host.isDestroyed() || !host.isActive() || page !== host.webView ||
                page.url != url || generation != videoGeneration
            ) {
                return@evaluateJavascript
            }
            if (result?.trim() != "true") {
                clearVideoDeadline()
                done(false)
                return@evaluateJavascript
            }
            videoSpeed = 1f
            showExpandedVideo()
            done(true)
        }
    }

    private fun showExpandedVideo() {
        webExpanded = true
        webVideoActive = true
        host.toolbar.visibility = View.GONE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showVideoBar()
    }

    fun exitVideo() {
        videoGeneration++
        clearVideoDeadline()
        webPollPending = false
        resumeNativeOnForeground = false
        resumeWebOnForeground = false
        pageGestureAt = -10_000L
        if (webPausedForNative) {
            runCatching { host.webView.onResume() }
            webPausedForNative = false
        }
        if (webExpanded) host.webView.evaluateJavascript(RESTORE_VIDEO_JS, null)
        webExpanded = false
        webVideoActive = false
        hideVideoBar()
        sessionSources = emptyList()
        sourceIndex = 0
        pagePlayer?.release()
        pagePlayer = null
        nativeSurface = null
        stopBarPoll()
        customView?.let { root.removeView(it) }
        customView = null
        publishPlayback("idle")
        val callback = customCallback
        customCallback = null
        callback?.onCustomViewHidden()
        host.webView.visibility = View.VISIBLE
        host.cursor.visibility = if (GlobalPointer.enabled) View.GONE else View.VISIBLE
        host.toolbar.visibility = if (host.isToolsCollapsed()) View.GONE else View.VISIBLE
        host.webView.requestFocus()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun exitVideoBar() {
        dismissedAutoPageUrl = host.webView.url
        if (customView != null || pagePlayer != null || playbackPhase == "searching") {
            exitVideo()
            return
        }
        if (webVideoActive) {
            host.webView.evaluateJavascript(jsVideo("v.pause();return true;"), null)
            webVideoActive = false
        }
        exitVideo()
    }

    fun handleTouchDown() {
        if (isVideoActive) showVideoBar()
    }

    /**
     * Handles D-pad, Menu, and Media Play/Pause keys while video playback is active or available.
     * Returns `true` if handled, `false` to let [BrowserActivity] continue normal dispatch.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (isVideoActive) toggleVideoPlay() else if (!host.isHomepageVisible()) playDetectedVideo {}
            }
            return true
        }
        if (isVideoActive && event.keyCode in DPAD_KEYS) {
            if (videoBarView?.visibility != View.VISIBLE) {
                if (event.action == KeyEvent.ACTION_DOWN) showVideoBar()
                return true
            }
            root.removeCallbacks(barHide)
            if (speedRow == null) root.postDelayed(barHide, BAR_AUTO_HIDE_MS)
            return false // allow normal focus movement within the visible video bar
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && isVideoActive) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (videoBarView?.visibility == View.VISIBLE) hideVideoBar() else showVideoBar()
            }
            return true
        }
        return false
    }

    /**
     * Handles Back press for speed row, seek editing, or active video playback.
     * Returns `true` if consumed.
     */
    fun handleBackPressed(): Boolean {
        if (speedRow != null) {
            closeSpeedRow()
            showVideoBar()
            return true
        }
        if (seekEditing) {
            seekEditing = false
            barSeek?.isActivated = false
            showVideoBar()
            return true
        }
        if (isBusyOrActive) {
            exitVideoBar()
            return true
        }
        return false
    }

    /**
     * Handles browser video remote commands. Returns `null` if [command] is not a video command.
     */
    fun handleRemoteCommand(command: String, value: String): Boolean? {
        return when (command) {
            "browserSelectSource" -> {
                val sources = sessionSources
                val index = value.toIntOrNull()?.takeIf { it in sources.indices } ?: return false
                exitVideo()
                playNativeCandidates(sources, videoGeneration, index)
                true
            }
            "browserStop" -> {
                if (!isBusyOrActive) return false
                exitVideoBar()
                true
            }
            "browserSeek" -> {
                val ms = value.toLongOrNull()?.takeIf { it >= 0 } ?: return false
                if (pagePlayer?.isCurrentMediaItemSeekable != true && (!webVideoActive || webVideoDurationMs <= 0)) {
                    return false
                }
                seekVideoTo(ms)
                true
            }
            "browserRewind", "browserFastForward" -> {
                if (pagePlayer?.isCurrentMediaItemSeekable != true && (!webVideoActive || webVideoDurationMs <= 0)) {
                    return false
                }
                seekVideoBy(if (command == "browserRewind") -10_000L else 10_000L)
                true
            }
            "browserFullscreen" -> {
                if (customView != null || webExpanded) {
                    exitVideoBar()
                    return true
                }
                if (host.isHomepageVisible()) return false
                val generation = videoGeneration
                host.webView.evaluateJavascript(jsVideo(EXPAND_VIDEO_JS)) { result ->
                    if (host.isDestroyed() || !host.isActive() || generation != videoGeneration) return@evaluateJavascript
                    if (result?.trim() == "true") showExpandedVideo()
                    else NativeUi.notice(activity, "当前网页没有可全屏的视频")
                }
                true
            }
            "browserSpeed" -> setVideoSpeed(value.toFloatOrNull() ?: return false)
            "browserLoop" -> {
                loopVideo = !loopVideo
                loopButton?.isActivated = loopVideo
                pagePlayer?.let {
                    it.repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                }
                if (pagePlayer == null && !host.isHomepageVisible()) {
                    val loop = if (loopVideo) "true" else "false"
                    host.webView.evaluateJavascript(jsVideo("v.loop=$loop;return true;"), null)
                }
                NativeUi.notice(activity, if (loopVideo) "已开启单曲循环" else "已关闭单曲循环")
                true
            }
            else -> null
        }
    }

    private fun publishPlayback(phase: String, position: Long = 0, duration: Long = 0, seekable: Boolean = false) {
        playbackPhase = phase
        val labels = sessionSources.mapIndexed { idx, url -> sniffer.sourceLabel(url, idx) }
        playbackState = JSONObject()
            .put("phase", phase)
            .put("title", host.webView.title.orEmpty().take(120))
            .put("position", position)
            .put("duration", duration)
            .put("seekable", seekable)
            .put("speed", videoSpeed.toDouble())
            .put("loop", loopVideo)
            .put("desktop", host.isDesktopMode())
            .put("fullscreen", customView != null || webExpanded)
            .put("sourceIndex", sourceIndex)
            .put("sources", JSONArray(labels))
            .toString()
    }

    private fun clearVideoDeadline() {
        videoDeadline?.let(root::removeCallbacks)
        videoDeadline = null
    }

    private fun videoBarSize(value: Int) = (value * 2 * NativeUi.scale(activity)).toInt()

    private fun videoBarButton(label: String, run: () -> Unit): Button {
        return Button(activity).apply {
            text = label
            isAllCaps = false
            includeFontPadding = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            if (Build.VERSION.SDK_INT >= 21) {
                backgroundTintList = null
                stateListAnimator = null
                elevation = 0f
            }
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            NativeUi.textSize(this, 28f)
            maxLines = 1
            setPadding(videoBarSize(14), 0, videoBarSize(14), 0)
            background = GlassUi.surface(this, 24f, video = true, dark = true, faint = true)
            setTextColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            NativeUi.touchClicks(this)
            setOnClickListener { run(); showVideoBar() }
        }
    }

    private fun createVideoBar() {
        if (videoBarView != null) return
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GlassUi.surface(this, 0f, video = true, dark = true, dense = true, border = false)
            setPadding(videoBarSize(12), videoBarSize(10), videoBarSize(12), videoBarSize(12))
            visibility = View.GONE
            isFocusable = false
            isFocusableInTouchMode = false
        }
        val rewindButton = videoBarButton("快退") { seekVideoBy(-10_000) }.apply { contentDescription = "快退 10 秒" }
        val playButton = videoBarButton("播放") { toggleVideoPlay() }
        val forwardButton = videoBarButton("快进") { seekVideoBy(10_000) }.apply { contentDescription = "快进 10 秒" }
        val exitButton = videoBarButton("退出全屏") { exitVideoBar() }
        val barButtons = listOf(rewindButton, playButton, forwardButton, exitButton)
        val widest = barButtons.maxOf {
            it.paint.measureText(it.text.toString()).toInt() + 1 + it.paddingLeft + it.paddingRight
        }
        bar.addView(rewindButton, LinearLayout.LayoutParams(widest, videoBarSize(40)).apply { marginEnd = videoBarSize(8) })
        barPlay = playButton.also {
            bar.addView(it, LinearLayout.LayoutParams(widest, videoBarSize(40)).apply { marginEnd = videoBarSize(8) })
        }
        bar.addView(forwardButton, LinearLayout.LayoutParams(widest, videoBarSize(40)).apply { marginEnd = videoBarSize(8) })
        barSeek = SeekBar(activity).apply {
            max = 0
            progress = 0
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "播放进度，按确认调整"
            background = GlassUi.surface(this, 10f, video = true, dark = true, faint = true)
            setOnFocusChangeListener { _, focused ->
                if (!focused) {
                    seekEditing = false
                    isActivated = false
                }
            }
            setOnKeyListener { _, key, event ->
                when (key) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            seekEditing = !seekEditing
                            isActivated = seekEditing
                            if (seekEditing) NativeUi.notice(activity, "左右键调整进度，再按确认结束")
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val left = key == KeyEvent.KEYCODE_DPAD_LEFT
                            if (seekEditing) seekVideoBy(if (left) -10_000 else 10_000)
                            else focusSearch(if (left) View.FOCUS_LEFT else View.FOCUS_RIGHT)?.requestFocus()
                        }
                        true
                    }
                    else -> false
                }
            }
            if (Build.VERSION.SDK_INT >= 21) {
                progressTintList = ColorStateList.valueOf(Color.WHITE)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(0x80ffffff.toInt())
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateBarTime(progress.toLong(), barDurationMs())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    barSeeking = true
                    showVideoBar()
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    barSeeking = false
                    seekVideoTo(seekBar.progress.toLong())
                    showVideoBar()
                }
            })
        }.also {
            bar.addView(it, LinearLayout.LayoutParams(0, videoBarSize(40), 1f).apply { marginEnd = videoBarSize(8) })
        }
        barTime = TextView(activity).apply {
            text = "0:00 / --:--"
            setTextColor(Color.WHITE)
            NativeUi.textSize(this, 28f)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            setSingleLine()
        }.also {
            bar.addView(it, LinearLayout.LayoutParams(-2, videoBarSize(40)).apply { marginEnd = videoBarSize(8) })
        }
        bar.addView(exitButton, LinearLayout.LayoutParams(widest, videoBarSize(40)))
        fun addOption(button: Button) {
            bar.addView(button, LinearLayout.LayoutParams(widest, videoBarSize(40)).apply { marginStart = videoBarSize(8) })
        }
        speedButton = videoBarButton("倍速") { toggleSpeedRow() }.also(::addOption)
        loopButton = videoBarButton("循环播放") { handleRemoteCommand("browserLoop", "") }.also(::addOption)
        addOption(videoBarButton("换源") {
            if (sessionSources.size > 1) {
                handleRemoteCommand("browserSelectSource", ((sourceIndex + 1) % sessionSources.size).toString())
            }
        }.apply { tag = "video-source" })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        videoBarView = bar
    }

    fun showVideoBar() {
        if (!isVideoActive) return
        createVideoBar()
        val bar = videoBarView ?: return
        val opening = bar.visibility != View.VISIBLE
        bar.bringToFront()
        speedRow?.bringToFront()
        speedButton?.text = speedLabel(videoSpeed)
        loopButton?.isActivated = loopVideo
        bar.findViewWithTag<View>("video-source")?.visibility = if (sessionSources.size > 1) View.VISIBLE else View.GONE
        bar.visibility = View.VISIBLE
        if (opening) barPlay?.requestFocus()
        root.removeCallbacks(barHide)
        if (speedRow == null) root.postDelayed(barHide, BAR_AUTO_HIDE_MS)
        startBarPoll()
        refreshVideoBar()
    }

    fun hideVideoBar() {
        closeSpeedRow(false)
        seekEditing = false
        barSeek?.isActivated = false
        root.removeCallbacks(barHide)
        if (!isVideoActive) stopBarPoll()
        videoBarView?.visibility = View.GONE
    }

    private fun startBarPoll() {
        if (barPolling) return
        barPolling = true
        root.removeCallbacks(barPoll)
        root.postDelayed(barPoll, 500L)
    }

    private fun stopBarPoll() {
        barPolling = false
        root.removeCallbacks(barPoll)
    }

    private fun pollVideoBar() {
        if (!barPolling) return
        if (!isVideoActive) {
            barPolling = false
            return
        }
        refreshVideoBar()
        // Fast 500ms updates while the TV bar is visible; lightweight 2000ms heartbeat
        // while hidden so the phone remote's /playback progress stays synchronized.
        val delay = if (videoBarView?.visibility == View.VISIBLE) 500L else 2000L
        root.postDelayed(barPoll, delay)
    }

    private fun refreshVideoBar() {
        pagePlayer?.let { player ->
            val phase = when {
                player.playbackState == Player.STATE_ENDED -> "ended"
                player.playbackState == Player.STATE_BUFFERING -> "loading"
                player.isPlaying -> "playing"
                else -> "paused"
            }
            val duration = player.duration.coerceAtLeast(0)
            val position = player.currentPosition.coerceAtLeast(0)
            publishPlayback(phase, position, duration, player.isCurrentMediaItemSeekable)
            if (videoBarView?.visibility != View.VISIBLE) return
            barSeek?.isEnabled = player.isCurrentMediaItemSeekable
            barPlay?.text = if (player.isPlaying) "暂停" else "播放"
            updateBarProgress(position, duration)
            return
        }
        if (!webVideoActive || webPollPending) return
        val generation = videoGeneration
        webPollPending = true
        host.webView.evaluateJavascript(
            jsVideo("var d=v.duration;if(!isFinite(d))d=0;return (v.paused?'1':'0')+'|'+v.currentTime+'|'+d+'|'+v.readyState;")
        ) { result ->
            if (generation == videoGeneration) webPollPending = false
            if (host.isDestroyed() || !host.isActive() || generation != videoGeneration || !webVideoActive) {
                return@evaluateJavascript
            }
            val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val parts = text.split('|')
            if (parts.size != 4) return@evaluateJavascript
            val paused = parts[0] == "1"
            val current = parts[1].toDoubleOrNull() ?: return@evaluateJavascript
            val duration = parts[2].toDoubleOrNull() ?: return@evaluateJavascript
            val positionMs = (current * 1000).toLong().coerceAtLeast(0)
            val durationMs = (duration * 1000).toLong().coerceAtLeast(0)
            webVideoPositionMs = positionMs
            webVideoDurationMs = durationMs
            val ready = (parts[3].toIntOrNull() ?: 0) >= 2
            if (ready && !paused) clearVideoDeadline()
            val phase = if (playbackPhase == "loading" && (!ready || paused)) "loading" else if (paused) "paused" else "playing"
            publishPlayback(phase, positionMs, durationMs, durationMs > 0)
            barSeek?.isEnabled = durationMs > 0
            if (videoBarView?.visibility != View.VISIBLE) return@evaluateJavascript
            barPlay?.text = if (paused) "播放" else "暂停"
            updateBarProgress(positionMs, durationMs)
        }
    }

    private fun updateBarProgress(positionMs: Long, durationMs: Long) {
        val seek = barSeek ?: return
        if (durationMs > 0) {
            val max = durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            if (seek.max != max) seek.max = max
            if (!barSeeking) seek.progress = positionMs.coerceIn(0, seek.max.toLong()).toInt()
        } else {
            if (seek.max != 0) seek.max = 0
            if (!barSeeking) seek.progress = 0
        }
        if (!barSeeking) updateBarTime(positionMs, durationMs)
    }

    private fun updateBarTime(positionMs: Long, durationMs: Long) {
        barTime?.text = if (durationMs > 0) {
            formatVideoTime(positionMs) + " / " + formatVideoTime(durationMs)
        } else {
            formatVideoTime(positionMs) + " / --:--"
        }
    }

    private fun formatVideoTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } else {
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    private fun barDurationMs(): Long = pagePlayer?.duration?.coerceAtLeast(0) ?: webVideoDurationMs

    private fun seekVideoBy(offsetMs: Long) {
        pagePlayer?.let { player ->
            val position = (player.currentPosition + offsetMs).coerceAtLeast(0)
            player.seekTo(if (player.duration > 0) position.coerceAtMost(player.duration) else position)
            showVideoBar()
            return
        }
        if (host.isHomepageVisible()) return
        val offsetSec = offsetMs / 1000
        host.webView.evaluateJavascript(
            jsVideo("var d=v.duration;var t=v.currentTime+($offsetSec);if(isFinite(d)){t=Math.max(0,Math.min(d,t));}else{t=Math.max(0,t);}v.currentTime=t;return true;"),
            null
        )
        showVideoBar()
    }

    private fun seekVideoTo(positionMs: Long) {
        pagePlayer?.let { player ->
            player.seekTo(if (player.duration > 0) positionMs.coerceIn(0, player.duration) else positionMs.coerceAtLeast(0))
            showVideoBar()
            return
        }
        if (host.isHomepageVisible()) return
        val seconds = positionMs / 1000.0
        host.webView.evaluateJavascript(
            jsVideo("var d=v.duration;var t=($seconds);if(isFinite(d)){t=Math.max(0,Math.min(d,t));}else{t=Math.max(0,t);}v.currentTime=t;return true;"),
            null
        )
        showVideoBar()
    }

    private fun toggleVideoPlay() {
        pagePlayer?.let { player ->
            if (player.playWhenReady) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            showVideoBar()
            return
        }
        if (customView != null || webVideoActive) {
            host.webView.evaluateJavascript(jsVideo("if(v.paused){v.play();}else{v.pause();}return true;"), null)
            showVideoBar()
            return
        }
        if (host.isHomepageVisible()) return
        playDetectedVideo {}
    }

    private fun speedLabel(rate: Float) = rate.toString().removeSuffix(".0") + "×"

    private fun toggleSpeedRow() {
        if (speedRow != null) {
            closeSpeedRow()
            return
        }
        val bar = videoBarView ?: return
        val row = LinearLayout(activity).apply {
            background = GlassUi.surface(this, 0f, video = true, dark = true, dense = true, border = false)
            setPadding(videoBarSize(8), videoBarSize(6), videoBarSize(8), videoBarSize(6))
        }
        for (rate in videoSpeeds) {
            val choice = videoBarButton(speedLabel(rate)) { closeSpeedRow(); setVideoSpeed(rate) }
            choice.isActivated = rate == videoSpeed
            choice.id = View.generateViewId()
            choice.nextFocusDownId = choice.id
            choice.nextFocusUpId = choice.id
            row.addView(choice, LinearLayout.LayoutParams(-2, videoBarSize(40)))
        }
        speedRow = row
        root.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = bar.height })
        row.getChildAt(videoSpeeds.indexOf(videoSpeed).coerceAtLeast(0)).requestFocus()
    }

    private fun closeSpeedRow(restoreFocus: Boolean = true) {
        val row = speedRow ?: return
        speedRow = null
        root.removeView(row)
        if (restoreFocus) speedButton?.requestFocus()
    }

    private fun setVideoSpeed(rate: Float): Boolean {
        if (rate !in videoSpeeds || host.isHomepageVisible()) return false
        if (!isVideoActive) return false
        videoSpeed = rate
        pagePlayer?.setPlaybackSpeed(rate)
        if (pagePlayer == null) {
            host.webView.evaluateJavascript(jsVideo("v.playbackRate=($rate);return true;"), null)
        }
        showVideoBar()
        return true
    }

    private fun jsVideo(body: String): String =
        "(function(){try{" + PICK_VIDEO_JS + body + "}catch(e){return null;}})()"

    companion object {
        private const val BAR_AUTO_HIDE_MS = 5000L
        private const val NATIVE_FIRST_FRAME_TIMEOUT_MS = 10_000L
        private const val CUSTOM_VIEW_GESTURE_WINDOW_MS = 2500L
        private const val AUTO_PROBE_SNIFF_DELAY_MS = 650L
        private const val AUTO_PROBE_PAGE_DELAY_MS = 1200L
        private val DPAD_KEYS = setOf(19, 20, 21, 22, 23, 66)

        private const val PICK_VIDEO_JS =
            "var videos=[];function collect(doc,depth){if(!doc||depth>3)return;" +
                "var list=doc.querySelectorAll('video');for(var i=0;i<list.length;i++)videos.push(list[i]);" +
                "var frames=doc.querySelectorAll('iframe');for(var j=0;j<frames.length;j++){try{collect(frames[j].contentDocument,depth+1);}catch(e){}}}" +
                "collect(document,0);var v=window.__oneExpandedVideo;var fe=document.fullscreenElement||document.webkitFullscreenElement;" +
                "if(!v&&fe&&fe.tagName==='VIDEO')v=fe;" +
                "if(!v){for(var i=0;i<videos.length;i++){if(!videos[i].paused){v=videos[i];break;}}}" +
                "if(!v&&videos.length>0)v=videos[0];if(!v)return null;"

        private const val EXPAND_VIDEO_JS =
            "if(!window.__oneExpandedVideo){" +
                "var nodes=[v];var player=v.closest&&v.closest('.video-js');if(player)nodes.push(player);" +
                "var frame=v.ownerDocument.defaultView.frameElement;" +
                "while(frame){nodes.push(frame);frame=frame.ownerDocument.defaultView.frameElement;}" +
                "window.__oneExpandedStyles=nodes.map(function(n){return [n,n.getAttribute('style')];});" +
                "nodes.forEach(function(n){n.style.cssText+=';position:fixed!important;inset:0!important;top:0!important;left:0!important;bottom:0!important;right:0!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important;margin:0!important;padding:0!important;';});" +
                "window.__oneExpandedVideo=v;}return true;"

        private const val RESTORE_VIDEO_JS =
            "(function(){(window.__oneExpandedStyles||[]).forEach(function(p){if(p[1]===null)p[0].removeAttribute('style');else p[0].setAttribute('style',p[1]);});delete window.__oneExpandedStyles;delete window.__oneExpandedVideo;})()"
    }
}
