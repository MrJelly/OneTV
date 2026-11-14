package com.tvbridge.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView

@UnstableApi
@SuppressLint("ViewConstructor")
class YangshipinPlayer(
    private val context: Context,
    private val onTap: () -> Unit = {},
    private val state: (String, Boolean) -> Unit
) : PagePlayback {

    override val view = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val resolver = YangshipinStreamResolver(context)
    private val playerView = PlayerView(context).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setShutterBackgroundColor(Color.TRANSPARENT)
        layoutParams = FrameLayout.LayoutParams(-1, -1)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onTap() }
    }

    override var quality: String = preferences.getString("official_quality", null)?.takeIf { it in listOf("hd", "fhd") } ?: (if (android.os.Build.VERSION.SDK_INT <= 19) "hd" else "fhd")
        private set

    private var exoPlayer: ExoPlayer? = null
    private var proxyServer: HlsProxyServer? = null
    private var currentAddress = ""
    private var lastStateMessage = ""
    private var lastStateReady = false
    private var active = false
    private var suspended = false
    private var generation = 0L
    private var startedAt = 0L
    private var retries = 0
    private var recovery: Runnable? = null
    private var softwareTracks = 0
    private var preferCctv = false
    private var rendered = false
    private var lastPosition = 0L
    private var lastProgressAt = 0L
    private var stableSince = 0L
    private val statusTick = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (!active || suspended || recovery != null || !player.playWhenReady) return
            val now = SystemClock.elapsedRealtime()
            val position = player.currentPosition
            if (rendered && player.isPlaying && position > lastPosition) {
                lastProgressAt = now
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= 10_000) retries = 0
            } else {
                stableSince = 0L
            }
            lastPosition = position
            if (now - lastProgressAt >= 18_000) {
                recover()
            } else {
                handler.postDelayed(this, 2_000)
            }
        }
    }
    private fun watchPlayback() {
        handler.removeCallbacks(statusTick)
        lastPosition = exoPlayer?.currentPosition ?: 0L
        lastProgressAt = SystemClock.elapsedRealtime()
        stableSince = 0L
        handler.postDelayed(statusTick, 2_000)
    }
    private fun cancelRecovery() { recovery?.let { handler.removeCallbacks(it) }; recovery = null }
    private fun recover(changeRoute:Boolean=true) {
        if (!active || suspended || recovery != null) return
        handler.removeCallbacks(statusTick)
        if (retries >= 2) {
            stopNativePlayer()
            notifyState("播放异常，请重试或换台", false)
            return
        }
        val epoch = generation
        retries++
        if (changeRoute) preferCctv = !preferCctv
        notifyState("正在重连 $retries/2", false)
        recovery = Runnable {
            recovery = null
            if (active && !suspended && generation == epoch) start(currentAddress, true)
        }.also { handler.postDelayed(it, retries * 1500L) }
    }

    private fun notifyState(message: String, ready: Boolean) {
        if (message == lastStateMessage && ready == lastStateReady) return
        lastStateMessage = message
        lastStateReady = ready
        val epoch = generation
        handler.post {
            if (generation != epoch || !active) return@post
            state(message, ready)
        }
    }

    init {
        try { LegacyTls.install(context.applicationContext) } catch (_: Throwable) {}
        view.addView(playerView)
    }

    override fun open(address: String, forceReload: Boolean) {
        retries = 0
        preferCctv = forceReload && address == currentAddress && !preferCctv
        softwareTracks = 0
        start(address, forceReload)
    }

    private fun start(address: String, forceReload: Boolean) {
        generation++
        startedAt = android.os.SystemClock.elapsedRealtime()
        cancelRecovery()
        currentAddress = address
        active = true
        suspended = false
        rendered = false
        view.visibility = View.VISIBLE
        resolver.cancel()
        stopNativePlayer()

        val pid = YangshipinStreamResolver.canonicalPid(YangshipinStreamResolver.extractPid(address))
        if (pid.isNotEmpty()) {
            try {
                HlsProxyServer.configureChannel(pid)
            } catch (_: Throwable) {}
        }

        notifyState("正在连接", false)

        resolver.resolve(address, quality, forceReload, preferCctv) { result ->
            if (!active || suspended) return@resolve
            if (result.isSuccess) {
                val streamUrl = result.getOrThrow()
                preferCctv = HlsProxyServer.transformFor(streamUrl) == "h5e"
                playStream(streamUrl)
            } else {
                recover()
            }
        }
    }

    private fun isLowRamDevice():Boolean=android.os.Build.VERSION.SDK_INT<=21||
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice==true

    private fun playStream(streamUrl: String) {
        try {
            proxyServer?.close()
            val lowRam = isLowRamDevice()
            val proxy = HlsProxyServer(quality, lowRam).also { proxyServer = it }
            val playTarget = proxy.proxyUrl(streamUrl)

            val epoch = generation
            val player = NativePlayback.player(context,softwareTracks=softwareTracks,startupMs=if(lowRam)5000 else 4000,official=true).apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    private val showRebuffering = Runnable {
                        if (active && !suspended && generation == epoch && playbackState == Player.STATE_BUFFERING) {
                            notifyState("加载中", false)
                        }
                    }
                    private var playbackState = Player.STATE_IDLE

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        this.playbackState = playbackState
                        handler.removeCallbacks(showRebuffering)
                        if (!active || generation != epoch) return
                        when (playbackState) {
                            Player.STATE_READY -> notifyState("正在播放", true)
                            Player.STATE_BUFFERING -> if (rendered) handler.postDelayed(showRebuffering, 1000) else notifyState("加载中", false)
                            Player.STATE_ENDED -> {
                                seekToDefaultPosition()
                                prepare()
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        if (active && generation == epoch) {
                            rendered = true
                            lastProgressAt = SystemClock.elapsedRealtime()
                            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                            PlaybackDiagnostics.update("firstFrameMs", elapsed)
                            notifyState("正在播放", true)
                            playerView.visibility = View.VISIBLE
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.e("YangshipinPlayer", "onPlayerError: ${error.errorCodeName} - ${error.message}", error)
                        if (active && generation == epoch) {
                            OfficialDecoders.noteFailure(error)
                            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                                if (retries >= 2) { stopNativePlayer(); notifyState("播放异常，请重试或换台", false) } else { retries++; seekToDefaultPosition(); prepare() }
                            } else {
                                val decoderError=error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                                val mime=(error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType.orEmpty()
                                val track=if(mime.startsWith("audio/"))1 else if(mime.startsWith("video/"))2 else 3
                                val fallback=decoderError&&(softwareTracks and track)!=track&&NativePlayback.softwareAvailable()
                                if(fallback)softwareTracks=softwareTracks or track
                                recover(changeRoute=!fallback)
                            }
                        }
                    }
                })
            }

            val httpFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000)
                .setUserAgent("OneTV/1.8 AndroidTV")
                .setAllowCrossProtocolRedirects(true)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            val mediaItem = MediaItem.Builder()
                .setUri(playTarget)
                .setLiveConfiguration(NativePlayback.liveConfiguration())
                .build()
            val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)

            exoPlayer = player
            playerView.player = player
            playerView.visibility = View.VISIBLE

            player.setMediaSource(mediaSource)
            player.prepare()

            watchPlayback()
        } catch (t: Throwable) {
            android.util.Log.w("YangshipinPlayer", "playStream failed: ${t.javaClass.simpleName} ${t.message}")
            if (active && !suspended) recover() else notifyState("原生播放启动异常，请重试", false)
        }
    }

    private fun stopNativePlayer() {
        handler.removeCallbacks(statusTick)
        val player = exoPlayer
        try { player?.stop() } catch (_: Throwable) { }
        try { player?.release() } catch (_: Throwable) { }
        exoPlayer = null
        playerView.player = null
        val proxy = proxyServer
        proxyServer = null
        try { proxy?.close() } catch (_: Throwable) { }
    }

    override fun setQuality(value: String) {
        if (quality == value) return
        quality = value
        preferences.edit().putString("official_quality", value).apply()
        if (active && currentAddress.isNotEmpty()) {
            open(currentAddress, forceReload = true)
        }
    }

    override fun toggle() {
        exoPlayer?.let {
            it.playWhenReady = !it.playWhenReady
            proxyServer?.setPrefetchEnabled(it.playWhenReady)
            if (it.playWhenReady) watchPlayback() else handler.removeCallbacks(statusTick)
        }
    }

    override fun pauseVideo() {
        exoPlayer?.playWhenReady = false
        handler.removeCallbacks(statusTick)
        proxyServer?.setPrefetchEnabled(false)
    }

    override fun suspend() {
        suspended = true
        cancelRecovery()
        handler.removeCallbacks(statusTick)
        proxyServer?.setPrefetchEnabled(false)
        exoPlayer?.playWhenReady = false
    }

    override fun resume() {
        suspended = false
        if (!active) return
        if (exoPlayer == null || exoPlayer?.playerError != null) { start(currentAddress, true); return }
        proxyServer?.setPrefetchEnabled(true)
        exoPlayer?.playWhenReady = true
        watchPlayback()
    }

    override fun stop() {
        generation++
        cancelRecovery()
        handler.removeCallbacksAndMessages(null)
        active = false
        resolver.cancel()
        stopNativePlayer()
        view.visibility = View.GONE
    }

    override fun release() {
        stop()
        resolver.release()
    }
}
