package com.tvbridge.app

import android.view.*
import android.widget.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
class ControlCenterLayoutTest {
    @Test fun legacyDialogBranchShowsItsFinishedLayoutAndCanDismiss(){
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val sdk=android.os.Build.VERSION.SDK_INT
        try{
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java,"SDK_INT",19)
            val dialog=NativeUi.show(android.app.AlertDialog.Builder(controller.get()).setTitle("兼容弹窗").setMessage("内容").setPositiveButton("关闭",null))
            dialog.window!!.decorView.viewTreeObserver.dispatchOnPreDraw()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
            assertTrue(dialog.isShowing);assertEquals(1f,dialog.window!!.attributes.alpha,0f)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertFalse(dialog.isShowing)
        }finally{org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java,"SDK_INT",sdk);controller.pause().stop().destroy()}
    }
    @org.junit.Before fun useCustomLiveFixture(){LiveLaunch.choose(org.robolectric.RuntimeEnvironment.getApplication(),null)}
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun layout(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));v.layout(0,0,1920,1080)}
    @After fun cleanup(){Hub.stop();Hub.activity=null;NativeUi.dialogCommand("back")}
    @Test fun keySoundSwitchRestoresSavedValueAndKeepsChanges(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            a.getSharedPreferences("appearance",0).edit().putBoolean("ui_sounds",true).commit()
            fun open():SettingsSwitch {
                views(a.window.decorView).first{it.contentDescription=="控制中心"&&it.visibility==View.VISIBLE}.performClick()
                return views(a.window.decorView).filterIsInstance<SettingsSwitch>().first{it.text=="按键音"}
            }
            val sound=open();assertTrue(sound.isChecked)
            sound.performClick();assertFalse(UiSounds.enabled(a))
            a.showHome();val reopened=open();assertFalse(reopened.isChecked)
            reopened.performClick();assertTrue(UiSounds.enabled(a))
        }finally{UiSounds.setEnabled(a,false);c.pause().stop().destroy()}
    }
    @Test fun wallpaperDialogStartsWithCurrentBackgroundSelected(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            HomeAppearance.setDark(a,true)
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="控制中心"}.performClick()
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="桌面背景"}.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val focus=views(dialog.window!!.decorView).first{it.tag=="gradient"}
            assertTrue(focus.isSelected)
            assertTrue(focus.isEnabled)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun unassignedHomeOpensSystemChooserInsteadOfGenericSettings(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            val intent=android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
            val info=android.content.pm.ResolveInfo().apply{activityInfo=android.content.pm.ActivityInfo().apply{packageName="android";name="com.android.internal.app.ResolverActivity";exported=true;applicationInfo=android.content.pm.ApplicationInfo().apply{packageName="android"}}}
            Shadows.shadowOf(a.packageManager).addResolveInfoForIntent(intent,info)
            MainActivity::class.java.getDeclaredMethod("chooseDefaultHome").apply{isAccessible=true}.invoke(a)
            val target=Shadows.shadowOf(a).nextStartedActivityForResult
            assertEquals(android.content.Intent.ACTION_MAIN,target.intent.action)
            assertTrue(target.intent.hasCategory(android.content.Intent.CATEGORY_HOME))
        }finally{c.pause().stop().destroy()}
    }
    @Test fun shortcutsStayInTopBarAndDesktopHasOneSetupAction(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="控制中心"}.performClick();layout(a.window.decorView)
            val texts=views(a.window.decorView).filterIsInstance<TextView>()
            val desktop=texts.filterIsInstance<Button>().first{it.text=="桌面应用"}.parent as ViewGroup
            val liveButton=texts.filterIsInstance<Button>().first{it.text=="直播管理"}
            assertSame("All settings share a single column",desktop,liveButton.parent)
            assertEquals(6,desktop.childCount);assertTrue(desktop.height>0)
            val shortcuts=texts.filterIsInstance<Button>().filter{it.text=="信号源"||it.text=="系统设置"||it.text=="网络"||it.text=="未连接"||it.text=="有线网络"||it.text=="Wi-Fi"}
            assertEquals(3,shortcuts.size)
            assertEquals("信号源",shortcuts[0].text.toString())
            assertEquals("系统设置",shortcuts[2].text.toString())
            assertTrue("Top shortcuts use the system settings width",shortcuts.all{it.width==shortcuts.last().width})
            val shortcutGroup=shortcuts.first().parent as ViewGroup
            assertTrue("Every shortcut fits inside its group",shortcuts.all{it.right<=shortcutGroup.width})
            val back=texts.filterIsInstance<Button>().first{it.text=="返回"}
            val backPosition=IntArray(2);back.getLocationInWindow(backPosition)
            val rightPosition=IntArray(2);shortcuts.last().getLocationInWindow(rightPosition)
            assertEquals("Header edge insets match",backPosition[0],a.window.decorView.width-rightPosition[0]-shortcuts.last().width)
            val detail=views(a.window.decorView).first{it.tag==NativeUi.CONTROL_DETAIL}
            assertTrue("Settings card fills the left column",desktop.height>=detail.height)
            assertTrue("Left column leaves room for details",desktop.width<=a.resources.displayMetrics.widthPixels*.55f)
            val position=IntArray(2);shortcuts[0].getLocationOnScreen(position)
            val cardPosition=IntArray(2);desktop.getLocationOnScreen(cardPosition)
            assertTrue("Shortcuts belong in header",position[1]<cardPosition[1])
            assertTrue(views(a.window.decorView).none{it is SeekBar})
            assertFalse(texts.any{it.text.startsWith("音量")||it.text.contains("亮度")})
            texts.filterIsInstance<Button>().first{it.text=="桌面应用"}.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val detailPosition=IntArray(2);detail.getLocationInWindow(detailPosition)
            assertTrue(dialog.window!!.attributes.width<=detail.width)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,dialog.window!!.attributes.height)
            assertEquals(detailPosition[0],dialog.window!!.attributes.x)
            val originPosition=IntArray(2);texts.first{it.text=="桌面应用"}.getLocationInWindow(originPosition)
            assertEquals(originPosition[1],dialog.window!!.attributes.y)
            assertTrue(texts.first{it.text=="桌面应用"}.isSelected)
            val buttons=views(dialog.window!!.decorView).filterIsInstance<Button>().map{it.text.toString()}
            assertTrue("设为桌面" in buttons);assertTrue("还原桌面" in buttons)
            assertFalse(buttons.any{it.contains("兼容")})
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,a.packageManager.getComponentEnabledSetting(android.content.ComponentName(a,"com.tvbridge.app.DesktopActivity")))
            assertNotEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,a.packageManager.getComponentEnabledSetting(android.content.ComponentName(a,MainActivity::class.java)))
            assertFalse(a.getSharedPreferences("startup",0).getBoolean("boot",true))
            assertFalse(a.getSharedPreferences("startup",0).getBoolean("desktop_guard",true))
        }finally{c.pause().stop().destroy()}
    }
    @Test fun emptyReceivedFilesUsesTheSameListSizeAsPopulatedFiles(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            val directory=java.io.File(a.filesDir,"received").apply{mkdirs()}
            a.command("filesOpen","");val empty=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(empty.listView);assertEquals(0,empty.listView.adapter.count)
            val emptyHeight=empty.listView.layoutParams.height;empty.dismiss()
            val file=java.io.File(directory,"test.txt").apply{writeText("test")}
            a.command("filesOpen","");val populated=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(1,populated.listView.adapter.count);assertEquals(emptyHeight,populated.listView.layoutParams.height)
            populated.dismiss();file.delete()
        }finally{c.pause().stop().destroy()}
    }
    @Test fun replacementDetailKeepsThePrimaryRowAsItsOrigin(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        try{
            views(a.window.decorView).filterIsInstance<Button>().first{it.text=="控制中心"}.performClick();layout(a.window.decorView)
            val source=views(a.window.decorView).filterIsInstance<Button>().first{it.text=="桌面背景"}
            source.performClick()
            val original=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val top=original.window!!.attributes.y
            original.dismiss()
            val next=NativeUi.show(android.app.AlertDialog.Builder(a).setTitle("上传背景").setItems(arrayOf("从本机选择")){_,_->})
            assertEquals(top,next.window!!.attributes.y)
            assertTrue(source.isSelected)
            next.dismiss();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertFalse(source.isSelected)
        }finally{c.pause().stop().destroy()}
    }
}
