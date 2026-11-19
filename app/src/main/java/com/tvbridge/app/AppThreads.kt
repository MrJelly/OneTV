package com.tvbridge.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's threading contract, in one place.
 *
 * - [state]: single serial thread owning system-state queries and snapshot recomputes.
 * - [boot]: dedicated serial lane for order-critical boot and unlock work.
 * - [persist]: dedicated serial lane for atomic disk persistence (e.g. live-sources.json).
 * - [io]: bounded pool (max 8 threads, 30s idle timeout) for network and disk work,
 *   preventing unbounded thread creation on low-RAM TVs under slow networks.
 */
object AppThreads {
    private val ioSeq = AtomicInteger(1)

    private fun daemonFactory(prefix: String): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, prefix).apply { isDaemon = true }
    }

    val state: ExecutorService = Executors.newSingleThreadExecutor(daemonFactory("app-state"))

    /** Boot and unlock work is order-critical and must never queue behind
     * snapshot recomputes on [state]; it gets its own serial lane. */
    val boot: ExecutorService = Executors.newSingleThreadExecutor(daemonFactory("app-boot"))

    /** Serial disk persistence lane shared by repositories. */
    val persist: ExecutorService = Executors.newSingleThreadExecutor(daemonFactory("app-persist"))

    val io: ExecutorService = ThreadPoolExecutor(
        8,
        8,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "app-io-${ioSeq.getAndIncrement()}").apply { isDaemon = true }
        }
    ).apply { allowCoreThreadTimeOut(true) }

    val main = Handler(Looper.getMainLooper())

    fun onState(block: () -> Unit) = state.execute(block)
    fun onBoot(block: () -> Unit) = boot.execute(block)
    fun onPersist(block: () -> Unit) = persist.execute(block)
    fun onIo(block: () -> Unit) = io.execute(block)
}
