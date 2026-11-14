package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DeviceAdaptersTest {
    @Test fun correctionsAreRestrictedToKnownModelsAndFirmware(){
        val c=RuntimeEnvironment.getApplication()
        assertEquals(23,DeviceAdapters.profile(c,"MiTV_NSOP0","1.0").sources[6])
        assertEquals(25,DeviceAdapters.profile(c,"MiTV4-ANSM0","22.8.1").sources[5])
        assertTrue(DeviceAdapters.profile(c,"MiTV4-ANSM0","unknown").sources.isEmpty())
        assertTrue(DeviceAdapters.profile(c,"unlisted","22.8.1").sources.isEmpty())
    }
    @Test fun hardwareIdsMustBeActualPortSuffixes(){
        assertEquals(10,DeviceAdapters.hardware("vendor/input/HW10"))
        assertNull(DeviceAdapters.hardware("vendor/HW10/child"))
        assertNull(DeviceAdapters.hardware("HDMI 1"))
    }
}
