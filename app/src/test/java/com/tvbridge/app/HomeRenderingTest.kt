package com.tvbridge.app

import android.app.Activity
import android.graphics.*
import android.view.View
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRenderingTest {
    @Test fun landscapeKeepsLightTextAndAllHomeLabelsFollowCustomTheme(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val output=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
        try{
            activity.getSharedPreferences("appearance",0).edit().putString("wallpaper_mode","bing").putBoolean("bing_dark",false).apply()
            assertTrue("Bright sky must not turn landscape text dark",HomeAppearance.dark(activity))
            val image=Bitmap.createBitmap(32,18,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
            val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle()
            HomeAppearance.importImage(activity,bytes.inputStream())
            val home=TvHomeView(activity,Runnable{},Runnable{},Runnable{},Runnable{},Runnable{},Runnable{}).apply{drawsBackdrop=false}
            activity.setContentView(home)
            for(theme in listOf("light","dark")){
                HomeAppearance.setCustomTheme(activity,theme);home.refreshAppearance()
                home.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));home.layout(0,0,1920,1080)
                output.eraseColor(Color.MAGENTA);home.draw(Canvas(output))
                val ink=GlassUi.ink(activity)
                fun hasInk(top:Int,bottom:Int)= (top until bottom).sumOf{y->(96 until 800).count{x->output.getPixel(x,y)==ink}}>30
                assertTrue("Date must follow $theme theme",hasInk(350,390))
                assertTrue("Favorites heading must follow $theme theme",hasInk(690,735))
                val button=(0 until home.childCount).map{home.getChildAt(it)}.filterIsInstance<Button>().first{it.text=="电视直播"}
                assertEquals(ink,button.currentTextColor)
            }
        }finally{output.recycle();controller.pause().stop().destroy()}
    }

    @Test fun settingsGlyphIsCenteredIndependentlyOfButtonTextAndPadding(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val output=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        try{
            val home=TvHomeView(activity,Runnable{},Runnable{},Runnable{},Runnable{},Runnable{},Runnable{})
            val button=(0 until home.childCount).map{home.getChildAt(it)}.first{it.contentDescription=="控制中心"&&it.visibility==View.VISIBLE}
            button.background=null
            button.measure(View.MeasureSpec.makeMeasureSpec(64,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(64,View.MeasureSpec.EXACTLY))
            button.layout(0,0,64,64);button.draw(Canvas(output))
            System.getProperty("tvbridge.qaDir")?.let{directory->java.io.File(directory,"home-settings-icon.png").apply{parentFile!!.mkdirs()}.outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)}}
            var left=64;var top=64;var right=-1;var bottom=-1
            for(y in 0 until 64)for(x in 0 until 64)if(Color.alpha(output.getPixel(x,y))>0){left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)}
            assertTrue(right>left)
            assertEquals("Horizontal bounds $left..$right; scroll ${button.scrollX}",31.5,(left+right)/2.0,1.0)
            assertEquals("Vertical bounds $top..$bottom; scroll ${button.scrollY}",31.5,(top+bottom)/2.0,1.0)
        }finally{output.recycle();controller.pause().stop().destroy()}
    }
}
