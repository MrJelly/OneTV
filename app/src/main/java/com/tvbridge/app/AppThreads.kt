package com.tvbridge.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The app's threading contract, in one place.
 *
 * [state] is a single serial thread that owns every system-state query
 * (PackageManager, Settings.Secure, usage stats) and every state snapshot
 * recompute. [io] is a shared pool for network and disk work. The main
 * thread only reads snapshots and draws: no component may query the system,
 * parse payloads or touch the disk on it. Components post work here instead
 * of spawning their own threads, so the thread population stays bounded
 * and every background task has an owner.
 */
object AppThreads {
    val state = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "app-state").apply { isDaemon = true } }
    /** Boot and unlock work is order-critical and must never queue behind
     * snapshot recomputes on [state]; it gets its own serial lane. */
    val boot = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "app-boot").apply { isDaemon = true } }
    val io = Executors.newCachedThreadPool { runnable -> Thread(runnable, "app-io").apply { isDaemon = true } }
    val main = Handler(Looper.getMainLooper())

    fun onState(block: () -> Unit) = state.execute(block)
    fun onBoot(block: () -> Unit) = boot.execute(block)
    fun onIo(block: () -> Unit) = io.execute(block)
}
