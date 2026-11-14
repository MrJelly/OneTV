package com.tvbridge.app

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w960dp-h540dp-land-xhdpi")
class DirectionalFocusTest {
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    private fun layout(view:View){view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));view.layout(0,0,1920,1080)}
    @Test fun settingsIconReturnsToEachOriginalHomeButton(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        try{
            activity.showHome();layout(activity.window.decorView)
            val all=views(activity.window.decorView)
            val settings=all.first{it.contentDescription=="控制中心"&&it.visibility==View.VISIBLE}
            for(label in listOf("电视直播","文件管理","浏览器")){
                val button=all.filterIsInstance<AppTileView>().first{it.title==label&&it.visibility==View.VISIBLE}
                assertTrue(button.requestFocusFromTouch())
                assertSame(settings,button.focusSearch(View.FOCUS_UP))
                assertTrue(settings.requestFocus())
                assertSame("Return to $label",button,settings.focusSearch(View.FOCUS_DOWN))
            }
        }finally{controller.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
    @Test fun settingsColumnsAndHeaderHaveExplicitQrRoutes(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        try{
            views(activity.window.decorView).first{it.contentDescription=="控制中心"&&it.visibility==View.VISIBLE}.performClick()
            layout(activity.window.decorView)
            val all=views(activity.window.decorView)
            val refresh=all.filterIsInstance<Button>().first{it.text=="刷新二维码"}
            for(label in listOf("桌面应用","桌面背景","直播管理","打开时进入直播","按键音","检查更新")){
                val row=all.filterIsInstance<android.widget.TextView>().first{it.text==label}
                assertTrue(row.requestFocusFromTouch())
                assertSame("Right from $label",refresh,row.focusSearch(View.FOCUS_RIGHT))
                assertTrue(refresh.requestFocus())
                assertSame("Left back to $label",row,refresh.focusSearch(View.FOCUS_LEFT))
            }
            val source=all.filterIsInstance<Button>().first{it.text=="信号源"}
            assertSame(refresh,source.focusSearch(View.FOCUS_DOWN))
            assertSame(source,refresh.focusSearch(View.FOCUS_UP))
        }finally{controller.pause().stop().destroy();Hub.stop();Hub.activity=null}
    }
}
