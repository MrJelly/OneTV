package com.tvbridge.app

import android.app.Activity
import android.graphics.*
import android.util.TypedValue
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveAppearanceTest {
    @Test fun liveStartingWindowIsDarkAndBrightVideoDoesNotWashOutDenseGlass(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            a.theme.applyStyle(R.style.LiveTheme,true);val value=TypedValue()
            assertTrue(a.theme.resolveAttribute(android.R.attr.windowBackground,value,true))
            assertEquals(0xff121820.toInt(),value.data)
            assertTrue(a.theme.resolveAttribute(android.R.attr.windowDisablePreview,value,true));assertEquals(0,value.data)
            val view=TextView(a);view.layout(0,0,300,600)
            val image=Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(image)
            for(dense in listOf(false,true)){
            val surface=GlassUi.surface(view,0f,video=true,dark=true,dense=dense);surface.setBounds(0,0,300,600)
            repeat(4){
                canvas.drawColor(Color.WHITE);surface.draw(canvas)
                val white=listOf(10,300,590).map{image.getPixel(150,it)}
                canvas.drawColor(Color.BLACK);surface.draw(canvas)
                for((index,y) in listOf(10,300,590).withIndex()){val color=image.getPixel(150,y);assertTrue(Color.red(color)<70);assertTrue(Color.green(color)<85);assertTrue(Color.blue(color)<110)
                    assertTrue(kotlin.math.abs(Color.red(color)-Color.red(white[index]))in 10..55)
                    assertTrue(kotlin.math.abs(Color.green(color)-Color.green(white[index]))in 10..55)
                    assertTrue(kotlin.math.abs(Color.blue(color)-Color.blue(white[index]))in 10..55)}
            }
            }
            image.recycle()
        }finally{c.pause().stop().destroy()}
    }
}
