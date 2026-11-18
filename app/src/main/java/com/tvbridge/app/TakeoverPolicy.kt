package com.tvbridge.app

import android.content.Context

/**
 * The one place where desktop-takeover decisions are resolved.
 *
 * Three sources used to feed the guard and the takeover actions directly,
 * with no stated precedence: [DesktopPolicy]'s hardcoded firmware
 * contracts, [DeviceProfile]'s probed-and-cached facts, and
 * [DeviceAdapters]' per-model JSON rules. Every consumer picked its own
 * mix, so a new device quirk meant new branches in several files. All
 * consumers now take a [Policy] from here instead.
 *
 * Precedence, highest first:
 *  1. Probed facts ([DeviceProfile], from [DesktopPolicy]'s contracts).
 *     Data never overrides what the firmware on this unit actually does.
 *  2. Per-model rules (device-adapters.json). A rule may only restrict
 *     (neverFilterKeys) or relax the one behaviour that is safe to relax
 *     per model (filterHomeWhenDefault); it cannot invent capabilities.
 *  3. The defaults in [Policy], which are the behaviour OneTV has always
 *     shipped: while takeover is active the guard filters Home, including
 *     when OneTV is already the default home.
 *
 * A future per-device override layer (written on one TV without an APK
 * update) belongs between 1 and 2: it may adjust the same rule fields,
 * never the probed facts.
 */
object TakeoverPolicy {
    data class Policy(
        /** VIDAA-style firmware answers Home itself; keys pass through. */
        val passKeys: Boolean,
        /** Filtering volume keys can wedge this firmware's volume UI. */
        val volumeFilterUnsafe: Boolean,
        /** Xiaomi API 31+: the firmware sends Back, not Home, on pause. */
        val backOnPause: Boolean,
        /** A Xiaomi input stack is present (mouse + input-source rules). */
        val xiaomiInput: Boolean,
        /** The guard must poll the foreground instead of trusting events. */
        val watchForeground: Boolean,
        /** API <= 23: another filter service owns the non-default slot. */
        val otherFilter: Boolean,
        /** Model rule: never request the key-filter flag on this unit. */
        val neverFilterKeys: Boolean,
        /** Model rule: while OneTV is the default home, let the firmware
         *  keep its own Home presses instead of consuming them. */
        val filterHomeWhenDefault: Boolean,
        /** How long the stock desktop may hold the screen after boot. */
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
