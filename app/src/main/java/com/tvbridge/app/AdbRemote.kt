package com.tvbridge.app

import android.content.Context
import android.content.ComponentName
import android.os.SystemClock
import java.io.*
import java.net.*
import java.nio.*
import java.security.*
import java.security.interfaces.*
import java.security.spec.*
import java.math.BigInteger
import android.util.Base64
import javax.crypto.Cipher

class AdbRemote(context:Context){
    private val appContext=context.applicationContext
    private val prefs=context.getSharedPreferences("remote-adb",Context.MODE_PRIVATE)
    private val keyDir=File(context.filesDir,"adb-key").apply{mkdirs()}
    @Volatile var ready=false;private set
    @Volatile var message="未连接。先开启投影仪 ADB 调试，再确认屏幕上的授权请求。";private set
    private val keyPair:KeyPair by lazy {
        val privateFile=File(keyDir,"private.der");val publicFile=File(keyDir,"public.der")
        if(privateFile.exists()&&publicFile.exists()){
            try{
                KeyPair(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicFile.readBytes())),KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())))
            }catch(_:Throwable){
                try{privateFile.delete()}catch(_:Throwable){}
                try{publicFile.delete()}catch(_:Throwable){}
                KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair().also{privateFile.writeBytes(it.private.encoded);publicFile.writeBytes(it.public.encoded)}
            }
        }
        else KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair().also{privateFile.writeBytes(it.private.encoded);publicFile.writeBytes(it.public.encoded)}
    }
    fun enabled()=prefs.getBoolean("enabled",false)
    fun port()=prefs.getInt("port",5555)
    fun disconnect(){prefs.edit().putBoolean("enabled",false).apply();ready=false;message="已关闭 ADB 系统遥控"}
    fun restore():Boolean {
        if(!enabled())return false
        if(ready)return true
        return try{check(shell("echo tvbridge-adb-ready",port(),false).contains("tvbridge-adb-ready"));ready=true;message="系统遥控已连接";true}catch(_:Exception){ready=false;message="系统遥控暂未连接，请检查电视调试授权";false}
    }
    fun connect(port:Int):Boolean {
        if(port !in 1024..65535){message="端口应为 1024–65535";return false}
        return try{val result=shell("echo tvbridge-adb-ready",port,true);check(result.contains("tvbridge-adb-ready")){"ADB 测试未通过"};prefs.edit().putInt("port",port).putBoolean("enabled",true).apply();ready=true;message="ADB 已授权，可跨应用遥控";true}catch(e:Exception){ready=false;message="连接未完成：${e.message?:"请检查调试端口和屏幕授权"}";false}
    }
    fun authorizeDesktop():Boolean {
        if(!enabled()||!ready)return false
        return try{
            val pkg=appContext.packageName;check(pkg=="com.tvbridge.app")
            val granted=shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",port(),false)
            check(!Regex("error|exception|denied|not allowed",RegexOption.IGNORE_CASE).containsMatchIn(granted))
            if(android.os.Build.VERSION.SDK_INT>=21)shell("appops set $pkg GET_USAGE_STATS allow",port(),false)
            message="桌面授权已配置";true
        }catch(_:Exception){message="授权未完成，请检查系统遥控连接";false}
    }
    fun setDefaultHome(component:ComponentName):Boolean {
        if(!ready||android.os.Build.VERSION.SDK_INT<21)return false
        val id=component.flattenToString()
        if(!Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$").matches(id))return false
        return try{
            val pkg=component.packageName
            val startup=appContext.getSharedPreferences("startup",Context.MODE_PRIVATE)
            val packagePattern=Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
            var vendorHome=false
            if(pkg==appContext.packageName&&DesktopPolicy.passKeys(appContext)){
                val before=shell("getprop persist.vt.use_home_pkg",port(),false).trim()
                if(packagePattern.matches(before)){
                    val hadBackup=startup.contains("desktop_vt_previous")
                    if(before!=pkg){
                        if(!startup.contains("desktop_vt_previous"))startup.edit().putString("desktop_vt_previous",before).apply()
                        shell("setprop persist.vt.use_home_pkg '$pkg'",port(),false)
                    }
                    vendorHome=shell("getprop persist.vt.use_home_pkg",port(),false).trim()==pkg
                    if(!vendorHome&&!hadBackup)startup.edit().remove("desktop_vt_previous").apply()
                }
            }else if(pkg!=appContext.packageName){
                val previous=startup.getString("desktop_vt_previous",null)
                if(previous!=null&&packagePattern.matches(previous)){
                    val current=shell("getprop persist.vt.use_home_pkg",port(),false).trim()
                    if(current==appContext.packageName)shell("setprop persist.vt.use_home_pkg '$previous'",port(),false)
                    if(shell("getprop persist.vt.use_home_pkg",port(),false).trim()!=appContext.packageName)startup.edit().remove("desktop_vt_previous").apply()
                }
            }
            val command=if(android.os.Build.VERSION.SDK_INT>=24)"cmd package"else "pm"
            if(android.os.Build.VERSION.SDK_INT>=29)runCatching{shell("cmd role add-role-holder --user 0 android.app.role.HOME '$pkg'",port(),false,timeout=2500)}
            shell("$command set-home-activity --user 0 '$id'",port(),false)
            val result=shell("$command resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",port(),false)
            vendorHome||result.lineSequence().mapNotNull{ComponentName.unflattenFromString(it.trim())}.lastOrNull()?.packageName==component.packageName
        }catch(_:Exception){false}
    }
    fun command(cmd:String):Boolean {
        if(!enabled())return false
        if(cmd=="liveOpen")return try{shell("am start -n com.tvbridge.app/.MainActivity --ez open_live true",port(),false);true}catch(_:Exception){ready=false;false}
        val key=when(cmd){"up"->19;"down"->20;"left"->21;"right"->22;"ok"->23;"back"->4;"home"->3;"recents"->187;"power"->26;"volumeUp"->24;"volumeDown"->25;"volumeMute"->164;else->return false}
        return try{val output=shell("input keyevent "+(if(cmd=="power")"--longpress " else "")+key,port(),false);check(!Regex("permission denied|exception|usage:|error:|unknown|not found",RegexOption.IGNORE_CASE).containsMatchIn(output)){"设备拒绝此输入命令"};ready=true;true}catch(e:Exception){ready=false;message="ADB 已断开，请重新连接：${e.message.orEmpty()}";false}
    }
    fun pointerTap(x:Int,y:Int)=pointerInput("input tap ${x.coerceIn(0,16384)} ${y.coerceIn(0,16384)}")
    fun sendText(text:String):Boolean {
        if(!RemoteText.valid(text))return false
        if(!enabled()||!ready){message="文字未发送，请先连接系统遥控";return false}
        return try{
            val component=ComponentName(appContext,RemoteInputService::class.java).flattenToShortString()
            val result=TextInputSession.send(text,component,{shell(it,port(),false)},{RemoteInputService.commit(it)})
            message=when{
                result.committed&&!result.restored->"文字已输入；恢复键盘失败，请在电视切换键盘"
                result.committed->"文字已输入到电视"
                else->"文字未发送，请先选中电视输入框"
            }
            result.committed
        }catch(_:Exception){message="文字输入失败，请检查系统遥控连接";false}
    }
    fun pointerScroll(points:IntArray):Boolean {
        if(points.size!=4)return false
        return pointerInput("input swipe "+points.joinToString(" "){it.coerceIn(0,16384).toString()}+" 160")
    }
    private fun pointerInput(command:String):Boolean {
        if(!enabled()||!ready)return false
        return try{val output=shell(command,port(),false);check(!output.contains("Exception",true)&&!output.contains("Permission denied",true)&&!output.contains("Error:",true));true}catch(e:Exception){ready=false;message="系统输入失败，请重新连接";false}
    }
    fun setHome(component:ComponentName):Boolean {
        val target=component.flattenToString();if(!target.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))||!enabled())return false
        return try{shell("cmd package set-home-activity "+target,port(),false);val result=shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",port(),false);result.lineSequence().mapNotNull{ComponentName.unflattenFromString(it.trim())}.any{it==component}}catch(e:Exception){message="桌面设置失败：${e.message.orEmpty()}";false}
    }
    fun installApk(file:File):Boolean {
        if(!enabled()||!ready)return false
        return try{
            check(file.isFile&&file.extension.equals("apk",true)&&file.canonicalFile.parentFile==File(appContext.filesDir,"received").canonicalFile)
            shell("pm install -r -S ${file.length()}",port(),false,120000,file).trim().endsWith("Success")
        }catch(_:Exception){false}
    }
    private fun shell(command:String,port:Int,authorize:Boolean,timeout:Int=5000,payload:File?=null):String {
        val budget=if(payload!=null)timeout else if(authorize)60000 else 30000
        val deadline=SystemClock.elapsedRealtime()+budget
        Socket().use{socket->
            socket.connect(InetSocketAddress("127.0.0.1",port),2500);socket.soTimeout=if(authorize)30000 else timeout
            val input=DataInputStream(BufferedInputStream(socket.getInputStream()));val output=socket.getOutputStream()
            fun readPacket():AdbWire.Packet{
                val remaining=deadline-SystemClock.elapsedRealtime()
                if(remaining<=0)throw SocketTimeoutException("ADB 命令总超时")
                socket.soTimeout=minOf(remaining,if(authorize)30000L else timeout.toLong()).coerceAtLeast(1000).toInt()
                return AdbWire.read(input)
            }
            AdbWire.write(output,"CNXN",0x01000000,4096,"host::\u0000".toByteArray())
            var sentSignature=false;var connected=false
            repeat(5){if(!connected){val packet=readPacket();when(packet.command){
                "CNXN"->connected=true
                "STLS"->error("这是配对码无线调试端口。本版使用传统 ADB TCP，请启用设备的 ADB 调试端口；不支持 TLS 配对码。")
                "AUTH"->{check(packet.arg0==1&&packet.data.size==20){"不支持的 ADB 授权格式"};if(!sentSignature){AdbWire.write(output,"AUTH",2,0,AdbWire.sign(keyPair.private,packet.data));sentSignature=true}else{check(authorize){"请在投影仪上重新确认 ADB 授权"};AdbWire.write(output,"AUTH",3,0,(AdbWire.publicKey(keyPair.public as RSAPublicKey)+" tvbridge@android\u0000").toByteArray())}}
                else->error("设备未返回 ADB 协议")
            }}}
            check(connected){"未获授权，请在投影仪确认后重试"}
            AdbWire.write(output,"OPEN",1,0,("shell:$command\u0000").toByteArray());val text=ByteArrayOutputStream();var remote=0
            val upload=payload?.inputStream()
            try{
                val buffer=ByteArray(4096)
                repeat(if(payload==null)64 else (payload.length()/4096+1024).coerceAtMost(300000).toInt()){
                    val packet=readPacket()
                    when(packet.command){
                        "OKAY"->{remote=packet.arg0;if(upload!=null){val count=upload.read(buffer);if(count>0)AdbWire.write(output,"WRTE",1,remote,buffer.copyOf(count))}}
                        "WRTE"->{remote=packet.arg0;check(text.size()+packet.data.size<=65536){"ADB 响应过大"};text.write(packet.data);AdbWire.write(output,"OKAY",1,remote)}
                        "CLSE"->{AdbWire.write(output,"CLSE",1,packet.arg0);return text.toString("UTF-8")}
                        else->error("ADB 命令连接中断")
                    }
                }
            }finally{upload?.close()}
            error("ADB 响应超出限制")
        }
    }
}

object AdbWire {
    data class Packet(val command:String,val arg0:Int,val arg1:Int,val data:ByteArray)
    private fun code(command:String)=ByteBuffer.wrap(command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    fun write(out:OutputStream,command:String,a:Int,b:Int,data:ByteArray=byteArrayOf()){
        val code=code(command);val checksum=data.sumOf{it.toInt() and 255};val header=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(code).putInt(a).putInt(b).putInt(data.size).putInt(checksum).putInt(code.inv()).array();out.write(header);out.write(data);out.flush()
    }
    fun read(input:DataInputStream):Packet {
        val header=ByteArray(24);input.readFully(header);val h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);val command=h.int;val a=h.int;val b=h.int;val length=h.int;val checksum=h.int;check(h.int==command.inv()&&length in 0..1048576){"无效 ADB 数据头"};val data=ByteArray(length);input.readFully(data);check(checksum==data.sumOf{it.toInt() and 255}){"ADB 数据校验失败"};val name=String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(),Charsets.US_ASCII);return Packet(name,a,b,data)
    }
    fun sign(key:PrivateKey,token:ByteArray):ByteArray {val prefix=byteArrayOf(0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14);return Cipher.getInstance("RSA/ECB/PKCS1Padding").run{init(Cipher.ENCRYPT_MODE,key);doFinal(prefix+token)}}
    fun publicKey(key:RSAPublicKey):String {
        require(key.modulus.bitLength()==2048)
        val n=key.modulus;val base=BigInteger.ONE.shiftLeft(32);val inv=base.subtract(n.modInverse(base)).toLong().toInt()
        fun little(value:BigInteger):ByteArray{val result=ByteArray(256);val bytes=value.toByteArray().reversedArray();System.arraycopy(bytes,0,result,0,minOf(bytes.size,256));return result}
        val binary=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).putInt(64).putInt(inv).put(little(n)).put(little(BigInteger.ONE.shiftLeft(4096).mod(n))).putInt(key.publicExponent.toInt()).array()
        return Base64.encodeToString(binary,Base64.NO_WRAP)
    }
}
