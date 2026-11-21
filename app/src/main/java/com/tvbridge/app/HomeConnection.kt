package com.tvbridge.app

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter

class HomeConnection(private val button:View,private val invalidate:()->Unit){
    private var qr:Bitmap?=null
    private var payload=""
    private var address=""
    private var code=""
    private var connected=0
    private var notice=""
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    fun update(base:String,pin:String,invite:String,count:Int,message:String):Boolean{
        val connectionChanged=address!=base||code!=pin||payload!=invite||connected!=count
        address=base;code=pin;connected=count;button.contentDescription="控制中心"+(if(count>0)"，手机已连接" else "");notice=message
        if(payload!=invite){
            payload=invite;qr=null
            if(invite.isNotEmpty())AppThreads.onIo{
                val image=try{
                    val matrix=QRCodeWriter().encode(invite,BarcodeFormat.QR_CODE,480,480,mapOf(EncodeHintType.MARGIN to 2))
                    val pixels=IntArray(480*480){i->if(matrix[i%480,i/480])0xff191727.toInt()else Color.WHITE}
                    Bitmap.createBitmap(pixels,480,480,Bitmap.Config.ARGB_8888)
                }catch(_:WriterException){null}
                mainHandler.post{
                    if(payload==invite){qr=image;invalidate()}
                }
            }
        }
        return connectionChanged
    }
    fun qrBitmap()=qr
    fun count()=connected
    fun lineText()=listOf(address.ifEmpty{"请先联网"},if(code.isNotEmpty())"配对码  $code" else "").filter{it.isNotEmpty()}.joinToString("    ")
}
