package com.tvbridge.app

import android.content.Context
import android.os.Build
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object NativeDecoders {
    fun available():Boolean=Build.VERSION.SDK_INT>=21&&Modern.available()
    fun renderers(context:Context,softwareTracks:Int):DefaultRenderersFactory =
        if(softwareTracks!=0&&available())Modern.renderers(context,softwareTracks) else DefaultRenderersFactory(context)

    @android.annotation.TargetApi(21)
    private object Modern {
        fun available():Boolean=try {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)==4096L&&FfmpegLibrary.isAvailable()
        }catch(_:LinkageError){false}catch(_:Exception){false}
        fun renderers(context:Context,softwareTracks:Int)=NextRenderersFactory(context).apply{
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setMediaCodecSelector { mime,secure,tunneling ->
                val selected=if(mime.startsWith("audio/"))1 else if(mime.startsWith("video/"))2 else 0
                if((softwareTracks and selected)!=0&&FfmpegLibrary.supportsFormat(mime))emptyList()
                else MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneling)
            }
        }
    }
}
