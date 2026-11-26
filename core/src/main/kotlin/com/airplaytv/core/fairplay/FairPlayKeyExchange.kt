package com.airplaytv.core.fairplay

/**
 * FairPlay key-exchange state machine used during AirPlay mirroring setup.
 *
 * The two `POST /fp-setup` requests and the `ekey` delivered later in the
 * AirPlay `SETUP` plist are handled here:
 *
 * 1. [setup] answers the 16-byte first request with one of four canned 142-byte
 *    FPLY replies (selected by the request's mode byte).
 * 2. [handshake] answers the 164-byte second request with a 32-byte reply and
 *    remembers that request, which [Playfair] needs to derive the stream key.
 * 3. [decrypt] turns the 72-byte `ekey` into the 16-byte AES key.
 *
 * Instances are mutable and not thread-safe; use one per session.
 */
class FairPlayKeyExchange {

    private var handshakeMessage: ByteArray? = null

    /** 2 = the newer selector-2 handshake, 3 = the classic Playfair one. */
    private var version = 0
    private var v2: FairPlayV2? = null

    /**
     * Handles the first `fp-setup` request.
     *
     * @param request16 the 16-byte request body
     * @return a 142-byte FPLY reply, or `null` when the version or the mode
     *   byte (`request[14]`) is unsupported
     */
    fun setup(request16: ByteArray): ByteArray? {
        if (request16.size != 16) return null
        return when (request16[4].toInt() and 0xff) {
            2 -> {
                // macOS 26/27 uses FairPlay v2 for audio and video while iOS
                // still speaks v3. Both paths must coexist per connection.
                val exchange = v2 ?: FairPlayV2().also { v2 = it }
                val reply = exchange.setup(request16)
                if (reply != null) {
                    handshakeMessage = null
                    version = 2
                }
                reply
            }

            3 -> {
                val mode = request16[14].toInt() and 0xff
                if (mode !in REPLY_MESSAGES.indices) return null
                handshakeMessage = null
                version = 3
                REPLY_MESSAGES[mode].copyOf()
            }

            else -> null
        }
    }

    /**
     * Handles the second `fp-setup` request. On success the request is retained
     * so that [decrypt] can use it.
     *
     * @param request164 the 164-byte FPLY request body
     * @return the 32-byte reply (`FPLY` header followed by the last 20 request
     *   bytes), or `null` when the version or length is unsupported
     */
    fun handshake(request164: ByteArray): ByteArray? {
        if (version == 2) return v2?.handshake(request164)
        if (request164.size != Playfair.SETUP_MESSAGE_SIZE) return null
        if (request164[4] != 0x03.toByte()) return null
        handshakeMessage = request164.copyOf()
        val response = ByteArray(32)
        System.arraycopy(FP_HEADER, 0, response, 0, FP_HEADER.size)
        System.arraycopy(request164, 144, response, 12, 20)
        return response
    }

    /**
     * Decrypts the AirPlay `ekey` using the message remembered by [handshake].
     *
     * @param encryptedKey72 the 72-byte `ekey` from the SETUP plist
     * @return the 16-byte AES key, or `null` if [handshake] has not succeeded or
     *   the key length is wrong
     */
    fun decrypt(encryptedKey72: ByteArray): ByteArray? {
        if (version == 2) return v2?.decryptKey(encryptedKey72)
        val message = handshakeMessage ?: return null
        if (encryptedKey72.size != Playfair.ENCRYPTED_KEY_SIZE) return null
        return Playfair.decryptAesKey(message, encryptedKey72)
    }

    /** Forgets any remembered handshake message. */
    fun reset() {
        handshakeMessage = null
        v2 = null
        version = 0
    }

    private companion object {
        /** 12-byte `FPLY` reply header for the handshake response. */
        val FP_HEADER: ByteArray = hex("46504c590301040000000014")

        /** Canned replies for the four supported first-request modes. */
        val REPLY_MESSAGES: Array<ByteArray> = arrayOf(
            hex(
                "46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d8" +
                    "2737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed09" +
                    "5adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44" +
                    "c78955e49d8155009549c4e2f7a3f6d5ba"
            ),
            hex(
                "46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd9" +
                    "5ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665" +
                    "a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898" +
                    "b80227721988571650942ad946688a"
            ),
            hex(
                "46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f6" +
                    "38ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a4575559" +
                    "10af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d" +
                    "877353ddb99adc8e07006e56f850ce"
            ),
            hex(
                "46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb" +
                    "97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c6" +
                    "78325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233" +
                    "729c227d820c999445d89246c8c359"
            ),
        )

        fun hex(text: String): ByteArray {
            require(text.length % 2 == 0) { "odd hex string length" }
            return ByteArray(text.length / 2) { i ->
                val hi = Character.digit(text[i * 2], 16)
                val lo = Character.digit(text[i * 2 + 1], 16)
                require(hi >= 0 && lo >= 0) { "invalid hex string" }
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
