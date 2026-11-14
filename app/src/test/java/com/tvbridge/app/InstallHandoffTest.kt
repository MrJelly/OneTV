package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class InstallHandoffTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Test fun newProcessOnlyRecoversAfterVisibleInstallerHasReturnedToStockHome(){
        InstallHandoff.begin(app)
        assertFalse(InstallHandoff.returnedToStock(app,true))
        InstallHandoff.opened(app)
        assertFalse(InstallHandoff.returnedToStock(app,true))
        val updated=app.packageManager.getPackageInfo(app.packageName,0).apply{lastUpdateTime=System.currentTimeMillis()}
        org.robolectric.Shadows.shadowOf(app.packageManager).installPackage(updated)
        assertFalse(InstallHandoff.returnedToStock(app,false))
        assertTrue(InstallHandoff.active(app))
        assertTrue(InstallHandoff.returnedToStock(app,true))
        assertFalse(InstallHandoff.active(app))
        assertFalse(InstallHandoff.returnedToStock(app,true))
    }
    @Test fun installationPauseSurvivesRecreationAndEndsOnReturn(){
        InstallHandoff.begin(app)
        assertTrue(InstallHandoff.active(app))
        val activity=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        assertTrue(InstallHandoff.active(activity))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        ApkInstaller(activity).onResume()
        assertFalse(InstallHandoff.active(app))
    }
    @Test fun abandonedOrWrongClockHandoffDoesNotDisableTheDesktopForever(){
        app.getSharedPreferences("startup",0).edit().putLong("installer_started",System.currentTimeMillis()-16*60_000L).commit()
        assertFalse(InstallHandoff.active(app))
        app.getSharedPreferences("startup",0).edit().putLong("installer_started",System.currentTimeMillis()+60_000).commit()
        assertFalse(InstallHandoff.active(app))
    }
}
