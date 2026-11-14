package com.tvbridge.app


import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object ChanghongSettings {
    const val PACKAGE = "com.changhong.easysetting"
    const val NETWORK = "StatusBar_to_netset"
    const val PICTURE = "StatusBar_to_videoset"
    val component = ComponentName(PACKAGE, "$PACKAGE.MainService")

    fun available(context: Context): Boolean = runCatching {
        val info = context.packageManager.resolveService(Intent().setComponent(component), 0)?.serviceInfo
        info != null && info.exported && info.enabled && info.applicationInfo.enabled &&
            (info.permission.isNullOrEmpty() || context.checkCallingOrSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    fun intent(context: Context, page: String? = null): Intent? {
        if (!available(context)) return null
        val intent = Intent("Changhong.EasySetting").setComponent(component)
        if (page != null) intent.putExtra(page, true)
        return intent
    }

    fun open(context: Context, intent: Intent): Boolean =
        intent.component == component && available(context) && runCatching { context.startService(intent) }.getOrNull() != null
}
