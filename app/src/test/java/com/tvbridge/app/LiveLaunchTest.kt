package com.tvbridge.app

import android.app.*
import android.content.*
import android.content.pm.*
import android.os.Looper
import android.view.*
import android.widget.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
class LiveLaunchTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val target=ComponentName("example.live","example.live.Player")
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    @Before fun prepare(){
        app.filesDir.resolve("live-sources.json").delete()
        LiveLaunch.choose(app,null)
        Hub.live=LiveRepository(app)
        val entry=ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName=target.packageName;name=target.className;enabled=true;exported=true;nonLocalizedLabel="测试直播";applicationInfo=ApplicationInfo().apply{packageName=target.packageName;enabled=true}}}
        Shadows.shadowOf(app.packageManager).installPackage(PackageInfo().apply{packageName=target.packageName;applicationInfo=entry.activityInfo.applicationInfo;activities=arrayOf(entry.activityInfo)})
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),entry)
        LauncherApps.invalidate(app)
    }
    @After fun cleanup(){NativeUi.dialogCommand("back");Hub.stop();Hub.activity=null;LiveLaunch.choose(app,null)}
    @Test fun externalChoiceHidesInternalChannelsAndPhoneSelectionUsesTheTvPreference(){
        val repo=LiveRepository(app)
        assertTrue(repo.importText("自定义频道,https://example.test/a.m3u8"))
        LiveLaunch.choose(app,target)
        val external=org.json.JSONObject(repo.snapshot())
        assertEquals("external",external.getString("playerMode"));assertEquals(0,external.getJSONArray("channels").length())
        assertEquals("测试直播",external.getString("playerName"))
        assertTrue(LiveLaunch.selectInternal(app,"one",repo))
        val builtin=org.json.JSONObject(repo.snapshot())
        assertEquals("one",builtin.getString("playerMode"));assertEquals("默认直播",LiveLaunch.label(app))
        assertTrue(builtin.getJSONArray("channels").length()>0);assertEquals(0,builtin.getJSONArray("sources").length())
        assertFalse(LiveLaunch.selectInternal(app,"unrecognized",repo));assertTrue(LiveLaunch.builtIn(app))
        assertTrue(LiveLaunch.selectInternal(app,"default",repo))
        assertEquals("自定义直播",LiveLaunch.label(app));assertEquals("自定义频道",repo.allChannels().single().name)
    }
    @Test fun homeLiveEntryLaunchesChosenApp(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            LiveLaunch.choose(a,target)
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="电视直播"}.performClick()
            assertEquals(target,Shadows.shadowOf(a).nextStartedActivity.component)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun automaticLiveLaunchWorksWithoutAnInternalPlaylist(){
        LiveLaunch.choose(app,target);app.getSharedPreferences("startup",0).edit().putBoolean("live",true).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            assertEquals(target,Shadows.shadowOf(a).nextStartedActivity.component)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun selectingExternalPlayerSavesChoiceWithoutLaunchingIt(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="控制中心"}.performClick()
            a.window.decorView.apply{measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));layout(0,0,1920,1080)}
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="直播管理"}.performClick()
            val settings=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val left=settings.window!!.attributes.x;val top=settings.window!!.attributes.y
            val chooser=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val options=views(chooser.window!!.decorView).filterIsInstance<Button>().filter{it.tag!=null}
            assertTrue(options.isNotEmpty());assertFalse(views(chooser.window!!.decorView).any{it is android.widget.RadioButton})
            assertTrue(options.all{(it.parent as android.view.ViewGroup).childCount==2})
            assertNotNull(options.first{it.tag=="one-player"}.compoundDrawablesRelative[2])
            options.first{it.tag==target}.performClick()
            assertEquals(target,LiveLaunch.component(a));assertNull(Shadows.shadowOf(a).nextStartedActivity)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(chooser.isShowing)
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="直播管理"}.performClick()
            val selected=views(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<Button>().first{it.tag==target}
            assertTrue(selected.isSelected);assertNotNull(selected.compoundDrawablesRelative[2])
        }finally{c.pause().stop().destroy()}
    }
    @Test fun homeBackStaysPutWhileDesktopIsEnabled(){
        app.getSharedPreferences("startup",0).edit().putBoolean("boot",true).putBoolean("desktop_guard",true).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{a.onBackPressed();assertFalse(a.isFinishing);assertFalse(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.isShowing==true)}
        finally{app.getSharedPreferences("startup",0).edit().clear().commit();c.pause().stop().destroy()}
    }
    @Test fun missingOrRemovedTargetNeverOpensAnUnrelatedApplication(){
        LiveLaunch.choose(app,ComponentName("missing.player","missing.player.Player"))
        assertFalse(LiveLaunch.open(app));assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertNull(app.getSharedPreferences("startup",0).getString("live_app",null))
        LiveLaunch.choose(app,null);assertFalse(LiveLaunch.configured(app))
    }
    @Test fun missingLiveAppFallsBackToOneInsteadOfLeavingHome(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            LiveLaunch.choose(a,ComponentName("missing.player","missing.player.Player"))
            assertTrue(a.command("liveOpen",""))
            assertFalse(LiveLaunch.configured(a))
            assertEquals("自定义直播",LiveLaunch.label(a))
            assertNull(Shadows.shadowOf(a).nextStartedActivity)
            val screen=MainActivity::class.java.getDeclaredField("screen").apply{isAccessible=true}.get(a)
            assertEquals("liveSetup",screen)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun removedPackageBroadcastClearsTheSelectedApp(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            LiveLaunch.choose(a,target)
            Shadows.shadowOf(a.packageManager).deletePackage(target.packageName)
            a.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REMOVED,android.net.Uri.parse("package:${target.packageName}")))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(a.getSharedPreferences("startup",0).getString("live_app",null))
        }finally{c.pause().stop().destroy()}
    }
}
