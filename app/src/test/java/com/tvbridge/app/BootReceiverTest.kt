package com.tvbridge.app

import android.content.Intent
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @After fun clear() { app.getSharedPreferences("startup", 0).edit().clear().commit() }
    @Test fun bootRequiresOptInAndIgnoresOtherBroadcasts() {
        val receiver = BootReceiver()
        val boot = BootReceiver::class.java.getDeclaredMethod("onBoot",android.content.Context::class.java,Intent::class.java).apply{isAccessible=true}
        boot.invoke(receiver,app,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedActivity)
        app.getSharedPreferences("startup", 0).edit().putBoolean("boot", true).commit()
        receiver.onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        assertNull(shadowOf(app).nextStartedActivity)
        boot.invoke(receiver,app,Intent(Intent.ACTION_BOOT_COMPLETED))
        val alarm=shadowOf(app.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).nextScheduledAlarm
        assertNotNull(alarm)
        BootAttemptReceiver().onReceive(app,Intent(shadowOf(alarm!!.operation).savedIntent))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
