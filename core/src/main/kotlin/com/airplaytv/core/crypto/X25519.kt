package com.airplaytv.core.crypto

import com.airplaytv.core.util.Bytes
import java.math.BigInteger

/**
 * X25519 (RFC 7748) Diffie-Hellman on Curve25519.
 *
 * This is a straightforward, allocation-friendly BigInteger implementation of
 * the Montgomery ladder. Pairing happens once per session, so constant-time
 * behaviour and raw speed are not required here.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BYTE_MASK: BigInteger = BigInteger.valueOf(0xff)

    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** Generates a fresh random key pair. */
    fun generate(): KeyPairRaw {
        val privateKey = Bytes.random(32)
        return KeyPairRaw(privateKey, publicKey(privateKey))
    }

    /**
     * Derives the public u-coordinate for [privateKey].
     *
     * @throws IllegalArgumentException if the key is not 32 bytes long.
     */
    fun publicKey(privateKey: ByteArray): ByteArray = sharedSecret(privateKey, BASE_POINT)

    /**
     * Computes the raw X25519 shared secret.
     *
     * @param privateKey 32-byte private scalar.
     * @param peerPublicKey 32-byte peer u-coordinate.
     * @return the 32-byte shared secret (little-endian u-coordinate).
     * @throws IllegalArgumentException on wrong lengths or an invalid (all-zero)
     *   shared secret, which indicates a low-order peer public key.
     */
    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes, got ${privateKey.size}" }
        require(peerPublicKey.size == 32) { "X25519 public key must be 32 bytes, got ${peerPublicKey.size}" }

        val scalar = leToBigInteger(clamp(privateKey))
        val x1 = decodeU(peerPublicKey)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 255 downTo 0) {
            val bit = if (scalar.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                var tmp = x2
                x2 = x3
                x3 = tmp
                tmp = z2
                z2 = z3
                z3 = tmp
            }
            swap = bit

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val sum = da.add(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            val diff = da.subtract(cb).mod(P)
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
        }

        if (swap == 1) {
            var tmp = x2
            x2 = x3
            x3 = tmp
            tmp = z2
            z2 = z3
            z3 = tmp
        }

        require(z2.signum() != 0) { "invalid X25519 peer public key (zero denominator)" }
        val secret = x2.multiply(z2.modInverse(P)).mod(P)
        require(secret.signum() != 0) { "invalid X25519 peer public key (all-zero shared secret)" }
        return toLittleEndian(secret)
    }

    private fun clamp(privateKey: ByteArray): ByteArray {
        val k = privateKey.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        return k
    }

    private fun decodeU(publicKey: ByteArray): BigInteger {
        val masked = publicKey.copyOf()
        // RFC 7748: the most significant bit of the final byte is ignored.
        masked[31] = (masked[31].toInt() and 0x7f).toByte()
        return leToBigInteger(masked).mod(P)
    }

    private fun leToBigInteger(bytes: ByteArray): BigInteger {
        val bigEndian = bytes.copyOf()
        bigEndian.reverse()
        return BigInteger(1, bigEndian)
    }

    private fun toLittleEndian(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        var v = value
        for (i in out.indices) {
            out[i] = v.and(BYTE_MASK).toByte()
            v = v.shiftRight(8)
        }
        return out
    }
}
