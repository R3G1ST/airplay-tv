package com.airplaytv.core.crypto

import com.airplaytv.core.util.Bytes
import java.math.BigInteger

/**
 * Ed25519 signatures (RFC 8032).
 *
 * The implementation uses extended Edwards coordinates (X:Y:Z:T) over
 * `p = 2^255 - 19`. Verification is strict: `S` must be canonical
 * (`S < L`) and small-order public keys are rejected. This interoperates with
 * signatures produced by OpenSSL / Apple.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)
    private val I: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val BYTE_MASK: BigInteger = BigInteger.valueOf(0xff)
    private val EIGHT: BigInteger = BigInteger.valueOf(8)

    private val BASE_X = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BASE_Y = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    private val BASE = Point(BASE_X, BASE_Y, BigInteger.ONE, BASE_X.multiply(BASE_Y).mod(P))
    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** Generates a fresh random Ed25519 key pair. */
    fun generate(): KeyPairRaw {
        val seed = Bytes.random(32)
        return KeyPairRaw(seed, publicKey(seed))
    }

    /**
     * Derives the 32-byte encoded public key from a 32-byte seed.
     *
     * @throws IllegalArgumentException if the seed is not 32 bytes long.
     */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes, got ${privateKey.size}" }
        return encode(scalarMult(BASE, secretScalar(privateKey)))
    }

    /**
     * Signs [message] with the 32-byte Ed25519 seed [privateKey].
     *
     * @return the 64-byte signature `R || S`.
     * @throws IllegalArgumentException if the key is not 32 bytes long.
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes, got ${privateKey.size}" }

        val digest = Bytes.sha512(privateKey)
        val secret = digest.copyOfRange(0, 32)
        secret[0] = (secret[0].toInt() and 248).toByte()
        secret[31] = ((secret[31].toInt() and 127) or 64).toByte()
        val scalar = leToBigInteger(secret)
        val prefix = digest.copyOfRange(32, 64)

        val publicKey = encode(scalarMult(BASE, scalar))
        val r = leToBigInteger(Bytes.sha512(prefix, message)).mod(L)
        val rEncoded = encode(scalarMult(BASE, r))
        val k = leToBigInteger(Bytes.sha512(rEncoded, publicKey, message)).mod(L)
        val s = r.add(k.multiply(scalar)).mod(L)
        return Bytes.concat(rEncoded, toLittleEndian(s, 32))
    }

    /**
     * Verifies an Ed25519 signature.
     *
     * @param publicKey 32-byte encoded public key.
     * @param message signed message.
     * @param signature 64-byte signature `R || S`.
     * @return `true` when the signature is valid.
     * @throws IllegalArgumentException on wrong key or signature lengths.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes, got ${publicKey.size}" }
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes, got ${signature.size}" }

        // Strict RFC 8032: reject non-canonical scalars.
        val s = leToBigInteger(signature.copyOfRange(32, 64))
        if (s >= L) return false

        val a = decode(publicKey) ?: return false
        if (isIdentity(scalarMult(a, EIGHT))) return false

        val rEncoded = signature.copyOfRange(0, 32)
        val r = decode(rEncoded) ?: return false

        val k = leToBigInteger(Bytes.sha512(rEncoded, publicKey, message)).mod(L)
        val left = scalarMult(BASE, s)
        val right = pointAdd(r, scalarMult(a, k))
        return pointEquals(left, right)
    }

    private fun secretScalar(privateKey: ByteArray): BigInteger {
        val digest = Bytes.sha512(privateKey)
        val secret = digest.copyOfRange(0, 32)
        secret[0] = (secret[0].toInt() and 248).toByte()
        secret[31] = ((secret[31].toInt() and 127) or 64).toByte()
        return leToBigInteger(secret)
    }

    private class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    /** Unified extended-coordinate addition for `a = -1`. */
    private fun pointAdd(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).mod(P).multiply(q.y.subtract(q.x).mod(P)).mod(P)
        val b = p.y.add(p.x).mod(P).multiply(q.y.add(q.x).mod(P)).mod(P)
        val c = p.t.multiply(D2).mod(P).multiply(q.t).mod(P)
        val d = p.z.multiply(q.z).mod(P).shiftLeft(1).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    /** Doubling in extended coordinates for `a = -1`. */
    private fun pointDouble(p: Point): Point {
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c = p.z.multiply(p.z).mod(P).shiftLeft(1).mod(P)
        val d = P.subtract(a).mod(P)
        val e = p.x.add(p.y).mod(P).let { it.multiply(it).mod(P) }.subtract(a).subtract(b).mod(P)
        val g = d.add(b).mod(P)
        val f = g.subtract(c).mod(P)
        val h = d.subtract(b).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    private fun scalarMult(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var n = scalar
        while (n.signum() > 0) {
            if (n.testBit(0)) result = pointAdd(result, addend)
            addend = pointDouble(addend)
            n = n.shiftRight(1)
        }
        return result
    }

    private fun isIdentity(p: Point): Boolean =
        p.x.signum() == 0 && p.y.subtract(p.z).mod(P).signum() == 0

    private fun pointEquals(a: Point, b: Point): Boolean =
        a.x.multiply(b.z).mod(P) == b.x.multiply(a.z).mod(P) &&
            a.y.multiply(b.z).mod(P) == b.y.multiply(a.z).mod(P)

    private fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = toLittleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decode(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val sign = (encoded[31].toInt() and 0x80) ushr 7
        val yBytes = encoded.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = leToBigInteger(yBytes)
        if (y >= P) return null

        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).mod(P).add(BigInteger.ONE).mod(P)
        var x = sqrtRatio(u, v) ?: return null

        if (x.signum() == 0 && sign == 1) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x).mod(P)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** Returns `sqrt(u / v)` on the curve, or `null` when no square root exists. */
    private fun sqrtRatio(u: BigInteger, v: BigInteger): BigInteger? {
        val v3 = v.multiply(v).mod(P).multiply(v).mod(P)
        val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
        var x = u.multiply(v3).mod(P)
            .multiply(u.multiply(v7).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P))
            .mod(P)
        val vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
        if (vx2 == u.mod(P)) return x
        if (vx2 == P.subtract(u.mod(P)).mod(P)) {
            x = x.multiply(I).mod(P)
            return x
        }
        return null
    }

    private fun leToBigInteger(bytes: ByteArray): BigInteger {
        val bigEndian = bytes.copyOf()
        bigEndian.reverse()
        return BigInteger(1, bigEndian)
    }

    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        var v = value
        for (i in out.indices) {
            out[i] = v.and(BYTE_MASK).toByte()
            v = v.shiftRight(8)
        }
        return out
    }
}
