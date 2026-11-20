package com.tvbridge.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

object DesktopState {
    @Volatile var hasData = false
        private set
    @Volatile var defaultHome: ComponentName? = null
        private set
    @Volatile var guardListed = false
        private set
    @Volatile var accessibilityOn = false
        private set
    @Volatile var listenerListed = false
        private set
    @Volatile var homeEntryEnabled = true
        private set
    @Volatile var bootReceiverEnabled = true
        private set
    @Volatile var hasSecureWrite = false
        private set
    @Volatile var usageAllowed = false
        private set
    @Volatile var homes: Set<ComponentName> = emptySet()
        private set

    @Volatile private var owner: Context? = null

    fun hasDataFor(context: Context): Boolean = hasData && owner === context.applicationContext

    @Volatile private var refreshQueued = false
    @Volatile private var refreshDirty = false

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (owner !== app) hasData = false
        synchronized(this) {
            if (refreshQueued) {
                refreshDirty = true
                return
            }
            refreshQueued = true
        }
        AppThreads.onState {
            while (true) {
                runCatching { compute(app) }
                synchronized(this) {
                    if (!refreshDirty) {
                        refreshQueued = false
                        return@onState
                    }
                    refreshDirty = false
                }
            }
        }
    }

    fun noteGuardWrite(context: Context, listed: Boolean, accessibilityOn: Boolean? = null) {
        if (!hasData || owner !== context.applicationContext) return
        guardListed = listed
        if (accessibilityOn != null) this.accessibilityOn = accessibilityOn
    }

    fun refreshNow(context: Context) {
        runCatching { compute(context.applicationContext) }
    }

    private fun compute(app: Context) {
        val pm = app.packageManager
        val resolver = app.contentResolver
        defaultHome = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull()
        val guard = ComponentName(app, DesktopGuardService::class.java)
        guardListed = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == guard }
        accessibilityOn = runCatching { Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 }.getOrDefault(false)
        val listener = ComponentName(app, DesktopRevivalService::class.java)
        listenerListed = Settings.Secure.getString(resolver, "enabled_notification_listeners").orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == listener }
        homeEntryEnabled = runCatching { pm.getComponentEnabledSetting(ComponentName(app, "com.tvbridge.app.DesktopActivity")) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }.getOrDefault(false)
        bootReceiverEnabled = componentEnabled(pm, ComponentName(app, BootReceiver::class.java))
        hasSecureWrite = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        usageAllowed = DesktopPolicy.usageAllowed(app)
        homes = runCatching { DesktopTakeover.homes(app).toSet() }.getOrDefault(emptySet())
        owner = app
        hasData = true
    }

    private fun componentEnabled(pm: PackageManager, component: ComponentName): Boolean {
        val state = runCatching { pm.getComponentEnabledSetting(component) }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
