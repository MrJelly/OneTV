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
    private var homeInFront=false
    private var installerLeftHome=false
    private var openingInstaller=false
    private var installerSeen=false
    private var confirmationCheck:Runnable?=null
    private var sessionId=-1
    private val abandonedSessions=mutableSetOf<Int>()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var awaitId=-1
    private var awaitConfirmation=false
    private var awaitShowConfirmation:Intent?=null
    private var awaitHomeLeft=false
    private var awaitLeftAt=0L
    private var awaitTimedOut=false
    private var awaitCheck:Runnable?=null

    private fun answerTimeout()=if(android.os.Build.VERSION.SDK_INT>=31)SILENT_INSTALL_TIMEOUT_MS else ANSWER_TIMEOUT_MS
    companion object{
        const val REQUEST_PERMISSION=11
        const val INSTALL_STATUS_ACTION="com.tvbridge.app.INSTALL_STATUS"
        const val ANSWER_TIMEOUT_MS=8_000L
        const val SILENT_INSTALL_TIMEOUT_MS=120_000L
        const val CONFIRM_TIMEOUT_MS=4_000L
        const val PAGE_DIED_MS=1_500L
        const val ANSWER_AFTER_RETURN_MS=1_500L
    }
    val allowed get()=if(android.os.Build.VERSION.SDK_INT>=26)activity.packageManager.canRequestPackageInstalls()else android.provider.Settings.Secure.getInt(activity.contentResolver,android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS,0)==1
    private val useInstallSession get()=android.os.Build.VERSION.SDK_INT>=28
    fun request(file:File){
        if((sessionId>=0||openingInstaller)&&activity.hasWindowFocus()&&
            (installerSeen||android.os.SystemClock.uptimeMillis()-pendingInstallAt>=10000))resetStaleInstall()
        if(sessionId>=0||openingInstaller){message("安装正在进行，请先完成或取消安装");return}
        if(!valid(file)){message("文件已不存在或不是 APK");return}
        if(allowed){launch(file);return}
        openPermissionSettings(file)
    }
    private fun resetStaleInstall(){
        val id=sessionId
        sessionId=-1;openingInstaller=false;installerSeen=false
        stopAwait()
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
        InstallHandoff.begin(activity)
        val own=Uri.parse("package:${activity.packageName}")
        for(intent in (if(android.os.Build.VERSION.SDK_INT>=26)listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,own),Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))else emptyList())+listOf(Intent(Settings.ACTION_SECURITY_SETTINGS),Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,own))){
            try{activity.startActivityForResult(intent,REQUEST_PERMISSION);return}catch(_:ActivityNotFoundException){}catch(_:SecurityException){}
        }
        awaitingPermission=false;pendingName=null;InstallHandoff.finish(activity);message("系统没有可用的安装授权页面")
    }
    fun onResult(request:Int):Boolean{
        if(request!=REQUEST_PERMISSION)return false
        if(awaitingPermission)main.post{resumePermission()}
        return true
    }
    fun onResume(){
        homeInFront=true
        if(installerLeftHome||(!openingInstaller&&sessionId<0&&pendingInstallName==null&&!awaitingPermission)){
            InstallHandoff.finish(activity);openingInstaller=false;installerLeftHome=false
        }
        if(awaitId>=0&&awaitId==sessionId){
            val parked=awaitShowConfirmation
            when{
                parked!=null->{awaitShowConfirmation=null;openConfirmation(parked)}
                awaitConfirmation&&awaitHomeLeft->{
                    if(android.os.SystemClock.uptimeMillis()-awaitLeftAt<PAGE_DIED_MS){
                        val id=awaitId
                        main.postDelayed({if(awaitId==id&&awaitConfirmation)giveUpSession()},ANSWER_AFTER_RETURN_MS)
                    }else stopAwait()
                }
                awaitTimedOut->runAwaitCheck()
            }
        }
        if(!awaitingPermission)return
        if(android.os.SystemClock.uptimeMillis()-permissionLaunchedAt<1000)return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({resumePermission()},500)
    }
    fun onPause(){
        homeInFront=false
        if(openingInstaller){installerLeftHome=true;installerSeen=true;InstallHandoff.opened(activity)}
        if(awaitConfirmation&&awaitId==sessionId&&!awaitHomeLeft){awaitLeftAt=android.os.SystemClock.uptimeMillis();awaitHomeLeft=true}
    }
    private fun openConfirmation(confirmation:Intent){
        val started=try{
            openingInstaller=true;installerLeftHome=false;pendingInstallAt=android.os.SystemClock.uptimeMillis();InstallHandoff.begin(activity)
            activity.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true
        }catch(_:RuntimeException){false}
        if(started)awaitSession(sessionId,CONFIRM_TIMEOUT_MS,confirmation=true)else giveUpSession()
    }
    private fun resumePermission(){
        if(!awaitingPermission)return
        awaitingPermission=false;val name=pendingName;pendingName=null;InstallHandoff.finish(activity)
        if(name==null)return
        if(!allowed){
            val file=File(activity.filesDir,"received/$name")
            if(valid(file)&&installViaSession(file))return
            message("尚未允许安装应用");return
        }
        val file=File(activity.filesDir,"received/$name")
        if(!valid(file)){message("APK 文件已不存在");return}
        launch(file)
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
            sessionId=restoredId;ensureInstallReceiver();awaitSession(restoredId,answerTimeout())
        }
    }
    private fun valid(file:File)=try{file.isFile&&file.extension.equals("apk",true)&&file.canonicalFile.parentFile==File(activity.filesDir,"received").canonicalFile}catch(_:Exception){false}
    private fun launch(file:File){
        try{
            val uri=if(android.os.Build.VERSION.SDK_INT<24){
                val directory=activity.externalCacheDir?:throw IllegalStateException("External cache unavailable")
                val staged=File(directory,"one-install.apk")
                file.copyTo(staged,overwrite=true)
                Uri.fromFile(staged)
            }else FileProvider.getUriForFile(activity,"${activity.packageName}.files",file)
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
        }catch(_:Exception){openingInstaller=false;InstallHandoff.finish(activity);message("无法打开系统安装器")}
    }
    private fun checkConfirmationOpened(){
        confirmationCheck?.let{main.removeCallbacks(it)};confirmationCheck=null
        val check=object:Runnable{
            override fun run(){
                if(!openingInstaller||installerLeftHome)return
                if(!homeInFront&&android.os.SystemClock.uptimeMillis()-pendingInstallAt<30000){main.postDelayed(this,2000);return}
                openingInstaller=false;InstallHandoff.finish(activity)
                message("系统安装确认页未打开，请重试")
            }
        }
        confirmationCheck=check
        main.postDelayed(check,10000)
    }
    private fun giveUpSession(){
        val id=sessionId;if(id<0)return
        stopAwait()
        sessionId=-1;installerSeen=false;openingInstaller=false;abandonedSessions.add(id)
        if(android.os.Build.VERSION.SDK_INT>=21)try{activity.packageManager.packageInstaller.abandonSession(id)}catch(_:Exception){}
        val file=pendingInstallName?.let{File(activity.filesDir,"received/$it")}
        if(file!=null&&valid(file)&&!sessionFallbackTried){sessionFallbackTried=true;launch(file)}else{InstallHandoff.finish(activity);message("无法打开系统安装器")}
    }
    private fun awaitSession(id:Int,timeoutMs:Long,confirmation:Boolean=false,showConfirmation:Intent?=null){
        stopAwait()
        if(id<0)return
        awaitId=id;awaitConfirmation=confirmation;awaitShowConfirmation=showConfirmation
        if(showConfirmation==null){
            val check=Runnable{runAwaitCheck()}
            awaitCheck=check
            main.postDelayed(check,timeoutMs)
        }
    }
    private fun runAwaitCheck(){
        if(awaitId<0||awaitId!=sessionId)return
        if(awaitConfirmation&&awaitHomeLeft)return
        awaitTimedOut=true
        if(homeInFront)giveUpSession()
    }
    private fun stopAwait(){
        awaitCheck?.let{main.removeCallbacks(it)};awaitCheck=null
        awaitId=-1;awaitConfirmation=false;awaitShowConfirmation=null;awaitHomeLeft=false;awaitLeftAt=0L;awaitTimedOut=false
    }
    private fun ensureInstallReceiver(){
        if(installReceiver!=null)return
        installReceiver=object:BroadcastReceiver(){
            override fun onReceive(context:Context,intent:Intent){
                val returnedId=intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID,-1)
                if(returnedId in abandonedSessions||(returnedId>=0&&returnedId!=sessionId))return
                if(returnedId==sessionId&&sessionId>=0)stopAwait()
                val status=intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,android.content.pm.PackageInstaller.STATUS_FAILURE)
                val msg=intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                if(status==android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION){
                    val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if(confirmation==null){giveUpSession();return}
                    if(homeInFront)openConfirmation(confirmation)
                    else awaitSession(sessionId,CONFIRM_TIMEOUT_MS,confirmation=true,showConfirmation=confirmation)
                    return
                }
                sessionId=-1;openingInstaller=false;installerSeen=false
                InstallHandoff.finish(activity)
                if(status==android.content.pm.PackageInstaller.STATUS_SUCCESS){message("安装完成");return}
                if(status==android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED){message("安装已取消");return}
                val name=pendingInstallName
                val file=name?.let{File(activity.filesDir,"received/$it")}
                if(file!=null&&valid(file)&&!sessionFallbackTried){sessionFallbackTried=true;message("会话安装失败，转系统安装器"+if(!msg.isNullOrBlank())"：$msg" else "");launch(file);return}
                message("安装失败"+if(!msg.isNullOrBlank())"：$msg" else "")
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(activity,installReceiver,IntentFilter(INSTALL_STATUS_ACTION),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun installViaSession(file:File):Boolean{
        if(!useInstallSession)return false
        return try{
            ensureInstallReceiver()
            sessionFallbackTried=false;installerSeen=false
            val installer=activity.packageManager.packageInstaller
            val params=android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if(android.os.Build.VERSION.SDK_INT>=31)params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            val sessionId=installer.createSession(params)
            this.sessionId=sessionId
            pendingInstallName=file.name;pendingInstallAt=android.os.SystemClock.uptimeMillis()
            InstallHandoff.begin(activity)
            val session=installer.openSession(sessionId)
            try{
                session.openWrite("apk",0,file.length()).use{out->
                    file.inputStream().use{input->input.copyTo(out)}
                    session.fsync(out)
                }
                val flags=android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if(android.os.Build.VERSION.SDK_INT>=31)android.app.PendingIntent.FLAG_MUTABLE else 0) or
                    (if(android.os.Build.VERSION.SDK_INT>=34)android.app.PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT else 0)
                val sender=android.app.PendingIntent.getBroadcast(activity,sessionId,Intent(INSTALL_STATUS_ACTION).setPackage(activity.packageName),flags)
                session.commit(sender.intentSender)
            }finally{session.close()}
            awaitSession(sessionId,answerTimeout())
            true
        }catch(_:Exception){resetStaleInstall();false}
    }
    fun checkInstallStatus(){
        val name=pendingInstallName?:return
        if(openingInstaller||sessionId>=0||android.os.SystemClock.uptimeMillis()-pendingInstallAt<1000)return
        pendingInstallName=null
        val file=File(activity.filesDir,"received/$name")
        if(!valid(file))return
        val pkg=try{activity.packageManager.getPackageArchiveInfo(file.absolutePath,0)?.packageName}catch(_:Exception){null}?:return
        val installed=try{activity.packageManager.getPackageInfo(pkg,0);true}catch(_:Exception){false}
        if(installed){message("安装完成")}
    }
    fun destroy(){main.removeCallbacksAndMessages(null);installReceiver?.let{try{activity.unregisterReceiver(it)}catch(_:Exception){}};installReceiver=null}
    private fun confirmCompatibleInstall(file:File){
        if(!Hub.adb.enabled()){message("此电视安装器异常，请先在遥控设置中连接系统遥控，再重试安装");return}
        NativeUi.show(AlertDialog.Builder(activity).setTitle("兼容安装").setMessage("通过已授权的系统调试安装 ${file.name}？")
            .setPositiveButton("安装"){_,_->
                message("正在安装")
                AppThreads.onIo{
                    val result=(Hub.adb.ready||Hub.adb.connect(Hub.adb.port()))&&Hub.adb.installApk(file)
                    activity.runOnUiThread{message(if(result)"安装完成"else "安装失败，请检查 APK 签名、版本和系统遥控连接")}
                }
            }.setNegativeButton("取消",null))
    }
    private fun message(text:String){NativeUi.notice(activity,text)}
}
