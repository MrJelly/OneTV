package com.tvbridge.app

import android.app.Activity
import android.view.*
import android.widget.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveDrawerInteractionTest {
    private lateinit var controller:org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var panel:LivePanel
    private lateinit var repo:LiveRepository
    @Suppress("UNCHECKED_CAST") private fun <T> field(name:String)=LivePanel::class.java.getDeclaredField(name).apply{isAccessible=true}.get(panel) as T
    @Test fun numberKeysChooseDisplayedChannelAndCancelPendingEntryOnExit(){
        assertTrue(panel.handleKey(KeyEvent.KEYCODE_2));assertTrue(panel.handleKey(KeyEvent.KEYCODE_ENTER))
        assertEquals("A1",field<LiveChannel?>("current")?.id)
        assertTrue(panel.handleKey(KeyEvent.KEYCODE_NUMPAD_4))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        assertEquals("A3",field<LiveChannel?>("current")?.id)
        panel.handleKey(KeyEvent.KEYCODE_9);panel.handleKey(KeyEvent.KEYCODE_ENTER)
        assertEquals("A3",field<LiveChannel?>("current")?.id)
        panel.handleKey(KeyEvent.KEYCODE_1);panel.pause()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        assertEquals("A3",field<LiveChannel?>("current")?.id)
    }
    @Before fun setup(){
        LiveLaunch.choose(RuntimeEnvironment.getApplication(),null)
        controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get();activity.filesDir.resolve("live-sources.json").delete()
        fun source(url:String,prefix:String,count:Int)=JSONObject().put("url",url).put("name",prefix).put("channels",JSONArray((0 until count).map{JSONObject().put("id","$prefix$it").put("name","$prefix $it").put("group","Group $it").put("streams",JSONArray(listOf("https://example.test/$prefix$it.m3u8")))}))
        repo=LiveRepository(activity)
        repo.restoreSnapshot(JSONObject().put("active","https://example.test/a.m3u").put("sources",JSONArray(listOf(source("https://example.test/a.m3u","A",4),source("https://example.test/b.m3u","B",1)))).put("favorites",JSONArray(listOf("A0"))).toString())
        panel=LivePanel(activity,repo,{})
        activity.setContentView(panel)
        panel.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));panel.layout(0,0,1920,1080)
        panel.handleKey(KeyEvent.KEYCODE_MENU)
        panel.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));panel.layout(0,0,1920,1080)
    }
    @After fun cleanup(){panel.stop();controller.pause().stop().destroy()}
    private fun tap(x:Float,y:Float){val time=android.os.SystemClock.uptimeMillis();for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(time,time+20,action,x,y,0);try{panel.dispatchTouchEvent(e)}finally{e.recycle()}}}
    @Test fun blankDrawerHeaderKeepsDrawerAndRightVideoClosesIt(){
        val drawer=field<LinearLayout>("drawer")
        tap(40f,30f);assertEquals(View.VISIBLE,drawer.visibility)
        tap(1200f,300f);assertEquals(View.GONE,drawer.visibility)
    }
    @Test fun sourcePopupSelectsSourceRatherThanChannelAtSameRow(){
        field<Button>("sourceButton").performClick()
        val popup=field<ListPopupWindow>("groupPopup")
        val list=popup.listView!!
        list.performItemClick(list.adapter.getView(1,null,list),1,1)
        assertEquals("https://example.test/b.m3u",repo.activeSource())
        assertEquals("B0",field<LiveChannel>("current").id)
    }
    @Test fun favoriteReturnsToPriorGroupBeforeOpeningDropdown(){
        val group=field<Button>("groupButton")
        group.performClick()
        var popup=field<ListPopupWindow>("groupPopup");var list=popup.listView!!
        list.performItemClick(list.adapter.getView(2,null,list),2,2)
        assertEquals("Group 1",field<String>("category"))
        val favorite=field<Button>("favoriteButton");favorite.requestFocus();favorite.performClick()
        assertTrue(field<Boolean>("favoritesOnly"))
        group.performClick()
        assertFalse(field<Boolean>("favoritesOnly"));assertEquals("Group 1",field<String>("category"))
        group.performClick();popup=field("groupPopup");list=popup.listView!!
        list.performItemClick(list.adapter.getView(1,null,list),1,1)
        assertFalse(field<Boolean>("favoritesOnly"));assertEquals("Group 0",field<String>("category"))
    }
}
