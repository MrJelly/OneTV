package com.tvbridge.app

import android.app.Activity
import android.graphics.*
import android.view.View
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvScreenshotTest {
    @Test fun exportPublicScreenshotsWithDemoConnectionOnly(){
        Assume.assumeTrue(!System.getProperty("tvbridge.qaDir").isNullOrEmpty())
        val control=Robolectric.buildActivity(MainActivity::class.java).setup();val a=control.get()
        fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun demoConnection(){
            val home=MainActivity::class.java.getDeclaredField("home").apply{isAccessible=true}.get(a) as TvHomeView
            home.update("http://192.168.1.100:8888","123456","OneTV demo connection",0,"")
            views(a.window.decorView).filterIsInstance<android.widget.TextView>().filter{it.text.contains("http://")}.forEach{it.text="http://192.168.1.100:8888    配对码  123456"}
            (MainActivity::class.java.getDeclaredField("controlQr").apply{isAccessible=true}.get(a) as? android.widget.ImageView)?.setImageBitmap(home.qrBitmap())
        }
        try{
            HomeAppearance.setGradient(a);a.showHome();demoConnection();capture(a.window.decorView,"home.png")
            views(a.window.decorView).filterIsInstance<android.widget.Button>().first{it.text=="控制中心"}.performClick()
            demoConnection();capture(a.window.decorView,"settings.png")
        }finally{control.pause().stop().destroy()}
    }
    @After fun cleanup(){Hub.stop();Hub.activity=null}
    @Test fun activityRetainsWallpaperBehindHomeAndSettings(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            HomeAppearance.setDark(a,true);a.showHome()
            val decor=a.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));decor.layout(0,0,1920,1080)
            val output=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(output))
            val first=output.getPixel(1880,1050);val second=output.getPixel(1800,850)
            assertNotEquals("Activity must draw the wallpaper rather than its flat fallback",first,second)
            val expected=HomeAppearance.backdrop(a,1280,720,true)
            val reference=expected.getPixel(1880*1280/1920,1050*720/1080)
            assertTrue(kotlin.math.abs(Color.red(reference)-Color.red(first))<=2&&kotlin.math.abs(Color.blue(reference)-Color.blue(first))<=2)
            fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
            views(decor).filterIsInstance<android.widget.Button>().first{it.text=="控制中心"}.performClick()
            decor.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));decor.layout(0,0,1920,1080);decor.draw(Canvas(output))
            assertEquals("Settings must retain the same wallpaper outside its panels",first,output.getPixel(1880,1050))
            output.recycle()
        }finally{c.pause().stop().destroy()}
    }
    private fun capture(view:View,name:String){val dir=File(System.getProperty("tvbridge.qaDir")!!);dir.mkdirs();view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));view.layout(0,0,1920,1080);val bitmap=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    @Test fun homeMenuOpensControlCenterWithoutOpeningSystemPages(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MENU))
            fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
            assertTrue(views(a.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text=="控制中心"})
            assertTrue(views(a.window.decorView).filterIsInstance<android.widget.SeekBar>().isEmpty())
            assertNull(org.robolectric.Shadows.shadowOf(a).nextStartedActivity)
            if(!System.getProperty("tvbridge.qaDir").isNullOrEmpty())capture(a.window.decorView,"one-control-center.png")
        }finally{c.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
    @Test fun renderHomepage(){Assume.assumeTrue(!System.getProperty("tvbridge.qaDir").isNullOrEmpty());val controller=Robolectric.buildActivity(MainActivity::class.java).setup();capture(controller.get().window.decorView,"tv-home-v03.png");controller.pause().stop().destroy()}
    @Test fun renderBothHomeThemes(){
        Assume.assumeTrue(!System.getProperty("tvbridge.qaDir").isNullOrEmpty())
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        try{
            HomeAppearance.setDark(activity,false)
            val home=TvHomeView(activity,{},{},{},{},{},{})
            activity.setContentView(home)
            capture(home,"one-home-light.png")
            HomeAppearance.setDark(activity,true);home.refreshAppearance()
            capture(home,"one-home-dark.png")
        }finally{HomeAppearance.setDark(activity,false);controller.pause().stop().destroy()}
    }
    @Test fun renderSettingsSidebar(){
        Assume.assumeTrue(!System.getProperty("tvbridge.qaDir").isNullOrEmpty())
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            HomeAppearance.setDark(a,true)
            fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
            views(a.window.decorView).filterIsInstance<android.widget.Button>().first{it.text=="控制中心"}.performClick()
            capture(a.window.decorView,"one-settings-dark.png")
            views(a.window.decorView).filterIsInstance<android.widget.Button>().first{it.text=="桌面背景"}.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val decor=dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(650,View.MeasureSpec.AT_MOST));decor.layout(0,0,860,decor.measuredHeight)
            val bitmap=Bitmap.createBitmap(860,decor.height,Bitmap.Config.ARGB_8888);decor.draw(Canvas(bitmap))
            File(System.getProperty("tvbridge.qaDir"),"one-wallpaper-dialog.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();dialog.dismiss()
        }finally{HomeAppearance.setDark(a,false);c.pause().stop().destroy()}
    }
    @Test fun renderLightSettingsSmokePreview(){
        Assume.assumeTrue(!System.getProperty("tvbridge.qaDir").isNullOrEmpty())
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            HomeAppearance.setDark(a,false);a.showHome()
            fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
            views(a.window.decorView).filterIsInstance<android.widget.Button>().first{it.text=="控制中心"}.performClick()
            capture(a.window.decorView,"one-settings-light-smoke.png")
        }finally{c.pause().stop().destroy()}
    }
    @Test fun renderEmptyLiveSetup(){
        Assume.assumeTrue(!System.getProperty("tvbridge.qaDir").isNullOrEmpty())
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{HomeAppearance.setDark(c.get(),true);c.get().showLive();capture(c.get().window.decorView,"one-live-empty-dark.png")}
        finally{c.pause().stop().destroy()}
    }
    @Test fun renderSubscriptions(){Assume.assumeTrue(!System.getProperty("tvbridge.qaDir").isNullOrEmpty());val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get();activity.filesDir.resolve("live-sources.json").delete();val repo=LiveRepository(activity);repo.importText("综合,#genre#\n示例频道一,https://example.test/one.m3u8\n示例频道二,https://example.test/two.m3u8\n文化,#genre#\n示例文化,https://example.test/three.m3u8");val panel=LivePanel(activity,repo,{});activity.setContentView(panel);capture(panel,"tv-live-sources-v03.png");panel.stop();controller.pause().stop().destroy()}
}
