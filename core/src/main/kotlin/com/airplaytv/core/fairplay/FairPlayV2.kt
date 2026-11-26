package com.airplaytv.core.fairplay

import com.airplaytv.core.crypto.AesCbc
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FairPlay "selector 2" (FPLY record version 2) receiver primitives.
 *
 * Newer macOS releases use this handshake for AirPlay audio and video, while
 * iOS still uses version 3 (see [FairPlayKeyExchange]). The transform below is
 * the algebraically normalised representation published by UxPlay (LGPL): it
 * reproduces Apple CoreFP input/output traces without Apple's white-box tables.
 *
 * Instances are mutable and not thread-safe; use one per sender connection.
 */
internal class FairPlayV2 {

    private var receiverSap: ByteArray = ByteArray(0)
    private var senderSap: ByteArray = ByteArray(0)
    private var mode: Int = -1
    private var hasSetup = false
    private var hasHandshake = false

    /** Handles the 16-byte setup record and returns the 142-byte answer. */
    fun setup(request: ByteArray): ByteArray? {
        hasSetup = false
        hasHandshake = false
        senderSap = ByteArray(0)
        if (!validRecord(request, 1, 4)) return null
        if ((request[12].toInt() and 0xff) != 2) return null
        if ((request[13].toInt() and 0xff) != 0) return null
        val m = request[14].toInt() and 0xff
        if (m >= 4) return null
        if ((request[15].toInt() and 0xff) != 0xbb) return null

        val sap = ByteArray(128)
        SecureRandom().nextBytes(sap)
        sap[1] = 1
        receiverSap = sap
        mode = m

        val response = ByteArray(142)
        recordHeader(response, 2, 130)
        response[12] = 2
        response[13] = m.toByte()
        encryptSap(m, sap, response, 14)
        hasSetup = true
        return response
    }

    /** Handles the 164-byte handshake record and returns the 32-byte answer. */
    fun handshake(request: ByteArray): ByteArray? {
        hasHandshake = false
        senderSap = ByteArray(0)
        if (!hasSetup) return null
        if (!validRecord(request, 3, 152)) return null
        if ((request[12].toInt() and 0xff) != mode) return null
        if ((request[13].toInt() and 0xff) != 0x8f) return null
        if ((request[14].toInt() and 0xff) != 0x1a) return null
        if ((request[15].toInt() and 0xff) != 0x9c) return null

        val sap = ByteArray(128)
        decryptSap(mode, request, 16, sap, 0)
        senderSap = sap
        val expected = confirmation(sap, receiverSap)
        for (i in 0 until 20) {
            if (expected[i] != request[144 + i]) {
                senderSap = ByteArray(0)
                return null
            }
        }
        val response = ByteArray(32)
        recordHeader(response, 4, 20)
        System.arraycopy(expected, 0, response, 12, 20)
        hasHandshake = true
        return response
    }

    /** Unwraps the 72-byte `ekey` from SETUP into the raw 16-byte AES key. */
    fun decryptKey(encrypted: ByteArray): ByteArray? {
        if (!hasHandshake || senderSap.isEmpty()) return null
        if (encrypted.size != 72) return null
        if (be32(encrypted, 0) != 0x46504c59) return null // "FPLY"
        if ((encrypted[4].toInt() and 0xff) != 1) return null
        if ((encrypted[5].toInt() and 0xff) != 2) return null
        if ((encrypted[6].toInt() and 0xff) != 1) return null
        if ((encrypted[7].toInt() and 0xff) != 0) return null
        if (be32(encrypted, 8) != 60) return null
        if (be32(encrypted, 12) != 0) return null
        if (be32(encrypted, 32) != 16) return null

        val wrappingKey = md5(WRAPPING_PREFIX, senderSap, receiverSap, WRAPPING_SUFFIX)
        val recovered = try {
            AesCbc.decrypt(wrappingKey, ByteArray(16), encrypted.copyOfRange(56, 72))
        } catch (_: Throwable) {
            return null
        }
        for (i in 0 until 16) {
            recovered[i] = (recovered[i].toInt() xor (encrypted[16 + i].toInt() and 0xff)).toByte()
        }

        val macKey = sha1(MAC_PREFIX, senderSap, receiverSap, MAC_SUFFIX)
        val authenticated = ByteArray(52)
        System.arraycopy(encrypted, 0, authenticated, 0, 36)
        System.arraycopy(recovered, 0, authenticated, 36, 16)
        val expected = hmacSha1(macKey, authenticated)
        for (i in 0 until 20) {
            if (expected[i] != encrypted[36 + i]) return null
        }
        return recovered
    }

    // ------------------------------------------------------------- crypto

    internal fun encryptSap(mode: Int, plaintext: ByteArray, out: ByteArray, outOffset: Int) {
        val keys = roundKeys[mode]
        val previous = IntArray(16)
        for (block in 0 until 8) {
            val state = IntArray(16)
            for (i in 0 until 16) {
                var value = plaintext[block * 16 + i].toInt() and 0xff
                value = value xor INPUT_MASKS[mode][i]
                if (block > 0) value = value xor previous[i] xor CHAIN_MASKS[mode][i]
                state[i] = value
            }
            encryptBlock(state, keys)
            for (i in 0 until 16) previous[i] = state[i]
            for (i in 0 until 16) {
                out[outOffset + block * 16 + i] = (state[i] xor OUTPUT_MASKS[mode][i]).toByte()
            }
        }
    }

    internal fun decryptSap(mode: Int, encrypted: ByteArray, inOffset: Int, out: ByteArray, outOffset: Int) {
        val keys = roundKeys[mode]
        val previous = IntArray(16)
        for (block in 0 until 8) {
            val state = IntArray(16)
            for (i in 0 until 16) {
                state[i] = (encrypted[inOffset + block * 16 + i].toInt() and 0xff) xor OUTPUT_MASKS[mode][i]
            }
            val current = state.copyOf()
            decryptBlock(state, keys)
            for (i in 0 until 16) {
                var value = state[i] xor INPUT_MASKS[mode][i]
                if (block > 0) value = value xor previous[i] xor CHAIN_MASKS[mode][i]
                out[outOffset + block * 16 + i] = value.toByte()
            }
            for (i in 0 until 16) previous[i] = current[i]
        }
    }

    private fun encryptBlock(state: IntArray, keys: Array<IntArray>) {
        xorKey(state, keys[0])
        for (i in 0 until 16) state[i] = SBOX[state[i]]
        shiftRows(state)
        for (round in 1 until 10) {
            mixColumns(state)
            xorKey(state, keys[round])
            for (i in 0 until 16) state[i] = SBOX[state[i]]
            shiftRows(state)
        }
        xorKey(state, keys[10])
    }

    private fun decryptBlock(state: IntArray, keys: Array<IntArray>) {
        xorKey(state, keys[10])
        for (round in 9 downTo 1) {
            inverseShiftRows(state)
            for (i in 0 until 16) state[i] = INVERSE_SBOX[state[i]]
            xorKey(state, keys[round])
            inverseMixColumns(state)
        }
        inverseShiftRows(state)
        for (i in 0 until 16) state[i] = INVERSE_SBOX[state[i]]
        xorKey(state, keys[0])
    }

    private fun shiftRows(state: IntArray) {
        val previous = state.copyOf()
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                state[4 * column + row] = previous[4 * ((column + row) and 3) + row]
            }
        }
    }

    private fun inverseShiftRows(state: IntArray) {
        val previous = state.copyOf()
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                state[4 * column + row] = previous[4 * ((column - row + 4) and 3) + row]
            }
        }
    }

    private fun mixColumns(state: IntArray) {
        for (column in 0 until 4) {
            val offset = column * 4
            val a = state[offset]
            val b = state[offset + 1]
            val c = state[offset + 2]
            val d = state[offset + 3]
            state[offset] = gfMultiply(a, 2) xor gfMultiply(b, 3) xor c xor d
            state[offset + 1] = a xor gfMultiply(b, 2) xor gfMultiply(c, 3) xor d
            state[offset + 2] = a xor b xor gfMultiply(c, 2) xor gfMultiply(d, 3)
            state[offset + 3] = gfMultiply(a, 3) xor b xor c xor gfMultiply(d, 2)
        }
    }

    private fun inverseMixColumns(state: IntArray) {
        for (column in 0 until 4) {
            val offset = column * 4
            val a = state[offset]
            val b = state[offset + 1]
            val c = state[offset + 2]
            val d = state[offset + 3]
            state[offset] = gfMultiply(a, 14) xor gfMultiply(b, 11) xor gfMultiply(c, 13) xor gfMultiply(d, 9)
            state[offset + 1] = gfMultiply(a, 9) xor gfMultiply(b, 14) xor gfMultiply(c, 11) xor gfMultiply(d, 13)
            state[offset + 2] = gfMultiply(a, 13) xor gfMultiply(b, 9) xor gfMultiply(c, 14) xor gfMultiply(d, 11)
            state[offset + 3] = gfMultiply(a, 11) xor gfMultiply(b, 13) xor gfMultiply(c, 9) xor gfMultiply(d, 14)
        }
    }

    private fun xorKey(state: IntArray, key: IntArray) {
        for (i in 0 until 16) state[i] = state[i] xor key[i]
    }

    private fun gfMultiply(a0: Int, b0: Int): Int {
        var a = a0
        var b = b0
        var product = 0
        while (b != 0) {
            if (b and 1 != 0) product = product xor a
            a = (a shl 1) xor (if (a and 0x80 != 0) 0x1b else 0)
            a = a and 0xff
            b = b shr 1
        }
        return product
    }

    private fun confirmation(sender: ByteArray, receiver: ByteArray): ByteArray {
        val key = sha1(MAC_PREFIX, sender, receiver, MAC_SUFFIX)
        return hmacSha1(key, byteArrayOf(2, 2))
    }

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun md5(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    private fun validRecord(record: ByteArray, type: Int, payloadLength: Int): Boolean {
        if (record.size < 12) return false
        if (record[0] != 'F'.code.toByte() || record[1] != 'P'.code.toByte()) return false
        if (record[2] != 'L'.code.toByte() || record[3] != 'Y'.code.toByte()) return false
        return (record[4].toInt() and 0xff) == 2 &&
            (record[5].toInt() and 0xff) == 1 &&
            (record[6].toInt() and 0xff) == type &&
            (record[7].toInt() and 0xff) == 0 &&
            be32(record, 8) == payloadLength
    }

    private fun recordHeader(record: ByteArray, type: Int, payloadLength: Int) {
        record[0] = 'F'.code.toByte()
        record[1] = 'P'.code.toByte()
        record[2] = 'L'.code.toByte()
        record[3] = 'Y'.code.toByte()
        record[4] = 2
        record[5] = 1
        record[6] = type.toByte()
        record[7] = 0
        putBe32(record, 8, payloadLength)
    }

    private fun be32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun putBe32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun decodeRoundKeys(mode: Int): Array<IntArray> {
        val hex = ROUND_KEYS_HEX[mode]
        return Array(11) { round ->
            IntArray(16) { i ->
                val index = (round * 16 + i) * 2
                hex.substring(index, index + 2).toInt(16)
            }
        }
    }

    private val roundKeys: Array<Array<IntArray>> = Array(4) { decodeRoundKeys(it) }

    private companion object {
        private val WRAPPING_PREFIX = "#l*GY&cqnwo*&5sd".toByteArray(Charsets.US_ASCII)
        private val WRAPPING_SUFFIX = "57bv&%niulqpdg3z".toByteArray(Charsets.US_ASCII)
        private val MAC_PREFIX = "asdiuAD8AKA9a>/?".toByteArray(Charsets.US_ASCII)
        private val MAC_SUFFIX = "!D4H&2*203kjgha9".toByteArray(Charsets.US_ASCII)

        private val SBOX: IntArray = intArrayOf(99,124,119,123,242,107,111,197,48,1,103,43,254,215,171,118,202,130,201,125,250,89,71,240,173,212,162,175,156,164,114,192,183,253,147,38,54,63,247,204,52,165,229,241,113,216,49,21,4,199,35,195,24,150,5,154,7,18,128,226,235,39,178,117,9,131,44,26,27,110,90,160,82,59,214,179,41,227,47,132,83,209,0,237,32,252,177,91,106,203,190,57,74,76,88,207,208,239,170,251,67,77,51,133,69,249,2,127,80,60,159,168,81,163,64,143,146,157,56,245,188,182,218,33,16,255,243,210,205,12,19,236,95,151,68,23,196,167,126,61,100,93,25,115,96,129,79,220,34,42,144,136,70,238,184,20,222,94,11,219,224,50,58,10,73,6,36,92,194,211,172,98,145,149,228,121,231,200,55,109,141,213,78,169,108,86,244,234,101,122,174,8,186,120,37,46,28,166,180,198,232,221,116,31,75,189,139,138,112,62,181,102,72,3,246,14,97,53,87,185,134,193,29,158,225,248,152,17,105,217,142,148,155,30,135,233,206,85,40,223,140,161,137,13,191,230,66,104,65,153,45,15,176,84,187,22)

        private val INVERSE_SBOX: IntArray = IntArray(256).also { inverse ->
            for (i in 0 until 256) inverse[SBOX[i]] = i
        }

        private val ROUND_KEYS_HEX = arrayOf(
            "523263286e2de137049c00832cd656070628a0846816a5207280964167be739daaa7fe01c2b15b21b031cd60d78fbefddd09aa0f1fb8f12eaf893c4e780682b3ba1ac7b3a5a2369d0a2b0ad3722d886072de17f3d77c216edd572bbdaf7aa3dd88d4d68a5fa8f7e482ffdc592d857f845f06895200ae7eb68251a2efafd4dd6b97c7f62b9769889d15382a72baecf71942af22dfd5c6aa42c0fe80307a1277296a7826bfc28ca7b268baa64b2d9d49bf",
            "d5a9ce40a0f13f2229f9fc95e08eec568e837ccb618bcbd7015ec427a8d2509339d0a009585b6bde5905aff9f1d7ff6a33c6a2a86b9dc9763298668fc34f99e5bf287b86d4b5b2f0e62dd47f25624d9a05cbc3b9d17e71493753a5361231e8ace2505270332e2339047d860f164c6ea38bcf5837b8e17b0ebc9cfd01aad093a27b13629bc3f219957f6ee494d5be7736cee667980d147e0d727a9a99a7c4edafafd5919340a4e654640182428969293a",
            "86989d3be7920dc2f5e37fd5f61124af7494da7735e813a22e6ac2d994cda344cb9ec155fe76d2f7d01c102e44d1b36af1f3c34e0f8511b9df9901979b48b2fdabc4975aa44186e37bd88774e0903589db5230bb7f13b65804cb312ce45b04a5c2a036d2bdb3808ab978b1a65d23b503a4754d9e19c6cd14a0be7cb2fd9dc9b17aa885ca636e48dec3d0346c3e4dfddd82fc4478e1920ca6224238ca1c0fc5177eedff55ed156b1c0842aafbfbe63371",
            "48cb53065e61a30ea823779991ed11e35c6b5d40f0605667e9369b7a54370eb8c4c0316034a06707dd96fc7d89a1f2c5f24997c7c6e9f0c01b7f0cbd92defe78e7f22b88211bdb483a64d7f5a8ba298d0357764a224cad0218287af7b092537a6cbaacad4ef601af56de7b58e64c2822058e3f234b783e8c1da645d4fbea6df602b27d2c49ca43a0546c0674af866b825dcd6e5514072df5406b2b81efed40033d2d5e53386c7df2add2e1aff7294558"
        )

        private val INPUT_MASKS: Array<IntArray> = arrayOf(
            intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
            intArrayOf(8,14,49,93,204,193,78,120,144,39,84,216,22,218,65,56),
            intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0),
        )

        private val OUTPUT_MASKS: Array<IntArray> = arrayOf(
            intArrayOf(215,34,161,186,170,16,138,245,192,216,11,60,255,237,147,225),
            intArrayOf(75,102,143,87,169,3,134,157,255,220,120,18,181,112,62,197),
            intArrayOf(188,183,75,177,206,221,211,94,9,200,42,115,230,99,118,238),
            intArrayOf(3,233,75,217,18,175,69,141,199,122,242,81,114,108,22,165),
        )

        private val CHAIN_MASKS: Array<IntArray> = arrayOf(
            intArrayOf(48,225,228,79,170,3,110,102,222,210,56,222,198,5,32,58),
            intArrayOf(117,110,176,15,230,250,14,163,182,240,139,119,252,114,70,39),
            intArrayOf(27,90,99,84,164,242,89,49,119,142,208,5,188,15,114,228),
            intArrayOf(106,99,96,229,224,197,237,164,118,15,72,213,94,128,146,132),
        )


    }
}
