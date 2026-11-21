package com.tvbridge.app

import android.content.Context

object VendorSources {
    private class Provider(val needsStandardEmpty: Boolean, val load: (Context) -> List<SystemControls.Input>)

    private val providers = listOf(
        Provider(false) { XgimiInputAdapter.load(it) },
        Provider(false) { KonkaInputAdapter.load(it) },
        Provider(false) { HisenseInputAdapter.load(it) },
        Provider(false) { HisenseInputAdapter.loadLegacy(it) },
        Provider(true) { VendorInputFallbacks.tcl(it) },
        Provider(true) { VendorInputFallbacks.whaley(it) },
        Provider(true) { VendorInputFallbacks.dangbei(it) },
        Provider(false) { InputRoutes.skyworth(it) }
    )

    fun load(context: Context, standardEmpty: Boolean): List<SystemControls.Input> {
        for (provider in providers) {
            if (provider.needsStandardEmpty && !standardEmpty) continue
            val found = provider.load(context)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }
}
