package com.tvbridge.app

import android.app.*
import android.content.*
import android.content.pm.*
import android.os.*
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DesktopCompatibilityTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val resolver get()=app.contentResolver
    private val prefs get()=app.getSharedPreferences("startup",0)
    private val me get()=ComponentName(app,DesktopGuardService::class.java).flattenToString()
    @Before fun setup(){
        prefs.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).commit()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        Shadows.shadowOf(app).grantPermissions(app.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        DesktopGuardService.allowStockLauncher(false)
        DesktopTakeover::class.java.getDeclaredField("lastRebind").apply{isAccessible=true}.setLong(null,-60000)
        DesktopTakeover::class.java.getDeclaredField("recoveryPending").apply{isAccessible=true}.setBoolean(null,false)
        (DesktopTakeover::class.java.getDeclaredField("recovery").apply{isAccessible=true}.get(null) as Handler).removeCallbacksAndMessages(null)
        DesktopRecovery::class.java.getDeclaredField("nextSyncCheck").apply{isAccessible=true}.setLong(null,Long.MAX_VALUE)
    }
    @After fun cleanup(){
        prefs.edit().putBoolean("boot",false).putBoolean("desktop_guard",false).commit()
        DesktopTakeover.cancel(app)
        DesktopRecovery::class.java.getDeclaredField("nextSyncCheck").apply{isAccessible=true}.setLong(null,0)
        Hub.activity=null
    }
    private fun home(pkg:String,cls:String,priority:Int=0)=ResolveInfo().apply{
        this.priority=priority;activityInfo=ActivityInfo().apply{
            packageName=pkg;name=cls;enabled=true;exported=true
            applicationInfo=ApplicationInfo().apply{packageName=pkg;enabled=true}
        }
    }
    private fun register(pkg:String,cls:String){Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(Intent("test.$pkg.$cls"),home(pkg,cls))}
    @Test fun detectsVidAALowPriorityHomeButNotFallbacksOrLetvAppDaemon(){
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        listOf(home("example.launcher","example.launcher.Home",100),home("com.keylab.speech.view.vidaa","com.ju.secondscreen.HomeActivity",-150),home("example.fallback","example.fallback.Home",-1000),home("com.stv.appdaemon","com.stv.appdaemon.DaemonActivity",10)).forEach{Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(intent,it)}
        val homes=DesktopTakeover.homes(app)
        assertTrue(ComponentName("com.keylab.speech.view.vidaa","com.ju.secondscreen.HomeActivity") in homes)
        assertFalse(homes.any{it.packageName=="example.fallback"||it.packageName=="com.stv.appdaemon"})
    }
    @Test fun recognizesPrivateCoocaaHomeWithoutAnyHomeFilter(){
        register("com.coocaa.aigc.home","com.coocaa.aigc.home.home.rootpage.HomeActivity")
        assertTrue(ComponentName("com.coocaa.aigc.home","com.coocaa.aigc.home.home.rootpage.HomeActivity") in DesktopTakeover.homes(app))
        assertTrue(DesktopPolicy.vendorCategory("android.intent.category.HOME.CC"))
        assertTrue(DesktopPolicy.vendorCategory("smartdevice.intent.category.LITE_HOME"))
        assertFalse(DesktopPolicy.vendorCategory("android.intent.category.SECONDARY_HOME"))
    }
    @Test fun standbyExceptionIsLimitedToTheReportedTclFirmware(){
        assertTrue(DesktopPolicy.standbyNeedsUnbind("tc8000_ay30a2",30))
        assertFalse(DesktopPolicy.standbyNeedsUnbind("tc8000_ay30a2",29))
        assertFalse(DesktopPolicy.standbyNeedsUnbind("MT5879",30))
    }
    @Test fun cancelDuringRebindCannotReenableOneAndKeepsNewServices(){
        val other="example.other/example.other.Service"
        Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,me)
        DesktopTakeover.rebindIfStalled(app)
        prefs.edit().putBoolean("desktop_guard",false).commit()
        Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,other)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600))
        assertEquals(other,Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
    @Test fun restoringDesktopRemovesOnlyOwnGuardAndRecoveryListener(){
        val other="example.other/example.other.Service";val listener=ComponentName(app,DesktopRevivalService::class.java).flattenToString()
        Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"$other:$me")
        Settings.Secure.putString(resolver,"enabled_notification_listeners","$other:$listener")
        prefs.edit().putBoolean("desktop_guard",false).commit();DesktopTakeover.cancel(app)
        assertEquals(other,Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(other,Settings.Secure.getString(resolver,"enabled_notification_listeners"))
    }
    @Test fun directBootWaitsForUnlockBeforeEnablingGuard(){
        val user=Shadows.shadowOf(app.getSystemService(Context.USER_SERVICE) as UserManager)
        user.setUserUnlocked(false)
        BootReceiver::class.java.getDeclaredMethod("onBoot",Context::class.java,Intent::class.java).apply{isAccessible=true}
            .invoke(BootReceiver(),app,Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse(DesktopTakeover.wanted(app));assertFalse(DesktopTakeover.enabled(app))
        user.setUserUnlocked(true);app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DesktopTakeover.enabled(app))
    }
    @Test fun inputTransitionThroughStockHomeLeavesThePlayerInFront(){
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(intent,home("example.launcher","example.launcher.Home"))
        register("com.tianci.tv","com.tianci.tv.Switcher");register("example.player","example.player.Player")
        val control=Robolectric.buildService(DesktopGuardService::class.java).create();val service=control.get()
        fun event(pkg:String,cls:String){val e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);e.packageName=pkg;e.className=cls;service.onAccessibilityEvent(e);e.recycle()}
        try{
            event("com.tianci.tv","com.tianci.tv.Switcher");event("example.launcher","example.launcher.Home")
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
            event("example.player","example.player.Player")
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
        }finally{control.destroy()}
    }
    @Test fun syncAdapterReceivesSettingsChangeScheduling(){
        app.resources.getXml(R.xml.desktop_sync).use{xml->
            while(xml.next()!=org.xmlpull.v1.XmlPullParser.START_TAG){}
            assertTrue(xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android","supportsUploading",false))
            assertFalse(xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android","userVisible",true))
        }
    }
    @Test fun installerConfirmationIsNeverCoveredByAutomaticOrExplicitRecovery(){
        val control=Robolectric.buildService(DesktopGuardService::class.java).create();val service=control.get()
        val launch=DesktopGuardService::class.java.getDeclaredMethod("launch",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        try{
            InstallHandoff.begin(app)
            launch.invoke(service,false);launch.invoke(service,true)
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
            InstallHandoff.finish(app);launch.invoke(service,true)
            assertNotNull(Shadows.shadowOf(app).nextStartedActivity)
        }finally{InstallHandoff.finish(app);control.destroy()}
    }
    @org.robolectric.annotation.Implements(android.accessibilityservice.AccessibilityService::class)
    class ServiceInfoShadow : org.robolectric.shadows.ShadowAccessibilityService(){
        private var info=android.accessibilityservice.AccessibilityServiceInfo()
        @org.robolectric.annotation.Implementation fun getServiceInfo()=info
        @org.robolectric.annotation.Implementation fun setServiceInfo(value:android.accessibilityservice.AccessibilityServiceInfo){info=value}
    }
    @Config(shadows=[ServiceInfoShadow::class])
    @Test fun skyworthDoesNotRequestTheGlobalKeyFilterThatCanLatchVolume(){
        Shadows.shadowOf(app.packageManager).installPackage(PackageInfo().apply{
            packageName="com.tianci.system";applicationInfo=ApplicationInfo().apply{packageName="com.tianci.system";enabled=true}
        })
        val control=Robolectric.buildService(DesktopGuardService::class.java).create();val service=control.get()
        try{
            service.serviceInfo=android.accessibilityservice.AccessibilityServiceInfo().apply{flags=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS}
            DesktopGuardService::class.java.getDeclaredMethod("configureKeys").apply{isAccessible=true}.invoke(service)
            assertEquals(0,service.serviceInfo.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
            val dispatch=DesktopGuardService::class.java.getDeclaredMethod("onKeyEvent",android.view.KeyEvent::class.java).apply{isAccessible=true}
            for(key in listOf(android.view.KeyEvent.KEYCODE_VOLUME_UP,android.view.KeyEvent.KEYCODE_VOLUME_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_MUTE)){
                assertEquals(false,dispatch.invoke(service,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,key)))
                assertEquals(false,dispatch.invoke(service,android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,key)))
            }
        }finally{control.destroy()}
    }
    @Test fun bootCanOpenConfiguredLiveWhileSubsequentHomeReturnsToDesktop(){
        prefs.edit().putBoolean("live",true).commit()
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(intent,home("example.launcher","example.launcher.Home"))
        val control=Robolectric.buildService(DesktopGuardService::class.java).create();val service=control.get()
        fun event(){val e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);e.packageName="example.launcher";e.className="example.launcher.Home";service.onAccessibilityEvent(e);e.recycle()}
        try{
            event();val boot=Shadows.shadowOf(app).nextStartedActivity
            assertTrue(boot.getBooleanExtra("from_boot",false));assertFalse(boot.getBooleanExtra("show_home",true))
            BootReceiver.markArrived(app);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            event();val next=Shadows.shadowOf(app).nextStartedActivity
            assertFalse(next.getBooleanExtra("from_boot",true));assertTrue(next.getBooleanExtra("show_home",false))
        }finally{control.destroy()}
    }
}
