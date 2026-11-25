package com.airplaytv.core.rtsp

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** Exercises the RTSP/HTTP parser and response writer against real sockets. */
object RtspVerify {
    @JvmStatic
    fun run() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val server = HttpServer(0) { request, response ->
            seen.add(request)
            when {
                request.method == "GET" && request.url.startsWith("/info") -> {
                    response.header("Content-Type", "application/x-apple-binary-plist")
                    response.body = byteArrayOf(1, 2, 3, 4)
                }
                request.method == "SETUP" -> {
                    response.header("Content-Type", "application/x-apple-binary-plist")
                    response.body = request.body.reversedArray()
                }
                else -> Unit
            }
        }
        server.start()
        val port = server.localPort
        check(port > 0) { "server did not bind" }

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            val out: OutputStream = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())

            // 1. plain RTSP GET, two requests on one keep-alive connection
            out.write("GET /info?txtAirPlay RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            out.flush()
            val first = readResponse(input)
            check(first.startsWith("RTSP/1.0 200 OK\r\n")) { "bad status line: $first" }
            check(first.contains("CSeq: 1\r\n")) { "missing CSeq" }
            check(first.contains("Content-Length: 4\r\n")) { "missing content length" }

            val body = ByteArray(4)
            var read = 0
            while (read < 4) read += input.read(body, read, 4 - read)
            check(body.contentEquals(byteArrayOf(1, 2, 3, 4))) { "bad body" }

            // 2. RTSP SETUP with a binary body and a header with mixed case
            val payload = byteArrayOf(9, 8, 7, 6, 5)
            out.write("SETUP /stream RTSP/1.0\r\ncseq: 42\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${payload.size}\r\n\r\n".toByteArray())
            out.write(payload)
            out.flush()
            val second = readResponse(input)
            check(second.startsWith("RTSP/1.0 200 OK\r\n")) { "bad setup status: $second" }
            check(second.contains("CSeq: 42\r\n")) { "CSeq not echoed: $second" }
            val setupBody = ByteArray(payload.size)
            read = 0
            while (read < payload.size) read += input.read(setupBody, read, payload.size - read)
            check(setupBody.contentEquals(payload.reversedArray())) { "body not round-tripped" }

            // 3. HTTP/1.1 request is echoed as HTTP/1.1
            out.write("GET /info HTTP/1.1\r\nCSeq: 7\r\n\r\n".toByteArray())
            out.flush()
            val third = readResponse(input)
            check(third.startsWith("HTTP/1.1 200 OK\r\n")) { "protocol not mirrored: $third" }
        }

        check(seen.size == 3) { "expected 3 requests, got ${seen.size}" }
        check(seen[0].url == "/info?txtAirPlay") { "query string lost: ${seen[0].url}" }
        check(seen[1].method == "SETUP") { "method lost" }
        check(seen[1].cseq == "42") { "CSeq lookup failed" }
        check(seen[1].body.contentEquals(byteArrayOf(9, 8, 7, 6, 5))) { "binary body lost" }

        server.stop()
    }

    private fun readResponse(input: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            check(c >= 0) { "connection closed while reading response" }
            sb.append(c.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }
}
