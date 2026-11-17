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

    /** Drop the cached list so the next read recomputes it (refresh button). */
    fun invalidate() {
        cachedAddresses = emptyList()
        cachedAddressesMs = 0L
    }

    fun addresses(context: Context, port: Int): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAddressesMs < 30000 && cachedAddresses.isNotEmpty() && cachedAddresses.all { it.endsWith(":$port") }) return cachedAddresses
        if (cachedAddresses.isEmpty()) {
            val found = compute(context, port)
            if (found.isNotEmpty()) {
                cachedAddresses = found
                cachedAddressesMs = now
            }
            return found
        }
        if (refreshing.compareAndSet(false, true)) {
            val app = context.applicationContext
            AppThreads.onIo {
                try {
                    val found = compute(app, port)
                    if (found.isNotEmpty()) {
                        cachedAddresses = found
                        cachedAddressesMs = SystemClock.elapsedRealtime()
                    }
                } finally {
                    refreshing.set(false)
                }
            }
        }
        return cachedAddresses
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
