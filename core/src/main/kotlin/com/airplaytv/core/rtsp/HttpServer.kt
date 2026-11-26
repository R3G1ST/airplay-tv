package com.airplaytv.core.rtsp

import com.airplaytv.core.util.Log
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** A parsed RTSP/HTTP request as sent by iOS/macOS AirPlay clients. */
class Request(
    val method: String,
    val url: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteAddress: java.net.InetAddress,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val cseq: String? get() = header("CSeq")
}

/** Response to a [Request]. */
class Response(var status: Int = 200, var reason: String = "OK") {
    val headers = LinkedHashMap<String, String>()
    var body: ByteArray? = null
    var closeConnection: Boolean = false

    /**
     * Keep the TCP connection open after this response and hand it to
     * [HttpServer.upgradeHandler]. This is how AirPlay's reverse HTTP channel
     * (`POST /reverse` + `Upgrade: PTTH/1.0`) is established.
     */
    var upgrade: Boolean = false

    /** Overrides the response protocol line (the reverse upgrade needs `HTTP/1.1`, not RTSP). */
    var protocol: String? = null

    fun header(name: String, value: String): Response {
        headers[name] = value
        return this
    }
}

/**
 * A very small, dependency-free RTSP/1.0 + HTTP/1.x server.
 *
 * AirPlay speaks RTSP for almost everything (including `SETUP`, `RECORD`,
 * `FLUSH`), but uses plain HTTP verbs for `/info`, `/pair-*`, `/fp-setup`.
 * Both arrive on the same port, so this server accepts both versions and
 * echoes the client's version back.
 */
class HttpServer(
    private val port: Int,
    private val handler: (Request, Response) -> Unit,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val clients = CopyOnWriteArrayList<Socket>()
    private val upgradedClients = CopyOnWriteArrayList<Socket>()
    private var acceptThread: Thread? = null

    /**
     * Called on the connection thread when a response sets [Response.upgrade].
     * The handler owns the socket until it returns; the server closes it after.
     * The input stream is unbuffered, so no client bytes are lost in the handoff.
     */
    @Volatile
    var upgradeHandler: ((Request, Socket, InputStream, BufferedOutputStream) -> Unit)? = null

    val localPort: Int get() = serverSocket?.localPort ?: port

    /** True while the accept loop is running and the socket is bound. */
    val isListening: Boolean get() = running && serverSocket?.isBound == true

    @Synchronized
    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "airplay-http-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "HTTP/RTSP server listening on ${ss.localPort}")
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        for (c in clients) {
            try {
                c.close()
            } catch (_: IOException) {
            }
        }
        for (c in upgradedClients) {
            try {
                c.close()
            } catch (_: IOException) {
            }
        }
        clients.clear()
        upgradedClients.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    /** Closes every connection opened by a given address (used when preempting a sender). */
    fun closeConnectionsFrom(address: java.net.InetAddress) {
        for (c in clients) {
            if (c.inetAddress == address) {
                try {
                    c.close()
                } catch (_: IOException) {
                }
            }
        }
        for (c in upgradedClients) {
            if (c.inetAddress == address) {
                try {
                    c.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            clients.add(socket)
            Log.i(TAG, "client connected: ${socket.inetAddress.hostAddress}:${socket.port}")
            Thread({ handleConnection(socket) }, "airplay-http-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        val remote = socket.inetAddress
        var upgraded = false
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream())

            while (running && !socket.isClosed) {
                val request = try {
                    readRequest(input, remote)
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: EOFException) {
                    break
                } catch (e: IOException) {
                    if (running) Log.d(TAG, "read failed: ${e.message}")
                    break
                } ?: break

                val response = Response()
                try {
                    handler(request, response)
                } catch (e: Throwable) {
                    Log.e(TAG, "handler crashed for ${request.method} ${request.url}", e)
                    response.headers.clear()
                    response.body = null
                    response.status = 500
                    response.reason = "Internal Server Error"
                }

                response.headers["CSeq"] = request.cseq ?: "0"
                if (response.headers["Server"] == null) {
                    response.headers["Server"] = "AirTunes/220.68"
                }
                writeResponse(output, request.version, response, request)
                output.flush()

                if (response.upgrade) {
                    upgraded = true
                    clients.remove(socket)
                    upgradedClients.add(socket)
                    Log.i(TAG, "connection upgraded: ${request.method} ${request.url} <- ${remote.hostAddress}")
                    try {
                        upgradeHandler?.invoke(request, socket, input, output)
                    } catch (e: Throwable) {
                        Log.d(TAG, "upgraded connection ended: ${e.message}")
                    }
                    break
                }

                if (response.closeConnection) break
            }
        } catch (e: Throwable) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            if (upgraded) upgradedClients.remove(socket) else clients.remove(socket)
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun readRequest(input: InputStream, remote: java.net.InetAddress): Request? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null

        val parts = requestLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            throw IOException("malformed request line: $requestLine")
        }
        val method = parts[0]
        val url = parts[1]
        val version = if (parts.size >= 3) parts[2] else "RTSP/1.0"

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw EOFException("headers truncated")
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            if (headers.containsKey(name)) {
                headers[name] = headers[name] + ", " + value
            } else {
                headers[name] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > 64 * 1024 * 1024) {
            throw IOException("bad content-length $contentLength")
        }
        val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)

        Log.d(TAG, "-> $method $url ${version} (${body.size} bytes)")
        return Request(method, url, version, Collections.unmodifiableMap(headers), body, remote)
    }

    private fun writeResponse(
        out: BufferedOutputStream,
        version: String,
        response: Response,
        request: Request,
    ) {
        val body = response.body ?: ByteArray(0)
        val proto = response.protocol
            ?: if (version.startsWith("HTTP")) "HTTP/1.1" else "RTSP/1.0"
        val sb = StringBuilder()
        sb.append(proto).append(' ').append(response.status).append(' ').append(response.reason).append("\r\n")
        for ((k, v) in response.headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        // 101 Switching Protocols has no body by definition.
        if (response.status != 101 &&
            response.headers.keys.none { it.equals("Content-Length", ignoreCase = true) }
        ) {
            sb.append("Content-Length: ").append(body.size).append("\r\n")
        }
        if (response.headers.keys.none { it.equals("Connection", ignoreCase = true) } && response.closeConnection) {
            sb.append("Connection: close\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        Log.d(TAG, "<- ${response.status} ${request.method} ${request.url} (${body.size} bytes)")
    }

    private fun readLine(input: InputStream): String? {
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
            if (buf.length > 16 * 1024) throw IOException("header line too long")
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
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
        private const val TAG = "HttpServer"
        private const val SOCKET_TIMEOUT_MS = 300_000
    }
}
