package com.airplaytv.core.util

/**
 * Minimal base64 decoder for protocol fields (AirPlay SDP carries the RSA
 * wrapped key and the IV this way).
 *
 * Written by hand because `java.util.Base64` only exists from Android 8
 * (API 26) while the receiver supports Android 5 (API 21), and the core
 * module is also compiled for plain JVM tests.
 */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val reverse = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var bits = 0
        var bitCount = 0
        for (ch in text) {
            if (ch == '=') break
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue
            val value = if (ch.code < reverse.size) reverse[ch.code] else -1
            require(value >= 0) { "invalid base64 character '$ch'" }
            bits = (bits shl 6) or value
            bitCount += 6
            if (bitCount >= 8) {
                bitCount -= 8
                out.write((bits shr bitCount) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
