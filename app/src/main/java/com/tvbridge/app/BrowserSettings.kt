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

internal class BrowserSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("browser-settings", Context.MODE_PRIVATE)
    fun engine() = prefs.getString("search_engine", "bing")?.takeIf { it in BrowserNavigation.engines } ?: "bing"
    fun autoFullscreen() = prefs.getBoolean("auto_fullscreen", false)
    fun setEngine(engine: String): Boolean {
        if (engine !in BrowserNavigation.engines) return false
        prefs.edit().putString("search_engine", engine).apply(); return true
    }
    fun setAutoFullscreen(enabled: Boolean) { prefs.edit().putBoolean("auto_fullscreen", enabled).apply() }
    fun snapshot() = JSONObject().put("searchEngine", engine()).put("autoFullscreen", autoFullscreen())
}
