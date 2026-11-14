package com.tvbridge.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserHomeTest {
    @Config(qualifiers = "w1920dp-h1080dp")
    @Test fun browserKeysAlwaysMoveAndClickCircleEvenWithAddressFocused(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup();val activity=controller.get()
        try{
            val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));root.layout(0,0,1920,1080)
            val address=views(root).filterIsInstance<android.widget.EditText>().single()
            assertTrue(address.requestFocusFromTouch());address.setText("https://example.org")
            val cursor=BrowserActivity::class.java.getDeclaredField("cursor").apply{isAccessible=true}.get(activity) as View
            val toolbar=BrowserActivity::class.java.getDeclaredField("toolbar").apply{isAccessible=true}.get(activity) as View
            assertTrue((root as ViewGroup).indexOfChild(cursor)>root.indexOfChild(toolbar))
            val x=cursor.javaClass.getDeclaredField("px").apply{isAccessible=true};val y=cursor.javaClass.getDeclaredField("py").apply{isAccessible=true}
            val before=x.getFloat(cursor);activity.remoteCommand("right");assertTrue(x.getFloat(cursor)>before)
            val search=views(root).first{it.contentDescription=="打开"};val rect=android.graphics.Rect();search.getGlobalVisibleRect(rect)
            x.setFloat(cursor,rect.centerX().toFloat());y.setFloat(cursor,rect.centerY().toFloat())
            activity.remoteCommand("ok")
            assertEquals("https://example.org",shadowOf(views(root).filterIsInstance<WebView>().single()).lastLoadedUrl)
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);assertTrue(GlobalPointer.show(activity))
            address.requestFocusFromTouch();val point=GlobalPointer.position();activity.remoteCommand("right")
            assertTrue(GlobalPointer.position().first>point.first)
            assertTrue(address.hasFocus())
        }finally{GlobalPointer.hide();org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);controller.pause().stop().destroy()}
    }
    @Test fun oldPageCompletionCannotReplaceEditedAddressAfterSearchButtonTakesFocus(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup();val activity=controller.get()
        try{
            openSite(activity,"央视频")
            val web=views(activity.window.decorView).filterIsInstance<WebView>().single()
            val address=views(activity.window.decorView).filterIsInstance<android.widget.EditText>().single()
            address.requestFocusFromTouch();address.setText("https://example.net")
            address.clearFocus()
            web.webViewClient.onPageFinished(web,"https://www.yangshipin.cn")
            assertEquals("https://example.net",address.text.toString())
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun rendererFailureReturnsToUsableNativeHome(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup();val activity=controller.get()
        try{
            fun all()=views(activity.window.decorView)
            openSite(activity,"优酷")
            val old=all().filterIsInstance<WebView>().single()
            val handled=old.webViewClient.onRenderProcessGone(old,object:android.webkit.RenderProcessGoneDetail(){
                override fun didCrash()=true
                override fun rendererPriorityAtExit()=0
            })
            assertTrue(handled)
            val fresh=all().filterIsInstance<WebView>().single();assertNotSame(old,fresh)
            assertTrue(all().filterIsInstance<android.widget.ScrollView>().single().isShown)
            openSite(activity,"哔哩哔哩")
            assertEquals("https://www.bilibili.com",shadowOf(fresh).lastLoadedUrl)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun returningHomeDiscardsOldPageBeforeOpeningAnotherSite(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup();val activity=controller.get()
        try{
            fun current()=views(activity.window.decorView).filterIsInstance<WebView>().single()
            fun site(name:String)=openSite(activity,name)
            site("优酷");val old=current()
            assertEquals("https://www.youku.com",shadowOf(old).lastLoadedUrl)
            activity.onBackPressed()
            assertNotSame(old,current());assertFalse(old.settings.javaScriptEnabled)
            site("腾讯视频")
            assertEquals("https://v.qq.com",shadowOf(current()).lastLoadedUrl)
            assertFalse(views(activity.window.decorView).contains(old))
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun webpageCircleClickSendsTouchscreenTap(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup();val activity=controller.get()
        try{
            val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            openSite(activity,"央视频")
            root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY));root.layout(0,0,800,320)
            val web=views(root).filterIsInstance<WebView>().single();val events=mutableListOf<Triple<Int,Int,Int>>()
            web.layout(0,0,800,320)
            web.setOnTouchListener{_,event->events.add(Triple(event.action,event.source,event.buttonState));true}
            val origin=IntArray(2);root.getLocationOnScreen(origin);activity.pointerClick(origin[0]+400,origin[1]+200)
            assertEquals(listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP),events.map{it.first})
            assertTrue(events.all{it.second==android.view.InputDevice.SOURCE_TOUCHSCREEN})
            assertEquals(0,events.first().third)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun cleanHomeOpensTypedUrlsWithoutWebsiteShortcuts(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup();val activity=controller.get()
        try{
            val all=views(activity.window.decorView)
            assertFalse(all.filterIsInstance<android.widget.TextView>().any{it.text=="视频随心看"})
            assertTrue(all.filterIsInstance<Button>().isEmpty())
            for(url in listOf("https://example.org","https://example.net")){
                all.first{it.contentDescription=="主页"}.performClick()
                all.filterIsInstance<android.widget.EditText>().single().setText(url)
                all.first{it.contentDescription=="打开"}.performClick()
                assertEquals(url,shadowOf(views(activity.window.decorView).filterIsInstance<WebView>().single()).lastLoadedUrl)
            }
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun toolbarVisibilityKeepsPageViewportStable(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup()
        try{
            val activity=controller.get()
            val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY));root.layout(0,0,800,320)}
            layout();layout()
            val all=views(root);val web=all.filterIsInstance<WebView>().single()
            val home=all.filterIsInstance<android.widget.ScrollView>().single()
            val button=all.first{it.contentDescription=="主页"}
            val before=listOf(web.top,web.height,home.top,home.height)
            val scroll=BrowserActivity::class.java.getDeclaredMethod("pageScrolled",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}
            scroll.invoke(activity,100,0);layout();assertFalse(button.isShown)
            scroll.invoke(activity,0,100);layout();assertTrue(button.isShown)
            activity.remoteCommand("menu");layout();assertFalse(button.isShown)
            repeat(12){scroll.invoke(activity,1,0);scroll.invoke(activity,0,1);layout();assertFalse(button.isShown);assertEquals(before,listOf(web.top,web.height,home.top,home.height))}
            scroll.invoke(activity,100,0);scroll.invoke(activity,10,100);scroll.invoke(activity,0,10);layout();assertTrue(button.isShown)
            assertEquals(before,listOf(web.top,web.height,home.top,home.height))
            activity.remoteCommand("menu");layout();assertFalse(button.isShown)
            activity.remoteCommand("menu");layout();assertTrue(button.isShown)
            assertEquals(before,listOf(web.top,web.height,home.top,home.height))
        }finally{controller.pause().stop().destroy()}
    }
    private fun openSite(activity:BrowserActivity,name:String){
        val url=mapOf("央视频" to "https://www.yangshipin.cn","优酷" to "https://www.youku.com","哔哩哔哩" to "https://www.bilibili.com","腾讯视频" to "https://v.qq.com").getValue(name)
        val all=views(activity.window.decorView)
        all.filterIsInstance<android.widget.EditText>().single().setText(url)
        all.first{it.contentDescription=="打开"}.performClick()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun homeScrollAndPointerCanReachNativeAddressBar(){
        val controller=Robolectric.buildActivity(BrowserActivity::class.java).setup()
        val activity=controller.get()
        try{
            val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY));root.layout(0,0,800,320)}
            layout();layout()
            val all=views(root);val home=all.filterIsInstance<android.widget.ScrollView>().single()
            assertTrue(activity.remoteCommand("touchScroll","0,300"));assertEquals(0,home.scrollY);layout()
            val address=all.filterIsInstance<android.widget.EditText>().single()
            address.clearFocus()
            assertFalse(address.hasFocus())
            val target=android.graphics.Rect();address.getGlobalVisibleRect(target)
            val origin=IntArray(2);root.getLocationOnScreen(origin);target.offset(-origin[0],-origin[1])
            activity.remoteCommand("touchMove","0,0");layout()
            val cursor=BrowserActivity::class.java.getDeclaredField("cursor").apply{isAccessible=true}.get(activity) as View
            assertEquals(0,cursor.top);assertEquals(root.height,cursor.height)
            repeat(6){activity.remoteCommand("touchMove","-240,-240")}
            val unit=activity.resources.displayMetrics.density
            var x=target.centerX()/unit;var y=target.centerY()/unit
            while(x>0||y>0){val dx=minOf(x,240f);val dy=minOf(y,240f);activity.remoteCommand("touchMove","$dx,$dy");x-=dx;y-=dy}
            assertTrue(activity.remoteCommand("ok"));assertTrue("Confirm at native address field: $target",address.hasFocus())
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun videoHomeNavigatesAndCanReturnWithoutReloadingSite() {
        val controller = Robolectric.buildActivity(BrowserActivity::class.java).setup()
        val activity = controller.get()
        try {
            val all = views(activity.window.decorView)
            val sites = listOf("哔哩哔哩", "央视频", "腾讯视频", "爱奇艺", "优酷", "芒果 TV")
            assertTrue(sites.none { title -> all.filterIsInstance<Button>().any { it.text == title } })
            val web = all.filterIsInstance<WebView>().single()
            val back = all.first { it.contentDescription == "返回" }
            val open = all.first { it.contentDescription == "打开" }
            val address = all.filterIsInstance<android.widget.EditText>().single()
            assertEquals(address.layoutParams.height, open.layoutParams.height)
            assertEquals(address.layoutParams.height, open.layoutParams.height)
            assertFalse(all.filterIsInstance<Button>().any { it.text == "返回One" })
            assertTrue(all.filterIsInstance<android.widget.ImageButton>().all{it.drawable!=null})
            assertEquals(View.GONE, web.visibility)
            openSite(activity,"央视频")
            assertEquals("https://www.yangshipin.cn", shadowOf(web).lastLoadedUrl)
            assertEquals(View.VISIBLE, web.visibility)
            assertFalse(all.filterIsInstance<Button>().any { it.text == "更多" })
            val home = all.first { it.contentDescription == "主页" }
            assertTrue(home.isShown)
            BrowserActivity::class.java.getDeclaredMethod("pageScrolled",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,100,0)
            assertFalse(home.isShown)
            activity.remoteCommand("menu")
            assertTrue(home.isShown)
            assertTrue(activity.remoteCommand("touchMove", "12,-8"))
            assertFalse(activity.remoteCommand("touchMove", "NaN,1"))
            var wheel=0f
            web.setOnGenericMotionListener{_,event->wheel=event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);true}
            assertTrue(activity.remoteCommand("touchScroll", "0,90"));assertTrue(wheel<0)

            activity.remoteCommand("menu")
            assertFalse(home.isShown)
            BrowserActivity::class.java.getDeclaredMethod("pageScrolled",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(activity,100,0)
            assertFalse(home.isShown)
            activity.remoteCommand("menu")
            assertTrue(home.isShown)
            assertTrue(activity.remoteCommand("touchMove", "12,-8"))
            assertFalse(activity.remoteCommand("touchMove", "NaN,1"))
            assertTrue(activity.remoteCommand("touchScroll", "0,90"))
            activity.remoteCommand("menu")
            all.first { it.contentDescription == "主页" }.performClick()
            assertEquals(View.GONE, web.visibility)
            assertTrue(activity.remoteCommand("back"))
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
        assertNull(BrowserActivity.active)
    }
}
