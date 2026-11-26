package com.airplaytv.core.airplay

import com.airplaytv.core.crypto.AesCbc
import com.airplaytv.core.util.Bytes
import com.airplaytv.core.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Receives AirPlay audio (mirroring audio and audio-only sessions).
 *
 * Audio is RTP over UDP. The 12-byte RTP header is followed by an AES-128-CBC
 * encrypted frame (a fresh cipher per packet, IV from the SETUP plist). AirPlay
 * deliberately sends every packet two or three times for redundancy, so packets
 * are de-duplicated by RTP sequence number before they reach the decoder.
 */
class AudioStream(
    private val sink: AudioSink,
) {
    interface AudioSink {
        fun onAudioFrame(frame: ByteArray, ptsMicros: Long)
        fun onAudioEnded()
    }

    /** `ct` from the stream SETUP: 2 = AAC-LC, 4/8 = AAC-ELD. */
    @Volatile var codecType: Int = 8
        private set

    @Volatile private var running = false
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null

    @Volatile var lastPacketAtMs: Long = 0
        private set

    @Volatile private var aesKey: ByteArray = ByteArray(16)
    @Volatile private var aesIv: ByteArray = ByteArray(16)

    private val sequenceLock = Any()
    private var expectedSeq = -1

    // Small reorder window: AirPlay sends every packet 2-3 times, so holding
    // audio for ~60 ms lets a retransmission fill a hole instead of clicking.
    private class Pending(val seq: Int, val data: ByteArray, val rtp: Long, val atMs: Long)
    private val slots = arrayOfNulls<Pending>(64)
    private val jitterWindowMs = 60L
    private var holes = 0L

    val dataPort: Int get() = dataSocket?.localPort ?: 0
    val controlPort: Int get() = controlSocket?.localPort ?: 0

    /**
     * Starts the audio sockets.
     *
     * @param plainKey true for classic RAOP, where `ANNOUNCE` already carried
     *   the final AES key; false for AirPlay 2, where the key is
     *   `sha512(ekey, ecdhSecret)`.
     */
    fun start(
        aesKey: ByteArray,
        aesIv: ByteArray,
        ecdhSecret: ByteArray,
        ct: Int = 8,
        plainKey: Boolean = false,
    ): Boolean {
        if (running) return true
        codecType = ct
        this.aesKey = if (plainKey) {
            aesKey.copyOf(16)
        } else {
            Bytes.sha512(aesKey, ecdhSecret).copyOfRange(0, 16)
        }
        this.aesIv = aesIv.copyOf(16)
        synchronized(sequenceLock) {
            expectedSeq = -1
            slots.fill(null)
            holes = 0
        }
        lastPacketAtMs = 0

        return try {
            val ds = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                soTimeout = 1000
                receiveBufferSize = 1024 * 1024
            }
            val cs = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                soTimeout = 1000
            }
            dataSocket = ds
            controlSocket = cs
            running = true
            dataThread = Thread({ dataLoop(ds) }, "airplay-audio-data").apply {
                isDaemon = true
                start()
            }
            controlThread = Thread({ controlLoop(cs) }, "airplay-audio-control").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "audio listening: data=${ds.localPort} control=${cs.localPort} ct=$ct")
            true
        } catch (e: IOException) {
            Log.e(TAG, "failed to bind audio sockets", e)
            stop()
            false
        }
    }

    fun stop() {
        running = false
        try {
            dataSocket?.close()
        } catch (_: IOException) {
        }
        try {
            controlSocket?.close()
        } catch (_: IOException) {
        }
        dataSocket = null
        controlSocket = null
        dataThread?.interrupt()
        controlThread?.interrupt()
        dataThread = null
        controlThread = null
        synchronized(sequenceLock) { expectedSeq = -1 }
    }

    private fun dataLoop(socket: DatagramSocket) {
        val buffer = ByteArray(65536)
        var firstRtp: Long? = null
        var packets = 0L
        try {
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val length = packet.length
                if (length < 12) continue
                val data = buffer

                // Ignore the sender's keep-alive / "no audio" packet.
                if (length == 16 &&
                    data[12] == 0.toByte() && data[13] == 0x68.toByte() &&
                    data[14] == 0x34.toByte() && data[15] == 0.toByte()
                ) {
                    continue
                }

                lastPacketAtMs = androidFreeClock()
                val seq = Bytes.be16(data, 2)
                val rtp = Bytes.be32l(data, 4)
                val payloadSize = length - 12
                val encrypted = data.copyOfRange(12, 12 + payloadSize)
                val decrypted = try {
                    decryptPayload(encrypted)
                } catch (e: Throwable) {
                    Log.e(TAG, "audio decrypt failed", e)
                    continue
                }
                if (packets == 0L) Log.i(TAG, "first audio packet: ${payloadSize}b seq=$seq ct=$codecType")
                packets++

                for (frame in offer(seq, decrypted, rtp)) {
                    if (firstRtp == null) firstRtp = frame.rtp
                    val pts = ((frame.rtp - (firstRtp ?: frame.rtp)) and 0xffffffffL) * 1_000_000L / 44100L
                    sink.onAudioFrame(frame.data, pts)
                }
            }
        } catch (e: Throwable) {
            if (running) Log.e(TAG, "audio data loop ended", e)
        } finally {
            if (running) sink.onAudioEnded()
        }
    }

    /**
     * Reorder/duplicate filter. Packets are buffered for a short window so a
     * retransmitted copy can fill a gap; frames are then handed to the decoder
     * strictly in sequence order.
     */
    private fun offer(seq: Int, data: ByteArray, rtp: Long): List<Pending> {
        val ready = ArrayList<Pending>(4)
        synchronized(sequenceLock) {
            val now = System.nanoTime() / 1_000_000L
            slots[seq and 63] = Pending(seq, data, rtp, now)
            if (expectedSeq < 0) expectedSeq = seq

            while (true) {
                val head = slots[expectedSeq and 63]
                if (head != null && head.seq == expectedSeq) {
                    ready.add(head)
                    slots[expectedSeq and 63] = null
                    expectedSeq = (expectedSeq + 1) and 0xffff
                    continue
                }
                // A hole: give the sender's retransmission a moment to arrive.
                val oldest = oldestPendingLocked(now) ?: break
                if (now - oldest.atMs < jitterWindowMs) break
                holes++
                if (holes % 10L == 1L) {
                    Log.i(TAG, "audio hole at ${expectedSeq}, skipped ${(oldest.seq - expectedSeq) and 0xffff} packets (total holes=$holes)")
                }
                expectedSeq = oldest.seq
            }
        }
        return ready
    }

    private fun oldestPendingLocked(now: Long): Pending? {
        var best: Pending? = null
        var bestDistance = Int.MAX_VALUE
        for (p in slots) {
            if (p == null) continue
            val ahead = (p.seq - expectedSeq) and 0xffff
            if (ahead == 0 || ahead > 0x8000) continue
            if (ahead < bestDistance) {
                bestDistance = ahead
                best = p
            }
        }
        return best
    }

    private fun androidFreeClock(): Long = System.nanoTime() / 1_000_000L

    /** Milliseconds since the last audio packet (Long.MAX_VALUE when nothing arrived yet). */
    fun millisSinceLastPacket(): Long {
        val last = lastPacketAtMs
        return if (last == 0L) Long.MAX_VALUE else androidFreeClock() - last
    }

    private fun decryptPayload(payload: ByteArray): ByteArray {
        val blockLen = payload.size / 16 * 16
        if (blockLen == 0) return payload
        val out = ByteArray(payload.size)
        val decrypted = AesCbc.decrypt(aesKey, aesIv, payload.copyOfRange(0, blockLen))
        System.arraycopy(decrypted, 0, out, 0, blockLen)
        System.arraycopy(payload, blockLen, out, blockLen, payload.size - blockLen)
        return out
    }

    private fun controlLoop(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        try {
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                // 0x54 = sync, 0x56 = resend request; not needed when the sender
                // has redundancy enabled.
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "AudioStream"
    }
}
