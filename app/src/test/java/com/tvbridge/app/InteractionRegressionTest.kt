package com.tvbridge.app

import android.content.*
import android.content.pm.*
import android.view.*
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class InteractionRegressionTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    @Test fun touchReleaseAndCancelClearPressedState(){
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val b=Button(activity);activity.setContentView(b);b.layout(0,0,100,100)
        var clicks=0;b.setOnClickListener{clicks++};NativeUi.touchClicks(b)
        fun event(action:Int){val e=MotionEvent.obtain(0,0,action,20f,20f,0);try{b.dispatchTouchEvent(e)}finally{e.recycle()}}
        event(MotionEvent.ACTION_DOWN);assertTrue(b.isPressed);event(MotionEvent.ACTION_UP)
        assertFalse(b.isPressed);assertEquals(1,clicks);assertFalse(b.isFocusableInTouchMode)
        event(MotionEvent.ACTION_DOWN);event(MotionEvent.ACTION_CANCEL);event(MotionEvent.ACTION_UP)
        assertFalse(b.isPressed);assertEquals(1,clicks)
        activity.finish()
    }
    @Test fun reopeningLiveSkipsCountdownAndMobileHasChannelButtons(){
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("startup",0).edit().clear().commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            a.showLive(false);a.getSharedPreferences("startup",0).edit().putBoolean("live",true).commit()
            c.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            assertNull(MainActivity::class.java.getDeclaredField("startupDialog").apply{isAccessible=true}.get(a))
            val buttons=views(a.window.decorView).filterIsInstance<Button>().map{it.text.toString()}
            assertTrue(buttons.contains("上一台"));assertTrue(buttons.contains("下一台"))
        }finally{app.getSharedPreferences("startup",0).edit().clear().commit();c.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
    @Test fun allAppsMenuRequestsSystemUninstallConfirmation(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            val packageName="example.uninstall"
            val application=ApplicationInfo().apply{this.packageName=packageName}
            shadowOf(a.packageManager).installPackage(PackageInfo().apply{this.packageName=packageName;applicationInfo=application})
            val info=ResolveInfo().apply{activityInfo=ActivityInfo().apply{this.packageName=packageName;name="$packageName.Main";exported=true;nonLocalizedLabel="测试应用";applicationInfo=application}}
            shadowOf(a.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),info)
            LauncherApps.invalidate(a)
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="全部应用"}.performClick()
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="测试应用"}.requestFocusFromTouch()
            assertTrue(a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU)))
            val physical=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(physical.isShowing)
            physical.dismiss();shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("Phone menu uses the same app actions",a.command("menu",""))
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing)
            val list=dialog.listView;val index=(0 until list.adapter.count).first{list.adapter.getItem(it)=="卸载应用"}
            list.performItemClick(null,index,index.toLong())
            val intent=shadowOf(a).nextStartedActivity
            assertEquals(Intent.ACTION_DELETE,intent.action);assertEquals("package:$packageName",intent.data.toString())
        }finally{c.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
}
