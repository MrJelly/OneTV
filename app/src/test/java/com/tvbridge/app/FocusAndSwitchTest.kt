package com.tvbridge.app

import android.app.Activity
import android.graphics.*
import android.view.*
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusAndSwitchTest {
    private fun children(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{children(v.getChildAt(it))}else emptyList()
    @Test fun touchEntryIsRestoredAfterHomeTemporarilyFocusesFirstButton(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val activity=c.get()
        try{
            val home=TvHomeView(activity,{},{},{},{},{},{})
            activity.setContentView(home)
            home.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));home.layout(0,0,1920,1080)
            val buttons=children(home).filterIsInstance<Button>()
            val files=buttons.first{it.text=="文件管理"};files.performClick()
            home.visibility=View.GONE;home.visibility=View.VISIBLE
            buttons.first{it.text=="电视直播"}.requestFocusFromTouch()
            home.focusFirst();assertTrue(files.hasFocus())
        }finally{c.pause().stop().destroy()}
    }
    @Test fun browserEntryRegainsFocusWhenActivityResumes(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            val buttons=children(a.window.decorView).filterIsInstance<Button>()
            val browser=buttons.first{it.text=="浏览器"};browser.performClick()
            buttons.first{it.text=="电视直播"}.requestFocusFromTouch()
            c.pause().resume();assertTrue(browser.hasFocus())
        }finally{c.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
    @Test fun focusedGlassHasDistinctFillInBothThemes(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            val view=TextView(a);val image=Bitmap.createBitmap(240,80,Bitmap.Config.ARGB_8888);val canvas=Canvas(image)
            for(dark in listOf(true,false)){
                HomeAppearance.setDark(a,dark)
                val glass=GlassUi.surface(view,dark=dark);glass.setBounds(0,0,240,80)
                canvas.drawColor(Color.GRAY);glass.draw(canvas);val normal=image.getPixel(120,40)
                glass.state=intArrayOf(android.R.attr.state_focused)
                canvas.drawColor(Color.GRAY);glass.draw(canvas);val focused=image.getPixel(120,40)
                val difference=kotlin.math.abs(Color.red(normal)-Color.red(focused))+kotlin.math.abs(Color.green(normal)-Color.green(focused))+kotlin.math.abs(Color.blue(normal)-Color.blue(focused))
                assertTrue("Focused fill should stand out: dark=$dark, normal=$normal, focused=$focused, difference=$difference",difference>50)
            };image.recycle()
        }finally{HomeAppearance.setDark(a,true);c.pause().stop().destroy()}
    }
    @Test fun switchThumbIsRoundAndFullyInsideTrackAtBothEnds(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            val toggle=SettingsSwitch(a).apply{text="系统遥控";setPadding(24,0,24,0);textSize=24f}
            toggle.layout(0,0,600,96)
            val image=Bitmap.createBitmap(600,96,Bitmap.Config.ARGB_8888)
            for(checked in listOf(false,true)){
                toggle.isChecked=checked;image.eraseColor(Color.TRANSPARENT);toggle.draw(Canvas(image))
                val cx=if(checked)546 else 490
                assertEquals(Color.WHITE,image.getPixel(cx,48))
                assertEquals(Color.WHITE,image.getPixel(cx-20,48));assertEquals(Color.WHITE,image.getPixel(cx,28))
                assertNotEquals(Color.WHITE,image.getPixel(cx-26,48));assertNotEquals(Color.WHITE,image.getPixel(cx,22))
            };image.recycle()
        }finally{c.pause().stop().destroy()}
    }
}
