package com.airplaytv.receiver

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * Plays AirPlay "video" sessions: the sender does not stream pixels, it sends a
 * URL (usually an HLS playlist) and expects the receiver to play it locally.
 *
 * MediaPlayer covers HLS and progressive HTTP, which is what Safari and most
 * iOS apps hand over. DRM-protected streams (FairPlay Streaming) are not
 * supported and simply fail to prepare.
 */
class RemoteVideoPlayer(
    private val context: Context,
    private val onEnded: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    @Volatile private var surface: Surface? = null
    @Volatile private var positionSeconds = 0.0
    @Volatile private var durationSeconds = 0.0
    @Volatile private var active = false

    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        main.post {
            try {
                player?.setSurface(newSurface)
            } catch (e: Throwable) {
                Log.w(TAG, "cannot switch surface: ${e.message}")
            }
        }
    }

    fun play(url: String, startSeconds: Double, headers: Map<String, String>) {
        Log.i(TAG, "play $url at $startSeconds")
        main.post {
            releaseInternal()
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            try {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                mediaPlayer.setDataSource(context, Uri.parse(url), headers)
                surface?.let { mediaPlayer.setSurface(it) }
                mediaPlayer.setOnPreparedListener { mp ->
                    durationSeconds = mp.duration.coerceAtLeast(0) / 1000.0
                    if (startSeconds > 0.5) {
                        mp.seekTo((startSeconds * 1000).toInt())
                    }
                    mp.start()
                    active = true
                    positionSeconds = startSeconds
                    Log.i(TAG, "playing, duration=" + java.lang.String.format(java.util.Locale.US, "%.1f", durationSeconds) + "s")
                }
                mediaPlayer.setOnCompletionListener {
                    Log.i(TAG, "playback completed")
                    releaseInternal()
                    onEnded()
                }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    releaseInternal()
                    onEnded()
                    true
                }
                mediaPlayer.prepareAsync()
            } catch (e: Throwable) {
                Log.e(TAG, "cannot start video: ${e.message}")
                releaseInternal()
                onEnded()
            }
        }
    }

    fun pause() {
        main.post {
            try {
                player?.takeIf { it.isPlaying }?.pause()
            } catch (_: Throwable) {
            }
        }
    }

    fun resume() {
        main.post {
            try {
                player?.start()
            } catch (_: Throwable) {
            }
        }
    }

    fun seek(seconds: Double) {
        main.post {
            try {
                player?.seekTo((seconds * 1000).toInt())
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        main.post { releaseInternal() }
    }

    fun snapshot(): Pair<Double, Double> {
        val p = player ?: return 0.0 to 0.0
        return try {
            if (active) p.currentPosition / 1000.0 to p.duration.coerceAtLeast(0) / 1000.0
            else 0.0 to 0.0
        } catch (_: Throwable) {
            0.0 to 0.0
        }
    }

    private fun releaseInternal() {
        val p = player ?: return
        player = null
        active = false
        positionSeconds = 0.0
        durationSeconds = 0.0
        try {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.reset()
        } catch (_: Throwable) {
        }
        try {
            p.release()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "RemoteVideo"
    }
}
