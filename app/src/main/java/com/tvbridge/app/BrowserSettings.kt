package com.tvbridge.app

import android.content.Context
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

internal object BrowserNavigation {
    val engines = linkedMapOf("bing" to "必应", "baidu" to "百度", "google" to "Google")
    fun resolve(input: String, engine: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > 2000 || value.any { it.isISOControl() }) return null
        val explicit = value.contains("://")
        val address = explicit || (!value.any { it.isWhitespace() } &&
            (Regex("^localhost(?::[0-9]+)?(?:/.*)?$", RegexOption.IGNORE_CASE).matches(value) || Regex("^[^/\\s]+\\.[^/\\s]+(?:/.*)?$").matches(value)))
        if (address) {
            val url = if (explicit) value else "https://$value"
            return runCatching { URI(url).takeIf { it.scheme?.lowercase() in listOf("http", "https") && it.userInfo == null && !it.host.isNullOrEmpty() }?.toASCIIString() }.getOrNull()
        }
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) return null
        val base = when (engine) { "baidu" -> "https://www.baidu.com/s?wd="; "google" -> "https://www.google.com/search?q="; else -> "https://cn.bing.com/search?q=" }
        return base + URLEncoder.encode(value, "UTF-8")
    }
}

internal object BrowserSettings {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("browser-settings", Context.MODE_PRIVATE)
    fun engine(context: Context) = prefs(context).getString("search_engine", "bing")?.takeIf { it in BrowserNavigation.engines } ?: "bing"
    fun autoFullscreen(context: Context) = prefs(context).getBoolean("auto_fullscreen", false)
    fun setEngine(context: Context, engine: String): Boolean {
        if (engine !in BrowserNavigation.engines) return false
        prefs(context).edit().putString("search_engine", engine).apply(); return true
    }
    fun setAutoFullscreen(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("auto_fullscreen", enabled).apply() }
    fun snapshot(context: Context) = JSONObject().put("searchEngine", engine(context)).put("autoFullscreen", autoFullscreen(context))
}
