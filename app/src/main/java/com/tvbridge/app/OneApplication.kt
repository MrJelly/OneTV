package com.tvbridge.app

import android.content.Context
import android.util.Log
import androidx.multidex.MultiDexApplication
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application entry point owning process-wide initialization:
 * installs a single uncaught exception logger (covering Activities, Services,
 * and BootReceiver) without nesting handlers across Activity recreations.
 */
class OneApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        installCrashRecorder(this)
    }

    companion object {
        private const val CRASH_FILE = "last-crash.txt"
        private val installed = AtomicBoolean(false)

        fun installCrashRecorder(context: Context) {
            if (!installed.compareAndSet(false, true)) return
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    val dir = app.filesDir
                    if (dir != null && (dir.isDirectory || dir.mkdirs())) {
                        File(dir, CRASH_FILE).writeText(
                            "Thread: ${thread.name}\n" + Log.getStackTraceString(error)
                        )
                    }
                } catch (_: Throwable) {
                    // Direct Boot locked storage or disk full: never mask the original crash.
                }
                previous?.uncaughtException(thread, error)
            }
        }

        fun consumeLastCrash(context: Context): String? {
            return try {
                val file = File(context.applicationContext.filesDir, CRASH_FILE)
                if (!file.isFile) return null
                val text = file.readText()
                file.delete()
                text.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
