package com.tvbridge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionReadabilityTest {
    @Test fun connectionIsAnchoredBelowPhoneAndControlCenterHasOnlyOneEntry(){
        val c=Robolectric.buildActivity(android.app.Activity::class.java).setup();val a=c.get()
        try{
            val home=TvHomeView(a,{},{},{},{},{},{})
            a.setContentView(home)
            home.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));home.layout(0,0,1920,1080)
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
            assertEquals(1,views(home).filterIsInstance<TextView>().count{it.text=="控制中心"})
            val phone=views(home).filterIsInstance<android.widget.Button>().first{it.text=="手机连接"}
            home.showConnection(Runnable{})
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();val attr=dialog.window!!.attributes
            assertTrue(attr.gravity and android.view.Gravity.TOP!=0)
            assertTrue(attr.y>=phone.bottom)
            assertTrue(attr.x+attr.width<=1920)
            assertEquals(0,attr.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val decor=dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(attr.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.AT_MOST));decor.layout(0,0,attr.width,decor.measuredHeight)
            for(button in views(decor).filterIsInstance<android.widget.Button>()){
                val row=button.parent as ViewGroup
                assertTrue(button.left>=row.paddingLeft);assertTrue(button.right<=row.width-row.paddingRight)
            }
            assertTrue(NativeUi.dialogCommand("back")==true);assertFalse(dialog.isShowing)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun compactQrStillDecodesAtItsDisplayedSize(){
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try{
            val home=TvHomeView(controller.get(),{},{},{},{},{},{})
            val payload="http://192.0.2.1:8765/#connect="+"a".repeat(64)
            home.update("http://192.0.2.1:8765","123456",payload,0,"")
            val field=TvHomeView::class.java.getDeclaredField("qr").apply{isAccessible=true}
            val source=field.get(home) as Bitmap
            val shown=Bitmap.createBitmap(260,260,Bitmap.Config.ARGB_8888)
            Canvas(shown).drawBitmap(source,null,android.graphics.Rect(0,0,260,260),Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels=IntArray(260*260);shown.getPixels(pixels,0,260,0,0,260,260)
            val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(260,260,pixels))))
            assertEquals(payload,result.text);shown.recycle()
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun remoteSettingsHasNoPermanentStatusAndTopNoticeExpires(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        try{
            fun texts(v:View):List<TextView> = (if(v is TextView)listOf(v)else emptyList())+if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))}else emptyList()
            assertFalse(MainActivity::class.java.declaredMethods.any{it.name=="remoteSettingsView"})
            for(dark in listOf(true,false)){
                HomeAppearance.setDark(activity,dark);NativeUi.notice(activity,"连接失败，请检查 ADB 调试")
                val status=texts(activity.window.decorView).first{it.text.startsWith("连接失败")}
                assertFalse(status.isFocusable)
                val params=status.layoutParams as android.widget.FrameLayout.LayoutParams
                assertTrue(params.gravity and android.view.Gravity.TOP!=0)
                val backing=Bitmap.createBitmap(280,60,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(backing);canvas.drawColor(android.graphics.Color.WHITE)
                status.background.setBounds(0,0,280,60);status.background.draw(canvas)
                assertTrue(ColorUtils.calculateContrast(status.currentTextColor,backing.getPixel(140,30))>=4.5);backing.recycle()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2200))
                assertNull(status.parent)
            }
        }finally{HomeAppearance.setDark(activity,true);controller.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
}
