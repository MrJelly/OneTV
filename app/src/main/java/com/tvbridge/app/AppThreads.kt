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

object AppThreads {
    private val ioSeq = AtomicInteger(1)

    private fun daemonFactory(prefix: String): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, prefix).apply { isDaemon = true }
    }

    val state: ExecutorService = Executors.newSingleThreadExecutor(daemonFactory("app-state"))

    val boot: ExecutorService = Executors.newSingleThreadExecutor(daemonFactory("app-boot"))

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
