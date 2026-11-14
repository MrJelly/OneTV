package com.tvbridge.app

import java.io.*
import java.net.Socket
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.*

class LanServerTest {
    private var port=0
    @Test fun pairedPhoneSurvivesServiceRestartAndExplicitRevocationPersists(){
        val base=Files.createTempDirectory("bridge-session-test").toFile()
        try{
            val token=LanServer(base,"test".byteInputStream()){_,_->true}.use{it.pair(it.pin())!!}
            LanServer(base,"test".byteInputStream()){_,_->true}.use{server->
                server.start();port=server.port();assertEquals(1,server.sessionCount())
                assertTrue(request("/status",token=token).startsWith("HTTP/1.1 200"))
                server.refreshInvite();assertTrue(request("/status",token=token).startsWith("HTTP/1.1 200"))
                assertEquals(token,request("/pair",server.pin(),token).substringAfter("\r\n\r\n"))
                assertEquals(1,server.sessionCount())
                server.resetPairing()
            }
            LanServer(base,"test".byteInputStream()){_,_->true}.use{server->
                server.start();port=server.port();assertEquals(0,server.sessionCount())
                assertTrue(request("/status",token=token).startsWith("HTTP/1.1 401"))
            }
        }finally{base.walkBottomUp().forEach{it.delete()}}
    }
    private fun request(path:String,body:String="",token:String?=null,origin:String?=null):String {
        Socket("127.0.0.1",port).use{socket->socket.soTimeout=5000;val bytes=body.toByteArray();val head="POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: ${bytes.size}\r\n"+(token?.let{"X-TV-Token: $it\r\n"}?:"")+(origin?.let{"Origin: $it\r\n"}?:"")+"\r\n";socket.getOutputStream().apply{write(head.toByteArray());write(bytes);flush()};val input=socket.getInputStream();val buffer=ByteArrayOutputStream();while(!buffer.toString("US-ASCII").endsWith("\r\n\r\n")){val c=input.read();check(c>=0);buffer.write(c)};val header=buffer.toString("US-ASCII");val length=header.lineSequence().first{it.startsWith("Content-Length: ")}.substringAfter(": ").trim().toInt();val content=ByteArray(length);DataInputStream(input).readFully(content);return header+content.toString(Charsets.UTF_8)}
    }
    @Test fun pairUploadAndRevokeAcrossKotlinService(){
        val base=Files.createTempDirectory("bridge-kotlin-test").toFile()
        try{LanServer(base,"test".byteInputStream()){cmd,_->cmd=="up"}.use{server->server.playbackData{"{\"phase\":\"segment\",\"http\":403}"};server.start();port=server.port()
            assertTrue(request("/command","up").startsWith("HTTP/1.1 401"))
            assertTrue(request("/playback").startsWith("HTTP/1.1 401"))
            assertTrue(request("/pair",server.pin(),origin="http://evil.example").startsWith("HTTP/1.1 403"))
            assertTrue(request("/pair","wrong").startsWith("HTTP/1.1 403"))
            val token=request("/pair",server.pin()).substringAfter("\r\n\r\n");assertEquals(64,token.length)
            assertTrue(request("/playback",token=token).contains("\"http\":403"))
            val old=server.scanSecret();assertTrue(request("/pair",old).startsWith("HTTP/1.1 200"));server.refreshInvite();assertTrue(request("/pair",old).startsWith("HTTP/1.1 403"));assertTrue(request("/status",token=token).startsWith("HTTP/1.1 200"))
            assertTrue(request("/command","up",token).startsWith("HTTP/1.1 200"));assertTrue(request("/command","unknown",token).startsWith("HTTP/1.1 409"))
            assertTrue(request("/upload?name=..%2Ftest.txt","中文 binary",token).startsWith("HTTP/1.1 200"));val file=base.resolve("received").listFiles()!!.single();assertEquals("中文 binary",file.readText());assertTrue(request("/files",token=token).contains("test.txt"))
            assertTrue(request("/files/delete",file.name).startsWith("HTTP/1.1 401"))
            assertTrue(request("/files/delete","../paired-sessions",token).startsWith("HTTP/1.1 400"))
            assertTrue(request("/files/delete",file.name,token).startsWith("HTTP/1.1 200"))
            assertFalse(file.exists())
            Socket("127.0.0.1",port).use{it.getOutputStream().write("POST /upload?name=broken HTTP/1.1\r\nHost: localhost\r\nContent-Length: 99999\r\nX-TV-Token: $token\r\n\r\nx".toByteArray())};Thread.sleep(150);assertTrue(base.resolve("received").listFiles()!!.none{it.name.endsWith(".part")})
            server.resetPairing();assertTrue(request("/command","up",token).startsWith("HTTP/1.1 401"));repeat(5){request("/pair","wrong")};assertTrue(request("/pair",server.pin()).startsWith("HTTP/1.1 403"))
        }}finally{base.walkBottomUp().forEach{it.delete()}}
    }
}
