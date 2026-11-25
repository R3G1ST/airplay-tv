package com.airplaytv.core.airplay

import com.airplaytv.core.crypto.AesCtr
import com.airplaytv.core.crypto.Ed25519
import com.airplaytv.core.crypto.KeyPairRaw
import com.airplaytv.core.crypto.X25519
import com.airplaytv.core.fairplay.FairPlayKeyExchange
import com.airplaytv.core.plist.PArray
import com.airplaytv.core.plist.PBool
import com.airplaytv.core.plist.PData
import com.airplaytv.core.plist.PDict
import com.airplaytv.core.plist.PInt
import com.airplaytv.core.plist.PReal
import com.airplaytv.core.plist.PStr
import com.airplaytv.core.plist.PVal
import com.airplaytv.core.plist.Plist
import com.airplaytv.core.plist.array
import com.airplaytv.core.plist.asDict
import com.airplaytv.core.plist.bool
import com.airplaytv.core.plist.data
import com.airplaytv.core.plist.dict
import com.airplaytv.core.plist.int
import com.airplaytv.core.plist.parr
import com.airplaytv.core.plist.pdict
import com.airplaytv.core.plist.real
import com.airplaytv.core.plist.str
import com.airplaytv.core.rtsp.HttpServer
import com.airplaytv.core.rtsp.Request
import com.airplaytv.core.rtsp.Response
import com.airplaytv.core.util.Bytes
import com.airplaytv.core.util.Log

/**
 * The AirPlay receiver: implements the RTSP/HTTP control channel, transient
 * pairing (AirPlay 2), FairPlay key exchange and the per-connection crypto that
 * protects the mirrored H.264 and AAC streams.
 *
 * The wire behaviour follows the reverse engineered protocol used by RPiPlay /
 * UxPlay / shairport-sync, which is what iOS and macOS actually speak.
 */
class AirPlayServer(
    private val config: Config,
    private val listener: Listener,
) {
    interface Listener {
        fun onSessionStarted(deviceName: String?)
        fun onSessionEnded()
        fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int)
        fun onVideoFrame(data: ByteArray, ptsMicros: Long)
        fun onAudioFrame(frame: ByteArray, ptsMicros: Long)
        fun onVolumeChanged(volumeDb: Float)

        /** Audio codec announced by the sender in the stream SETUP (ct) and its samples per frame. */
        fun onAudioCodec(codecType: Int, samplesPerFrame: Int) {}

        /** AirPlay video (Safari video, TV app "play on TV"): play this URL locally. */
        fun onPlayVideo(url: String, startPositionSeconds: Double, headers: Map<String, String>) {}

        /** Stop local video playback. */
        fun onStopVideo() {}

        /** 1.0 = play, 0.0 = pause. */
        fun onSetRate(rate: Double) {}

        /** Scrub request in seconds. */
        fun onSeek(positionSeconds: Double) {}

        /** Current (position, duration) of local video playback, in seconds. */
        fun videoPosition(): Pair<Double, Double> = 0.0 to 0.0

        /** Track metadata from the DMAP channel (Apple Music). */
        fun onTrackMetadata(title: String?, artist: String?, album: String?) {}

        /** Cover art (JPEG/PNG bytes) pushed by the sender. */
        fun onArtwork(bytes: ByteArray) {}

        /** DACP identity of the sender: enables the TV remote to control playback. */
        fun onDacpInfo(dacpId: String?, activeRemote: String?) {}

        /** Playback progress of the current track, in seconds. */
        fun onProgress(positionSeconds: Double, durationSeconds: Double) {}

        /** Track duration announced in the metadata, in seconds. */
        fun onTrackDuration(seconds: Double) {}
    }

    data class Config(
        val name: String,
        val deviceId: String, // 12 uppercase hex chars, e.g. "AABBCCDDEEFF"
        val port: Int = 7000,
        val model: String = "AppleTV3,2",
        val sourceVersion: String = "220.68",
        val displayWidth: Int = 1920,
        val displayHeight: Int = 1080,
        /** Frame duration in seconds, as Apple's /info expects it (1/60). */
        val refreshRate: Double = 1.0 / 60.0,
        /** Highest frame rate the sender may use. */
        val maxFps: Int = 60,
    )

    private val lock = Any()

    private var identity: KeyPairRaw = Ed25519.generate()

    /**
     * Pairing and FairPlay state are per sender device, not global: iOS and
     * macOS open several TCP connections (control, events, audio) and they all
     * share one pairing. Keeping it global let a second connection wipe the
     * keys of the first, which produced black screens on the first attempt.
     */
    private class ClientState {
        val fairPlay = FairPlayKeyExchange()
        var pairingStatus = PairingStatus.INITIAL
        var ecdhOursPrivate: ByteArray? = null
        var ecdhOursPublic: ByteArray? = null
        var ecdhTheirsPublic: ByteArray? = null
        var ecdhSecret: ByteArray? = null
        var edTheirsPublic: ByteArray? = null
    }

    private val clients = java.util.concurrent.ConcurrentHashMap<String, ClientState>()

    /**
     * One sender owns the screen. A different device that starts a session takes
     * it over, even when it skips pair-setup (a previously paired phone often
     * goes straight to pair-verify), which used to leave a stuck session alive.
     */
    private fun preemptIfNeeded(request: Request) {
        val ip = request.remoteAddress.hostAddress ?: "unknown"
        val previous = activeClientIp
        if (previous != null && previous != ip) {
            Log.i(TAG, "new sender $ip preempts $previous")
            stopStreams()
            synchronized(lock) {
                aesKey = null
                aesIv = null
            }
            // A takeover always ends the old cast. This must not depend on the
            // `streaming` flag: a TEARDOWN from the old sender (macOS sends one
            // whenever it restarts its audio channel) clears that flag while its
            // mirroring socket is still alive. Gating on it left the last video
            // frame frozen on screen and the new sender's music invisible.
            streaming = false
            senderName = null
            listener.onSessionEnded()
            try {
                http.closeConnectionsFrom(java.net.InetAddress.getByName(previous))
            } catch (_: Throwable) {
            }
        }
        activeClientIp = ip
    }

    private fun client(request: Request): ClientState {
        val key = request.remoteAddress.hostAddress ?: "unknown"
        return clients.getOrPut(key) { ClientState() }
    }

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null

    private var ntp: NtpClient? = null
    private var mirror: MirrorStream? = null
    private var audio: AudioStream? = null

    @Volatile private var streaming = false
    @Volatile private var activeClientIp: String? = null
    /** Human-readable sender name from the SETUP plist ("iPhone (Никита)"). */
    @Volatile private var senderName: String? = null
    private val sessionCounter = java.util.concurrent.atomic.AtomicLong(0)

    private enum class PairingStatus { INITIAL, SETUP, HANDSHAKE, FINISHED }

    private val http = HttpServer(config.port) { request, response -> handle(request, response) }

    val deviceId: String get() = config.deviceId

    /** Volume in dB the receiver is actually playing at (0 = maximum). */
    @Volatile private var reportedVolumeDb: Float = 0f

    fun setReportedVolumeDb(db: Float) {
        reportedVolumeDb = db
    }

    /** Drops the current mirroring socket; the sender starts a fresh session. */
    fun dropMirroring() {
        mirror?.stop()
        if (streaming) {
            streaming = false
            senderName = null
            listener.onSessionEnded()
        }
    }

    /** True while the RTSP/HTTP server accepts connections. */
    val isListening: Boolean get() = http.isListening
    val deviceName: String get() = config.name
    val port: Int get() = http.localPort

    /** Monotonic id of the current cast; changes on every RECORD. */
    fun sessionId(): Long = sessionCounter.get()

    /** How long ago the last audio packet arrived (MAX_VALUE when no audio session). */
    fun audioIdleMillis(): Long = audio?.millisSinceLastPacket() ?: Long.MAX_VALUE

    fun start() {
        identity = Ed25519.generate()
        mirror = MirrorStream(object : MirrorStream.VideoSink {
            override fun onVideoConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) =
                listener.onVideoConfig(sps, pps, width, height)

            override fun onVideoFrame(data: ByteArray, ptsMicros: Long) =
                listener.onVideoFrame(data, ptsMicros)

            override fun onVideoEnded() {
                // The mirroring socket closed: the cast is over, even when a
                // TEARDOWN already cleared `streaming`. Otherwise its last frame
                // stays frozen on screen forever.
                streaming = false
                senderName = null
                listener.onSessionEnded()
                // Drop the sender's control connections too, so its UI stops
                // showing the receiver as still connected.
                val sender = activeClientIp
                if (sender != null) {
                    try {
                        http.closeConnectionsFrom(java.net.InetAddress.getByName(sender))
                    } catch (_: Throwable) {
                    }
                }
            }
        })
        audio = AudioStream(object : AudioStream.AudioSink {
            override fun onAudioFrame(frame: ByteArray, ptsMicros: Long) =
                listener.onAudioFrame(frame, ptsMicros)

            override fun onAudioEnded() = Unit
        })
        http.start()
        Log.i(TAG, "AirPlay receiver \"${config.name}\" on port ${http.localPort}")
    }

    fun stop() {
        stopStreams()
        http.stop()
    }

    private fun stopStreams() {
        mirror?.stop()
        audio?.stop()
        ntp?.stop()
        ntp = null
    }

    // ---------------------------------------------------------------- mDNS data

    private fun macWithColons(): String =
        config.deviceId.chunked(2).joinToString(":")

    private val txtFeatures = "0x5A7FFEE6,0x0"

    fun airplayTxt(): Map<String, String> = linkedMapOf(
        "deviceid" to macWithColons(),
        "features" to txtFeatures,
        "flags" to "0x4",
        "model" to config.model,
        "pk" to Bytes.hex(identity.publicKey),
        "pi" to PI_UUID,
        "srcvers" to config.sourceVersion,
        "vv" to "2",
        "pw" to "false",
    )

    fun raopTxt(): Map<String, String> = linkedMapOf(
        "ch" to "2",
        "cn" to "3",
        "et" to "0,3,5",
        "vv" to "2",
        "ft" to "0x5A7FFEE6,0x0",
        "am" to config.model,
        "md" to "0,1,2",
        "rhd" to "5.6.0.0",
        "pw" to "false",
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
        "tp" to "UDP",
        "txtvers" to "1",
        "sf" to "0x4",
        "vs" to config.sourceVersion,
        "vn" to "65537",
        "pk" to Bytes.hex(identity.publicKey),
    )

    // ------------------------------------------------------------- RTSP routing

    private fun handle(request: Request, response: Response) {
        val path = request.url.substringBefore('?')
        when {
            request.method == "GET" && path == "/info" -> handleInfo(response)
            request.method == "POST" && path == "/pair-setup" -> handlePairSetup(request, response)
            request.method == "POST" && path == "/pair-verify" -> handlePairVerify(request, response)
            request.method == "POST" && path == "/fp-setup" -> handleFpSetup(request, response)
            request.method == "POST" && path == "/fp-setup2" -> handleFpSetup2(request, response)
            request.method == "POST" && path == "/feedback" -> Unit
            request.method == "POST" && path == "/audioMode" -> Unit
            request.method == "POST" && path == "/pair-pin-start" -> Unit
            request.method == "POST" && path == "/command" -> Unit
            request.method == "OPTIONS" ->
                response.header("Public", "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER")
            request.method == "SETUP" -> handleSetup(request, response)
            request.method == "RECORD" -> handleRecord(request, response)
            request.method == "POST" && path == "/play" -> handlePlay(request, response)
            request.method == "POST" && path == "/stop" -> handleStopVideo(response)
            request.method == "POST" && path == "/rate" -> handleRate(request, response)
            request.method == "POST" && path == "/scrub" -> handleScrub(request, response)
            request.method == "GET" && path == "/scrub" -> handleScrub(request, response)
            request.method == "GET" && path == "/playback-info" -> handlePlaybackInfo(response)
            request.method == "POST" && path == "/reverse" -> handleReverse(response)
            request.method == "POST" && path == "/setProperty" -> Unit
            request.method == "PUT" && path == "/setProperty" -> Unit
            request.method == "PUT" && path == "/getProperty" -> handlePlaybackInfo(response)
            request.method == "GET" && path == "/server-info" -> handleServerInfo(response)
            request.method == "POST" && path == "/getProperty" -> handlePlaybackInfo(response)
            request.method == "POST" && path == "/action" -> Unit
            request.method == "GET" && path == "/playback-info" -> handlePlaybackInfo(response)
            request.method == "TEARDOWN" -> handleTeardown(response)
            request.method == "FLUSH" -> Unit
            request.method == "GET_PARAMETER" -> handleGetParameter(request, response)
            request.method == "SET_PARAMETER" -> handleSetParameter(request, response)
            else -> {
                Log.w(TAG, "unhandled ${request.method} ${request.url}")
                response.status = 404
                response.reason = "Not Found"
            }
        }
    }

    // ------------------------------------------------------------------ /info

    private fun handleInfo(response: Response) {
        val root = PDict()
        root.map["txtAirPlay"] = PData(Txt.encode(airplayTxt()))
        root.map["features"] = PInt(FEATURES)
        root.map["name"] = PStr(config.name)
        root.map["pi"] = PStr(PI_UUID)
        root.map["vv"] = PInt(2)
        root.map["statusFlags"] = PInt(68)
        root.map["keepAliveLowPower"] = PBool(true)
        root.map["keepAliveSendStatsAsBody"] = PBool(true)
        root.map["sourceVersion"] = PStr(config.sourceVersion)
        root.map["pk"] = PData(identity.publicKey)
        root.map["deviceID"] = PStr(macWithColons())
        root.map["macAddress"] = PStr(macWithColons())
        root.map["model"] = PStr(config.model)

        val formats = PArray()
        for (type in listOf(100L, 101L)) {
            formats.items.add(
                pdict(
                    "type" to PInt(type),
                    "audioInputFormats" to PInt(67108860L),
                    "audioOutputFormats" to PInt(67108860L),
                )
            )
        }
        root.map["audioFormats"] = formats

        val latencies = PArray()
        for (type in listOf(100L, 101L)) {
            latencies.items.add(
                pdict(
                    "outputLatencyMicros" to PInt(0),
                    "type" to PInt(type),
                    "audioType" to PStr("default"),
                    "inputLatencyMicros" to PInt(0),
                )
            )
        }
        root.map["audioLatencies"] = latencies

        val displays = PArray()
        displays.items.add(
            pdict(
                "uuid" to PStr(DISPLAY_UUID),
                "widthPhysical" to PInt(0),
                "heightPhysical" to PInt(0),
                "width" to PInt(config.displayWidth.toLong()),
                "height" to PInt(config.displayHeight.toLong()),
                "widthPixels" to PInt(config.displayWidth.toLong()),
                "heightPixels" to PInt(config.displayHeight.toLong()),
                "rotation" to PBool(false),
                "refreshRate" to PReal(config.refreshRate),
                // "overscanned" makes the sender shrink the picture to a ~94 %
                // safe area (we measured 1804x1016 instead of 1920x1080), which
                // then gets upscaled again and looks soft. The TV has no overscan
                // problem, so ask for the full frame.
                "overscanned" to PBool(false),
                "maxFPS" to PInt(config.maxFps.toLong()),
                "features" to PInt(14),
            )
        )
        root.map["displays"] = displays

        response.header("Content-Type", "application/x-apple-binary-plist")
        response.body = Plist.encodeBinary(root)
    }

    // ------------------------------------------------------------ pair-setup

    private fun handlePairSetup(request: Request, response: Response) {
        if (request.body.size != 32) {
            Log.w(TAG, "pair-setup with unexpected body ${request.body.size}")
            response.status = 400
            response.reason = "Bad Request"
            return
        }
        preemptIfNeeded(request)
        val st = client(request)
        synchronized(lock) {
            st.pairingStatus = PairingStatus.SETUP
            st.ecdhSecret = null
            st.ecdhOursPrivate = null
            st.ecdhOursPublic = null
            st.ecdhTheirsPublic = null
            st.edTheirsPublic = null
        }
        response.header("Content-Type", "application/octet-stream")
        response.body = identity.publicKey
        Log.i(TAG, "pair-setup: sent Ed25519 public key")
    }

    // ----------------------------------------------------------- pair-verify

    private fun handlePairVerify(request: Request, response: Response) {
        val data = request.body
        if (data.size < 4) {
            response.status = 400
            return
        }
        when (Bytes.u8(data, 0)) {
            1 -> {
                if (data.size != 4 + 32 + 32) {
                    response.status = 400
                    return
                }
                val clientEcdh = data.copyOfRange(4, 36)
                val clientEd = data.copyOfRange(36, 68)
                val ours = X25519.generate()
                val secret = X25519.sharedSecret(ours.privateKey, clientEcdh)
                val st = client(request)
                synchronized(lock) {
                    st.ecdhOursPrivate = ours.privateKey
                    st.ecdhOursPublic = ours.publicKey
                    st.ecdhTheirsPublic = clientEcdh
                    st.ecdhSecret = secret
                    st.edTheirsPublic = clientEd
                    st.pairingStatus = PairingStatus.HANDSHAKE
                }
                val message = Bytes.concat(ours.publicKey, clientEcdh)
                val signature = Ed25519.sign(identity.privateKey, message)
                val aes = pairingCipher(secret)
                val encrypted = aes.process(signature)
                response.header("Content-Type", "application/octet-stream")
                response.body = Bytes.concat(ours.publicKey, encrypted)
                Log.i(TAG, "pair-verify: step 1 done")
            }

            0 -> {
                if (data.size != 4 + 64) {
                    response.status = 400
                    return
                }
                val st = client(request)
                var secret: ByteArray? = null
                var oursPublic: ByteArray? = null
                var theirsPublic: ByteArray? = null
                var theirsEd: ByteArray? = null
                synchronized(lock) {
                    secret = st.ecdhSecret
                    oursPublic = st.ecdhOursPublic
                    theirsPublic = st.ecdhTheirsPublic
                    theirsEd = st.edTheirsPublic
                }
                val secretKey = secret
                val oursKey = oursPublic
                val theirsKey = theirsPublic
                val edKey = theirsEd
                if (secretKey == null || oursKey == null || theirsKey == null || edKey == null) {
                    response.status = 400
                    return
                }
                val encrypted = data.copyOfRange(4, 68)
                val aes = pairingCipher(secretKey)
                // The sender encrypts its signature after a 64 byte preamble of keystream.
                aes.process(ByteArray(64))
                val signature = aes.process(encrypted)
                val message = Bytes.concat(theirsKey, oursKey)
                if (!Ed25519.verify(edKey, message, signature)) {
                    Log.w(TAG, "pair-verify: signature mismatch")
                    response.status = 403
                    response.reason = "Forbidden"
                    response.closeConnection = true
                    return
                }
                synchronized(lock) { st.pairingStatus = PairingStatus.FINISHED }
                Log.i(TAG, "pair-verify: complete, session trusted")
            }

            else -> response.status = 400
        }
    }

    private fun pairingCipher(secret: ByteArray): AesCtr {
        val key = Bytes.sha512(Bytes.ascii("Pair-Verify-AES-Key"), secret).copyOfRange(0, 16)
        val iv = Bytes.sha512(Bytes.ascii("Pair-Verify-AES-IV"), secret).copyOfRange(0, 16)
        return AesCtr(key, iv)
    }

    // -------------------------------------------------------------- fp-setup

    private fun handleFpSetup(request: Request, response: Response) {
        val data = request.body
        val fp = client(request).fairPlay
        when (data.size) {
            16 -> {
                val reply = fp.setup(data)
                if (reply == null) {
                    response.status = 400
                    return
                }
                response.header("Content-Type", "application/octet-stream")
                response.body = reply
                Log.i(TAG, "fp-setup: mode ${data[14].toInt() and 0xff} acknowledged")
            }

            164 -> {
                val reply = fp.handshake(data)
                if (reply == null) {
                    response.status = 400
                    return
                }
                response.header("Content-Type", "application/octet-stream")
                response.body = reply
                Log.i(TAG, "fp-setup: handshake complete")
            }

            else -> {
                Log.w(TAG, "fp-setup with unexpected body ${data.size}")
                response.status = 400
            }
        }
    }

    /**
     * `/fp-setup2` is the FairPlay Streaming "auth setup" used by AirPlay 2
     * video. Real DRM needs Apple-signed keys we cannot have, but senders also
     * issue it for unprotected HLS; answering with the same FPLY handshake
     * shape lets those sessions continue to `/play`.
     */
    private fun handleFpSetup2(request: Request, response: Response) {
        val body = request.body
        Log.i(TAG, "fp-setup2: ${body.size}b, answering with FPLY stub")
        val header = byteArrayOf(0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x14)
        val suffixLen = minOf(20, body.size)
        val reply = ByteArray(12 + suffixLen)
        System.arraycopy(header, 0, reply, 0, 12)
        if (suffixLen > 0) System.arraycopy(body, body.size - suffixLen, reply, 12, suffixLen)
        response.header("Content-Type", "application/octet-stream")
        response.body = reply
    }

    // ----------------------------------------------------------------- SETUP

    private fun handleSetup(request: Request, response: Response) {
        preemptIfNeeded(request)
        val root = try {
            Plist.parse(request.body)
        } catch (e: Throwable) {
            Log.e(TAG, "SETUP: cannot parse plist", e)
            response.status = 400
            return
        } as? PDict ?: run {
            response.status = 400
            return
        }

        val out = PDict()
        val dacpId = request.header("DACP-ID")
        val activeRemote = request.header("Active-Remote")
        if (dacpId != null && activeRemote != null) {
            Log.i(TAG, "DACP: id=$dacpId token=$activeRemote")
            listener.onDacpInfo(dacpId, activeRemote)
        }
        Log.i(TAG, "SETUP request: ${root.toString().take(900)}")
        root.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            senderName = it
            Log.i(TAG, "sender name: $it (model=${root.str("model")})")
        }

        val eiv = root.data("eiv")
        val ekey = root.data("ekey")
        if (eiv != null && ekey != null) {
            if (eiv.size < 16) {
                Log.w(TAG, "SETUP: short eiv ${eiv.size}")
            } else {
                val key = client(request).fairPlay.decrypt(ekey)
                if (key == null) {
                    Log.e(TAG, "SETUP: FairPlay key decryption failed")
                } else {
                    synchronized(lock) {
                        aesKey = key
                        aesIv = eiv.copyOf(16)
                    }
                    Log.i(TAG, "SETUP: got stream key ${Bytes.hex(key)}")
                }
            }

            val timingPort = root.int("timingPort")
            if (timingPort != null && timingPort > 0) {
                val client = NtpClient()
                client.start(request.remoteAddress, timingPort.toInt())
                ntp = client
                out.map["timingPort"] = PInt(client.localPort.toLong())
            }
            out.map["eventPort"] = PInt(0L)
            Log.i(TAG, "SETUP: timing/event ports announced (eiv=${eiv.size}b ekey=${ekey.size}b)")
        }

        val streams = root.array("streams")
        if (streams != null) {
            val outStreams = PArray()
            for (item in streams.items) {
                val stream = item.asDict()
                val type = stream.int("type") ?: continue
                var keyCopy: ByteArray? = null
                var ivCopy: ByteArray? = null
                var secret: ByteArray? = null
                val st = client(request)
                synchronized(lock) {
                    keyCopy = aesKey
                    ivCopy = aesIv
                    secret = st.ecdhSecret
                }
                val streamKey = keyCopy
                val streamIv = ivCopy
                val streamSecret = secret
                if (streamKey == null || streamIv == null || streamSecret == null) {
                    Log.e(TAG, "SETUP: stream requested before keys are ready (type=$type)")
                    response.status = 400
                    return
                }
                when (type) {
                    110L -> {
                        val id = stream.int("streamConnectionID") ?: 0L
                        val port = mirror?.start(id, streamKey, streamSecret) ?: 0
                        outStreams.items.add(
                            pdict(
                                "dataPort" to PInt(port.toLong()),
                                "type" to PInt(110),
                            )
                        )
                        Log.i(TAG, "SETUP: mirroring stream on port $port")
                    }

                    96L -> {
                        val ct = stream.int("ct")?.toInt() ?: 8
                        val spf = stream.int("spf")?.toInt() ?: 352
                        listener.onAudioCodec(ct, spf)
                        val ok = audio?.start(streamKey, streamIv, streamSecret, ct) ?: false
                        outStreams.items.add(
                            pdict(
                                "dataPort" to PInt((audio?.dataPort ?: 0).toLong()),
                                "controlPort" to PInt((audio?.controlPort ?: 0).toLong()),
                                "type" to PInt(96),
                            )
                        )
                        Log.i(TAG, "SETUP: audio stream ok=$ok dataPort=${audio?.dataPort} controlPort=${audio?.controlPort}")
                    }

                    else -> Log.w(TAG, "SETUP: unsupported stream type $type")
                }
            }
            out.map["streams"] = outStreams
        }

        response.header("Content-Type", "application/x-apple-binary-plist")
        response.body = Plist.encodeBinary(out)
    }

    // ------------------------------------------------------------- AirPlay video

    @Volatile private var videoActive = false

    /**
     * Safari and TV apps "cast" a video by sending its URL instead of a screen
     * stream. We hand the URL to the local player and keep answering the
     * transport questions (position, duration, rate) the sender asks.
     */
    private fun handlePlay(request: Request, response: Response) {
        val plist = try {
            Plist.parse(request.body)
        } catch (e: Throwable) {
            // Senders routinely emit XML plists with unescaped '&' inside the
            // media URL, which a strict XML parser rejects. Fall through to the
            // tolerant extractor below instead of failing the session.
            Log.d(TAG, "PLAY: strict plist parse failed (${e.message}), using tolerant parser")
            null
        } as? PDict

        var url = plist?.str("Content-Location")
        var start = plist?.real("Start-Position") ?: 0.0
        if (url.isNullOrBlank()) {
            val text = request.body.toString(Charsets.UTF_8)
            if (text.isNotBlank() && !text.startsWith("bplist")) {
                Log.d(TAG, "PLAY body (${request.body.size}b): ${text.take(400).replace("\n", " ")}")
            }
            url = extractString(text, "Content-Location")
            start = extractDouble(text, "Start-Position") ?: start
        }
        if (url.isNullOrBlank()) {
            Log.w(TAG, "PLAY without Content-Location")
            response.status = 400
            response.reason = "Bad Request"
            return
        }
        val headers = LinkedHashMap<String, String>()
        for (key in listOf("User-Agent", "X-Apple-ProtocolVersion")) {
            (plist?.str(key) ?: extractString(request.body.toString(Charsets.UTF_8), key))
                ?.let { headers[key] = it }
        }
        videoActive = true
        Log.i(TAG, "PLAY $url (start ${"%.2f".format(java.util.Locale.US, start)}s)")
        listener.onPlayVideo(url, start, headers)
        response.header("Content-Type", "application/x-apple-binary-plist")
        response.body = Plist.encodeBinary(pdict("Start-Position" to PReal(start)))
    }

    /** Reads `<key>name</key><string>value</string>`, tolerating broken XML. */
    private fun extractString(text: String, name: String): String? {
        val keyFirst = Regex(
            "<key>\\s*" + Regex.escape(name) + "\\s*</key>\\s*<string>(.*?)</string>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(text)?.groupValues?.get(1)
        if (keyFirst != null) return unescapeXml(keyFirst).trim()
        val plain = Regex(
            "(?m)^\\s*" + Regex.escape(name) + "\\s*[:=]\\s*(\\S.*?)\\s*$",
        ).find(text)?.groupValues?.get(1)
        return plain?.trim()
    }

    private fun extractDouble(text: String, name: String): Double? {
        val m = Regex(
            "<key>\\s*" + Regex.escape(name) + "\\s*</key>\\s*<(?:real|integer)>([-0-9.eE+]+)</(?:real|integer)>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(text)
        return m?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun unescapeXml(value: String): String {
        var out = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        out = Regex("&#(x?)([0-9a-fA-F]+);").replace(out) { m ->
            val code = if (m.groupValues[1].isNotEmpty())
                m.groupValues[2].toIntOrNull(16)
            else
                m.groupValues[2].toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        return out.replace("&amp;", "&")
    }

    /**
     * Minimal DMAP reader: the sender nests "title"/"artist"/"album" fields in
     * tagged containers. A tolerant scan for the four known tags is enough for
     * a now-playing screen and is robust against layout variations.
     */
    private fun parseDmap(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val known = setOf("minm", "asar", "asal", "cmmt", "asgn", "astm")
        var i = 0
        while (i + 8 <= data.size) {
            val tag = String(data, i, 4, Charsets.US_ASCII)
            if (tag in known) {
                val len = Bytes.be32(data, i + 4)
                if (tag == "astm" && len == 4 && i + 12 <= data.size) {
                    out["astm"] = Bytes.be32(data, i + 8).toString()
                    i += 12
                    continue
                }
                if (len in 1..4096 && i + 8 + len <= data.size) {
                    val value = String(data, i + 8, len, Charsets.UTF_8)
                    if (value.all { it.code >= 0x20 || it == '\n' || it == '\t' }) {
                        out[tag] = value
                        i += 8 + len
                        continue
                    }
                }
            }
            i++
        }
        return out
    }

    private fun handleServerInfo(response: Response) {
        val info = pdict(
            "deviceID" to PStr(config.deviceId),
            "macAddress" to PStr(macWithColons()),
            "name" to PStr(config.name),
            "model" to PStr(config.model),
            "protocolVersion" to PStr("1.0"),
            "sourceVersion" to PStr(config.sourceVersion),
            "features" to PInt(FEATURES),
        )
        response.header("Content-Type", "application/x-apple-binary-plist")
        response.body = Plist.encodeBinary(info)
    }

    private fun handleStopVideo(response: Response) {
        Log.i(TAG, "STOP video")
        videoActive = false
        listener.onStopVideo()
        response.header("Content-Type", "text/parameters")
    }

    private fun handleRate(request: Request, response: Response) {
        val rate = parseRate(request.body) ?: 1.0
        Log.i(TAG, "RATE $rate")
        listener.onSetRate(rate)
        response.header("Content-Type", "text/parameters")
    }

    private fun parseRate(body: ByteArray): Double? {
        val text = body.toString(Charsets.UTF_8)
        if (text.isBlank()) return null
        if (text.trimStart().startsWith("bplist")) {
            val dict = try {
                Plist.parseBinary(body) as? PDict
            } catch (_: Throwable) {
                null
            }
            return dict?.real("rate")
        }
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("rate:")) return t.substringAfter(':').trim().toDoubleOrNull()
            return t.toDoubleOrNull()
        }
        return null
    }

    private fun handleScrub(request: Request, response: Response) {
        val text = request.body.toString(Charsets.UTF_8)
        val requested = Regex("position:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("^\\s*([0-9.]+)\\s*$").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (requested != null) {
            Log.i(TAG, "SCRUB ${"%.2f".format(java.util.Locale.US, requested)}")
            listener.onSeek(requested)
        }
        val (position, duration) = listener.videoPosition()
        response.header("Content-Type", "text/parameters")
        response.body = ("duration: %.3f\r\nposition: %.3f\r\n")
            .format(java.util.Locale.US, duration, position)
            .toByteArray(Charsets.US_ASCII)
    }

    private fun handlePlaybackInfo(response: Response) {
        val (position, duration) = listener.videoPosition()
        val info = pdict(
            "duration" to PReal(duration),
            "position" to PReal(position),
            "rate" to PReal(if (videoActive) 1.0 else 0.0),
            "readyToPlay" to PBool(videoActive),
            "playbackBufferEmpty" to PBool(false),
            "playbackLikelyToKeepUp" to PBool(true),
            "playbackBufferFull" to PBool(true),
        )
        response.header("Content-Type", "application/x-apple-binary-plist")
        response.body = Plist.encodeBinary(info)
    }

    /**
     * `/reverse` is a long-poll channel used by the sender to receive events.
     * We answer with an empty plist: the important part is that the sender gets
     * a 200 instead of retrying forever.
     */
    private fun handleReverse(response: Response) {
        response.header("Content-Type", "application/x-apple-binary-plist")
        response.body = ByteArray(0)
    }

    // ---------------------------------------------------------------- RECORD

    private fun handleRecord(request: Request, response: Response) {
        preemptIfNeeded(request)
        response.header("Audio-Latency", "11025")
        response.header("Audio-Jack-Status", "connected; type=analog")
        sessionCounter.incrementAndGet()
        if (!streaming) {
            streaming = true
            listener.onSessionStarted(senderName)
        }
        Log.i(TAG, "RECORD: streaming started")
    }

    private fun handleTeardown(response: Response) {
        Log.i(TAG, "TEARDOWN")
        // macOS sends TEARDOWN when it restarts just the audio channel, while
        // the mirroring socket keeps running. Killing everything here made the
        // picture disappear two seconds after it appeared.
        audio?.stop()
        ntp?.stop()
        ntp = null
        // Keep the phase, the keys and the mirror socket: the cast is over only
        // when the video connection closes (MirrorStream.onVideoEnded).
        // Keep the session keys: the sender may resume with a SETUP that only
        // carries streams (Apple Music does exactly this on pause/resume).
        streaming = false
    }

    // ------------------------------------------------------- GET/SET_PARAMETER

    private fun handleGetParameter(request: Request, response: Response) {
        val contentType = request.header("Content-Type")
        if (contentType == "text/parameters") {
            val text = request.body.toString(Charsets.US_ASCII)
            if (text.lineSequence().any { it.trim() == "volume" }) {
                // This is how a sender asks "how loud are you really?". It used
                // to answer a hardcoded 0 dB, so the iPhone slider showed maximum
                // while the TV played at the remembered (lower) level.
                response.header("Content-Type", "text/parameters")
                val body = java.lang.String.format(
                    java.util.Locale.US, "volume: %.1f\r\n", reportedVolumeDb,
                )
                response.body = body.toByteArray(Charsets.US_ASCII)
                Log.i(TAG, "GET_PARAMETER volume -> ${reportedVolumeDb}dB")
            }
        }
    }

    private fun handleSetParameter(request: Request, response: Response) {
        val contentType = request.header("Content-Type")
        if (contentType == "application/x-dmap-tagged") {
            val meta = parseDmap(request.body)
            if (meta["minm"] == null && meta["asar"] == null && meta["asal"] == null) {
                val head = request.body.copyOf(minOf(64, request.body.size))
                Log.i(TAG, "dmap without known tags: ${request.body.size}b ${Bytes.hex(head)}")
            }
            listener.onTrackMetadata(meta["minm"], meta["asar"], meta["asal"])
            meta["astm"]?.toDoubleOrNull()?.let {
                Log.i(TAG, "metadata duration astm=${it.toLong()}ms")
                listener.onTrackDuration(it / 1000.0)
            }
        } else if (contentType == "image/jpeg" || contentType == "image/png") {
            if (request.body.isNotEmpty()) listener.onArtwork(request.body)
        }
        if (contentType == "text/parameters") {
            val text = request.body.toString(Charsets.US_ASCII)
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("volume:") -> {
                        val volume = trimmed.substringAfter(':').trim().toFloatOrNull()
                        if (volume != null) listener.onVolumeChanged(volume)
                    }
                    trimmed.startsWith("progress:") -> {
                        // "progress: start/current/end" in RTP frames (44100 Hz),
                        // some senders use seconds instead.
                        val parts = trimmed.substringAfter(':').trim().split('/')
                        if (parts.size == 3) {
                            val start = parts[0].trim().toDoubleOrNull()
                            val current = parts[1].trim().toDoubleOrNull()
                            val end = parts[2].trim().toDoubleOrNull()
                            if (start != null && current != null && end != null) {
                                val rate = if (end > 20_000.0) 44100.0 else 1.0
                                val duration = ((end - start) / rate).coerceAtLeast(0.0)
                                val position = ((current - start) / rate).coerceIn(0.0, maxOf(duration, 0.0))
                                Log.i(TAG, "progress raw ${start.toLong()}/${current.toLong()}/${end.toLong()} -> ${"%.1f".format(java.util.Locale.US, position)}s / ${"%.1f".format(java.util.Locale.US, duration)}s")
                                listener.onProgress(position, duration)
                            }
                        }
                    }
                }
            }
        }
        response.header("Content-Type", "text/parameters")
    }

    companion object {
        private const val TAG = "AirPlayServer"

        /**
         * Feature flags. Only the low 32 bits are advertised: bit 27 tells the
         * sender it may use the legacy (AirPlay 1) pairing + streaming flow,
         * which is what this receiver implements. Advertising the AirPlay 2
         * high word makes iOS/macOS pick buffered audio and AirPlay 2 video,
         * which are different protocols entirely.
         */
        private const val FEATURES = 0x5A7FFEE6L
        const val PI_UUID = "2e388006-13ba-4041-9a67-25dd4a43d536"
        const val DISPLAY_UUID = "e0ff8a27-6738-3d56-8a16-cc53aacee925"
    }
}
