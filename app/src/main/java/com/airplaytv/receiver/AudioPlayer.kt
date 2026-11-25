package com.airplaytv.receiver

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.airplaytv.core.alac.AlacConfig
import com.airplaytv.core.alac.AlacDecoder
import java.nio.ByteBuffer

/**
 * Plays the AirPlay audio track.
 *
 * Two paths:
 *  - AAC (mirroring audio: AAC-ELD, audio-only AAC) via the platform MediaCodec;
 *  - ALAC (Apple Music, lossless) via our own bit-exact decoder, because Android
 *    ships no ALAC decoder at all.
 */
class AudioPlayer {
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var failed = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var outputFormatConfigured = false

    private var alac: AlacDecoder? = null
    private var alacTrack: AudioTrack? = null
    private var alacWarned = false

    @Volatile
    var isActive: Boolean = false
        private set

    /** `ct`: 2 = ALAC, 4 = AAC, 8/3 = AAC-ELD. `spf` = samples per frame. */
    @Synchronized
    fun setCodec(ct: Int, samplesPerFrame: Int = 352) {
        if (isActive) return
        releaseInternal()
        failed = false
        if (ct == 2) {
            val frameLength = if (samplesPerFrame in 64..8192) samplesPerFrame else 352
            audioSpecificConfig = ALAC_MARKER
            val cfg = AlacConfig(frameLength, 16, 40, 10, 14, 2, 44100)
            alacConfig = cfg
            alac = try {
                AlacDecoder(cfg)
            } catch (e: Throwable) {
                Log.e(TAG, "cannot create ALAC decoder", e)
                null
            }
            Log.i(TAG, "audio codec ct=2 -> ALAC (frame=$frameLength)")
        } else {
            audioSpecificConfig = when (ct) {
                4 -> AAC_LC_CONFIG
                else -> ELD_CONFIG
            }
            Log.i(TAG, "audio codec ct=$ct -> ${if (ct == 4) "AAC-LC" else "AAC-ELD"}")
        }
    }

    fun ensureStarted(): Boolean {
        if (isActive) return true
        if (failed) return false
        if (alac != null) {
            val cfg = alacConfig ?: AlacConfig(352, 16, 40, 10, 14, 2, 44100)
            createAlacTrack(cfg.channels, cfg.sampleRate)
            isActive = alacTrack != null
            return isActive
        }
        if (audioSpecificConfig === ALAC_MARKER) {
            if (!alacWarned) {
                Log.w(TAG, "sender streams ALAC (lossless), which Android cannot decode")
                alacWarned = true
            }
            return false
        }
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(audioSpecificConfig))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            isActive = true
            Log.i(TAG, "AAC decoder started")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "AAC decoder unavailable: ${e.message}")
            failed = true
            false
        }
    }

    fun submit(frame: ByteArray, ptsMicros: Long) {
        val alacDecoder = alac
        if (alacDecoder != null) {
            submitAlac(alacDecoder, frame)
            return
        }
        val c = codec ?: return
        try {
            var attempts = 0
            while (true) {
                val index = c.dequeueInputBuffer(2_000)
                if (index >= 0) {
                    val buffer = c.getInputBuffer(index) ?: return
                    buffer.clear()
                    buffer.put(frame)
                    c.queueInputBuffer(index, 0, frame.size, ptsMicros, 0)
                    break
                }
                if (++attempts > 4) return
            }
            drain(c)
        } catch (e: Throwable) {
            Log.e(TAG, "audio decode error", e)
            release()
            failed = true
        }
    }

    private fun submitAlac(decoder: AlacDecoder, frame: ByteArray) {
        val t = alacTrack ?: return
        try {
            val pcm = decoder.decode(frame)
            var i = 0
            var audible = false
            while (i < pcm.size) {
                if (pcm[i].toInt() > 32 || pcm[i].toInt() < -32) { audible = true; break }
                i += 8
            }
            if (audible) lastAudibleAt = android.os.SystemClock.elapsedRealtime()
            t.write(pcm, 0, pcm.size)
            if (pcmFrames++ % 1000L == 0L) {
                val underruns = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try { t.underrunCount } catch (_: Throwable) { -1 }
                } else -1
                val bufferFrames = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try { t.bufferSizeInFrames } catch (_: Throwable) { -1 }
            } else -1
            Log.i(TAG, "alac frames=$pcmFrames underruns=$underruns buf=${bufferFrames}f")
            }
        } catch (e: Throwable) {
            // The first packets of an Apple Music stream can carry format
            // information rather than audio; skip those quietly.
            if (alacErrors++ < 3) Log.d(TAG, "skipping non-audio ALAC packet (${frame.size}b): ${e.message}")
        }
    }

    private var pcmFrames = 0L
    private var lastTrackRebuild = 0L
    private var alacErrors = 0

    private fun drain(c: MediaCodec) {
        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val pcm = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(pcm)
                        writePcm(pcm)
                    }
                    c.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!outputFormatConfigured) {
                        outputFormatConfigured = true
                        val f = c.outputFormat
                        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        Log.i(TAG, "decoder output: ${rate}Hz ${channels}ch")
                        createTrack(rate, channels)
                    }
                }
                else -> return
            }
        }
    }

    private fun writePcm(pcm: ByteArray) {
        val t = track ?: createFallbackTrack() ?: return
        var i = 0
        var audible = false
        while (i + 1 < pcm.size) {
            val v = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            if (v > 32 || v < -32) { audible = true; break }
            i += 16
        }
        if (audible) lastAudibleAt = android.os.SystemClock.elapsedRealtime()
        if (pcmFrames++ % 500L == 0L) {
            val underruns = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try { t.underrunCount } catch (_: Throwable) { -1 }
            } else -1
            val bufferFrames = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try { t.bufferSizeInFrames } catch (_: Throwable) { -1 }
            } else -1
            Log.i(TAG, "aac chunks=$pcmFrames underruns=$underruns buf=${bufferFrames}f")
        }
        try {
            t.write(pcm, 0, pcm.size)
        } catch (e: Throwable) {
            // The audio route can disappear (HDMI re-negotiation, device sleep).
            // Rebuild the track once in a while instead of going silent forever.
            Log.e(TAG, "AudioTrack write failed: ${e.message}, rebuilding")
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTrackRebuild > 5_000) {
                lastTrackRebuild = now
                releaseTrack()
                createFallbackTrack()
            }
        }
    }

    private fun createTrack(sampleRate: Int, channels: Int) {
        releaseTrack()
        track = buildTrack(sampleRate, channels, isMusic = false)
        track?.setVolume(pendingVolume)
        track?.play()
        Log.i(TAG, "AudioTrack ${sampleRate}Hz ${channels}ch")
    }

    private fun createAlacTrack(channels: Int, sampleRate: Int) {
        if (alacTrack != null) return
        alacTrack = buildTrack(sampleRate, channels, isMusic = true)
        alacTrack?.setVolume(pendingVolume)
        alacTrack?.play()
        Log.i(TAG, "ALAC AudioTrack ${sampleRate}Hz ${channels}ch")
    }

    private fun buildTrack(sampleRate: Int, channels: Int, isMusic: Boolean): AudioTrack? = try {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuf * 6, sampleRate * 3 / 4 * channels * 2)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STREAM,
            )
        } else AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        if (isMusic) AudioAttributes.CONTENT_TYPE_MUSIC
                        else AudioAttributes.CONTENT_TYPE_MOVIE
                    )
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            // ~0.5 s of audio: absorbs Wi-Fi jitter and decoder hiccups, which is
            // what actually causes crackle. Latency stays acceptable for video.
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                }
            }
            .build()
    } catch (e: Throwable) {
        Log.e(TAG, "cannot create AudioTrack", e)
        null
    }

    private fun createFallbackTrack(): AudioTrack? {
        if (track != null) return track
        createTrack(44100, 2)
        return track
    }

    /** Frames actually played by the ALAC track; used to advance the timeline. */
    fun framesPlayed(): Long = try {
        (alacTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL
    } catch (_: Throwable) {
        0L
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var flushing = false

    /**
     * Drops whatever is still queued so pause/stop takes effect immediately.
     * A short fade-out first: pausing a live AudioTrack abruptly produces an
     * audible click.
     */
    fun flushOutput() {
        if (flushing) return
        flushing = true
        val t = if (alac != null) alacTrack else track
        if (t == null) {
            flushing = false
            return
        }
        val target = pendingVolume
        try {
            for (i in 1..4) {
                mainHandler.postDelayed({
                    try { t.setVolume(target * (1f - i / 5f)) } catch (_: Throwable) {}
                }, i * 12L)
            }
            mainHandler.postDelayed({
                try {
                    t.pause()
                    t.flush()
                    t.play()
                    t.setVolume(target)
                } catch (_: Throwable) {
                }
                flushing = false
            }, 70)
        } catch (_: Throwable) {
            flushing = false
        }
    }

    @Volatile private var pendingVolume: Float = 1f

    /** elapsedRealtime of the last audible PCM chunk (sender streams silence while paused). */
    @Volatile var lastAudibleAt: Long = 0
        private set

    fun setVolume(linear: Float) {
        val v = linear.coerceIn(0f, 1f)
        pendingVolume = v
        try {
            track?.setVolume(v)
            alacTrack?.setVolume(v)
        } catch (_: Throwable) {
        }
    }

    private fun releaseTrack() {
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
        outputFormatConfigured = false
    }

    private fun releaseAlacTrack() {
        try {
            alacTrack?.pause()
            alacTrack?.flush()
            alacTrack?.release()
        } catch (_: Throwable) {
        }
        alacTrack = null
    }

    private fun releaseInternal() {
        alac = null
        alacConfig = null
        releaseAlacTrack()
    }

    fun release() {
        isActive = false
        failed = false
        alacErrors = 0
        pcmFrames = 0
        codec?.let {
            try {
                it.stop()
            } catch (_: Throwable) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        codec = null
        releaseTrack()
        releaseInternal()
    }

    private var alacConfig: AlacConfig? = null

    companion object {
        private const val TAG = "AudioPlayer"
        private val ELD_CONFIG = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00)
        private val AAC_LC_CONFIG = byteArrayOf(0x12, 0x10)
        private val ALAC_MARKER = byteArrayOf(0x00)
    }

    @Volatile private var audioSpecificConfig: ByteArray = ELD_CONFIG
}
