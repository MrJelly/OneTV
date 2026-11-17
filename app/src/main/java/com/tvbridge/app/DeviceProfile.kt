package com.tvbridge.app

import android.content.Context
import android.os.Build

/**
 * What kind of TV this is, probed once per process.
 *
 * The vendor answers (package probes in [DesktopPolicy]) used to be
 * recomputed at every guard tick and every key configuration, a handful of
 * PackageManager binder calls each time. They only change when the package
 * set changes, so they are computed once on the state thread, cached here,
 * and recomputed when a package broadcast invalidates them. Readers use
 * the cached fields; before the first compute lands they see the safe
 * defaults, and the warm-up posted at guard connect lands within
 * milliseconds, so a key decision never waits on a probe.
 */
object DeviceProfile {
    @Volatile private var computed = false
    @Volatile private var owner: Context? = null
    @Volatile var passKeys = false
        private set
    @Volatile var volumeFilterUnsafe = false
        private set
    @Volatile var xiaomiInput = false
        private set
    @Volatile var backOnPause = false
        private set
    @Volatile var watchForeground = false
        private set
    /** API <= 23: another enabled accessibility service filters key events
     * without the DEFAULT flag, so the guard must yield the default slot. */
    @Volatile var otherFilter = false
        private set

    fun warm(context: Context) {
        val app = context.applicationContext
        if (computed && owner === app) return
        computed = false
        AppThreads.onState { compute(app) }
    }

    /** Synchronous first compute for this application: decision points
     * (key configuration, the guard's connect path) must not act on the
     * safe defaults while an async warm-up is still in flight. Costs a
     * few package queries, once per process per application. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        if (computed && owner === app) return
        compute(app)
    }

    fun invalidate(context: Context) {
        computed = false
        val app = context.applicationContext
        AppThreads.onState { compute(app) }
    }

    @Synchronized private fun compute(context: Context) {
        if (computed && owner === context) return
        passKeys = DesktopPolicy.passKeys(context)
        volumeFilterUnsafe = DesktopPolicy.volumeFilterUnsafe(context)
        xiaomiInput = DesktopPolicy.xiaomiInput(context)
        backOnPause = DesktopPolicy.backOnPause(context)
        watchForeground = Build.VERSION.SDK_INT in 21..23 || passKeys || volumeFilterUnsafe
        otherFilter = false
        if (Build.VERSION.SDK_INT <= 23) {
            val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val others = runCatching { manager?.installedAccessibilityServiceList }.getOrNull().orEmpty()
            val enabled = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').mapNotNull { android.content.ComponentName.unflattenFromString(it) }.toSet()
            otherFilter = others.any {
                android.content.ComponentName.unflattenFromString(it.id) in enabled &&
                    it.resolveInfo?.serviceInfo?.packageName != context.packageName &&
                    it.flags and flag != 0 &&
                    it.flags and android.accessibilityservice.AccessibilityServiceInfo.DEFAULT == 0
            }
        }
        owner = context
        computed = true
    }
}
