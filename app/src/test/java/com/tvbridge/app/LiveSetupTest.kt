package com.tvbridge.app

import android.content.Intent
import android.view.*
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveSetupTest {
    @org.junit.Before fun useCustomLiveFixture(){LiveLaunch.choose(org.robolectric.RuntimeEnvironment.getApplication(),null)}
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    @Test fun emptySourceRetainsConfiguredCountdownAndUsesSetupWithoutAPlayer(){
        val app=RuntimeEnvironment.getApplication();app.filesDir.resolve("live-sources.json").delete();Hub.live=LiveRepository(app);app.getSharedPreferences("startup",0).edit().putBoolean("live",true).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            assertNotNull(MainActivity::class.java.getDeclaredField("startupDialog").apply{isAccessible=true}.get(a))
            a.showLive()
            assertTrue(views(a.window.decorView).any{it.tag==NativeUi.QR_BOX})
            assertFalse(views(a.window.decorView).any{it is LivePanel||it is android.webkit.WebView})
            assertTrue(views(a.window.decorView).filterIsInstance<Button>().any{it.text=="刷新二维码"})
            assertTrue(views(a.window.decorView).filterIsInstance<TextView>().any{it.text=="手机连接，导入直播源"})
            views(a.window.decorView).filterIsInstance<Button>().single{it.text=="返回"}.performClick()
            assertFalse(views(a.window.decorView).any{it.tag==NativeUi.QR_BOX})
            a.showLive()
            assertFalse(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.isShowing==true)
            Hub.live.importText("测试,https://example.test/live.m3u8");a.liveUpdated()
            assertTrue(views(a.window.decorView).any{it is LivePanel})
            Hub.live.removeSource("local");a.liveUpdated()
            assertTrue(views(a.window.decorView).any{it.tag==NativeUi.QR_BOX})
            a.showHome();assertFalse(a.command("castPlay","https://example.test/video.mp4"))
            assertFalse(views(a.window.decorView).any{it is LivePanel})
            assertFalse(views(a.window.decorView).any{it.tag==NativeUi.QR_BOX})
        }finally{app.getSharedPreferences("startup",0).edit().clear().commit();c.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
}
