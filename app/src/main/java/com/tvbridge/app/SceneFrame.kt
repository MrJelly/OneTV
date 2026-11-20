package com.tvbridge.app

internal object SceneFrame {
    fun hasContent(pixels:IntArray)=pixels.any{(it ushr 24)>0&&(it and 0x00ffffff)!=0}
}
