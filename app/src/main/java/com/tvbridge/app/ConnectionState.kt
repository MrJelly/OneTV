package com.tvbridge.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone-connection card's address list, owned in one place.
 *
 * Enumerating network interfaces is a system call sweep that used to run on
 * the UI thread whenever the 30-second cache went stale under the home
 * ticker. The cache lives here now: the first fill is synchronous so the
 * card is right at once, later refreshes run on the io pool and callers
 * always read the last known list.
 */
object ConnectionState {
    @Volatile private var cachedAddresses = emptyList<String>()
    @Volatile private var cachedAddressesMs = 0L
    private val refreshing = AtomicBoolean(false)

    private const val FRESH_TTL_MS = 30_000L
    private const val EMPTY_RETRY_MS = 5_000L

    /** Drop the cached list so the next read recomputes it (refresh button). */
    fun invalidate() {
        cachedAddresses = emptyList()
        cachedAddressesMs = 0L
    }

    /**
     * Returns the last known LAN addresses without blocking the caller.
     * When the cache is empty or stale, refreshes on [AppThreads.onIo] and
     * invokes [onUpdated] if the resolved list changed.
     */
    fun addresses(context: Context, port: Int, onUpdated: (() -> Unit)? = null): List<String> {
        val current = cachedAddresses
        val now = SystemClock.elapsedRealtime()
        val age = now - cachedAddressesMs
        val fresh = if (current.isNotEmpty()) {
            age < FRESH_TTL_MS && current.all { it.endsWith(":$port") }
        } else {
            cachedAddressesMs != 0L && age < EMPTY_RETRY_MS
        }
        if (fresh) return current

        if (refreshing.compareAndSet(false, true)) {
            val app = context.applicationContext
            AppThreads.onIo {
                var changed = false
                try {
                    val found = compute(app, port)
                    changed = found != cachedAddresses
                    cachedAddresses = found
                    cachedAddressesMs = SystemClock.elapsedRealtime()
                } finally {
                    refreshing.set(false)
                }
                if (changed) onUpdated?.invoke()
            }
        }
        return current
    }

    private fun compute(context: Context, port: Int): List<String> {
        return try {
            val preferred = if (Build.VERSION.SDK_INT >= 23) {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                manager.activeNetwork?.let(manager::getLinkProperties)?.interfaceName
            } else null
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .sortedBy { if (it.name == preferred) 0 else if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.map { "http://${it.hostAddress}:$port" }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
