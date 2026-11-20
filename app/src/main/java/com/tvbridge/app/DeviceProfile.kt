package com.tvbridge.app

import android.content.Context
import android.os.Build

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
    @Volatile var otherFilter = false
        private set

    fun warm(context: Context) {
        val app = context.applicationContext
        if (computed && owner === app) return
        computed = false
        AppThreads.onState { compute(app) }
    }

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
