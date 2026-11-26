package com.airplaytv.core.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Apple's AirPort/Apple TV RSA private key, used by classic RAOP senders to
 * wrap the per-session AES-128 key (`a=rsaaeskey` in the ANNOUNCE SDP).
 *
 * The same well-known key is shipped by shairport-sync, RPiPlay and UxPlay:
 * AirPlay 1 senders encrypt the key with its public half and PKCS#1 OAEP
 * (SHA-1) padding, and the receiver decrypts it here.
 */
internal object RsaAppleKey {

    private val MODULUS = BigInteger(
        "e7d744f2a2e2788b6c1f55a08eb70544a8fa7945aa8be6c62ce5f51cbdd4dc6842fe3d1083dd2edec1bfd4252dc02e6f398bdf0e6148ea84855e2e442da6d62664f674a1f304929ade4f6893ef2df6e711a8c77a0d91c9d980822e50d12922afea40ea9f0e14c0f76938c5f3882fc0323dd9fe55155f51bb5921c201629fd73352d5e2efaabf9ba048d7b813a2b6767f6c3ccf1eb4ce673d037b0d2ea30c5fffeb06f8d08adde409571a9c689fef10728855dd8cfb9a8bef5c8943ef3b5faa15dde698beddf3599603eb3e6f61372bb628f6559f599a78bf500687aa7f4976c0562d412956f8989e18a6355bd81597825e0fc875343ec782117625cdbf98447b",
        16,
    )

    private val PUBLIC_EXPONENT = BigInteger.valueOf(65537)

    private val PRIVATE_EXPONENT = BigInteger(
        "00e5f00c72f577d604b9a4ce4122aa84b01743ec995acfcc7f4ab27c0b187f90665be359df1259818deeed79d3b1ef845e4ddddac9a155373b5e270d8e1315001a2e527d54cdf9000a5768bc98d4446b37bbbd00b29dd8b53062133b2a6e77f4ee32505622904da720fb1c12c03996da713a0506098edbedecf936d0fa9cbd5929abb0eda35799502f9894dcb8fc569a892d17780324a2b6c3166e346709134b854041b867706b58fef2a0db922b77628b68e69693c7af43bf2a73d0b732377a0ba17b44f051e9bf79849dcb3332571fd8a70933c2d60bdec479934a3daca40bb6f2f37c0a9d07106eadc8b369a03f2f41c880098e8add46240dac68cc5354f361",
        16,
    )

    /**
     * Decrypts a 256-byte RSA/OAEP-SHA1 block.
     *
     * @return the plaintext (a 16-byte AES key for AirPlay), or null when the
     *   block is malformed or the padding does not check out.
     */
    fun decryptOaep(ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size != 256) return null
        val value = BigInteger(1, ciphertext)
        if (value >= MODULUS) return null
        val em = normalize(value.modPow(PRIVATE_EXPONENT, MODULUS).toByteArray(), 256) ?: return null
        if (em[0] != 0.toByte()) return null

        val maskedSeed = em.copyOfRange(1, 21)
        val maskedDb = em.copyOfRange(21, em.size)
        val seed = xor(maskedSeed, mgf1(maskedDb, maskedSeed.size))
        val db = xor(maskedDb, mgf1(seed, maskedDb.size))

        val lHash = MessageDigest.getInstance("SHA-1").digest()
        for (i in lHash.indices) {
            if (db[i] != lHash[i]) return null
        }
        var index = lHash.size
        while (index < db.size && db[index] == 0.toByte()) index++
        if (index >= db.size || db[index] != 1.toByte()) return null
        val message = db.copyOfRange(index + 1, db.size)
        return if (message.isEmpty()) null else message
    }

    /**
     * Test-only counterpart of [decryptOaep]: pads [message] with OAEP-SHA1
     * and encrypts it with the public exponent, so the round trip can be
     * checked without an Apple sender.
     */
    internal fun encryptForTest(message: ByteArray, seed: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-1")
        val lHash = hash.digest()
        val db = ByteArray(256 - lHash.size - 1)
        System.arraycopy(lHash, 0, db, 0, lHash.size)
        db[db.size - message.size - 1] = 1
        System.arraycopy(message, 0, db, db.size - message.size, message.size)
        val maskedDb = xor(db, mgf1(seed, db.size))
        val maskedSeed = xor(seed, mgf1(maskedDb, lHash.size))
        val em = ByteArray(256)
        System.arraycopy(maskedSeed, 0, em, 1, maskedSeed.size)
        System.arraycopy(maskedDb, 0, em, 1 + maskedSeed.size, maskedDb.size)
        val value = BigInteger(1, em).modPow(PUBLIC_EXPONENT, MODULUS)
        val out = value.toByteArray()
        return normalize(out, 256) ?: throw IllegalStateException("RSA block does not fit")
    }

    private fun normalize(bytes: ByteArray, size: Int): ByteArray? = when {
        bytes.size == size -> bytes
        bytes.size == size + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
        bytes.size < size -> ByteArray(size - bytes.size) + bytes
        else -> null
    }

    private fun mgf1(seed: ByteArray, length: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        val out = ByteArray(length)
        var written = 0
        var counter = 0
        while (written < length) {
            digest.reset()
            digest.update(seed)
            digest.update(byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            val block = digest.digest()
            val take = minOf(block.size, length - written)
            System.arraycopy(block, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { i ->
        (a[i].toInt() xor b[i].toInt()).toByte()
    }
}
