package com.tvbridge.app

import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

class FileExporter(private val activity:Activity){
    private var pending:File?=null
    fun request(file:File){
        if(Build.VERSION.SDK_INT in 23..28&&activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            pending=file
            activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),REQUEST_STORAGE)
        }else write(file)
    }
    fun onPermissionResult(request:Int,results:IntArray):Boolean{
        if(request!=REQUEST_STORAGE)return false
        val file=pending;pending=null
        if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED&&file!=null)write(file)
        else NativeUi.notice(activity,"未授权保存文件")
        return true
    }
    private fun write(file:File){
        Thread({
            val message=try{
                check(file.isFile)
                if(Build.VERSION.SDK_INT>=29){
                    val resolver=activity.contentResolver
                    val values=ContentValues().apply{
                        put(MediaStore.MediaColumns.DISPLAY_NAME,file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE,if(file.extension.equals("apk",true))"application/vnd.android.package-archive" else "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/One")
                        put(MediaStore.MediaColumns.IS_PENDING,1)
                    }
                    val uri=checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                    try{
                        file.inputStream().use{input->checkNotNull(resolver.openOutputStream(uri)).use{output->input.copyTo(output)}}
                        resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                    }catch(e:Exception){resolver.delete(uri,null,null);throw e}
                }else{
                    @Suppress("DEPRECATION") val directory=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"One")
                    check(directory.isDirectory||directory.mkdirs())
                    var target=File(directory,file.name);var suffix=1
                    while(target.exists()){target=File(directory,"${file.nameWithoutExtension} (${suffix++}).${file.extension}")}
                    try{file.inputStream().use{input->target.outputStream().use{input.copyTo(it)}}}catch(e:Exception){target.delete();throw e}
                }
                "已保存到 Download/One"
            }catch(_:Exception){"保存失败，请检查存储权限和剩余空间"}
            activity.runOnUiThread{NativeUi.notice(activity,message)}
        },"file-export").start()
    }
    companion object{const val REQUEST_STORAGE=12}
}
