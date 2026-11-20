package com.tvbridge.app

import android.content.Context

object TakeoverPolicy {
    data class Policy(
        val passKeys: Boolean,
        val volumeFilterUnsafe: Boolean,
        val backOnPause: Boolean,
        val xiaomiInput: Boolean,
        val watchForeground: Boolean,
        val otherFilter: Boolean,
        val neverFilterKeys: Boolean,
        val filterHomeWhenDefault: Boolean,
        val bootStockMs: Int
    )

    fun resolve(context: Context): Policy {
        DeviceProfile.ensure(context)
        val rules = DeviceAdapters.profile(context)
        return Policy(
            passKeys = DeviceProfile.passKeys,
            volumeFilterUnsafe = DeviceProfile.volumeFilterUnsafe,
            backOnPause = DeviceProfile.backOnPause,
            xiaomiInput = DeviceProfile.xiaomiInput,
            watchForeground = DeviceProfile.watchForeground,
            otherFilter = DeviceProfile.otherFilter,
            neverFilterKeys = rules.neverFilterKeys,
            filterHomeWhenDefault = rules.filterHomeWhenDefault,
            bootStockMs = rules.bootStockMs
        )
    }
}
