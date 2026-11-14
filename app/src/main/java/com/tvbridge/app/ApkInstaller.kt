package com.tvbridge.app

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class ApkInstaller(private val activity:Activity){
    private var pendingName:String?=null
    private var awaitingPermission=false
    private var permissionLaunchedAt=0L
    private var pendingInstallName:String?=null
    private var pendingInstallAt=0L
    private var installReceiver:BroadcastReceiver?=null
    private var sessionFallbackTried=false
    private var homePaused=false
    private var installerLeftHome=false
    private var openingInstaller=false
    private var deferredConfirmation:Intent?=null
    private var deferredRetries=0
    private var installerSeen=false
    private var confirmationCheck:Runnable?=null
    private var sessionId=-1
    private val abandonedSessions=mutableSetOf<Int>()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var sessionTimeout:Runnable?=null
    companion object{const val REQUEST_PERMISSION=11;const val INSTALL_STATUS_ACTION="com.tvbridge.app.INSTALL_STATUS"}
    val allowed get()=if(android.os.Build.VERSION.SDK_INT>=26)activity.packageManager.canRequestPackageInstalls()else android.provider.Settings.Secure.getInt(activity.contentResolver,android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS,0)==1
    fun request(file:File){
        if((sessionId>=0||openingInstaller)&&android.os.SystemClock.uptimeMillis()-pendingInstallAt>=120000)resetStaleInstall()
        if(sessionId>=0||openingInstaller){message("安装正在进行，请先完成或取消安装");return}
        if(!valid(file)){message("文件已不存在或不是 APK");return}
        if(installViaSession(file))return
        if(allowed){launch(file);return}
        openPermissionSettings(file)
    }
    private fun resetStaleInstall(){
        val id=sessionId
        sessionId=-1;openingInstaller=false;installerSeen=false;deferredConfirmation=null;deferredRetries=0
        sessionTimeout?.let{main.removeCallbacks(it)};sessionTimeout=null
        confirmationCheck?.let{main.removeCallbacks(it)};confirmationCheck=null
        if(id>=0){
            abandonedSessions.add(id)
            if(android.os.Build.VERSION.SDK_INT>=21)try{activity.packageManager.packageInstaller.abandonSession(id)}catch(_:Exception){}
        }
        pendingInstallName=null
        InstallHandoff.finish(activity)
    }
    fun openPermissionSettings(file:File?=null){
        pendingName=file?.takeIf{valid(it)}?.name;awaitingPermission=true;permissionLaunchedAt=android.os.SystemClock.uptimeMillis()
        val own=Uri.parse("package:${activity.packageName}")
        for(intent in (if(android.os.Build.VERSION.SDK_INT>=26)listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,own),Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))else emptyList())+listOf(Intent(Settings.ACTION_SECURITY_SETTINGS),Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,own))){
            try{activity.startActivityForResult(intent,REQUEST_PERMISSION);return}catch(_:ActivityNotFoundException){}catch(_:SecurityException){}
        }
        awaitingPermission=false;pendingName=null;message("系统没有可用的安装授权页面")
    }
    fun onResult(request:Int):Boolean{if(request!=REQUEST_PERMISSION)return false;return true}
    fun onResume(){
        homePaused=false
        val justLeftInstaller=installerLeftHome
        if(installerLeftHome||(!openingInstaller&&sessionId<0&&pendingInstallName==null)){
            InstallHandoff.finish(activity);openingInstaller=false;installerLeftHome=false
        }
        val confirmation=deferredConfirmation
        if(confirmation!=null){deferredConfirmation=null;openConfirmation(confirmation)}
        else if(!justLeftInstaller&&sessionId>=0&&!openingInstaller&&android.os.SystemClock.uptimeMillis()-pendingInstallAt>=20000)fallbackSession()
        if(!awaitingPermission)return
        if(android.os.SystemClock.uptimeMillis()-permissionLaunchedAt<1000)return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({resumePermission()},500)
    }
    fun onPause(){homePaused=true;if(openingInstaller){installerLeftHome=true;installerSeen=true;InstallHandoff.opened(activity)}}
    private fun openConfirmation(confirmation:Intent){
        if(homePaused){
            deferredConfirmation=confirmation
            if(deferredRetries<5){
                deferredRetries++
                main.postDelayed({
                    val pending=deferredConfirmation
                    if(pending!=null){deferredConfirmation=null;openConfirmation(pending)}
                },3000)
            }
            return
        }
        deferredRetries=0
        try{
            openingInstaller=true;installerLeftHome=false;pendingInstallAt=android.os.SystemClock.uptimeMillis();InstallHandoff.begin(activity)
            activity.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));checkConfirmationOpened()
        }catch(_:RuntimeException){fallbackSession()}
    }
    private fun resumePermission(){
        if(!awaitingPermission)return
        awaitingPermission=false;val name=pendingName;pendingName=null
        if(name==null)return
        if(!allowed){
            val file=File(activity.filesDir,"received/$name")
            if(valid(file)&&installViaSession(file))return
            message("尚未允许安装应用");return
        }
        val file=File(activity.filesDir,"received/$name")
        if(!valid(file)){message("APK 文件已不存在");return}
        if(!installViaSession(file))launch(file)
    }
    fun save(state:Bundle){
        state.putString("install.pending",pendingName);state.putBoolean("install.awaiting",awaitingPermission)
        state.putString("install.sessionName",pendingInstallName);state.putInt("install.sessionId",sessionId);state.putLong("install.sessionAt",pendingInstallAt)
    }
    fun restore(state:Bundle?){
        pendingName=state?.getString("install.pending")?.takeIf{it==File(it).name};awaitingPermission=state?.getBoolean("install.awaiting",false)==true
        if(state==null)return
        pendingInstallName=state.getString("install.sessionName")?.takeIf{it==File(it).name}
        pendingInstallAt=state.getLong("install.sessionAt",0)
        val restoredId=state.getInt("install.sessionId",-1)
        if(restoredId>=0&&pendingInstallName!=null&&android.os.Build.VERSION.SDK_INT>=21){
            sessionId=restoredId;ensureInstallReceiver();armSessionWatchdog(restoredId)
        }
    }
    private fun valid(file:File)=try{file.isFile&&file.extension.equals("apk",true)&&file.canonicalFile.parentFile==File(activity.filesDir,"received").canonicalFile}catch(_:Exception){false}
    private fun launch(file:File){
        try{
            val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.files",file)
            val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .apply{clipData=ClipData.newRawUri("APK",uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val handler=activity.packageManager.resolveActivity(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            if(android.os.Build.VERSION.SDK_INT>=21&&handler?.activityInfo?.name?.endsWith(".DBInstallStart")==true&&Hub.adb.enabled()){
                confirmCompatibleInstall(file)
            }else {
                pendingInstallName=file.name;pendingInstallAt=android.os.SystemClock.uptimeMillis()
                openingInstaller=true;installerLeftHome=false;installerSeen=false;InstallHandoff.begin(activity);activity.startActivity(intent)
                checkConfirmationOpened()
            }
        }catch(_:RuntimeException){InstallHandoff.finish(activity);message("无法打开系统安装器")}
    }
    private fun checkConfirmationOpened(){
        confirmationCheck?.let{main.removeCallbacks(it)};confirmationCheck=null
        val check=object:Runnable{
            override fun run(){
                if(!openingInstaller||installerLeftHome)return
                if(homePaused&&android.os.SystemClock.uptimeMillis()-pendingInstallAt<30000){main.postDelayed(this,2000);return}
                openingInstaller=false;InstallHandoff.finish(activity)
                if(sessionId>=0)fallbackSession()else message("系统安装确认页未打开，请重试")
            }
        }
        confirmationCheck=check
        main.postDelayed(check,10000)
    }
    private fun fallbackSession(){
        val id=sessionId;if(id<0)return
        sessionTimeout?.let{main.removeCallbacks(it)};sessionTimeout=null
        sessionId=-1;installerSeen=false;abandonedSessions.add(id)
        if(android.os.Build.VERSION.SDK_INT>=21)try{activity.packageManager.packageInstaller.abandonSession(id)}catch(_:Exception){}
        val file=pendingInstallName?.let{File(activity.filesDir,"received/$it")}
        if(file!=null&&valid(file)&&!sessionFallbackTried){sessionFallbackTried=true;launch(file)}else{InstallHandoff.finish(activity);message("无法打开系统安装器")}
    }
    private fun ensureInstallReceiver(){
        if(installReceiver!=null)return
        installReceiver=object:BroadcastReceiver(){
            override fun onReceive(context:Context,intent:Intent){
                val returnedId=intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID,-1)
                if(returnedId in abandonedSessions||(returnedId>=0&&returnedId!=sessionId))return
                sessionTimeout?.let{main.removeCallbacks(it)};sessionTimeout=null
                val status=intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,android.content.pm.PackageInstaller.STATUS_FAILURE)
                val msg=intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                if(status==android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION){
                    val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if(confirmation!=null)openConfirmation(confirmation)else fallbackSession()
                    return
                }
                sessionId=-1;openingInstaller=false;installerSeen=false
                InstallHandoff.finish(activity)
                if(status==android.content.pm.PackageInstaller.STATUS_SUCCESS){message("安装完成");Hub.live.refreshPlugin();return}
                if(status==android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED){message("安装已取消");return}
                val name=pendingInstallName
                val file=name?.let{File(activity.filesDir,"received/$it")}
                if(file!=null&&valid(file)&&!sessionFallbackTried){sessionFallbackTried=true;message("会话安装失败，转系统安装器"+if(!msg.isNullOrBlank())"：$msg" else "");launch(file);return}
                message("安装失败"+if(!msg.isNullOrBlank())"：$msg" else "")
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(activity,installReceiver,IntentFilter(INSTALL_STATUS_ACTION),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun armSessionWatchdog(id:Int){
        sessionTimeout?.let{main.removeCallbacks(it)};sessionTimeout=null
        val watch=object:Runnable{
            override fun run(){
                if(sessionId!=id)return
                val silentPossible=android.os.Build.VERSION.SDK_INT>=31
                val limit=if(silentPossible)90000L else 60000L
                val wedged=!installerSeen&&android.os.SystemClock.uptimeMillis()-pendingInstallAt>=limit
                if(wedged||(!homePaused&&!installerSeen&&!silentPossible)){fallbackSession();return}
                main.postDelayed(this,5000)
            }
        }
        sessionTimeout=watch
        main.postDelayed(watch,20000)
    }
    private fun installViaSession(file:File):Boolean{
        if(android.os.Build.VERSION.SDK_INT<21)return false
        return try{
            ensureInstallReceiver()
            sessionFallbackTried=false;deferredRetries=0;installerSeen=false
            val installer=activity.packageManager.packageInstaller
            val params=android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if(android.os.Build.VERSION.SDK_INT>=31)params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val sessionId=installer.createSession(params)
            this.sessionId=sessionId
            pendingInstallName=file.name;pendingInstallAt=android.os.SystemClock.uptimeMillis()
            InstallHandoff.begin(activity)
            val session=installer.openSession(sessionId)
            try{
                val out=session.openWrite("apk",0,file.length())
                file.inputStream().use{input->input.copyTo(out)}
                session.fsync(out);out.close()
                val flags=android.app.PendingIntent.FLAG_UPDATE_CURRENT or if(android.os.Build.VERSION.SDK_INT>=31)android.app.PendingIntent.FLAG_MUTABLE else 0
                val sender=android.app.PendingIntent.getBroadcast(activity,sessionId,Intent(INSTALL_STATUS_ACTION).setPackage(activity.packageName),flags)
                session.commit(sender.intentSender)
            }finally{session.close()}
            armSessionWatchdog(sessionId)
            true
        }catch(_:Exception){sessionId=-1;InstallHandoff.finish(activity);false}
    }
    fun checkInstallStatus(){
        val name=pendingInstallName?:return
        if(openingInstaller||sessionId>=0||android.os.SystemClock.uptimeMillis()-pendingInstallAt<1000)return
        pendingInstallName=null
        val file=File(activity.filesDir,"received/$name")
        if(!valid(file))return
        val pkg=try{activity.packageManager.getPackageArchiveInfo(file.absolutePath,0)?.packageName}catch(_:Exception){null}?:return
        val installed=try{activity.packageManager.getPackageInfo(pkg,0);true}catch(_:Exception){false}
        if(installed){message("安装完成");Hub.live.refreshPlugin()}
    }
    fun destroy(){main.removeCallbacksAndMessages(null);installReceiver?.let{try{activity.unregisterReceiver(it)}catch(_:Exception){}};installReceiver=null}
    private fun confirmCompatibleInstall(file:File){
        if(!Hub.adb.enabled()){message("此电视安装器异常，请先在遥控设置中连接系统遥控，再重试安装");return}
        NativeUi.show(AlertDialog.Builder(activity).setTitle("兼容安装").setMessage("通过已授权的系统调试安装 ${file.name}？")
            .setPositiveButton("安装"){_,_->
                message("正在安装")
                Thread({
                    val result=(Hub.adb.ready||Hub.adb.connect(Hub.adb.port()))&&Hub.adb.installApk(file)
                    activity.runOnUiThread{message(if(result)"安装完成"else "安装失败，请检查 APK 签名、版本和系统遥控连接")}
                },"apk-compatible-install").start()
            }.setNegativeButton("取消",null))
    }
    private fun message(text:String){NativeUi.notice(activity,text)}
}
