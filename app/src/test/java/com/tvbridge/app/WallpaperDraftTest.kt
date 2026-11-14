package com.tvbridge.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperDraftTest {
    private fun views(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    private fun image(color: Int): ByteArray {
        val bitmap=Bitmap.createBitmap(32,18,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
        return ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle()}.toByteArray()
    }
    @Test fun uploadedReplacementDoesNotChangeActiveImageOrThemeUntilConfirmed(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val draft=WallpaperDraft(activity)
        try{
            HomeAppearance.importImage(activity,image(Color.BLACK).inputStream())
            val before=HomeAppearance.customFile(activity).readBytes();val key=HomeAppearance.key(activity)
            draft.importImage(image(Color.WHITE).inputStream());draft.selection="custom"
            assertArrayEquals(before,HomeAppearance.customFile(activity).readBytes())
            assertEquals(key,HomeAppearance.key(activity));assertTrue(HomeAppearance.dark(activity))
            draft.commit()
            assertFalse(before.contentEquals(HomeAppearance.customFile(activity).readBytes()))
            assertEquals("custom",HomeAppearance.mode(activity));assertFalse(HomeAppearance.dark(activity))
        }finally{draft.dispose();controller.pause().stop().destroy()}
    }
    @Test fun abandoningUploadedPreviewPreservesDesktop(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        try{
            HomeAppearance.setGradient(activity);val key=HomeAppearance.key(activity)
            val draft=WallpaperDraft(activity)
            draft.importImage(image(Color.RED).inputStream());draft.selection="custom";draft.dispose()
            assertEquals(key,HomeAppearance.key(activity));assertEquals("gradient",HomeAppearance.mode(activity))
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun clickingWallpaperTileConfirmsAndClosesDialog(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        var changes=0
        try{
            HomeAppearance.importImage(activity,image(Color.BLACK).inputStream())
            val ui=AppearanceController(activity,ApkInstaller(activity),{changes++},{})
            ui.wallpaper()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            views(dialog.window!!.decorView).first{it.tag=="gradient"}.performClick()
            val deadline=System.nanoTime()+5_000_000_000L
            while(changes==0&&System.nanoTime()<deadline){Thread.sleep(10);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()}
            assertEquals("gradient",HomeAppearance.mode(activity));assertEquals(1,changes);assertFalse(dialog.isShowing)
        }finally{controller.pause().stop().destroy()}
    }
}
