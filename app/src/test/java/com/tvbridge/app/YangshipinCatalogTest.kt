package com.tvbridge.app

import android.app.Activity
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class YangshipinCatalogTest {
    @org.junit.Before fun chooseBuiltIn(){LiveLaunch.choose(org.robolectric.RuntimeEnvironment.getApplication(),null,true)}
    @org.junit.After fun resetChoice(){LiveLaunch.choose(org.robolectric.RuntimeEnvironment.getApplication(),null)}
    @Test fun officialDefaultPersistsWithoutOverwritingLaterSourceChoice(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(activity)
        assertEquals(LiveCatalog.SOURCE,repo.activeSource())
        assertEquals(54,repo.allChannels().size)
        assertEquals("yangshipin-600001859",repo.allChannels().first().id)
        assertTrue(repo.allChannels().all{LiveCatalog.isPage(it.streams.single())})
        val favorite=repo.allChannels().first().id;repo.toggleFavorite(favorite)
        repo.importText("自定义,https://example.com/live.m3u8")
        LiveLaunch.choose(activity,null)
        val loaded=LiveRepository(activity)
        assertEquals("local",loaded.activeSource());assertTrue(loaded.favorite(favorite))
        assertEquals(1,loaded.allChannels().size)
        loaded.removeSource(LiveCatalog.SOURCE)
        assertTrue(LiveRepository(activity).sourceList().none{it.url==LiveCatalog.SOURCE})
    }
    @Test fun pageRecognitionRejectsNonOfficialAndMalformedAddresses(){
        assertTrue(YangshipinCatalog.isPage("https://www.yangshipin.cn/tv/home?pid=600001859"))
        for(url in listOf("https://www.yangshipin.cn.evil.test/tv/home?pid=600001859","http://www.yangshipin.cn/tv/home?pid=600001859","https://user@www.yangshipin.cn/tv/home?pid=600001859","https://www.yangshipin.cn:9999/tv/home?pid=600001859","https://www.yangshipin.cn/tv/home?pid=bad","https://example.com/video.mp4"))assertFalse(url,YangshipinCatalog.isPage(url))
    }
    @Test fun officialPreviewKeepsOpaqueCoverWhileWebSurfaceRenders(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete();val repo=LiveRepository(activity)
        val panel=LivePanel(activity,repo,{}){c,tap,state->YangshipinPlayer(c,tap,state)};activity.setContentView(panel);panel.play(repo.allChannels().first())
        fun field(name:String)=LivePanel::class.java.getDeclaredField(name).apply{isAccessible=true}.get(panel)
        val cover=field("title") as android.widget.TextView
        assertEquals(android.view.View.VISIBLE,cover.visibility)
        assertTrue(cover.background is android.graphics.drawable.GradientDrawable)
        assertEquals(255,cover.background.alpha)
        assertEquals("加载中",cover.text.toString())
        assertNull("Official playback must not allocate a second unused player",field("nativePlayer"))
        val web=field("officialPlayer") as YangshipinPlayer
        assertEquals(1f,web.view.alpha,0f)
        val stage=field("videoSurface") as android.view.View
        val parent=stage.parent
        val epoch=field("playbackEpoch")
        panel.command("liveFull","");assertSame(parent,stage.parent);assertEquals(epoch,field("playbackEpoch"))
        panel.command("liveFull","");assertSame(parent,stage.parent);assertEquals(epoch,field("playbackEpoch"))
        panel.play(LiveChannel("cast","File","投屏",listOf("https://example.com/sample.mp4")),true)
        assertEquals(android.view.View.GONE,web.view.visibility)
        panel.stop()
    }
    @Test fun compactFiltersDoNotClipAndFocusedChannelUsesWhiteText(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete();val repo=LiveRepository(activity)
        val panel=LivePanel(activity,repo,{}){c,tap,state->YangshipinPlayer(c,tap,state)};activity.setContentView(panel)
        panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY));panel.layout(0,0,1920,1080)
        fun field(name:String)=LivePanel::class.java.getDeclaredField(name).apply{isAccessible=true}.get(panel)
        val filter=field("groupButton") as android.widget.Button
        assertEquals(0,filter.paddingTop);assertEquals(0,filter.paddingBottom)
        panel.handleKey(android.view.KeyEvent.KEYCODE_MENU);panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY));panel.layout(0,0,1920,1080)
        val list=field("drawerList") as android.widget.GridView;list.requestFocus();list.setSelection(1)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        val label=list.adapter.getView(1,null,list) as android.widget.TextView
        assertTrue(label.isSelected);assertEquals(android.graphics.Color.WHITE,label.currentTextColor)
        filter.requestFocus();val inactive=list.adapter.getView(1,null,list) as android.widget.TextView
        assertFalse(inactive.isSelected);assertEquals(android.graphics.Color.WHITE,inactive.currentTextColor)
        panel.stop()
    }
    @Test fun smallCategorySetsUseTabsAndLargeSetsUseDropdown(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete();val repo=LiveRepository(activity)
        val panel=LivePanel(activity,repo,{}){c,tap,state->YangshipinPlayer(c,tap,state)};activity.setContentView(panel)
        val field=LivePanel::class.java.getDeclaredField("filterRow").apply{isAccessible=true}
        val row=field.get(panel) as android.widget.LinearLayout
        assertEquals(listOf("全部","央视","卫视","收藏"),(0 until row.childCount).map{(row.getChildAt(it) as android.widget.Button).text.toString()})
        LiveLaunch.choose(activity,null)
        repo.importText("甲,#genre#\nA,https://example.com/a.m3u8\n乙,#genre#\nB,https://example.com/b.m3u8\n丙,#genre#\nC,https://example.com/c.m3u8")
        panel.refresh();assertEquals(5,row.childCount)
        repo.importText("甲,#genre#\nA,https://example.com/a.m3u8\n乙,#genre#\nB,https://example.com/b.m3u8\n丙,#genre#\nC,https://example.com/c.m3u8\n丁,#genre#\nD,https://example.com/d.m3u8")
        panel.refresh();assertEquals(2,row.childCount);assertNotNull((row.getChildAt(0) as android.widget.Button).compoundDrawablesRelative[2])
        panel.stop()
    }
}
