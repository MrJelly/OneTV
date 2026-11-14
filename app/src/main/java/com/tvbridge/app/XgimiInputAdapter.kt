package com.tvbridge.app


import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

internal object XgimiInputAdapter {
    private const val TAG = "OneXgimiSources"
    private const val CONFIG_MANAGER = "com.xgimi.clients.GimiConfigManager"
    private val player = ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity")
    private val hdmiSwitch = Regex("HDMI([1-9][0-9]*)?")

    fun load(context: Context): List<SystemControls.Input> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return emptyList()
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull()
            ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        val permission = activity.permission
        if (permission != null && ContextCompat.checkSelfPermission(context, permission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return runCatching {
            val managerClass = Class.forName(CONFIG_MANAGER)
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val config = managerClass.getMethod("getXgimiConfig", String::class.java)
                .invoke(manager, "global") as? String ?: return emptyList()
            parseConfig(config, connectedSources())
        }.onFailure { Log.w(TAG, "Could not read the native XGIMI input config", it) }
            .getOrDefault(emptyList())
    }

    internal fun parseConfig(raw: String, connected: Map<String, Boolean>): List<SystemControls.Input> {
        val signals = JSONObject(raw).optJSONArray("signal") ?: return emptyList()
        return (0 until signals.length()).mapNotNull { index ->
            val signal = signals.optJSONObject(index) ?: return@mapNotNull null
            val switch = signal.optString("sw").trim().uppercase(Locale.ROOT)
            val match = hdmiSwitch.matchEntire(switch) ?: return@mapNotNull null
            val port = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return@mapNotNull null
            val sourceName = "HDMI$port"
            val nativeLabel = signal.optString("name").trim()
            SystemControls.Input(
                id = "xgimi:$sourceName",
                label = nativeLabel.takeUnless {
                    it.isBlank() || it.equals("HDMI", ignoreCase = true)
                } ?: "HDMI $port",
                kind = SystemControls.Kind.Hdmi,
                connected = connected[signal.optString("source")] == true,
                deviceName = null,
                intent = Intent("com.xgimi.action.hdmiPlayer").setComponent(player)
                    .putExtra("launcher_start_source", sourceName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.distinctBy { it.id }
    }

    private fun connectedSources(): Map<String, Boolean> = runCatching {
        val commonClass = Class.forName("com.mstar.android.tv.TvCommonManager")
        val common = commonClass.getMethod("getInstance").invoke(null)
        val flags = commonClass.getMethod("GetInputSourceStatus").invoke(common) as? BooleanArray
            ?: return emptyMap()
        val sourceClass = Class.forName("com.mstar.android.tvapi.common.vo.TvOsType\$EnumInputSource")
        sourceClass.enumConstants.orEmpty().filterIsInstance<Enum<*>>()
            .associate { it.name to (flags.getOrNull(it.ordinal) == true) }
    }.getOrDefault(emptyMap())
}
