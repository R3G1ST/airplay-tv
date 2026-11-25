package com.airplaytv.core.airplay

import com.airplaytv.core.crypto.AesCtr
import com.airplaytv.core.crypto.Ed25519
import com.airplaytv.core.crypto.X25519
import com.airplaytv.core.plist.PDict
import com.airplaytv.core.plist.Plist
import com.airplaytv.core.plist.data
import com.airplaytv.core.plist.int
import com.airplaytv.core.plist.str
import com.airplaytv.core.util.Bytes
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end test of the mirroring transport: starts a [MirrorStream], plays the
 * role of an iOS sender over a real TCP socket, and checks that the SPS/PPS
 * configuration, AES-CTR decryption, NAL framing and timestamp anchoring all
 * behave exactly as MediaCodec needs them to.
 */
object AirPlayVerify {
    @JvmStatic
    fun run() {
        verifyMirrorStream()
        verifyTxt()
        verifyControlPlane()
    }

    /**
     * Full control-plane handshake against a real AirPlayServer over TCP:
     * pair-setup, both pair-verify steps, /info and fp-setup. This is the exact
     * sequence an iPhone performs before it starts mirroring.
     */
    private fun verifyControlPlane() {
        val server = AirPlayServer(
            AirPlayServer.Config(name = "Test TV", deviceId = "AABBCCDDEEFF", port = 0),
            object : AirPlayServer.Listener {
                override fun onSessionStarted(deviceName: String?) = Unit
                override fun onSessionEnded() = Unit
                override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) = Unit
                override fun onVideoFrame(data: ByteArray, ptsMicros: Long) = Unit
                override fun onAudioFrame(frame: ByteArray, ptsMicros: Long) = Unit
                override fun onVolumeChanged(volumeDb: Float) = Unit
            },
        )
        server.start()
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                val input = java.io.BufferedInputStream(socket.getInputStream())
                val out = socket.getOutputStream()

                // --- pair-setup
                val setupBody = ByteArray(32)
                val setupReply = exchange(out, input, "POST", "/pair-setup", "application/octet-stream", setupBody)
                check(setupReply.body.size == 32) { "pair-setup reply is ${setupReply.body.size} bytes" }
                val serverEd = setupReply.body

                // --- pair-verify step 1
                val clientEcdh = X25519.generate()
                val clientEd = Ed25519.generate()
                val step1 = Bytes.concat(byteArrayOf(1, 0, 0, 0), clientEcdh.publicKey, clientEd.publicKey)
                val step1Reply = exchange(out, input, "POST", "/pair-verify", "application/octet-stream", step1)
                check(step1Reply.body.size == 96) { "pair-verify step1 reply is ${step1Reply.body.size} bytes" }
                val serverEcdh = step1Reply.body.copyOfRange(0, 32)
                val encryptedServerSig = step1Reply.body.copyOfRange(32, 96)

                val shared = X25519.sharedSecret(clientEcdh.privateKey, serverEcdh)
                val key = Bytes.sha512(Bytes.ascii("Pair-Verify-AES-Key"), shared).copyOfRange(0, 16)
                val iv = Bytes.sha512(Bytes.ascii("Pair-Verify-AES-IV"), shared).copyOfRange(0, 16)
                val serverSig = AesCtr(key, iv).process(encryptedServerSig)
                check(Ed25519.verify(serverEd, Bytes.concat(serverEcdh, clientEcdh.publicKey), serverSig)) {
                    "server pair-verify signature is invalid"
                }

                // --- pair-verify step 2
                val clientSig = Ed25519.sign(clientEd.privateKey, Bytes.concat(clientEcdh.publicKey, serverEcdh))
                val clientCtr = AesCtr(key, iv)
                clientCtr.process(ByteArray(64))
                val step2 = Bytes.concat(byteArrayOf(0, 0, 0, 0), clientCtr.process(clientSig))
                val step2Reply = exchange(out, input, "POST", "/pair-verify", "application/octet-stream", step2)
                check(step2Reply.status == 200) { "pair-verify step2 failed: ${step2Reply.status}" }

                // --- /info
                val info = exchange(out, input, "GET", "/info", null, ByteArray(0))
                check(info.status == 200) { "GET /info failed" }
                val infoPlist = Plist.parse(info.body) as PDict
                check(infoPlist.str("name") == "Test TV") { "info name wrong" }
                check(infoPlist.int("features") == 0x5A7FFEE6L) { "info features wrong: ${infoPlist.int("features")}" }
                check(infoPlist.data("pk")!!.contentEquals(serverEd)) { "info pk does not match pair-setup key" }
                check(infoPlist.data("txtAirPlay") != null) { "info txtAirPlay missing" }

                // --- fp-setup (16-byte request, mode 0)
                val fpReq = ByteArray(16)
                fpReq[4] = 3
                fpReq[14] = 0
                val fpReply = exchange(out, input, "POST", "/fp-setup", "application/octet-stream", fpReq)
                check(fpReply.body.size == 142) { "fp-setup reply is ${fpReply.body.size} bytes" }
                check(String(fpReply.body, 0, 4, Charsets.US_ASCII) == "FPLY") { "fp-setup magic wrong" }

                // --- fp-setup handshake (164 bytes)
                val fpHandshake = ByteArray(164)
                fpHandshake[4] = 3
                for (i in 144 until 164) fpHandshake[i] = (i - 144).toByte()
                val fpHandshakeReply = exchange(out, input, "POST", "/fp-setup", "application/octet-stream", fpHandshake)
                check(fpHandshakeReply.body.size == 32) { "fp handshake reply is ${fpHandshakeReply.body.size} bytes" }
                check(fpHandshakeReply.body.copyOfRange(12, 32).contentEquals(fpHandshake.copyOfRange(144, 164))) {
                    "fp handshake echo mismatch"
                }

                // --- POST /play with a URL that contains an unescaped '&'
                // (Safari and several apps produce exactly this kind of XML)
                val played = AtomicReference<String>()
                val playing = AirPlayServer(
                    AirPlayServer.Config(name = "Test TV", deviceId = "AABBCCDDEEFF", port = 0),
                    object : AirPlayServer.Listener {
                        override fun onSessionStarted(deviceName: String?) = Unit
                        override fun onSessionEnded() = Unit
                        override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) = Unit
                        override fun onVideoFrame(data: ByteArray, ptsMicros: Long) = Unit
                        override fun onAudioFrame(frame: ByteArray, ptsMicros: Long) = Unit
                        override fun onVolumeChanged(volumeDb: Float) = Unit
                        override fun onPlayVideo(url: String, startPositionSeconds: Double, headers: Map<String, String>) {
                            played.set(url)
                        }
                    },
                )
                playing.start()
                try {
                    Socket("127.0.0.1", playing.port).use { playSocket ->
                        playSocket.soTimeout = 3000
                        val playInput = java.io.BufferedInputStream(playSocket.getInputStream())
                        val playOut = playSocket.getOutputStream()
                        val plist = "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict>" +
                            "<key>Content-Location</key><string>https://cdn.example.com/v.m3u8?token=abc&expires=1</string>" +
                            "<key>Start-Position</key><real>12.5</real></dict></plist>"
                        val reply = exchange(playOut, playInput, "POST", "/play", "application/x-apple-binary-plist", plist.toByteArray())
                        check(reply.status == 200) { "PLAY failed: ${reply.status}" }
                        check(played.get() == "https://cdn.example.com/v.m3u8?token=abc&expires=1") {
                            "Content-Location not extracted: ${played.get()}"
                        }
                    }
                } finally {
                    playing.stop()
                }

                // --- TEARDOWN
                val teardown = exchange(out, input, "TEARDOWN", "/stream", null, ByteArray(0))
                check(teardown.status == 200) { "TEARDOWN failed" }
            }
        } finally {
            server.stop()
        }
    }

    private class Reply(val status: Int, val headers: String, val body: ByteArray)

    private fun exchange(
        out: java.io.OutputStream,
        input: java.io.BufferedInputStream,
        method: String,
        path: String,
        contentType: String?,
        body: ByteArray,
    ): Reply {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" RTSP/1.0\r\n")
        sb.append("CSeq: 1\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()

        val headerText = StringBuilder()
        while (true) {
            val c = input.read()
            check(c >= 0) { "connection closed while reading $method $path" }
            headerText.append(c.toChar())
            if (headerText.endsWith("\r\n\r\n")) break
        }
        val headers = headerText.toString()
        val status = headers.substringAfter(' ').substringBefore(' ').toInt()
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
        val payload = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(payload, read, length - read)
            check(n >= 0) { "body truncated" }
            read += n
        }
        return Reply(status, headers, payload)
    }

    private fun verifyTxt() {
        val encoded = Txt.encode(linkedMapOf("deviceid" to "AABB", "vv" to "2", "flag" to ""))
        // 1+8 | 1+4 | 1+4 = 19
        check(encoded.size == 24) { "unexpected TXT length ${encoded.size}" }
        check(encoded[0].toInt() == 13) { "bad TXT length prefix" }
        check(String(encoded, 1, 13, Charsets.US_ASCII) == "deviceid=AABB") { "bad TXT content" }
    }

    private fun verifyMirrorStream() {
        val aesKey = ByteArray(16) { it.toByte() }
        val ecdhSecret = ByteArray(32) { (0x40 + it).toByte() }
        // Deliberately > Long.MAX_VALUE: real senders use the full uint64 range.
        val streamId = 0xB4C1_9E1A_3F55_1234uL.toLong()

        val configLatch = CountDownLatch(1)
        val frameLatch = CountDownLatch(2)
        val spsRef = AtomicReference<ByteArray>()
        val ppsRef = AtomicReference<ByteArray>()
        val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<ByteArray, Long>>())
        val sizeRef = AtomicReference<Pair<Int, Int>>()

        val stream = MirrorStream(object : MirrorStream.VideoSink {
            override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
                spsRef.set(sps)
                ppsRef.set(pps)
                sizeRef.set(width to height)
                configLatch.countDown()
            }

            override fun onVideoFrame(data: ByteArray, ptsMicros: Long) {
                frames.add(data to ptsMicros)
                frameLatch.countDown()
            }

            override fun onVideoEnded() = Unit
        })

        val port = stream.start(streamId, aesKey, ecdhSecret)
        check(port > 0) { "mirror stream did not bind" }

        // Derive the same stream key independently, the way the protocol specifies.
        val eaes = Bytes.sha512(aesKey, ecdhSecret).copyOfRange(0, 16)
        val idText = java.lang.Long.toUnsignedString(streamId)
        val key = Bytes.sha512(Bytes.ascii("AirPlayStreamKey$idText"), eaes).copyOfRange(0, 16)
        val iv = Bytes.sha512(Bytes.ascii("AirPlayStreamIV$idText"), eaes).copyOfRange(0, 16)
        val ctr = AesCtr(key, iv)

        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1f, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
        val nal1 = ByteArray(31) { (0x60 + it).toByte() }
        val nal2 = ByteArray(17) { (0x30 + it).toByte() }
        val nal3 = ByteArray(45) { (0x11 * it + 3).toByte() }

        Socket("127.0.0.1", port).use { socket ->
            socket.tcpNoDelay = true
            val out: OutputStream = socket.getOutputStream()

            // --- config packet (unencrypted)
            val configPayload = ByteArray(maxOf(64, 8 + sps.size + 3 + pps.size))
            configPayload[0] = 1
            configPayload[1] = 0x64
            Bytes.putBe16(configPayload, 6, sps.size)
            System.arraycopy(sps, 0, configPayload, 8, sps.size)
            configPayload[8 + sps.size] = 1
            configPayload[8 + sps.size + 1] = 0
            configPayload[8 + sps.size + 2] = pps.size.toByte()
            System.arraycopy(pps, 0, configPayload, 8 + sps.size + 3, pps.size)
            Bytes.putLe32(configPayload, 56, java.lang.Float.floatToIntBits(1920f))
            Bytes.putLe32(configPayload, 60, java.lang.Float.floatToIntBits(1080f))
            sendPacket(out, configPayload, 1, 0)

            // --- first video packet: two NAL units
            val videoPayload = Bytes.concat(
                Bytes.be32(nal1.size), nal1,
                Bytes.be32(nal2.size), nal2,
            )
            sendPacket(out, ctr.process(videoPayload), 0, 1_000_000L)

            // --- second video packet: odd length, exercises keystream continuity
            val videoPayload2 = Bytes.concat(Bytes.be32(nal3.size), nal3)
            sendPacket(out, ctr.process(videoPayload2), 0, 1_016_666L)
        }

        check(configLatch.await(3, TimeUnit.SECONDS)) { "no video config delivered" }
        check(spsRef.get().contentEquals(sps)) { "SPS mismatch" }
        check(ppsRef.get().contentEquals(pps)) { "PPS mismatch" }
        check(sizeRef.get() == (1920 to 1080)) { "size mismatch: ${sizeRef.get()}" }

        check(frameLatch.await(3, TimeUnit.SECONDS)) { "video frames not delivered: ${frames.size}" }
        check(frames.size == 2) { "expected 2 frames, got ${frames.size}" }

        val expected1 = Bytes.concat(
            byteArrayOf(0, 0, 0, 1), nal1,
            byteArrayOf(0, 0, 0, 1), nal2,
        )
        check(frames[0].first.contentEquals(expected1)) {
            "frame 1 Annex-B mismatch: ${Bytes.hex(frames[0].first)}"
        }
        check(frames[0].second == 0L) { "frame 1 pts should be anchored to zero, got ${frames[0].second}" }
        check(kotlin.math.abs(frames[1].second - 16_666L) <= 2) { "frame 2 pts mismatch: ${frames[1].second}" }
        val wantedTail = Bytes.concat(byteArrayOf(0, 0, 0, 1), nal3)
        check(frames[1].first.contentEquals(wantedTail)) { "frame 2 mismatch" }

        stream.stop()
    }

    private fun sendPacket(out: OutputStream, payload: ByteArray, type: Int, ptsMicros: Long) {
        val header = ByteArray(128)
        Bytes.putLe32(header, 0, payload.size)
        Bytes.putLe16(header, 4, type)
        Bytes.putLe64(header, 8, ntp(ptsMicros))
        out.write(header)
        out.write(payload)
        out.flush()
        Thread.sleep(30)
    }

    /** Encodes microseconds since an arbitrary epoch as an AirPlay NTP timestamp. */
    private fun ntp(micros: Long): Long {
        val seconds = micros / 1_000_000L
        val fraction = ((micros % 1_000_000L) shl 32) / 1_000_000L
        return (seconds shl 32) or fraction
    }
}
