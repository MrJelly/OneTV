package com.tvbridge.app

import android.app.*
import android.os.Build
import android.view.Gravity
import android.widget.*

class UpdateController(private val activity:Activity,private val installer:ApkInstaller){
    private var request:GithubUpdates.Request?=null
    private var dialog:AlertDialog?=null
    private var destroyed=false
    private fun reason(error:Exception)=when(error){
        is java.net.SocketTimeoutException->"连接超时，请稍后重试"
        is java.net.UnknownHostException->"更新线路暂不可用，请检查网络"
        is javax.net.ssl.SSLException->"安全连接失败，请检查设备时间或系统证书"
        else->error.message?:"更新失败，请稍后重试"
    }
    private fun available()=!destroyed&&!activity.isFinishing&&(Build.VERSION.SDK_INT<17||!activity.isDestroyed)
    fun destroy(){destroyed=true;request?.cancel();request=null;dialog?.dismiss()}
    fun check(){
        if(request!=null)return
        val work=GithubUpdates.Request();request=work
        val waiting=NativeUi.show(AlertDialog.Builder(activity).setTitle("检查更新").setMessage("正在检查更新，自动选择可用线路…").setNegativeButton("取消"){_,_->work.cancel()});dialog=waiting
        waiting.setOnCancelListener{work.cancel();if(request===work)request=null}
        Thread({
            var release:GithubUpdates.Release?=null
            val error=try{release=work.latest();null}catch(e:Exception){reason(e)}
            activity.runOnUiThread{
                if(request!==work)return@runOnUiThread
                val cancelled=work.cancelled();request=null;waiting.dismiss()
                if(!available()||cancelled)return@runOnUiThread
                if(error!=null){NativeUi.show(AlertDialog.Builder(activity).setTitle("检查更新失败").setMessage(error).setPositiveButton("重试"){_,_->check()}.setNegativeButton("关闭",null));return@runOnUiThread}
                val candidate=release
                if(candidate==null){NativeUi.show(AlertDialog.Builder(activity).setTitle("检查更新").setMessage("尚未发布适用于 One 的正式安装包").setPositiveButton("关闭",null));return@runOnUiThread}
                if(!GithubUpdates.newer(candidate.version,BuildConfig.VERSION_NAME)){NativeUi.show(AlertDialog.Builder(activity).setTitle("已是最新版本").setMessage("One ${BuildConfig.VERSION_NAME}").setPositiveButton("关闭",null));return@runOnUiThread}
                val mb=String.format(java.util.Locale.ROOT,"%.1f",candidate.size/1048576.0)
                NativeUi.show(AlertDialog.Builder(activity).setTitle("发现新版本 ${candidate.version}").setMessage("当前 ${BuildConfig.VERSION_NAME} · 下载 $mb MB"+(if(candidate.notes.isBlank())""else "\n\n"+candidate.notes))
                    .setPositiveButton("下载更新"){_,_->download(candidate)}.setNegativeButton("稍后",null))
            }
        },"github-update-check").start()
    }
    private fun download(release:GithubUpdates.Release){
        if(request!=null||!available())return
        val work=GithubUpdates.Request();request=work
        val row=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
        val label=TextView(activity).apply{text="下载中 · 0%";gravity=Gravity.CENTER;NativeUi.textSize(this,26f);setTextColor(GlassUi.ink(activity))}
        val bar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
        row.addView(label);row.addView(bar,LinearLayout.LayoutParams(-1,(12*NativeUi.scale(activity)).toInt().coerceAtLeast(4)))
        val waiting=NativeUi.show(AlertDialog.Builder(activity).setTitle("更新 One").setView(row).setNegativeButton("取消"){_,_->work.cancel()});dialog=waiting;waiting.setOnCancelListener{work.cancel();if(request===work)request=null}
        Thread({
            var file:java.io.File?=null
            val error=try{file=work.download(activity.applicationContext,release){percent->activity.runOnUiThread{if(waiting.isShowing){bar.progress=percent;label.text="下载中 · $percent%"}}};null}catch(e:Exception){reason(e)}
            activity.runOnUiThread{
                if(request!==work)return@runOnUiThread
                val cancelled=work.cancelled();request=null;waiting.dismiss()
                if(!available()||cancelled)return@runOnUiThread
                val downloaded=file
                if(error==null&&downloaded!=null)installer.request(downloaded)
                else NativeUi.show(AlertDialog.Builder(activity).setTitle("更新未完成").setMessage(error?:"更新下载失败").setPositiveButton("重试"){_,_->download(release)}.setNegativeButton("关闭",null))
            }
        },"github-update-download").start()
    }
}
