package com.tvbridge.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ApkInstallerTest {
    @Before @After fun resetFileProviderPaths(){
        val field=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply{isAccessible=true}
        (field.get(null) as MutableMap<*,*>).clear()
    }
    private fun file(a:Activity)=File(a.filesDir,"received/permission-test.apk").apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3))}
    private fun clear(a:Activity){while(shadowOf(a).nextStartedActivity!=null){}}
    @Test fun transientResumeDoesNotReleaseDesktopGuardBeforeInstallerActuallyOpens(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(a.packageManager).setCanRequestPackageInstalls(true)
        val installer=ApkInstaller(a)
        try{
            installer.request(file(a))
            val launch=shadowOf(a).nextStartedActivity
            assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0)
            assertTrue(InstallHandoff.active(a))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
            installer.onResume();assertTrue(InstallHandoff.active(a))
            installer.onPause();installer.onResume();assertFalse(InstallHandoff.active(a))
        }finally{installer.destroy();InstallHandoff.finish(a)}
    }
    private fun returnFromPermissionSettings(installer:ApkInstaller){
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        installer.onResume();shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
    }
    @Test fun deniedPermissionOpensOwnSettingsAndResumesSelectedApkOnce(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get();val pm=shadowOf(a.packageManager);pm.setCanRequestPackageInstalls(false)
        val installer=ApkInstaller(a);installer.openPermissionSettings(file(a));val settings=shadowOf(a).nextStartedActivityForResult
        assertEquals(ApkInstaller.REQUEST_PERMISSION,settings.requestCode);assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,settings.intent.action);assertEquals("package:${a.packageName}",settings.intent.dataString)
        clear(a);pm.setCanRequestPackageInstalls(true);assertTrue(installer.onResult(ApkInstaller.REQUEST_PERMISSION));returnFromPermissionSettings(installer);val launch=shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW,launch.action);assertEquals("content",launch.data!!.scheme);assertEquals("application/vnd.android.package-archive",launch.type);assertTrue(launch.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertNotNull(launch.clipData)
        installer.onResume();assertNull(shadowOf(a).nextStartedActivity)
    }
    @Test fun permissionDenialNeverStartsInstaller(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get();shadowOf(a.packageManager).setCanRequestPackageInstalls(false)
        val installer=ApkInstaller(a);installer.openPermissionSettings(file(a));clear(a);installer.onResult(ApkInstaller.REQUEST_PERMISSION);assertNull(shadowOf(a).nextStartedActivity)
    }
    @Test fun savedPermissionRequestSurvivesActivityRecreation(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get();shadowOf(a.packageManager).setCanRequestPackageInstalls(false)
        val installer=ApkInstaller(a);installer.openPermissionSettings(file(a));val state=Bundle();installer.save(state);clear(a)
        val restored=ApkInstaller(a);restored.restore(state);shadowOf(a.packageManager).setCanRequestPackageInstalls(true);returnFromPermissionSettings(restored);assertEquals(Intent.ACTION_VIEW,shadowOf(a).nextStartedActivity.action);restored.onResume();assertNull(shadowOf(a).nextStartedActivity)
    }
    @Test fun sessionCallbackOpensSystemConfirmationInsteadOfTreatingItAsFailure(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(a.application).grantPermissions("${a.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(a.packageManager).setCanRequestPackageInstalls(false)
        val installer=ApkInstaller(a)
        try{
            installer.request(file(a));shadowOf(android.os.Looper.getMainLooper()).idle();clear(a)
            a.sendBroadcast(Intent(ApkInstaller.INSTALL_STATUS_ACTION).setPackage(a.packageName)
                .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT,Intent("test.install.confirm")))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("test.install.confirm",shadowOf(a).nextStartedActivity?.action)
        }finally{installer.destroy()}
    }
    @Test fun missingFileAndOutsideReceivedDirectoryDoNotLaunch(){
        val a=Robolectric.buildActivity(Activity::class.java).setup().get();shadowOf(a.packageManager).setCanRequestPackageInstalls(false);val f=file(a);val installer=ApkInstaller(a)
        installer.openPermissionSettings(f);f.delete();clear(a);shadowOf(a.packageManager).setCanRequestPackageInstalls(true);installer.onResult(ApkInstaller.REQUEST_PERMISSION);assertNull(shadowOf(a).nextStartedActivity)
        val outside=File(a.filesDir,"outside.apk").apply{writeText("apk")};installer.request(outside);assertNull(shadowOf(a).nextStartedActivity)
    }
    class LegacySettingsActivity:Activity(){override fun startActivityForResult(intent:Intent,request:Int){if(intent.action==Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)throw ActivityNotFoundException();super.startActivityForResult(intent,request)}}
    @Test fun televisionsWithoutPerAppPermissionScreenFallBackToSecuritySettings(){
        val a=Robolectric.buildActivity(LegacySettingsActivity::class.java).setup().get();shadowOf(a.packageManager).setCanRequestPackageInstalls(false)
        ApkInstaller(a).openPermissionSettings(file(a));assertEquals(Settings.ACTION_SECURITY_SETTINGS,shadowOf(a).nextStartedActivityForResult.intent.action)
    }
}
