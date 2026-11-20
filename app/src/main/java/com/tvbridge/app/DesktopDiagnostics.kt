package com.tvbridge.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.util.ArrayDeque

internal object DesktopDiagnostics {
    private val events = ArrayDeque<String>()
    @Synchronized fun event(value: String) {
        if (events.size >= 100) events.removeFirst()
        events.addLast("${SystemClock.elapsedRealtime()}ms $value")
    }
    @Synchronized private fun recentEvents() = events.toList()

    fun report(context: Context): String = buildString {
        fun line(key: String, value: Any?) { append(key).append(": ").append(value ?: "unknown").append('\n') }
        append("One 桌面接管诊断 v1\n")
        line("生成时间", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        line("应用", context.packageName)
        line("版本", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.versionCode})" } }.getOrNull())
        line("设备", "${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL}")
        line("系统", "Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ${Build.DISPLAY}")
        line("运行时间秒", SystemClock.elapsedRealtime() / 1000)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        fun resolved(flags: Int) = runCatching { context.packageManager.resolveActivity(home, flags)?.activityInfo?.let { "${it.packageName}/${it.name}" } }.getOrNull()
        line("HOME 默认解析", resolved(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY))
        line("HOME 宽松解析", resolved(0))
        line("HOME 候选", runCatching { context.packageManager.queryIntentActivities(home, 0).joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" } }.getOrNull())
        line("接管已启用", DesktopTakeover.wanted(context))
        line("守护已授权", DesktopTakeover.enabled(context))
        line("守护已连接", DesktopGuardService.connected)
        line("UI 性能采样",UiPerformance.report())
        line("开机目标",LauncherApps(context).bootApp())
        line("开机目标已处理",BootReceiver.targetOfferedThisBoot(context))
        if(DesktopPolicy.stockHomeMustRemainDefault(context))line("MIUI 后台弹出界面",DesktopPolicy.stockHomeBackgroundAllowed(context))
        line("守护运行状态", DesktopGuardService.diagnosticState())
        line("使用情况访问", DesktopPolicy.usageAllowed(context))
        line("前台检测", if(DesktopPolicy.usageAllowed(context))"无障碍事件 + UsageStats 后台轮询（通常 2 秒）" else "无障碍事件；UsageStats 兜底待授权")
        line("原桌面豁免剩余毫秒", DesktopRecovery.stockVisitLeftMs(context))
        line("系统屏保组件", android.provider.Settings.Secure.getString(context.contentResolver,"screensaver_components"))
        line("系统屏保启用", android.provider.Settings.Secure.getInt(context.contentResolver,"screensaver_enabled",0))
        line("系统屏保触发", "sleep=${android.provider.Settings.Secure.getInt(context.contentResolver,"screensaver_activate_on_sleep",0)}, dock=${android.provider.Settings.Secure.getInt(context.contentResolver,"screensaver_activate_on_dock",0)}")
        val service = runCatching {
            (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .firstOrNull { it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name.endsWith("DesktopGuardService") }
        }.getOrNull()
        line("守护服务 flags", service?.flags)
        line("请求过滤按键", service?.let { it.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0 })
        val policy = TakeoverPolicy.resolve(context)
        line("策略", policy)
        line("机型规则序号", DeviceAdapters.profile(context).matchedRule ?: "未命中，使用默认策略")
        append("厂商包探测:\n")
        for (pkg in listOf("com.mitv.tvhome", "com.xiaomi.mitv.settings", "com.tianci.system", "com.hisense.vidaaassistant", "com.jamdeo.tv.vod", "com.tcl.cyberui", "com.tcl.tv", "com.whaley.tv.tvplayer.ui", "com.huanglong.tvui", "com.android.newsettings", "com.konka.tvsettings")) {
            line("  $pkg", DesktopPolicy.installed(context, pkg))
        }
        append("接管事件（本进程最近 100 条，不包含系统全量日志）:\n")
        recentEvents().forEach { append(it).append('\n') }
    }

    fun show(activity: MainActivity) {
        NativeUi.notice(activity, "正在生成接管诊断…")
        AppThreads.onState {
            val report = runCatching { report(activity.applicationContext) }.getOrElse { "诊断生成失败：${it.javaClass.simpleName}" }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                NativeUi.show(AlertDialog.Builder(activity).setTitle("桌面接管诊断").setMessage(report)
                    .setPositiveButton("复制") { _, _ ->
                        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("One 桌面接管诊断", report))
                        NativeUi.notice(activity, "诊断已复制")
                    }
                    .setNeutralButton("保存") { _, _ ->
                        val file = File(activity.cacheDir, "one-desktop-${System.currentTimeMillis()}.txt")
                        runCatching { file.writeText(report); activity.exportFile(file) }.onFailure { NativeUi.notice(activity, "诊断保存失败") }
                    }
                    .setNegativeButton("关闭", null), widthDp = 600)
            }
        }
    }
}
