package com.tvbridge.app

import android.content.ComponentName
import android.content.Context
import android.os.Build

/** Home-specific firmware contracts from Aerial edfffc7 (MIT; assets/licenses/aerial-MIT.txt). */
internal object DesktopPolicy {
    val inputPlayers=setOf("com.tcl.tv","com.xiaomi.mitv.tvplayer","com.skyworth.tv","com.mediatek.wwtv.tvcenter")
    val notLaunchers=setOf("com.stv.appdaemon","com.letv.lekids")
    val sourceSwitchers=setOf("com.tianci.tv")
    private val vidaa=setOf("com.hisense.vidaaassistant","com.jamdeo.tv.vod")
    private val unfiltered=mapOf(
        "com.skyworth.panasonichome" to "com.skyworth.panasonichome.MainActivity",
        "com.coocaa.aigc.home" to "com.coocaa.aigc.home.home.rootpage.HomeActivity",
        "com.tianci.movieplatform" to "com.coocaa.homepage.vast.HomePageActivity",
        "com.mylejia.launcher" to "com.mylejia.launcher.activity.DesktopActivity",
        "com.jamdeo.tv.vod" to "com.ju.main.launcher.LauncherActivity",
        "com.xiaomi.eetv.litehome" to "com.xiaomi.eetv.litehome.Launcher",
        "com.mitv.tvhome.atv" to "com.mitv.tvhome.atv.app.PatchWallActivity"
    )
    val FIRMWARE_BOOT_APPS=setOf("com.yunos.tv.bluray")
    private val trampolines=mapOf(
        "com.stv.deskplatform" to "com.stv.deskplatform.ui.activity.Launcher",
        "com.ktcp.launcher" to "com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity",
        "com.cos.launcher" to "com.dangbei.tvlauncher.IndexActivity"
    )
    fun vendorCategory(category:String)=category.startsWith("android.intent.category.HOME.")||(category.endsWith("_HOME")&&category!="android.intent.category.SECONDARY_HOME")
    fun acceptsPriority(component:ComponentName,priority:Int)=priority>=0||component==ComponentName("com.keylab.speech.view.vidaa","com.ju.secondscreen.HomeActivity")
    fun installed(context:Context,pkg:String)=runCatching{context.packageManager.getApplicationInfo(pkg,0).enabled}.getOrDefault(false)
    fun passKeys(context:Context)=vidaa.any{installed(context,it)}
    fun volumeFilterUnsafe(context:Context)=installed(context,"com.tianci.system")||
        Build.MANUFACTURER.lowercase(java.util.Locale.US).let{it.contains("skyworth")||it.contains("coocaa")}
    fun usageAllowed(context:Context):Boolean {
        if(Build.VERSION.SDK_INT<21)return false
        return runCatching{(context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager).checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.packageName)==android.app.AppOpsManager.MODE_ALLOWED}.getOrDefault(false)
    }
    fun watchForeground(context:Context)=Build.VERSION.SDK_INT in 21..23||passKeys(context)||volumeFilterUnsafe(context)
    fun backOnPause(context:Context)=Build.VERSION.SDK_INT>=31&&installed(context,"com.mitv.tvhome")
    fun standbyNeedsUnbind(model:String=Build.MODEL,sdk:Int=Build.VERSION.SDK_INT)=sdk==30&&model.equals("tc8000_ay30a2",true)
    fun startsOnSignalSource(context:Context):Boolean=runCatching{
        context.packageManager.getApplicationInfo("com.jmgo.tv",0)
        val getter=Class.forName("android.os.SystemProperties").getMethod("getInt",String::class.java,Int::class.javaPrimitiveType)
        (getter.invoke(null,"persist.sys.boot.source",0) as Int)!=0
    }.getOrDefault(false)

    fun bootCover(component:ComponentName)=android.os.SystemClock.elapsedRealtime()<180000&&(component==ComponentName("com.mitv.tvhome","com.mitv.tvhome.BootActivity")||component==ComponentName("android","com.android.internal.app.ResolverActivity"))
    fun extras(context:Context,known:Set<ComponentName>):Set<ComponentName> {
        val packages=known.map{it.packageName}.toSet()
        return (unfiltered+trampolines.filterKeys{it in packages}).map{ComponentName(it.key,it.value)}
            .filter{runCatching{context.packageManager.getActivityInfo(it,0).let{a->a.enabled&&a.applicationInfo.enabled}}.getOrDefault(false)}.toSet()
    }
}
