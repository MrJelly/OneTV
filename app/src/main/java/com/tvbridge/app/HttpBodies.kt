package com.tvbridge.app

import java.io.EOFException
import java.io.InputStream

internal object HttpBodies {
    fun readText(input:InputStream,count:Int):String {
        require(count>=0)
        var remaining=count
        val bounded=object:InputStream(){
            override fun read():Int{
                if(remaining==0)return -1
                val value=input.read();if(value<0)throw EOFException()
                remaining--;return value
            }
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int{
                if(length==0)return 0
                if(remaining==0)return -1
                val n=input.read(bytes,offset,minOf(length,remaining));if(n<0)throw EOFException()
                remaining-=n;return n
            }
        }
        val reader=bounded.reader(Charsets.UTF_8)
        val chars=CharArray(8192)
        val text=StringBuilder(minOf(count,64*1024))
        while(true){val n=reader.read(chars);if(n<0)break;text.append(chars,0,n)}
        return text.toString()
    }
}
