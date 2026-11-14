package com.tvbridge.app
import android.view.View

interface PagePlayback {
    val view:View
    val quality:String
    fun open(address:String,forceReload:Boolean=false)
    fun setQuality(value:String)
    fun toggle()
    fun pauseVideo()
    fun suspend()
    fun resume()
    fun stop()
    fun release()
}
