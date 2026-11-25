package com.airplaytv.core.util

import java.security.MessageDigest
import java.security.SecureRandom

/** Small byte helpers shared by every protocol layer. */
object Bytes {
    private val hexChars = "0123456789abcdef".toCharArray()

    fun hex(data: ByteArray): String {
        val out = CharArray(data.size * 2)
        for (i in data.indices) {
            val v = data[i].toInt() and 0xff
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0f]
        }
        return String(out)
    }

    fun unhex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() && it != ':' }
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xff

    fun be16(b: ByteArray, off: Int): Int = (u8(b, off) shl 8) or u8(b, off + 1)

    fun be32(b: ByteArray, off: Int): Int =
        (u8(b, off) shl 24) or (u8(b, off + 1) shl 16) or (u8(b, off + 2) shl 8) or u8(b, off + 3)

    fun be32l(b: ByteArray, off: Int): Long = be32(b, off).toLong() and 0xffffffffL

    fun be64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or u8(b, off + i).toLong()
        return v
    }

    fun le16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)

    fun le32(b: ByteArray, off: Int): Int =
        u8(b, off) or (u8(b, off + 1) shl 8) or (u8(b, off + 2) shl 16) or (u8(b, off + 3) shl 24)

    fun le64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or u8(b, off + i).toLong()
        return v
    }

    fun putBe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun putBe64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - i * 8)).toByte()
    }

    fun putLe16(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
    }

    fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    fun putLe64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (i * 8)).toByte()
    }

    fun be64(v: Long): ByteArray = ByteArray(8).also { putBe64(it, 0, v) }

    fun be32(v: Int): ByteArray = ByteArray(4).also { putBe32(it, 0, v) }

    fun be16(v: Int): ByteArray = ByteArray(2).also { putBe16(it, 0, v) }

    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    fun slice(b: ByteArray, off: Int, len: Int = b.size - off): ByteArray = b.copyOfRange(off, off + len)

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size)
        return ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** Constant-time comparison for signatures / digests. */
    fun equalsCt(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Unsigned big-endian bytes -> BigInteger-friendly long, used by tests. */
    fun u32ToLong(b: ByteArray, off: Int): Long = be32(b, off).toLong() and 0xffffffffL
}
