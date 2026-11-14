package com.tvbridge.app

import android.app.Activity
import android.content.*
import android.content.pm.*
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SystemControlsTest {
    @Test fun settingsFallbackTriesOnlyExportedResolvedActivities(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        try{
            fun register(action:String,exported:Boolean){
                val info=ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName="example.settings";name="example.settings."+if(exported)"Visible"else "Private";this.exported=exported;applicationInfo=ApplicationInfo().apply{packageName="example.settings"}}}
                Shadows.shadowOf(a.packageManager).addResolveInfoForIntent(Intent(action),info)
            }
            register(Settings.ACTION_WIFI_SETTINGS,false);register(Settings.ACTION_SETTINGS,true)
            val options=SystemControls.networkIntents(a)
            assertFalse(options.any{it.action==Settings.ACTION_WIFI_SETTINGS});assertTrue(options.any{it.action==Settings.ACTION_SETTINGS})
            assertTrue(SystemControls.open(a,options));assertEquals(Settings.ACTION_SETTINGS,Shadows.shadowOf(a).nextStartedActivity.action)
        }finally{c.pause().stop().destroy()}
    }
}
