package com.airplaytv.core.fairplay

import com.airplaytv.core.crypto.RsaAppleKey
import com.airplaytv.core.util.Base64
import java.security.SecureRandom

/**
 * Self-tests for the FairPlay v2 receiver primitives and the RAOP RSA path.
 *
 * The live interop is covered by real macOS senders; these checks make sure a
 * refactor cannot silently break the tables, the record framing or the OAEP
 * padding.
 */
object FairPlayV2Verify {

    @JvmStatic
    fun run() {
        sapRoundTrip()
        setupRecord()
        rsaRoundTrip()
        base64()
    }

    /** Encrypting and decrypting a SAP must be lossless for every mode. */
    private fun sapRoundTrip() {
        val exchange = FairPlayV2()
        val random = SecureRandom()
        for (mode in 0..3) {
            val sap = ByteArray(128).also { random.nextBytes(it) }
            val cipher = ByteArray(128)
            exchange.encryptSap(mode, sap, cipher, 0)
            val back = ByteArray(128)
            exchange.decryptSap(mode, cipher, 0, back, 0)
            check(sap.contentEquals(back)) { "SAP round trip failed for mode $mode" }
        }
    }

    /** The exact 16-byte record macOS 27.2 sends must be accepted. */
    private fun setupRecord() {
        val request = byteArrayOf(
            0x46, 0x50, 0x4c, 0x59, // FPLY
            0x02, 0x01, 0x01, 0x00, // version 2, setup record
            0x00, 0x00, 0x00, 0x04, // payload length 4
            0x02, 0x00, 0x03, 0xbb.toByte(),
        )
        val reply = FairPlayV2().setup(request)
        check(reply != null && reply.size == 142) { "v2 setup did not answer" }
        check(reply!![4] == 2.toByte() && reply[6] == 2.toByte()) { "wrong reply header" }
        check(reply[12] == 2.toByte() && reply[13] == 3.toByte()) { "wrong reply mode" }
    }

    /** RSA/OAEP-SHA1 must round trip and reject malformed blocks. */
    private fun rsaRoundTrip() {
        val key = ByteArray(16) { (it * 7 + 3).toByte() }
        val seed = ByteArray(20) { (it + 11).toByte() }
        val cipher = RsaAppleKey.encryptForTest(key, seed)
        val back = RsaAppleKey.decryptOaep(cipher)
        check(back != null && back.contentEquals(key)) { "RSA/OAEP round trip failed" }
        check(RsaAppleKey.decryptOaep(ByteArray(255)) == null) { "short block accepted" }
    }

    private fun base64() {
        check(Base64.decode("Q29uZmlkZW50aWFs").contentEquals("Confidential".toByteArray())) {
            "base64 decode mismatch"
        }
        check(Base64.decode("Zg==").contentEquals(byteArrayOf('f'.code.toByte()))) {
            "base64 padding mismatch"
        }
    }
}
