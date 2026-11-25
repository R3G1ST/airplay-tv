package com.airplaytv.core.airplay

import com.airplaytv.core.util.Bytes
import com.airplaytv.core.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Minimal NTP client for the AirPlay timing channel.
 *
 * After SETUP the sender expects the receiver to poll its timing port with
 * 32-byte NTP requests (mode 3, version 4). We do this every 3 seconds and keep
 * the best (lowest-delay) offset, which maps the sender's clock onto ours.
 */
class NtpClient {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile var offsetMicros: Long = 0
        private set
    @Volatile var lastRoundTripMicros: Long = 0
        private set

    val localPort: Int get() = socket?.localPort ?: 0

    @Synchronized
    fun start(remote: InetAddress, remotePort: Int) {
        if (running) return
        val s = try {
            DatagramSocket().apply { soTimeout = 1000 }
        } catch (e: Throwable) {
            Log.e(TAG, "cannot create timing socket", e)
            return
        }
        socket = s
        running = true
        thread = Thread({ loop(s, remote, remotePort) }, "airplay-ntp").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        thread?.interrupt()
        thread = null
    }

    /** Converts a timestamp of the sender clock into our local clock. */
    fun remoteToLocal(remoteMicros: Long): Long = remoteMicros - offsetMicros

    private fun loop(s: DatagramSocket, remote: InetAddress, remotePort: Int) {
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xd2.toByte()
        request[2] = 0x00
        request[3] = 0x07
        val response = ByteArray(128)
        var bestDelay = Long.MAX_VALUE

        try {
            while (running) {
                val t0 = localMicros()
                putNtpTimestamp(request, 24, t0)
                val packet = DatagramPacket(request, request.size, remote, remotePort)
                try {
                    s.send(packet)
                    val reply = DatagramPacket(response, response.size)
                    s.receive(reply)
                } catch (_: SocketTimeoutException) {
                    Thread.sleep(1000)
                    continue
                }
                val t3 = localMicros()
                val t1 = getNtpTimestamp(response, 16)
                val t2 = getNtpTimestamp(response, 24)
                val offset = ((t1 - t0) + (t2 - t3)) / 2
                val delay = (t3 - t0) - (t2 - t1)
                if (delay in 0 until bestDelay) {
                    bestDelay = delay
                    offsetMicros = offset
                    lastRoundTripMicros = delay
                }
                Thread.sleep(3000)
            }
        } catch (e: Throwable) {
            if (running) Log.d(TAG, "timing loop ended: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun localMicros(): Long = System.currentTimeMillis() * 1000L

    private fun putNtpTimestamp(buffer: ByteArray, offset: Int, microsSinceEpoch: Long) {
        val seconds = microsSinceEpoch / 1_000_000L + SECONDS_FROM_1900_TO_1970
        val micros = microsSinceEpoch % 1_000_000L
        val fraction = (micros shl 32) / 1_000_000L
        Bytes.putBe32(buffer, offset, seconds.toInt())
        Bytes.putBe32(buffer, offset + 4, fraction.toInt())
    }

    private fun getNtpTimestamp(buffer: ByteArray, offset: Int): Long {
        val seconds = Bytes.be32l(buffer, offset) - SECONDS_FROM_1900_TO_1970
        val fraction = Bytes.be32l(buffer, offset + 4)
        return seconds * 1_000_000L + ((fraction * 1_000_000L) shr 32)
    }

    companion object {
        private const val TAG = "NtpClient"
        private const val SECONDS_FROM_1900_TO_1970 = 2_208_988_800L
    }
}
