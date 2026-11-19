package com.tvbridge.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPolicyTest {

    @Test
    fun vendorCategoryMatchesVendorHomeVariantsOnly() {
        assertTrue(DesktopPolicy.vendorCategory("android.intent.category.HOME.VENDOR"))
        assertTrue(DesktopPolicy.vendorCategory("com.vendor.category.CUSTOM_HOME"))
        assertFalse(DesktopPolicy.vendorCategory("android.intent.category.HOME"))
        assertFalse(DesktopPolicy.vendorCategory("android.intent.category.SECONDARY_HOME"))
        assertFalse(DesktopPolicy.vendorCategory("com.vendor.category.WORK_AT_HOME"))
    }

    @Test
    fun standbyNeedsUnbindMatchesSpecificModelAndApi30() {
        assertTrue(DesktopPolicy.standbyNeedsUnbind(model = "tc8000_ay30a2", sdk = 30))
        assertTrue(DesktopPolicy.standbyNeedsUnbind(model = "TC8000_AY30A2", sdk = 30))
        assertFalse(DesktopPolicy.standbyNeedsUnbind(model = "tc8000_ay30a2", sdk = 31))
        assertFalse(DesktopPolicy.standbyNeedsUnbind(model = "other_tv", sdk = 30))
    }

    @Test
    fun dangbeiStandbyDisableMatchesManufacturerOrBrandCaseInsensitively() {
        assertTrue(DesktopPolicy.dangbeiStandbyDisable(manufacturer = "Dangbei", brand = "generic"))
        assertTrue(DesktopPolicy.dangbeiStandbyDisable(manufacturer = "generic", brand = "DANGBEI"))
        assertFalse(DesktopPolicy.dangbeiStandbyDisable(manufacturer = "Xiaomi", brand = "MiTV"))
    }
}
