package com.tvbridge.app

import android.view.*
import android.widget.*
import android.app.AlertDialog
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TvRegressionTest {
    @Before fun platformPlayers(){
        LiveLaunch.choose(RuntimeEnvironment.getApplication(),null)
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply{isAccessible=true}
        (cache.get(null) as MutableMap<*,*>).clear()
    }
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))} else emptyList()
    @After fun clean(){Hub.stop();Hub.activity=null}
    @Test fun reopeningRunningAppDoesNotApplyLiveStartupAgain(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        try{
            descendants(activity.window.decorView).filterIsInstance<Button>().first{it.text=="控制中心"}.performClick()
            activity.getSharedPreferences("startup",0).edit().putBoolean("live",true).commit()
            Hub.live.importText("测试频道,https://example.test/live.m3u8")
            val screen=MainActivity::class.java.getDeclaredField("screen").apply{isAccessible=true}
            assertEquals("settings",screen.get(activity))
            controller.newIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER))
            assertEquals("settings",screen.get(activity))
            val countdown=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(countdown.isShowing)
            countdown.cancel()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            assertEquals("settings",screen.get(activity))
            assertFalse(countdown.isShowing)
            controller.newIntent(android.content.Intent().putExtra("show_home",true))
            assertEquals("home",screen.get(activity))
        }finally{activity.getSharedPreferences("startup",0).edit().remove("live").commit();controller.pause().stop().destroy()}
    }
    @Test fun allAppsLaunchesWhileAddAppsOnlyPins(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        try {
            activity.getSharedPreferences("launcher-apps",0).edit().clear().commit()
            val component=android.content.ComponentName("example.catalog","example.catalog.Main")
            val info=android.content.pm.ResolveInfo().apply { activityInfo=android.content.pm.ActivityInfo().apply {packageName=component.packageName;name=component.className;exported=true;nonLocalizedLabel="示例应用";applicationInfo=android.content.pm.ApplicationInfo().apply{packageName=component.packageName}} }
            shadowOf(activity.packageManager).addResolveInfoForIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),info)
            LauncherApps.invalidate(activity)
            fun button(label:String)=descendants(activity.window.decorView).filterIsInstance<Button>().first{it.text==label&&it.isShown}
            button("全部应用").performClick()
            assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>().any{it.text=="编辑首页"&&it.isShown})
            button("示例应用").performClick()
            assertEquals(component,shadowOf(activity).nextStartedActivity.component)
            assertTrue(LauncherApps(activity).saved().isEmpty())
            activity.command("back","");button("添加应用").performClick();button("示例应用").performClick()
            assertEquals(listOf(component.flattenToString()),LauncherApps(activity).saved())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { activity.getSharedPreferences("launcher-apps",0).edit().clear().commit();controller.pause().stop().destroy() }
    }
    @Test fun homepageFocusAndDialogClickWork(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        val decor=activity.window.decorView;decor.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));decor.layout(0,0,1920,1080)
        val buttons=descendants(decor).filterIsInstance<Button>();val files=buttons.first{it.text=="文件管理"}
        assertTrue(activity.command("right",""));assertTrue(files.hasFocus());assertTrue(activity.command("ok",""));assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing)
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(activity.command("back",""));assertFalse(dialog.isShowing)
        files.performClick();val second=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(second.isShowing);activity.command("back","");assertFalse(second.isShowing)
        repeat(2){assertTrue(activity.command("back",""))}
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
    @Test fun sourceChooserIsCompactAndOldCategoryDoesNotHideNewSource(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get();val context=activity;context.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(context);assertTrue(repo.importText("新闻,#genre#\n频道一,https://example.com/one.m3u8\n频道二,https://example.com/two.m3u8"))
        val panel=LivePanel(context,repo,{});activity.setContentView(panel)
        panel.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));panel.layout(0,0,1920,1080)
        panel.handleKey(KeyEvent.KEYCODE_MENU);panel.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));panel.layout(0,0,1920,1080)
        val texts=descendants(panel).filterIsInstance<TextView>();assertFalse(texts.any{it.text.toString().contains("本地导入")&&it.isShown});assertFalse(texts.any{it.text=="首页"})
        descendants(panel).filterIsInstance<Button>().first{it.text=="直播源"}.performClick()
        val sourceChooser=LivePanel::class.java.getDeclaredField("groupPopup").apply{isAccessible=true}.get(panel) as ListPopupWindow;assertTrue((0 until sourceChooser.listView!!.adapter.count).any{sourceChooser.listView!!.adapter.getItem(it).toString().contains("本地导入")});sourceChooser.dismiss()
        val list=descendants(panel).filterIsInstance<GridView>().first();assertEquals(2,list.adapter.count);assertTrue(list.measuredHeight>100)
        descendants(panel).filterIsInstance<Button>().first{it.text.toString()=="新闻"}.performClick();assertEquals(2,list.adapter.count)
        repo.removeSource("local");assertTrue(repo.importText("体育,#genre#\n体育频道,https://example.com/sport.m3u8"));panel.refresh();assertEquals(1,list.adapter.count);panel.stop()
    }
    @Test fun addEntryEditsHomeWithoutLaunchingAndControlCenterHasOneDesktopEntry(){
        val context=org.robolectric.RuntimeEnvironment.getApplication();context.getSharedPreferences("startup",0).edit().clear().commit();context.getSharedPreferences("launcher-apps",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        val info=android.content.pm.ResolveInfo().apply{activityInfo=android.content.pm.ActivityInfo().apply{packageName="example.player";name="example.player.Main";exported=true;nonLocalizedLabel="测试播放器";applicationInfo=android.content.pm.ApplicationInfo().apply{packageName="example.player"}}}
        shadowOf(activity.packageManager).addResolveInfoForIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),info)
            LauncherApps.invalidate(activity)
        descendants(activity.window.decorView).filterIsInstance<Button>().first{it.text=="添加应用"}.performClick()
        assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any{it.text=="完成"})
        descendants(activity.window.decorView).filterIsInstance<Button>().first{it.text=="测试播放器"}.performClick()
        assertTrue(LauncherApps(activity).saved().contains("example.player/example.player.Main"));assertNull(shadowOf(activity).nextStartedActivity)
        activity.showHome();descendants(activity.window.decorView).filterIsInstance<Button>().first{it.text=="控制中心"}.performClick()
        val labels=descendants(activity.window.decorView).filterIsInstance<TextView>().map{it.text.toString()}
        assertEquals(1,labels.count{it=="桌面应用"})
        assertFalse(labels.any{it in listOf("遥控设置","常规设置","开机接管","系统遥控","后台连接","撤销手机配对","授权安装应用")})
        controller.pause().stop().destroy();context.getSharedPreferences("launcher-apps",0).edit().clear().commit()
    }
    @Test fun allAppsAndStartupPreferenceAreAccessible(){
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("startup",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any{it.text=="全部应用"})
        descendants(activity.window.decorView).filterIsInstance<Button>().first{it.text=="控制中心"}.performClick()
        val toggle=descendants(activity.window.decorView).filterIsInstance<Switch>().first{it.text=="打开时进入直播"}
        assertFalse(toggle.isChecked);toggle.performClick();assertTrue(context.getSharedPreferences("startup",0).getBoolean("live",false))
        assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any{it.text=="桌面应用"})
        Hub.live.importText("启动测试,https://example.test/startup.m3u8")
        controller.pause().stop().destroy()
        val second=Robolectric.buildActivity(MainActivity::class.java).setup();val field=MainActivity::class.java.getDeclaredField("screen").apply{isAccessible=true}
        assertEquals("home",field.get(second.get()))
        assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals("live",field.get(second.get()));second.get().showHome();assertEquals("home",field.get(second.get()))
        second.pause().stop().destroy();context.getSharedPreferences("startup",0).edit().clear().commit()
    }
    @Test fun favoritesShowAllAndRestorePreviousCategory(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(activity);repo.importText("新闻,#genre#\n新闻一,https://example.com/a.m3u8\n体育,#genre#\n体育一,https://example.com/b.m3u8")
        repo.toggleFavorite(repo.allChannels().first{it.group=="新闻"}.id)
        val panel=LivePanel(activity,repo,{});activity.setContentView(panel)
        fun button(name:String)=descendants(LivePanel::class.java.getDeclaredField("filterRow").apply{isAccessible=true}.get(panel) as View).filterIsInstance<Button>().first{it.text==name}
        val list=descendants(panel).filterIsInstance<GridView>().first()
        button("体育").performClick();assertEquals(1,list.adapter.count)
        button("收藏").performClick();assertEquals(1,list.adapter.count);assertTrue(button("收藏").isSelected)
        button("体育").requestFocusFromTouch();assertEquals(1,list.adapter.count);assertTrue(button("体育").isSelected)
        panel.stop()
    }
    @Test fun fullscreenDrawerUsesTwoColumnsAndOnlyConfirmChangesChannel(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(activity);repo.importText("A,https://example.com/a.m3u8\nB,https://example.com/b.m3u8")
        val panel=LivePanel(activity,repo,{});activity.setContentView(panel)
        val list=descendants(panel).filterIsInstance<GridView>().first()
        val field=LivePanel::class.java.getDeclaredField("current").apply{isAccessible=true}
        panel.handleKey(KeyEvent.KEYCODE_MENU);panel.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));panel.layout(0,0,1920,1080);assertEquals(2,list.numColumns)
        list.setSelection(1);shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));assertNull(field.get(panel))
        list.performItemClick(null,0,0);assertNotNull(field.get(panel));assertFalse(list.isShown);panel.handleKey(KeyEvent.KEYCODE_MENU)
        val first=field.get(panel);list.setSelection(1);shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));assertSame(first,field.get(panel))
        list.performItemClick(null,0,0);assertFalse(list.isShown);panel.stop()
    }
    @Test fun fullscreenRemoteCanFocusAndToggleFavorite(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(activity);repo.importText("Focus channel,https://example.com/focus.m3u8")
        val panel=LivePanel(activity,repo,{});activity.setContentView(panel)
        val channel=repo.allChannels().first();panel.play(channel,true)
        val controls=LivePanel::class.java.getDeclaredField("controls").apply{isAccessible=true}.get(panel) as LinearLayout
        assertFalse(controls.isShown)
        assertTrue(panel.handleKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("上一台","频道列表","收藏","切换线路","下一台","返回"),(0 until controls.childCount).map{controls.getChildAt(it)}.filter{it.visibility==android.view.View.VISIBLE}.map{(it as Button).text.toString()})
        assertTrue(controls.getChildAt(0).hasFocus())
        assertTrue(panel.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT));assertTrue(panel.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(controls.getChildAt(2).hasFocus())
        val initial=repo.favorite(channel.id);assertTrue(panel.handleKey(KeyEvent.KEYCODE_DPAD_CENTER));assertEquals(!initial,repo.favorite(channel.id))
        assertEquals(if(!initial)"已收藏"else "收藏",(controls.getChildAt(2) as Button).text.toString())
        repeat(3){assertTrue(panel.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT))};assertTrue(controls.getChildAt(controls.childCount-1).hasFocus())
        assertTrue(panel.back());assertFalse(controls.isShown)
        assertTrue(panel.handleKey(KeyEvent.KEYCODE_DPAD_CENTER));assertTrue(controls.getChildAt(0).hasFocus());panel.stop()
    }
    @Test fun fullscreenArrowsChangeChannelsAndMenuOpensDrawer(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(activity);repo.importText("A,https://example.com/a.m3u8\nB,https://example.com/b.m3u8\nC,https://example.com/c.m3u8")
        val panel=LivePanel(activity,repo,{});activity.setContentView(panel)
        val channels=repo.allChannels();panel.play(channels[0],true)
        fun field(name:String)=LivePanel::class.java.getDeclaredField(name).apply{isAccessible=true}.get(panel)
        fun current()=(field("current") as LiveChannel).id
        val controls=field("controls") as LinearLayout;val drawer=field("drawer") as LinearLayout;val drawerList=field("drawerList") as GridView
        for((key,index) in listOf(KeyEvent.KEYCODE_DPAD_RIGHT to 1,KeyEvent.KEYCODE_DPAD_DOWN to 2,KeyEvent.KEYCODE_DPAD_LEFT to 1,KeyEvent.KEYCODE_DPAD_UP to 0,KeyEvent.KEYCODE_DPAD_LEFT to 2,KeyEvent.KEYCODE_DPAD_RIGHT to 0)){
            assertTrue(panel.handleKey(key));assertEquals(channels[index].id,current());assertFalse(controls.isShown)
        }
        assertTrue(panel.handleKey(KeyEvent.KEYCODE_MENU));assertTrue(drawer.isShown);assertTrue(drawerList.hasFocus());assertFalse(controls.isShown)
        drawerList.setSelection(1);assertEquals(channels[0].id,current())
        assertTrue(panel.back());assertFalse(drawer.isShown);assertFalse(controls.isShown)
        panel.handleKey(KeyEvent.KEYCODE_DPAD_CENTER);assertTrue(controls.isShown)
        panel.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(channels[0].id,current())
        panel.handleKey(KeyEvent.KEYCODE_MENU);assertTrue(drawer.isShown);assertFalse(controls.isShown)
        panel.handleKey(KeyEvent.KEYCODE_MENU);assertFalse(drawer.isShown);assertFalse(controls.isShown)
        panel.stop()
    }
    @Test fun dialogButtonsFollowThemeAndSharedFileUriIsScoped(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val dialog=NativeUi.show(AlertDialog.Builder(activity).setMessage("测试").setPositiveButton("确定",null).setNegativeButton("取消",null))
        assertEquals(GlassUi.ink(activity),dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
        assertEquals(GlassUi.ink(activity),dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor)
        dialog.dismiss()
        val folder=java.io.File(activity.filesDir,"received").apply{mkdirs()};val file=java.io.File(folder,"sample.apk").apply{writeText("fixture")}
        val uri=androidx.core.content.FileProvider.getUriForFile(activity,"com.tvbridge.app.files",file)
        assertEquals("content",uri.scheme);assertTrue(uri.path!!.startsWith("/received/"))
    }
    @Test fun liveWindowRecoveryKeepsChannelAndSwitchingCancelsOldRetry(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo=LiveRepository(activity);repo.importText("A,https://example.com/a.m3u8\nB,https://example.com/b.m3u8")
        val panel=LivePanel(activity,repo,{});activity.setContentView(panel)
        val channels=repo.allChannels();panel.play(channels[0],true)
        fun field(name:String)=LivePanel::class.java.getDeclaredField(name).apply{isAccessible=true}.get(panel)
        val error=LivePanel::class.java.getDeclaredMethod("handlePlaybackError",androidx.media3.common.PlaybackException::class.java).apply{isAccessible=true}
        error.invoke(panel,androidx.media3.common.PlaybackException("window expired",null,androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(channels[0].id,(field("current") as LiveChannel).id);assertEquals(0,field("line"))
        assertTrue((field("fullTitle") as TextView).text.contains("正在同步直播进度"));assertNull(field("pendingRetry"))
        error.invoke(panel,androidx.media3.common.PlaybackException("network",null,androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertNotNull(field("pendingRetry"));panel.play(channels[1],true);assertNull(field("pendingRetry"))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        assertEquals(channels[1].id,(field("current") as LiveChannel).id)
        error.invoke(panel,androidx.media3.common.PlaybackException("decoder",null,androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue((field("title") as TextView).text.contains("设备无法解码"));assertNull(field("pendingRetry"));panel.stop()
    }
    @Test fun consecutiveCastsSwitchMediaAndStayPausedAfterResume(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete();val repo=LiveRepository(activity)
        val panel=LivePanel(activity,repo,{});activity.setContentView(panel)
        fun field(name:String)=LivePanel::class.java.getDeclaredField(name).apply{isAccessible=true}.get(panel)
        panel.play(LiveChannel("cast","First","投屏",listOf("https://example.com/one.mp4")),true)
        panel.play(LiveChannel("cast","Second","投屏",listOf("https://example.com/two.mp4")),true)
        assertEquals("Second",(field("current") as LiveChannel).name)
        panel.refresh();assertNotNull(field("current"))
        panel.handleKey(KeyEvent.KEYCODE_DPAD_CENTER)
        val controls=field("controls") as LinearLayout
        assertEquals(listOf("后退 10 秒","播放 / 暂停","前进 10 秒","返回"),(0 until controls.childCount).map{controls.getChildAt(it)}.filter{it.visibility==android.view.View.VISIBLE}.map{(it as Button).text.toString()})
        val player=field("nativePlayer") as androidx.media3.exoplayer.ExoPlayer
        panel.command("videoToggle","");assertFalse(player.playWhenReady)
        player.seekTo(7000);panel.pause();panel.resume();assertFalse(player.playWhenReady);assertEquals(7000L,player.currentPosition)
        player.stop();val epoch=field("playbackEpoch") as Int
        panel.play(field("current") as LiveChannel,true);assertTrue((field("playbackEpoch") as Int)>epoch);panel.stop()
    }
}
