package com.tvbridge.app

import android.app.AlarmManager
import android.content.*
import android.content.pm.*
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DesktopTakeoverTest {
    @org.junit.Before fun receiverPermission(){val app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app).grantPermissions(app.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")}
    @Test fun stalledBindingRebindsOnlyOurServiceAndPreservesConcurrentChanges(){
        val a=RuntimeEnvironment.getApplication();val p=a.getSharedPreferences("startup",0)
        Shadows.shadowOf(a).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        p.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).commit()
        val resolver=a.contentResolver;val other="example.other/example.other.Service";val added="example.new/example.new.Service"
        val me=ComponentName(a,DesktopGuardService::class.java).flattenToString()
        Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"$other:$me")
        DesktopTakeover.rebindIfStalled(a)
        assertEquals(other,Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"$other:$added")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600))
        assertEquals("$other:$added:$me",Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        p.edit().clear().commit()
    }
    @Test fun disabledGuardConnectsWithoutForegroundAccess(){
        val c=Robolectric.buildService(DesktopGuardService::class.java).create();val s=c.get()
        try{
            DesktopGuardService::class.java.getDeclaredMethod("onServiceConnected").apply{isAccessible=true}.invoke(s)
            assertTrue(DesktopGuardService.connected)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }finally{c.destroy()}
    }
    @Test fun guardRestorationPreservesOtherServicesAndHonorsOptOut(){
        val a=RuntimeEnvironment.getApplication();val p=a.getSharedPreferences("startup",0)
        Shadows.shadowOf(a).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        val resolver=a.contentResolver;val other="example.other/example.other.Service"
        Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,other)
        p.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).commit()
        assertTrue(DesktopTakeover.ensureGuard(a));assertTrue(DesktopTakeover.enabled(a))
        val once=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assertTrue(once.startsWith(other+":"));DesktopTakeover.ensureGuard(a)
        assertEquals(once,Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        p.edit().putBoolean("desktop_guard",false).commit()
        Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,other)
        assertFalse(DesktopTakeover.ensureGuard(a));assertEquals(other,Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
    @Test fun homeKeyIsOptInAndDoesNotConsumeDirectionOrLaunchOnKeyDown(){
        val a=RuntimeEnvironment.getApplication();val p=a.getSharedPreferences("startup",0)
        val c=Robolectric.buildService(DesktopGuardService::class.java).create();val s=c.get()
        val key=DesktopGuardService::class.java.getDeclaredMethod("onKeyEvent",android.view.KeyEvent::class.java).apply{isAccessible=true}
        fun send(action:Int,code:Int)=key.invoke(s,android.view.KeyEvent(action,code)) as Boolean
        try{
            assertFalse(send(0,android.view.KeyEvent.KEYCODE_HOME))
            p.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).commit()
            assertFalse(send(0,android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            assertTrue(send(0,android.view.KeyEvent.KEYCODE_HOME))
            assertNull(Shadows.shadowOf(a).nextStartedActivity)
            assertTrue(send(1,android.view.KeyEvent.KEYCODE_HOME))
            assertEquals(MainActivity::class.java.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        }finally{c.destroy();p.edit().clear().commit()}
    }
    @Test fun durableBootAttemptsAreBoundedAndArrivalCancelsThem(){
        val a=RuntimeEnvironment.getApplication();val p=a.getSharedPreferences("startup",0);p.edit().putBoolean("boot",true).commit()
        val alarms=Shadows.shadowOf(a.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        DesktopTakeover.schedule(a)
        repeat(3){
            val intent=Intent(Shadows.shadowOf(alarms.nextScheduledAlarm!!.operation).savedIntent)
            assertEquals(it,intent.getIntExtra("step",-1));BootAttemptReceiver().onReceive(a,intent)
            assertEquals(MainActivity::class.java.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
        }
        assertFalse(p.contains("boot_token"))
        DesktopTakeover.schedule(a);val stale=Intent(Shadows.shadowOf(alarms.nextScheduledAlarm!!.operation).savedIntent)
        BootReceiver.markArrived(a);BootAttemptReceiver().onReceive(a,stale)
        assertNull(Shadows.shadowOf(a).nextStartedActivity);assertFalse(p.contains("boot_token"))
    }
    @Test fun disablingBootInvalidatesScheduledAttemptAndPermissionStateIsHonest(){
        val a=RuntimeEnvironment.getApplication();val p=a.getSharedPreferences("startup",0);p.edit().putBoolean("boot",true).putBoolean("desktop_guard",true).commit()
        assertEquals("待授权",DesktopTakeover.label(a))
        Settings.Secure.putString(a.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,ComponentName(a,DesktopGuardService::class.java).flattenToString())
        assertTrue(DesktopTakeover.enabled(a))
        DesktopTakeover.schedule(a)
        val alarm=Shadows.shadowOf(a.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextScheduledAlarm
        val intent=Intent(Shadows.shadowOf(alarm!!.operation).savedIntent)
        p.edit().putBoolean("boot",false).commit();BootAttemptReceiver().onReceive(a,intent)
        assertNull(Shadows.shadowOf(a).nextStartedActivity);assertFalse(DesktopTakeover.wanted(a));DesktopTakeover.cancel(a)
    }
    @Test fun guardTargetsOnlyHomeActivityAndRespectsIntentionalStockLaunch(){
        val a=RuntimeEnvironment.getApplication();a.getSharedPreferences("startup",0).edit().putBoolean("boot",true).putBoolean("desktop_guard",true).commit()
        val info=ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName="example.launcher";name="example.launcher.Home";exported=true;applicationInfo=ApplicationInfo().apply{packageName="example.launcher"}}}
        Shadows.shadowOf(a.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),info)
        val c=Robolectric.buildService(DesktopGuardService::class.java).create();val service=c.get()
        fun event(pkg:String,cls:String){val e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);e.packageName=pkg;e.className=cls;service.onAccessibilityEvent(e);e.recycle()}
        try{
            event(a.packageName,MainActivity::class.java.name)
            event("example.video","example.video.Player");event("example.launcher","example.launcher.Settings")
            assertNull(Shadows.shadowOf(a).nextStartedActivity)
            DesktopGuardService.allowStockLauncher();event("example.launcher","example.launcher.Home")
            assertNull(Shadows.shadowOf(a).nextStartedActivity)
            DesktopGuardService.onOneResumed();event(a.packageName,MainActivity::class.java.name);event("example.launcher","example.launcher.Home")
            val started=Shadows.shadowOf(a).nextStartedActivity
            assertEquals(MainActivity::class.java.name,started.component!!.className);assertTrue(started.getBooleanExtra("show_home",false))
            event("example.launcher","example.launcher.Home");assertNull(Shadows.shadowOf(a).nextStartedActivity)
        }finally{c.destroy();a.getSharedPreferences("startup",0).edit().clear().commit()}
    }
}
