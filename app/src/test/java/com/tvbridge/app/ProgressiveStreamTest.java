// SPDX-License-Identifier: GPL-3.0-only

package com.tvbridge.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;

public class ProgressiveStreamTest {
    private byte[] packet(int pid, boolean start, int cc) {
        byte[] b=new byte[188];Arrays.fill(b,(byte)0x55);
        b[0]=0x47;b[1]=(byte)((pid>>8)|(start?64:0));b[2]=(byte)pid;b[3]=(byte)(0x10|cc);return b;
    }
    private byte[] join(byte[]...parts)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] b:parts)out.write(b);return out.toByteArray();
    }
    private byte[] read(InputStream input)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[137];int n;
        while((n=input.read(b))>=0)out.write(b,0,n);return out.toByteArray();
    }
    @Test public void completeVideoPesArrivesWhileTheDownloadIsStillOpen()throws Exception{
        CmgBytePipe pipe=new CmgBytePipe(1024);OutputStream writer=pipe.output();
        ProgressiveMediaBuffer media=new ProgressiveMediaBuffer(8192);
        CountDownLatch video=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        byte[] prefix=join(packet(0,true,0),packet(100,true,0),packet(256,true,3),packet(256,false,4),packet(256,true,5));
        Future<?> decoding=worker.submit(()->{
            try{
                new CmgTsStream(new CmgTsStream.Decoder(){
                    public int videoPid(byte[] b){return b.length>=376?256:-1;}
                    public byte[] decode(byte[] b,int pid){return b;}
                },8192).copy(pipe,b->{media.append(b);if(b.length>=376&&(((b[1]&31)<<8)|(b[2]&255))==256)video.countDown();});
                media.finish();
            }catch(IOException e){media.fail(e);throw new RuntimeException(e);}
        });
        try{
            writer.write(prefix);assertTrue(video.await(3,TimeUnit.SECONDS));assertFalse(decoding.isDone());
            writer.write(packet(256,false,6));writer.close();decoding.get(3,TimeUnit.SECONDS);
            assertArrayEquals(join(prefix,packet(256,false,6)),read(media.open()));
        }finally{pipe.close();writer.close();decoding.cancel(true);worker.shutdownNow();}
    }
    @Test public void consolidatingFinishedMediaDoesNotReplayBytesToExistingReaders()throws Exception{
        ProgressiveMediaBuffer media=new ProgressiveMediaBuffer(1024);
        byte[] a=packet(100,true,0),b=packet(256,true,1);media.append(a);
        InputStream reader=media.open();assertEquals(a[0]&255,reader.read());
        media.append(b);byte[] whole=media.finish();
        assertArrayEquals(Arrays.copyOfRange(whole,1,whole.length),read(reader));
        assertArrayEquals(whole,read(media.open()));
    }
}
