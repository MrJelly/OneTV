package com.tvbridge.app

import android.app.Activity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GlobalPointerTest {
    @After fun cleanup(){GlobalPointer.hide();ShadowSettings.setCanDrawOverlays(false)}
    @Test fun permissionIsRequiredAndDeniedDoesNotCreatePointer(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(GlobalPointer.show(activity));assertFalse(GlobalPointer.enabled)
    }
    @Test fun pointerMovesClampsAndDisappearsWhenDisabled(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(GlobalPointer.show(activity));val before=GlobalPointer.position()
        assertTrue(GlobalPointer.move(20f,10f));val after=GlobalPointer.position()
        assertTrue(after.first>before.first);assertTrue(after.second>before.second)
        assertFalse(GlobalPointer.move(Float.NaN,1f));assertEquals(after,GlobalPointer.position())
        repeat(100){GlobalPointer.move(-240f,-240f)};assertEquals(Pair(0,0),GlobalPointer.position())
        assertTrue(GlobalPointer.scroll(600f,600f).all{it>=0})
        GlobalPointer.hide();assertFalse(GlobalPointer.enabled);assertFalse(GlobalPointer.move(1f,1f))
    }
}
