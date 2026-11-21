
package com.tvbridge.app

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

internal class ProgressiveMediaBuffer(private val limit: Int) {
    private val chunks = ArrayList<ByteArray>()
    private var size = 0
    private var expectedLen = -1
    private var complete = false
    private var completedBody: ByteArray? = null
    private var failure: IOException? = null

    @Synchronized
    @Throws(IOException::class)
    fun append(ownedBytes: ByteArray) {
        failure?.let { throw it }
        if (complete) throw IOException("Media already complete")
        if (ownedBytes.size > limit - size) throw IOException("Progressive media exceeds limit")
        if (expectedLen >= 0 && ownedBytes.size > expectedLen - size)
            throw IOException("Progressive media exceeds declared length")
        if (ownedBytes.isEmpty()) return
        chunks.add(ownedBytes)
        size += ownedBytes.size
        (this as Object).notifyAll()
    }

    @Synchronized
    @Throws(IOException::class)
    fun finish(): ByteArray {
        failure?.let { throw it }
        completedBody?.let { return it }
        if (expectedLen >= 0 && size != expectedLen)
            throw IOException("Incomplete progressive media $size/$expectedLen")
        val body = if (chunks.size == 1) chunks[0] else ByteArray(size)
        if (chunks.size != 1) {
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, body, offset, chunk.size)
                offset += chunk.size
            }
        }
        completedBody = body
        chunks.clear()
        complete = true
        (this as Object).notifyAll()
        return body
    }

    @Synchronized
    fun fail(error: IOException) {
        if (complete || failure != null) return
        failure = error
        (this as Object).notifyAll()
    }

    fun open(): InputStream {
        return object : InputStream() {
            private var chunkIndex = 0
            private var offset = 0
            private var position = 0
            private var closed = false

            @Throws(IOException::class)
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }

            @Throws(IOException::class)
            override fun read(target: ByteArray, start: Int, count: Int): Int {
                if (start < 0 || count < 0 || count > target.size - start)
                    throw IndexOutOfBoundsException()
                if (count == 0) return 0
                synchronized(this@ProgressiveMediaBuffer) {
                    while (true) {
                        if (closed) throw IOException("Media reader closed")
                        failure?.let { throw it }
                        val done = completedBody
                        if (done != null) {
                            if (position == size) return -1
                            val n = minOf(count, size - position)
                            System.arraycopy(done, position, target, start, n)
                            position += n
                            return n
                        }
                        if (chunkIndex < chunks.size) {
                            val chunk = chunks[chunkIndex]
                            val n = minOf(count, chunk.size - offset)
                            System.arraycopy(chunk, offset, target, start, n)
                            offset += n
                            position += n
                            if (offset == chunk.size) {
                                chunkIndex++
                                offset = 0
                            }
                            return n
                        }
                        if (complete) return -1
                        try {
                            (this@ProgressiveMediaBuffer as Object).wait()
                        } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException("Media reader cancelled")
                        }
                    }
                }
            }

            override fun close() {
                synchronized(this@ProgressiveMediaBuffer) {
                    closed = true
                    (this@ProgressiveMediaBuffer as Object).notifyAll()
                }
            }
        }
    }
}
