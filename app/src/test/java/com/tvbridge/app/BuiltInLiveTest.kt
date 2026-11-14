package com.tvbridge.app

import android.app.Activity
import android.view.*
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BuiltInLiveTest {
    @Test fun aFreshInstallDefaultsToTheFirstBuiltInChoice(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("startup",0).edit().remove("live_mode").remove("live_app").commit()
        assertTrue(LiveLaunch.builtIn(app));assertEquals("默认直播",LiveLaunch.label(app))
        val control=Robolectric.buildActivity(Activity::class.java).setup()
        try{
            LiveSettings(control.get()).show()
            val options=views(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<Button>().filter{it.tag in listOf("one-live","one-player")}
            assertEquals(listOf("默认直播","自定义直播"),options.map{it.text.toString()})
            assertTrue(options[0].isSelected);assertFalse(options[1].isSelected)
        }finally{NativeUi.dialogCommand("back");control.pause().stop().destroy();LiveLaunch.choose(app,null)}
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    @Test fun switchingPlayersKeepsImportedSourcesAndFavoritesWithoutInstallingAnything(){
        val control=Robolectric.buildActivity(Activity::class.java).setup();val a=control.get()
        try{
            LiveLaunch.choose(a,null);a.filesDir.resolve("live-sources.json").delete()
            Hub.live=LiveRepository(a)
            assertTrue(Hub.live.allChannels().isEmpty())
            assertTrue(Hub.live.importText("自选,https://example.test/live.m3u8"))
            val custom=Hub.live.allChannels().single();Hub.live.toggleFavorite(custom.id);Hub.live.rememberChannel(custom.id)
            LiveSettings(a).show()
            val chooser=ShadowAlertDialog.getLatestAlertDialog()
            val buttons=views(chooser.window!!.decorView).filterIsInstance<Button>()
            assertEquals(1,buttons.count{it.text=="自定义直播"});assertEquals(1,buttons.count{it.text=="默认直播"})
            assertFalse(buttons.any{it.text.contains("插件")||it.text.contains("挂载")})
            buttons.single{it.text=="默认直播"}.performClick()
            assertFalse(chooser.isShowing);assertTrue(LiveLaunch.builtIn(a))
            assertTrue(Hub.live.allChannels().any{it.id=="yangshipin-600000492"})
            assertTrue(Hub.live.allChannels().all{it.streams.all(LiveCatalog::isPage)})
            assertNull(Shadows.shadowOf(a).nextStartedActivity)
            LiveSettings(a).show()
            views(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<Button>().single{it.text=="自定义直播"}.performClick()
            assertEquals(custom,Hub.live.resumeChannel());assertTrue(Hub.live.favorite(custom.id))
            assertEquals(listOf(custom),LiveRepository(a).allChannels())
        }finally{LiveLaunch.choose(a,null);control.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
}
