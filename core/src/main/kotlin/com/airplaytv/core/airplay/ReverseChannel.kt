package com.airplaytv.core.airplay

import com.airplaytv.core.util.Log
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AirPlay reverse HTTP channel ("PTTH/1.0").
 *
 * The sender opens it with `POST /reverse` + `Upgrade: PTTH/1.0`; the receiver
 * answers `101 Switching Protocols` and the TCP connection becomes duplex.
 * From then on the receiver may send requests to the sender on this socket —
 * most importantly `/event` FCUP requests that ask for an HLS playlist — and
 * receives the answers here.
 *
 * The upgrade handler thread runs [readLoop]; writers use [requestPlaylist].
 * Reading is intentionally tolerant: macOS answers with `EVENT/1.0` lines,
 * some senders with plain `HTTP/1.1`, and unknown messages must not kill the
 * channel.
 */
class ReverseChannel(
    private val socket: Socket,
    private val input: InputStream,
    private val output: BufferedOutputStream,
) {
    /** Bodies of the messages received on this channel, oldest first. */
    private val incoming = LinkedBlockingQueue<Message>()

    private val writeLock = Any()

    @Volatile
    private var closed = false

    private var requestId = 0

    class Message(val startLine: String, val headers: Map<String, String>, val body: ByteArray) {
        val status: Int
            get() = startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    }

    /**
     * Reads messages until the peer closes the connection. Runs on the HTTP
     * server's connection thread; the socket is closed by the server afterwards.
     */
    fun readLoop() {
        try {
            while (!closed && !socket.isClosed) {
                val startLine = readLine() ?: break
                if (startLine.isBlank()) continue
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine() ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (length in 1..(8 * 1024 * 1024)) readExactly(length) else ByteArray(0)
                incoming.offer(Message(startLine, headers, body))
            }
        } catch (e: Throwable) {
            if (!closed) Log.d(TAG, "reverse channel read ended: ${e.message}")
        } finally {
            closed = true
        }
    }

    /** Blocks until the sender answers a request (or the timeout expires). */
    fun awaitMessage(timeoutMs: Long): Message? =
        try {
            incoming.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }

    /**
     * Sends an FCUP `/event` request for [url] to the sender.
     *
     * @return the request id used, so a matching `/action` can be recognised.
     */
    fun requestPlaylist(url: String, playbackSessionId: String?): Int {
        val id = ++requestId
        val body = buildFcupRequest(url, playbackSessionId, id).toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("POST /event RTSP/1.0\r\n")
            append("CSeq: ").append(id).append("\r\n")
            append("Content-Type: text/x-apple-plist+xml\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        synchronized(writeLock) {
            if (closed) throw IOException("reverse channel closed")
            output.write(header)
            output.write(body)
            output.flush()
        }
        Log.i(TAG, "FCUP request #$id for $url")
        return id
    }

    fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun buildFcupRequest(url: String, playbackSessionId: String?, id: Int): String {
        val escapedUrl = escapeXml(url)
        val escapedSession = escapeXml(playbackSessionId ?: "")
        // Field names and the "clientInfo/clientRef" values follow the AirPlay
        // reverse-engineered protocol (same shape UxPlay sends to Apple TVs).
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
            append("<plist version=\"1.0\"><dict>\n")
            append("<key>sessionID</key><integer>1</integer>\n")
            append("<key>type</key><string>unhandledURLRequest</string>\n")
            append("<key>request</key><dict>\n")
            append("<key>FCUP_Response_ClientInfo</key><integer>1</integer>\n")
            append("<key>FCUP_Response_ClientRef</key><integer>40030004</integer>\n")
            append("<key>FCUP_Response_RequestID</key><integer>").append(id).append("</integer>\n")
            append("<key>FCUP_Response_URL</key><string>").append(escapedUrl).append("</string>\n")
            append("<key>sessionID</key><integer>1</integer>\n")
            append("<key>FCUP_Response_Headers</key><dict>\n")
            append("<key>X-Playback-Session-Id</key><string>").append(escapedSession).append("</string>\n")
            append("<key>User-Agent</key><string>AppleCoreMedia/1.0.0.11B554a ")
            append("(Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us)</string>\n")
            append("</dict>\n")
            append("</dict>\n")
            append("</dict></plist>\n")
        }
    }

    private fun escapeXml(value: String): String = buildString(value.length + 16) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun readLine(): String? {
        val buf = StringBuilder(128)
        while (true) {
            val c = input.read()
            if (c < 0) {
                if (buf.isEmpty()) return null
                throw EOFException("truncated line")
            }
            if (c == '\n'.code) {
                if (buf.isNotEmpty() && buf[buf.length - 1] == '\r') buf.setLength(buf.length - 1)
                return buf.toString()
            }
            buf.append(c.toChar())
            if (buf.length > 16 * 1024) throw IOException("line too long")
        }
    }

    private fun readExactly(length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) throw EOFException("body truncated at $read/$length")
            read += n
        }
        return out
    }

    companion object {
        private const val TAG = "ReverseChannel"
    }
}
