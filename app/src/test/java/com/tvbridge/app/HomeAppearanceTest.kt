package com.tvbridge.app

import android.app.Activity
import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.*
import java.io.ByteArrayOutputStream

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAppearanceTest {
    @Test fun proceduralBackdropBoundsRasterMemoryAndStillPaintsAnOpaqueBackground(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        val output=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
        try{
            HomeAppearance.setDark(a,true)
            HomeAppearance.backdrop(a,1920,1080,true)
            HomeAppearance.drawBackdrop(a,Canvas(output),320,180,true,Paint())
            val cached=HomeAppearance::class.java.getDeclaredField("cached").apply{isAccessible=true}.get(HomeAppearance) as Bitmap
            assertTrue("Default wallpaper cache must remain bounded",cached.byteCount<=1280*720*4)
            assertEquals(255,Color.alpha(output.getPixel(160,90)))
        }finally{output.recycle();c.pause().stop().destroy()}
    }
    @Test fun automaticThemeSamplesWholeImageAndManualChoicePersists(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            fun upload(color:Int,mixed:Boolean=false){
                val image=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
                if(mixed)Canvas(image).drawRect(0f,0f,192f,90f,Paint().apply{this.color=Color.BLACK})
                val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle()
                HomeAppearance.importImage(a,bytes.inputStream())
            }
            upload(Color.WHITE,true);assertFalse(HomeAppearance.dark(a))
            HomeAppearance.setCustomTheme(a,"dark");assertTrue(HomeAppearance.dark(a))
            upload(Color.WHITE);assertTrue(HomeAppearance.dark(a));assertEquals("dark",HomeAppearance.customTheme(a))
            HomeAppearance.setDark(a,false);HomeAppearance.useCustom(a);assertTrue(HomeAppearance.dark(a))
            HomeAppearance.setCustomTheme(a,"auto");assertFalse(HomeAppearance.dark(a))
            HomeAppearance.setCustomTheme(a,"light");upload(Color.BLACK);assertFalse(HomeAppearance.dark(a))
            HomeAppearance.setCustomTheme(a,"auto");assertTrue(HomeAppearance.dark(a))
        }finally{c.pause().stop().destroy()}
    }
    @Test fun customBackgroundSurvivesThemeChangesAndRejectsInvalidReplacement(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            val image=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(12,24,48))}
            val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle()
            HomeAppearance.importImage(a,bytes.inputStream())
            assertEquals("custom",HomeAppearance.mode(a));assertTrue(HomeAppearance.dark(a))
            val before=HomeAppearance.customFile(a).readBytes();val key=HomeAppearance.key(a)
            try{HomeAppearance.importImage(a,"not an image".byteInputStream());fail("Invalid image was accepted")}catch(_:IllegalArgumentException){}
            assertArrayEquals(before,HomeAppearance.customFile(a).readBytes());assertEquals(key,HomeAppearance.key(a))
            HomeAppearance.setDark(a,false);HomeAppearance.useCustom(a)
            assertTrue(HomeAppearance.dark(a));assertEquals("custom",HomeAppearance.mode(a))
            val background=HomeAppearance.backdrop(a,640,360,true)
            assertEquals(640,background.width);assertEquals(360,background.height)
            assertTrue(Color.blue(background.getPixel(320,180))>Color.red(background.getPixel(320,180)))
            assertFalse(HomeAppearance.customFile(a).parentFile!!.listFiles()!!.any{it.name.startsWith("import-")})
        }finally{HomeAppearance.setDark(a,true);c.pause().stop().destroy()}
    }
    @Test fun oversizedUploadLeavesExistingWallpaperUntouched(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            HomeAppearance.setDark(a,false);val key=HomeAppearance.key(a)
            val input=object:java.io.InputStream(){var remaining=HomeAppearance.MAX_IMAGE_BYTES+1;override fun read()=if(remaining-->0)0 else -1;override fun read(b:ByteArray,off:Int,len:Int):Int{if(remaining<=0)return -1;val n=minOf(len,remaining);b.fill(0,off,off+n);remaining-=n;return n}}
            try{HomeAppearance.importImage(a,input);fail("Oversized upload was accepted")}catch(_:IllegalArgumentException){}
            assertEquals(key,HomeAppearance.key(a));assertEquals("gradient",HomeAppearance.mode(a))
        }finally{c.pause().stop().destroy()}
    }
}
