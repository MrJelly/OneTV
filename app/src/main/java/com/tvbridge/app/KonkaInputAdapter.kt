package com.tvbridge.app


import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

internal object KonkaInputAdapter {
    private const val TAG = "OneKonkaInputAdapter"
    private const val MANAGER = "com.konka.android.tv.KKCommonManager"
    private const val SOURCE_ENUM = "com.konka.android.tv.KKCommonManager\$EN_KK_INPUT_SOURCE"
    private val player = ComponentName("com.konka.tvsettings", "com.konka.tvsettings.RootActivity")

    fun load(context: Context): List<SystemControls.Input> {
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull()
            ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        return runCatching {
            val managerClass = Class.forName(MANAGER)
            val sourceClass = Class.forName(SOURCE_ENUM)
            val manager = managerClass.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            val reported = managerClass.getMethod("getTVInputSourceList").invoke(manager) as? List<*>
                ?: return emptyList()
            val connected = runCatching {
                managerClass.getMethod("getAvaliableInputSourceList").invoke(manager) as? List<*>
            }.getOrNull().orEmpty()
            val labelMethod = runCatching { managerClass.getMethod("getInputSourceName", sourceClass) }.getOrNull()
            reported.filterIsInstance<Enum<*>>().map { source ->
                val label = runCatching { labelMethod?.invoke(manager, source) as? String }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: source.name
                val kind = when {
                    source.name.startsWith("HDMI") || source.name == "EXTERNAL_8K_HDMI" -> SystemControls.Kind.Hdmi
                    source.name == "DP" || source.name == "TYPE_C" || source.name == "EXTERNAL_8K_DP" -> SystemControls.Kind.DisplayPort
                    source.name.startsWith("AV") || source.name.startsWith("YPBPR") || source.name.startsWith("SVIDEO") -> SystemControls.Kind.Av
                    source.name == "ATV" || source.name == "DTMB" || source.name.startsWith("DVB_") ||
                        source.name == "ATSC" || source.name == "ISDB_T" -> SystemControls.Kind.Tuner
                    else -> SystemControls.Kind.Other
                }
                SystemControls.Input(
                    id = "konka:${source.name}",
                    label = label,
                    kind = kind,
                    connected = source in connected,
                    deviceName = null,
                    intent = Intent("com.konka.tvsettings.intent.action.RootActivity")
                        .setComponent(player)
                        .putExtra("source", source.ordinal)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.distinctBy { it.id }.sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
        }.onFailure { Log.w(TAG, "Could not read the native Konka input list", it) }.getOrDefault(emptyList())
    }
}
