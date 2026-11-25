package com.airplaytv.core.airplay

import com.airplaytv.core.crypto.AesCtr
import com.airplaytv.core.util.Bytes
import com.airplaytv.core.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Receives the AirPlay screen-mirroring stream.
 *
 * Wire format (reverse engineered, matches RPiPlay/UxPlay):
 *   [128-byte header][payload] repeated.
 *   The header is a native (little endian) C struct:
 *   header[0..3]  = payload length (little endian)
 *   header[4..5]  = payload type: 0 = video NALs, 1 = SPS/PPS config
 *   header[6..7]  = option/flags
 *   header[8..15] = 64-bit NTP presentation timestamp of the sender clock
 * The payload of a video packet is AES-128-CTR encrypted with a per-stream key and
 * contains 4-byte big-endian length-prefixed NAL units, which we convert to
 * Annex-B start codes for MediaCodec.
 */
class MirrorStream(
    private val sink: VideoSink,
) {
    interface VideoSink {
        fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int)
        fun onVideoFrame(data: ByteArray, ptsMicros: Long)
        fun onVideoEnded()
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var client: Socket? = null

    @Volatile private var aesKey: ByteArray? = null
    @Volatile private var aesIv: ByteArray? = null

    val localPort: Int get() = serverSocket?.localPort ?: 0

    /**
     * Derives the per-stream AES-CTR key the way the protocol requires:
     *   eaeskey = SHA512(aesKey || ecdhSecret)[0..15]
     *   key     = SHA512("AirPlayStreamKey" + streamConnectionId || eaeskey)[0..15]
     *   iv      = SHA512("AirPlayStreamIV"  + streamConnectionId || eaeskey)[0..15]
     */
    private fun deriveKey(streamConnectionId: Long, aesKey: ByteArray, ecdhSecret: ByteArray): ByteArray {
        val eaeskey = Bytes.sha512(aesKey, ecdhSecret).copyOfRange(0, 16)
        // streamConnectionID is an unsigned 64-bit value; the sender formats it
        // as unsigned decimal. Kotlin Long is signed, so convert explicitly.
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val keySalt = Bytes.ascii("AirPlayStreamKey$id")
        val ivSalt = Bytes.ascii("AirPlayStreamIV$id")
        val key = Bytes.sha512(keySalt, eaeskey).copyOfRange(0, 16)
        val iv = Bytes.sha512(ivSalt, eaeskey).copyOfRange(0, 16)
        return Bytes.concat(key, iv)
    }

    @Synchronized
    fun start(streamConnectionId: Long, aesKey: ByteArray, ecdhSecret: ByteArray): Int {
        if (running) return localPort
        val material = deriveKey(streamConnectionId, aesKey, ecdhSecret)
        this.aesKey = material.copyOfRange(0, 16)
        this.aesIv = material.copyOfRange(16, 32)

        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(0), 1)
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "airplay-mirror-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "mirror stream listening on ${ss.localPort} (streamConnectionID=${java.lang.Long.toUnsignedString(streamConnectionId)})")
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        try {
            client?.close()
        } catch (_: IOException) {
        }
        client = null
        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "mirror stream stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            Log.i(TAG, "mirroring client connected: ${socket.inetAddress.hostAddress}")
            client = socket
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                try {
                    socket.receiveBufferSize = 2 * 1024 * 1024
                } catch (_: Throwable) {
                }
                // No read timeout: a static screen legitimately produces long
                // pauses, and a timeout here used to kill healthy sessions.
                socket.soTimeout = 0
                readLoop(socket)
            } catch (e: java.io.EOFException) {
                Log.d(TAG, "mirroring client disconnected")
            } catch (e: Throwable) {
                if (running) Log.e(TAG, "mirror stream read error", e)
            } finally {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                client = null
                if (running) sink.onVideoEnded()
            }
        }
    }

    private fun readLoop(socket: Socket) {
        val input = socket.getInputStream()
        val header = ByteArray(128)
        val key = aesKey ?: error("mirror stream started without key")
        val iv = aesIv ?: error("mirror stream started without iv")
        val ctr = AesCtr(key, iv)
        var firstPts: Long? = null
        var frameCount = 0L

        while (running) {
            readFully(input, header, 128)
            val payloadSize = Bytes.le32(header, 0)
            val payloadType = Bytes.u8(header, 4)
            if (payloadSize < 0 || payloadSize > 16 * 1024 * 1024) {
                Log.w(TAG, "invalid mirror payload size $payloadSize, dropping connection")
                return
            }
            val payload = ByteArray(payloadSize)
            readFully(input, payload, payloadSize)

            when (payloadType) {
                0 -> {
                    if (payloadSize < 4) continue
                    val decrypted = ctr.process(payload)
                    // Convert length-prefixed NAL units to Annex-B start codes in place.
                    var offset = 0
                    var ok = true
                    while (offset + 4 <= decrypted.size) {
                        val nalLength = Bytes.be32(decrypted, offset)
                        if (nalLength <= 0 || offset + 4 + nalLength > decrypted.size) {
                            ok = false
                            break
                        }
                        decrypted[offset] = 0
                        decrypted[offset + 1] = 0
                        decrypted[offset + 2] = 0
                        decrypted[offset + 3] = 1
                        offset += 4 + nalLength
                    }
                    if (!ok) {
                        Log.w(TAG, "malformed NAL framing (offset=$offset size=${decrypted.size})")
                        continue
                    }
                    val rawPts = ntpToMicros(Bytes.le64(header, 8))
                    val base = firstPts ?: rawPts.also { firstPts = it }
                    val pts = (rawPts - base).coerceAtLeast(0)
                    frameCount++
                    if (frameCount == 1L) {
                        Log.i(TAG, "first video frame: ${decrypted.size}b, naluType=${Bytes.u8(decrypted, 4) and 0x1f}, pts=$pts")
                    } else if (frameCount % 300 == 0L) {
                        Log.d(TAG, "video frames: $frameCount, last ${decrypted.size}b")
                    }
                    sink.onVideoFrame(decrypted, pts)
                }
                1 -> {
                    // Not encrypted: codec configuration block with SPS/PPS.
                    parseConfig(payload, header)
                }
                5 -> Unit // once-per-second "streaming report", nothing to decode
                else -> Log.d(TAG, "ignoring mirror payload type $payloadType (${payloadSize}b)")
            }
        }
    }

    private fun parseConfig(payload: ByteArray, header: ByteArray) {
        try {
            if (payload.size < 10) {
                Log.w(TAG, "video config too short: ${payload.size}b ${Bytes.hex(payload)}")
                return
            }
            val spsSize = ((payload[6].toInt() and 0xff) shl 8) or (payload[7].toInt() and 0xff)
            if (spsSize <= 0 || spsSize > 4096 || 8 + spsSize + 3 > payload.size) {
                Log.w(TAG, "bad SPS size $spsSize")
                return
            }
            val sps = payload.copyOfRange(8, 8 + spsSize)
            val ppsLenOffset = 8 + spsSize
            val ppsSize = ((payload[ppsLenOffset + 1].toInt() and 0xf8) +
                (payload[ppsLenOffset + 2].toInt() and 0xff)) and 0xff
            if (ppsSize <= 0 || ppsSize > 4096 || ppsLenOffset + 3 + ppsSize > payload.size) {
                Log.w(TAG, "bad PPS size $ppsSize")
                return
            }
            val pps = payload.copyOfRange(ppsLenOffset + 3, ppsLenOffset + 3 + ppsSize)
            // Frame geometry lives in the 128 byte stream header, not in the payload.
            val width = java.lang.Float.intBitsToFloat(Bytes.le32(header, 56)).toInt()
            val height = java.lang.Float.intBitsToFloat(Bytes.le32(header, 60)).toInt()
            val sourceWidth = java.lang.Float.intBitsToFloat(Bytes.le32(header, 40)).toInt()
            val sourceHeight = java.lang.Float.intBitsToFloat(Bytes.le32(header, 44)).toInt()
            val outWidth = width.takeIf { it in 1..8192 } ?: sourceWidth.takeIf { it in 1..8192 } ?: 1920
            val outHeight = height.takeIf { it in 1..8192 } ?: sourceHeight.takeIf { it in 1..8192 } ?: 1080
            Log.i(TAG, "video config: sps=${sps.size}b pps=${pps.size}b ${outWidth}x${outHeight} (source ${sourceWidth}x$sourceHeight)")
            sink.onVideoConfig(sps, pps, outWidth, outHeight)
        } catch (e: Throwable) {
            Log.e(TAG, "failed to parse video config", e)
        }
    }

    /** NTP timestamp (seconds in the high word, 2^-32 s fraction in the low word) -> microseconds. */
    private fun ntpToMicros(raw: Long): Long {
        val seconds = raw ushr 32
        val fraction = raw and 0xffffffffL
        return seconds * 1_000_000L + ((fraction * 1_000_000L) ushr 32)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw EOFException("stream closed")
            read += n
        }
    }

    companion object {
        private const val TAG = "MirrorStream"
    }
}
