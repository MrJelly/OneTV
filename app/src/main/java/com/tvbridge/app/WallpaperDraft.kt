package com.tvbridge.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.InputStream

internal class WallpaperDraft(private val owner: Context) {
    private val directory = File.createTempFile("wallpaper-draft-", "", owner.cacheDir).apply { delete(); mkdirs() }
    private val preferencesName = directory.name
    private val context = object : ContextWrapper(owner) {
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            owner.getSharedPreferences(preferencesName, mode)
    }
    var selection = HomeAppearance.mode(owner)
    var busy = false
    var dismissed = false
    init {
        OnlineWallpapers.copyMetadata(context,owner)
        context.getSharedPreferences("appearance", 0).edit()
            .putString("custom_theme", HomeAppearance.customTheme(owner))
            .putInt("bing_idx", HomeAppearance.bingIdx(owner)).apply()
    }
    private fun source(mode: String): Context = when (mode) {
        "custom" -> if (HomeAppearance.hasCustom(context)) context else owner
        "bing" -> if (HomeAppearance.hasBing(context)) context else owner
        else -> owner
    }
    fun available(mode: String) = when (mode) {
        "custom" -> HomeAppearance.hasCustom(source(mode))
        "bing" -> HomeAppearance.hasBing(source(mode))
        else -> true
    }
    fun thumbnail(mode: String): Bitmap? = when (mode) {
        "custom" -> HomeAppearance.customThumbnail(source(mode))
        "bing" -> HomeAppearance.bingThumbnail(source(mode))
        else -> ProceduralWallpaper.bitmap(320, 180, true)
    }
    fun importImage(input: InputStream) { HomeAppearance.importImage(context, input) }
    fun refresh(): String? = HomeAppearance.downloadBing(context, available("bing")).also{if(it==null)OnlineWallpapers.copyMetadata(owner,context)}
    fun commit() {
        when (selection) {
            "custom" -> {
                if (HomeAppearance.hasCustom(context)) HomeAppearance.customFile(context).inputStream().use { HomeAppearance.importImage(owner, it) }
                HomeAppearance.useCustom(owner)
            }
            "bing" -> {
                if (HomeAppearance.hasBing(context)) HomeAppearance.applyBingDraft(owner, context)
                HomeAppearance.setBing(owner)
            }
            else -> HomeAppearance.setGradient(owner)
        }
    }
    fun dispose() {
        directory.deleteRecursively()
        if (Build.VERSION.SDK_INT >= 24) owner.deleteSharedPreferences(preferencesName)
        else context.getSharedPreferences("appearance", 0).edit().clear().apply()
    }
}
