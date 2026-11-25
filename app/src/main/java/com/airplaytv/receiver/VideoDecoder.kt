package com.airplaytv.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * H.264 decoder for the mirrored stream. Frames arrive already paced by the
 * network, so they are rendered as soon as they are decoded (that is what all
 * AirPlay mirroring receivers do; the sender does not buffer).
 */
class VideoDecoder(
    private val onSizeChanged: (Int, Int) -> Unit,
) {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private val bufferInfo = MediaCodec.BufferInfo()

    fun setSurface(newSurface: Surface?) {
        synchronized(lock) {
            if (surface === newSurface) return
            surface = newSurface
            if (newSurface == null) {
                releaseCodecLocked()
            } else {
                configureLocked()
            }
        }
    }

    fun configure(newSps: ByteArray, newPps: ByteArray, width: Int, height: Int) {
        synchronized(lock) {
            val changed = sps?.contentEquals(newSps) != true || pps?.contentEquals(newPps) != true
            sps = newSps.copyOf()
            pps = newPps.copyOf()
            configuredWidth = width
            configuredHeight = height
            if (changed || codec == null) {
                configureLocked()
                onSizeChanged(width, height)
            }
        }
    }

    fun submit(data: ByteArray, ptsMicros: Long) {
        synchronized(lock) {
            val c = codec ?: return
            try {
                var attempts = 0
                while (true) {
                    val index = c.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = c.getInputBuffer(index) ?: return
                        buffer.clear()
                        buffer.put(data)
                        c.queueInputBuffer(index, 0, data.size, ptsMicros, 0)
                        break
                    }
                    if (++attempts > 40) {
                        dropped++
                        if (dropped % 60L == 1L) Log.w(TAG, "decoder is behind, dropped $dropped frames")
                        break
                    }
                }
                drainLocked(c)
            } catch (e: Throwable) {
                Log.e(TAG, "decode error", e)
                configureLocked()
            }
        }
    }

    private var rendered = 0L
    private var dropped = 0L

    private fun drainLocked(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index >= 0 -> {
                    if (rendered++ == 0L) Log.i(TAG, "first decoded frame rendered (${bufferInfo.size}b)")
                    c.releaseOutputBuffer(index, true)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    Log.i(TAG, "decoder output ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}")
                }
                else -> return
            }
        }
    }

    private fun configureLocked() {
        val target = surface ?: return
        val s = sps ?: return
        val p = pps ?: return
        releaseCodecLocked()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, configuredWidth.coerceAtLeast(1920), configuredHeight.coerceAtLeast(1080))
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(annexB(s)))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(annexB(p)))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            } catch (_: Throwable) {
            }
            try {
                // Screen mirroring wants the shortest possible pipeline; the
                // decoder still buffers one frame for reference.
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            } catch (_: Throwable) {
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, target, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "H.264 decoder started (${configuredWidth}x$configuredHeight)")
        } catch (e: Throwable) {
            Log.e(TAG, "cannot start H.264 decoder", e)
            codec = null
        }
    }

    private fun releaseCodecLocked() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (_: Throwable) {
        }
        try {
            c.release()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        synchronized(lock) {
            releaseCodecLocked()
            sps = null
            pps = null
            configuredWidth = 0
            configuredHeight = 0
        }
    }

    private fun annexB(nal: ByteArray): ByteArray {
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) {
            return nal
        }
        val out = ByteArray(nal.size + 4)
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}
