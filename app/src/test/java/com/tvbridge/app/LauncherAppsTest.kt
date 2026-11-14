package com.tvbridge.app

import android.app.Activity
import android.content.*
import android.content.pm.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LauncherAppsTest {
    @Test fun shortcutsPersistAndCanBeRemoved(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get()
        context.getSharedPreferences("launcher-apps",0).edit().clear().commit()
        val entry=LauncherApps.Entry(ComponentName("example.app","example.app.Main"),"测试应用")
        LauncherApps(context).toggle(entry)
        assertEquals(listOf(entry.component.flattenToString()),LauncherApps(context).saved())
        LauncherApps(context).toggle(entry);assertTrue(LauncherApps(context).saved().isEmpty())
    }
    @Test fun appCatalogIncludesTvAppsAndDeduplicatesPackages(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get()
        fun add(pkg:String,category:String){val info=ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName=pkg;name="$pkg.Main";exported=true;nonLocalizedLabel=pkg;applicationInfo=ApplicationInfo().apply{packageName=pkg}}};shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(category),info)}
        add("example.tv",Intent.CATEGORY_LEANBACK_LAUNCHER);add("example.tv",Intent.CATEGORY_LAUNCHER);add("example.phone",Intent.CATEGORY_LAUNCHER)
        val apps=LauncherApps(context).installed();assertEquals(1,apps.count{it.component.packageName=="example.tv"});assertTrue(apps.any{it.component.packageName=="example.phone"});assertFalse(apps.any{it.component.packageName==context.packageName})
        assertEquals(Intent.CATEGORY_LEANBACK_LAUNCHER,apps.first{it.component.packageName=="example.tv"}.category)
        LauncherApps(context).launch(apps.first{it.component.packageName=="example.tv"})
        assertTrue(shadowOf(context).nextStartedActivity.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
    }
    @Test fun catalogCacheInvalidatesAndUpdatedActivityKeepsFavorite(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get();val repo=LauncherApps(context)
        val original=repo.installed();assertSame(original,LauncherApps(context).installed())
        val pkg="example.updated";val info=ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName=pkg;name="$pkg.NewEntry";exported=true;nonLocalizedLabel="更新应用";applicationInfo=ApplicationInfo().apply{packageName=pkg}}}
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),info)
        repo.toggle(LauncherApps.Entry(ComponentName(pkg,"$pkg.OldEntry"),"旧入口"))
        LauncherApps.invalidate(context)
        val entry=repo.favorites().single();assertEquals("$pkg.NewEntry",entry.component.className)
        assertEquals(listOf(entry.component.flattenToString()),repo.saved())
        val icon=repo.icon(entry);assertNotSame(icon,repo.icon(entry));assertSame(icon.constantState,repo.icon(entry).constantState)
    }
    @Test fun shortcutLimitStillAllowsRemovingAndReplacing(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get();context.getSharedPreferences("launcher-apps",0).edit().clear().commit()
        val repo=LauncherApps(context);val entries=(0..10).map{LauncherApps.Entry(ComponentName("example.app$it","example.app$it.Main"),"App $it")}
        entries.take(10).forEach{assertTrue(repo.toggle(it))};assertFalse(repo.toggle(entries.last()));assertEquals(10,repo.saved().size)
        assertTrue(repo.toggle(entries.first()));assertTrue(repo.toggle(entries.last()));assertEquals(10,repo.saved().size)
        context.getSharedPreferences("launcher-apps",0).edit().clear().commit()
    }
    @Test fun shortcutsMoveAndDeleteWithoutRemovingInstalledApps(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get();context.getSharedPreferences("launcher-apps",0).edit().clear().commit()
        val repo=LauncherApps(context);val a=LauncherApps.Entry(ComponentName("example.a","example.a.Main"),"A");val b=LauncherApps.Entry(ComponentName("example.b","example.b.Main"),"B")
        repo.toggle(a);repo.toggle(b);assertTrue(repo.move(a,1));assertEquals(listOf(b.component.flattenToString(),a.component.flattenToString()),repo.saved());assertFalse(repo.move(a,1));repo.remove(a);assertEquals(listOf(b.component.flattenToString()),repo.saved());context.getSharedPreferences("launcher-apps",0).edit().clear().commit()
    }
    @Test fun manifestProvidesHomeCandidate(){
        val context=Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0).any{it.activityInfo.packageName==context.packageName})
    }
}
