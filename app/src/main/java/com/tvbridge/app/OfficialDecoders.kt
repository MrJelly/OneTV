package com.tvbridge.app

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object OfficialDecoders {
    private val failed=java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String,Boolean>())
    fun available()=false
    fun noteFailure(error:Throwable){
        val codec=generateSequence(error){it.cause}.filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo?:return
        if(codec.mimeType.startsWith("video/")){failed.add(codec.name);android.util.Log.w("OneDecoder","Retiring failed decoder ${codec.name}")}
    }
    fun renderers(context:Context,softwareTracks:Int=0)=object:DefaultRenderersFactory(context){
        override fun buildVideoRenderers(context:Context,extensionRendererMode:Int,selector:MediaCodecSelector,enableFallback:Boolean,
            handler:Handler,listener:VideoRendererEventListener,joiningMs:Long,out:ArrayList<Renderer>){
            out.add(object:MediaCodecVideoRenderer(context,codecAdapterFactory,selector,joiningMs,enableFallback,handler,listener,50){
                override fun getDecoderInfos(selector:MediaCodecSelector,format:Format,secure:Boolean):List<MediaCodecInfo>{
                    val normal=super.getDecoderInfos(selector,format,secure)
                    val usable=normal.filter{it.name !in failed}.ifEmpty{normal}
                    val profile=MediaCodecUtil.getCodecProfileAndLevel(format)?.first
                    if(Build.VERSION.SDK_INT>=21&&!secure&&format.sampleMimeType=="video/avc"&&profile==8&&format.width>0&&format.height>0){
                        val vendor=usable.firstOrNull{it.name=="OMX.MTK.VIDEO.DECODER.AVC"&&it.hardwareAccelerated&&
                            it.isVideoSizeAndRateSupportedV21(format.width,format.height,format.frameRate.toDouble())}
                        if(vendor!=null){android.util.Log.i("OneDecoder","Trying vendor AVC: ${vendor.name}");return listOf(vendor)+usable.filter{it!==vendor}}
                    }
                    return usable
                }
            })
        }
    }
}
