package com.tvbridge.app

import java.io.*
import java.net.*
import java.security.*
import java.util.*
import java.util.concurrent.*


class LanServer(base:File,html:InputStream,private val onFatal: ((LanServer) -> Unit)? = null,private val commands:Commands):Closeable {
    fun interface TextSource { fun get():String }
    fun interface Commands { @Throws(Exception::class) fun run(command:String,value:String):Boolean }
    private val directory=File(base,"received").apply{check(isDirectory||mkdirs()){"无法创建接收目录"}}
    private val page=html.use{it.readBytes()}
    private val random=SecureRandom()
    private val sessions=ConcurrentHashMap<String,Boolean>()
    private val sessionFile=File(base,"paired-sessions")
    private val pool=ThreadPoolExecutor(8,8,0,TimeUnit.SECONDS,ArrayBlockingQueue(32))
    private val connections=java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket,Boolean>())
    private val uploads=Semaphore(1)
    private var listener:ServerSocket?=null
    @Volatile private var boundPort=0
    fun port()=boundPort
    @Volatile private var closed=false
    private var code=""
    private var scan=""
    private var codeExpires=0L
    private var failures=0
    private var blockedUntil=0L
    @Volatile private var lastAction="等待手机连接"
    private var capabilities=TextSource{"{}"}
    private var liveData:(String)->String={"{}"}
    private var liveMessage=TextSource{"直播源操作失败"}
    private var playbackData=TextSource{"{}"}
    private var textMessage=TextSource{"请先选中电视输入框，并连接系统遥控"}
    init{
        directory.listFiles{_,name->name.endsWith(".part")}?.forEach{it.delete()}
        try{cleanupReceived()}catch(_:Throwable){}
        try{if(sessionFile.isFile&&sessionFile.length()<=16384)sessionFile.readLines().filter{it.matches(Regex("[0-9a-f]{64}"))}.take(32).forEach{sessions[it]=true}}catch(_:IOException){}
        refreshInvite()
    }
    private fun cleanupReceived(){
        val now=System.currentTimeMillis()
        val files=directory.listFiles{f->f.isFile&&!f.name.endsWith(".part")}.orEmpty().sortedBy{it.lastModified()}
        var total=files.sumOf{it.length()}
        var count=files.size
        for(file in files){
            if(total<=2L*1024*1024*1024&&count<=200)break
            if(now-file.lastModified()<7L*24*60*60*1000)continue
            val size=file.length()
            if(file.delete()){total-=size;count--;android.util.Log.i("LanServer","清理 received：已删 ${file.name}（${size} 字节）")}
        }
    }
    private fun saveSessions(){
        val temp=File(sessionFile.parentFile,"paired-sessions.tmp")
        FileOutputStream(temp).use{it.write(sessions.entries.joinToString("\n"){entry->entry.key}.toByteArray(Charsets.US_ASCII));it.fd.sync()}
        if(!temp.renameTo(sessionFile)){temp.copyTo(sessionFile,true);temp.delete()}
    }
    @Synchronized fun pin():String {if(System.currentTimeMillis()>codeExpires)newPin();return code}
    @Synchronized fun scanSecret():String {pin();return scan}
    private fun newPin(){code=String.format(Locale.US,"%06d",random.nextInt(1000000));scan=randomToken();codeExpires=System.currentTimeMillis()+600000}
    @Synchronized fun refreshInvite(){newPin();failures=0;blockedUntil=0}
    @Synchronized fun resetPairing(){sessions.clear();saveSessions();refreshInvite()}
    fun sessionCount():Int=sessions.size
    fun lastAction()=lastAction
    fun capabilities(value:TextSource){capabilities=value}
    fun liveData(value:(String)->String){liveData=value}
    fun liveMessage(value:TextSource){liveMessage=value}
    fun playbackData(value:TextSource){playbackData=value}
    fun textMessage(value:TextSource){textMessage=value}
    @Synchronized fun pair(candidate:String,existing:String=""):String? {
        val now=System.currentTimeMillis();if(now<blockedUntil||now>codeExpires)return null
        val bytes=candidate.toByteArray()
        if(!MessageDigest.isEqual(code.toByteArray(),bytes)&&!MessageDigest.isEqual(scan.toByteArray(),bytes)){
            if(++failures>=5){blockedUntil=now+60000;failures=0};return null
        }
        if(sessions.containsKey(existing)){failures=0;lastAction="手机已连接";return existing}
        if(sessions.size>=32)return null
        val token=randomToken();sessions[token]=true;saveSessions();failures=0;lastAction="手机已连接";return token
    }
    private fun randomToken()=ByteArray(32).also{random.nextBytes(it)}.joinToString(""){"%02x".format(it)}
    @Throws(IOException::class) fun start(){
        var lastError:IOException?=null
        for(candidate in 8888..8999){
            try{
                listener=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(candidate))};boundPort=candidate;lastError=null;break
            }catch(e:IOException){lastError=e;try{listener?.close()}catch(_:IOException){};listener=null}
        }
        if(lastError!=null)throw lastError
        Thread({
            var failures=0
            while(!closed){
                try{
                    val server=listener?:throw IllegalStateException("LanServer listener is gone")
                    val socket=server.accept().apply{soTimeout=30000};connections.add(socket)
                    failures=0
                    try{pool.execute{handle(socket)}}catch(_:RejectedExecutionException){
                        try{reply(socket.getOutputStream(),503,"服务器繁忙，请稍后再试")}catch(_:Throwable){}
                        connections.remove(socket)
                        try{socket.close()}catch(_:Throwable){}
                    }
                }catch(t:Throwable){
                    if(closed)return@Thread
                    failures++
                    android.util.Log.w("LanServer","accept failed ($failures): ${t.javaClass.simpleName} ${t.message}")
                    if(failures>=10||listener==null||listener?.isClosed==true){try{close()}catch(_:Throwable){};onFatal?.invoke(this);return@Thread}
                    try{Thread.sleep(200)}catch(_:InterruptedException){return@Thread}
                }
            }
        },"tvbridge-http").apply{isDaemon=true;start()}
    }
    private fun line(input:InputStream):String {
        val out=ByteArrayOutputStream()
        while(true){val c=input.read();if(c<0)throw EOFException();if(c==10)return out.toString("US-ASCII").replace("\r","");if(out.size()>=8192)throw IOException("Header too long");out.write(c)}
    }
    private fun handle(socket:Socket){
        try{socket.use{s->
            try{
            val input=BufferedInputStream(s.getInputStream());val out=s.getOutputStream()
            val first=line(input).split(' ');if(first.size!=3){reply(out,400,"Bad request");return}
            val headers=HashMap<String,String>();var total=0
            while(true){val value=line(input);total+=value.length;if(total>16384){reply(out,431,"Headers too large");return};if(value.isEmpty())break;val colon=value.indexOf(':');if(colon<=0){reply(out,400,"Bad header");return};if(headers.put(value.substring(0,colon).lowercase(Locale.ROOT),value.substring(colon+1).trim())!=null){reply(out,400,"Duplicate header");return}}
            if(headers.containsKey("transfer-encoding")){reply(out,411,"Content-Length required");return}
            val method=first[0];val path=first[1]
            val pagePath=path.substringBefore('?').substringBefore('#')
            if(method=="GET"&&pagePath in setOf("/","/index.html","/remote","/remote.html")){response(out,200,"text/html; charset=utf-8",page);return}
            if(method!="POST"){reply(out,405,"请通过手机控制页操作，或重新扫描电视二维码。");return}
            fun reject(code:Int,message:String){
                val bytes=headers["content-length"]?.toLongOrNull()?:0
                if(bytes in 1..8192)read(input,bytes.toInt())
                reply(out,code,message)
            }
            val origin=headers["origin"];if(origin!=null&&origin!="http://${headers["host"]}"){reject(403,"Origin rejected");return}
            if(headers["sec-fetch-site"]?.let{it!="same-origin"}==true){reject(403,"Same origin required");return}
            val length=headers["content-length"]?.toLongOrNull()?:-1
            if(length<0){reply(out,411,"Content-Length required");return}
            if(path=="/pair"){
                if(length>64){reply(out,413,"Too large");return};val token=pair(read(input,length.toInt()),headers["x-tv-token"].orEmpty())
                reply(out,if(token==null)403 else 200,token?:"配对失败：请刷新电视二维码后重扫，连续失败请等待一分钟。");return
            }
            if(!sessions.containsKey(headers["x-tv-token"].orEmpty())){reject(401,"请重新配对");return}
            when(path){
                "/status"->{response(out,200,"application/json; charset=utf-8",capabilities.get().toByteArray());return}
                "/live"->{
                    if(length>32){reject(413,"直播版本无效");return}
                    response(out,200,"application/json; charset=utf-8",liveData(read(input,length.toInt())).toByteArray())
                    return
                }
                "/playback"->{response(out,200,"application/json; charset=utf-8",playbackData.get().toByteArray());return}
                "/files"->{val files=directory.listFiles{f->!f.name.endsWith(".part")}.orEmpty().sortedByDescending{it.lastModified()}.take(100);val json=files.joinToString(",","[","]"){"{\"name\":${quote(it.name)},\"size\":${it.length()}}"};response(out,200,"application/json; charset=utf-8",json.toByteArray());return}
                "/files/delete"->{
                    if(length !in 1..1024){reject(400,"无效文件名");return}
                    val name=read(input,length.toInt())
                    if(name.any{it=='\\'||it.isISOControl()}){reply(out,400,"无效文件名");return}
                    val file=File(directory,name)
                    val canonical=file.canonicalFile
                    if(file.name!=name||name.endsWith(".part")||canonical.parentFile!=directory.canonicalFile||canonical.name!=name){
                        reply(out,400,"无效文件名");return
                    }
                    if(!file.isFile){reply(out,404,"文件已不存在");return}
                    val deleted=file.delete()
                    reply(out,if(deleted)200 else 409,if(deleted)"已删除文件" else "无法删除文件")
                    return
                }
                "/live/import","/live/subscribe"->{if(length>if(path.endsWith("import"))20*1024*1024 else 8192){reply(out,413,"节目单超过大小限制");return};val ok=commands.run(if(path.endsWith("import"))"liveImport" else "liveSubscribe",read(input,length.toInt()));reply(out,if(ok)200 else 409,liveMessage.get());return}
                "/command"->{if(length>8192){reply(out,413,"输入过长");return};val body=read(input,length.toInt());val split=body.indexOf('\n');val cmd=if(split<0)body else body.substring(0,split);val value=if(split<0)"" else body.substring(split+1);val ok=commands.run(cmd,value);reply(out,if(ok)200 else 409,if(cmd=="inputText")textMessage.get()else if(ok)"已执行" else "操作未完成，请检查电视连接。");return}
            }
            if(path.startsWith("/upload?name=")){upload(input,out,path,length);return}
            reply(out,404,"Not found")
            }catch(t:Throwable){android.util.Log.w("LanServer","handle failed: ${t.javaClass.simpleName} ${t.message}");try{reply(s.getOutputStream(),500,"服务器内部错误")}catch(_:Throwable){}}
        }}catch(_:Exception){ }finally{connections.remove(socket)}
    }
    private fun upload(input:InputStream,out:OutputStream,path:String,length:Long){
        if(length>1073741824){reply(out,413,"单个文件最大 1 GB");return}
        if(!uploads.tryAcquire()){reply(out,409,"另一文件正在上传");return}
        var partial:File?=null
        try{
            if(length+67108864>directory.usableSpace){reply(out,507,"电视存储空间不足");return}
            val name=URLDecoder.decode(path.substringAfter("/upload?name="),"UTF-8").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").takeLast(120)
            if(name.isBlank()||name=="."||name==".."){reply(out,400,"无效文件名");return}
            val id=UUID.randomUUID().toString();partial=File(directory,"$id.part")
            partial.outputStream().buffered().use{file->val buffer=ByteArray(65536);var remaining=length;while(remaining>0){val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(n<0)throw EOFException();file.write(buffer,0,n);remaining-=n}}
            val target=File(directory,id.take(8)+"_"+name);check(partial.renameTo(target)){"保存失败"};try{cleanupReceived()}catch(_:Throwable){};lastAction="已接收：$name";reply(out,200,"已保存：${target.name}")
        }finally{partial?.takeIf{it.exists()}?.delete();uploads.release()}
    }
    private fun read(input:InputStream,count:Int):String {val bytes=ByteArray(count);var pos=0;while(pos<count){val n=input.read(bytes,pos,count-pos);if(n<0)throw EOFException();pos+=n};return bytes.toString(Charsets.UTF_8)}
    private fun quote(value:String)=buildString{append('"');for(c in value){if(c=='"'||c=='\\')append('\\');if(c<' ')append(String.format(Locale.US,"\\u%04x",c.code))else append(c)};append('"')}
    private fun reply(out:OutputStream,status:Int,text:String)=response(out,status,"text/plain; charset=utf-8",text.toByteArray())
    private fun response(out:OutputStream,status:Int,type:String,bytes:ByteArray){val headers="HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n";out.write(headers.toByteArray(Charsets.US_ASCII));out.write(bytes);out.flush()}
    override fun close(){closed=true;try{listener?.close()}catch(_:IOException){};connections.forEach{try{it.close()}catch(_:IOException){}};pool.shutdownNow()}
}
