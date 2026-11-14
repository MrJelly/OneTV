package com.tvbridge.app

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefaultWallpaperTest {
    @Test fun defaultKeepsClockAreaDarkAndDrawsBlueAndPurpleEdges(){
        val bitmap=ProceduralWallpaper.bitmap(640,360,true)
        try{
            var blueHighlight=false
            var purpleHighlight=false
            val hsv=FloatArray(3)
            for(x in 0 until bitmap.width step 4)for(y in 0 until bitmap.height){
                val color=bitmap.getPixel(x,y)
                Color.colorToHSV(color,hsv)
                assertEquals(255,Color.alpha(color))
                if(x<190&&y<125)assertTrue("Keep clock area readable",hsv[2]<.65f)
                if(hsv[2]>.65f&&hsv[1]>.45f){
                    if(hsv[0] in 175f..245f)blueHighlight=true
                    if(hsv[0] in 255f..320f)purpleHighlight=true
                }
            }
            assertTrue(blueHighlight)
            assertTrue(purpleHighlight)
            System.getProperty("tvbridge.qaDir")?.let{directory->
                java.io.File(directory,"default-neon-wallpaper.png").apply{parentFile!!.mkdirs()}.outputStream().use{
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
            }
        }finally{bitmap.recycle()}
    }
}
