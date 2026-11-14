package com.tvbridge.app

import android.app.Activity
import android.graphics.*
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.*
import java.io.ByteArrayOutputStream

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassMaterialTest {
    @Test fun fasterBlurMatchesOriginalKernelWithoutRecyclingWallpaper(){
        val w=320;val h=9
        val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val random=java.util.Random(42)
        var expected=IntArray(w*h){Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256))}
        source.setPixels(expected,0,w,0,0,w,h)
        repeat(3){
            for(horizontal in listOf(true,false)){
                val input=expected
                expected=IntArray(w*h){i->
                    val x=i%w;val y=i/w;var r=0;var g=0;var b=0
                    for(k in -7..7){val c=input[if(horizontal)y*w+(x+k).coerceIn(0,w-1)else (y+k).coerceIn(0,h-1)*w+x];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c)}
                    Color.rgb(r/15,g/15,b/15)
                }
            }
        }
        val blurred=GlassUi.frost(source)
        try{
            assertFalse("Shared wallpaper must stay alive",source.isRecycled)
            val actual=IntArray(w*h);blurred.getPixels(actual,0,w,0,0,w,h)
            assertArrayEquals(expected,actual)
        }finally{blurred.recycle();source.recycle()}
    }
    @Test fun videoMaterialIgnoresCapturedWallpaperAndHomeUsesOneTint() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val red = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            val view = View(activity)
            activity.setContentView(view)
            view.layout(0, 0, 320, 180)
            fun render(source: Bitmap, video: Boolean, faint: Boolean): Int {
                val output = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                val material = GlassUi.surface(view, frost = GlassUi.Frost(source, 320, 180), video = video, dark = true, faint = faint)
                material.setBounds(0, 0, 320, 180)
                material.draw(Canvas(output))
                return output.getPixel(160, 130).also { output.recycle() }
            }
            assertEquals("Video glass must not inherit captured home wallpaper", render(red, true, false), render(blue, true, false))
            assertEquals("Home panels and buttons must share their tint", render(red, false, false), render(red, false, true))
            assertNotEquals("Home must preserve wallpaper color", render(red, false, false), render(blue, false, false))
        } finally {
            red.recycle(); blue.recycle(); controller.pause().stop().destroy()
        }
    }

    @Test fun customWallpaperContoursSurviveBothThemesAndDialogSampling(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        try{
            val source=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(source)
            canvas.drawColor(Color.rgb(20,40,80))
            canvas.drawRect(160f,0f,320f,180f,Paint().apply{color=Color.rgb(220,180,110)})
            val bytes=ByteArrayOutputStream().also{source.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
            source.recycle();HomeAppearance.importImage(activity,bytes.inputStream())
            for(theme in listOf("dark","light")){
                HomeAppearance.setCustomTheme(activity,theme)
                val view=View(activity);activity.setContentView(view)
                view.layout(0,0,1920,1080)
                view.setBackgroundColor(Color.GRAY)
                val frost=GlassUi.snapshot(activity)!!
                assertTrue(Color.red(frost.bitmap.getPixel(220,60))-Color.red(frost.bitmap.getPixel(20,60))>100)
                val material=GlassUi.surface(view,24f,frost=frost)
                material.setBounds(0,0,1920,1080)
                val output=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
                material.draw(Canvas(output))
                val contrast=Color.red(output.getPixel(1700,540))-Color.red(output.getPixel(200,540))
                assertTrue("Wallpaper contours lost in $theme glass: $contrast",contrast>65)
                System.getProperty("tvbridge.qaDir")?.let{directory->
                    val file=java.io.File(directory,"one-glass-$theme.png");file.parentFile!!.mkdirs()
                    file.outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                output.recycle()
            }
        }finally{HomeAppearance.setDark(activity,true);controller.pause().stop().destroy()}
    }
}
