package com.tvbridge.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal object VendorInputFallbacks {
    private fun allowed(context: Context, intent: Intent): Boolean {
        val info = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull() ?: return false
        return info.enabled && info.applicationInfo.enabled && info.exported &&
            (info.permission.isNullOrEmpty() || androidx.core.content.ContextCompat.checkSelfPermission(context, info.permission) == PackageManager.PERMISSION_GRANTED)
    }

    private fun inputs(context: Context, family: String, ports: List<VendorSourceContracts.Port>, make: (Int) -> Intent): List<SystemControls.Input> =
        ports.mapNotNull { port ->
            val intent = make(port.selector).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!allowed(context, intent)) null else SystemControls.Input("$family:${port.key}", port.label, intent, when (port.kind) {
                VendorSourceContracts.Kind.HDMI -> SystemControls.Kind.Hdmi
                VendorSourceContracts.Kind.AV -> SystemControls.Kind.Av
                VendorSourceContracts.Kind.TV -> SystemControls.Kind.Tuner
            })
        }

    fun tcl(context: Context) = inputs(context, "tcl", VendorSourceContracts.tcl) { selector ->
        Intent().setComponent(ComponentName("com.tcl.tv", "com.tcl.tv.TVActivity"))
            .putExtra("targetSourceId", selector).putExtra("referer", "com.tcl.settings.module_source")
    }

    fun whaley(context: Context) = inputs(context, "whaley", VendorSourceContracts.whaley) { selector ->
        Intent("com.whaley.tv.tvplayer.ui.START_TV_PLAYER")
            .setComponent(ComponentName("com.whaley.tv.tvplayer.ui", "com.whaley.tv.tvplayer.ui.RootActivity")).putExtra("inputSrc", selector)
    }

    fun dangbei(context: Context): List<SystemControls.Input> {
        val chooser = Intent("com.dangbei.action.INPUTSOURCE_CHOOSER_ACTIVITY").setPackage("com.huanglong.tvui")
        if (!allowed(context, chooser)) return emptyList()
        return inputs(context, "huanglong", VendorSourceContracts.dangbei) { selector ->
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.huanglong.tvui", "com.huanglong.tvui.MainActivity")).putExtra("SourceName", selector)
        }
    }
}
