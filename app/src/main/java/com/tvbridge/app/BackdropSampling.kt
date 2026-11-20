package com.tvbridge.app

internal object BackdropSampling {
    fun forCover(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2 &&
            sourceWidth / (sample * 2) >= width && sourceHeight / (sample * 2) >= height) {
            sample *= 2
        }
        return sample
    }
}
