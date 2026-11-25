package com.airplaytv.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR stream cipher with a continuous keystream.
 *
 * The counter is a 128-bit big-endian integer, incremented after every block,
 * exactly like OpenSSL's `EVP_aes_128_ctr`. The keystream position is preserved
 * across [process] calls, so callers may feed arbitrarily sized (non
 * block-aligned) packets such as mirroring video frames.
 *
 * Encryption and decryption are the same operation. The keystream is produced
 * in batches of [BLOCKS_PER_REFILL] blocks so that a 1080p stream does not
 * call into the JCE once per 16 bytes.
 */
class AesCtr(key: ByteArray, iv: ByteArray) {
    private val initialCounter: ByteArray = iv.copyOf()
    private val cipher: Cipher
    private val counter: ByteArray = iv.copyOf()
    private val counterBatch = ByteArray(BLOCK_SIZE * BLOCKS_PER_REFILL)
    private val keystream = ByteArray(BLOCK_SIZE * BLOCKS_PER_REFILL)
    private var keystreamPos = 0
    private var keystreamLen = 0

    init {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        require(iv.size == 16) { "AES-CTR initial counter must be 16 bytes, got ${iv.size}" }
        cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }

    /** Encrypts or decrypts [data] with the current keystream position. */
    fun process(data: ByteArray): ByteArray = process(data, 0, data.size)

    /**
     * Encrypts or decrypts `len` bytes starting at [off].
     *
     * @return a new array holding exactly [len] bytes.
     */
    fun process(data: ByteArray, off: Int, len: Int): ByteArray {
        require(off >= 0 && len >= 0 && off + len <= data.size) {
            "range [$off, ${off + len}) out of bounds for ${data.size} bytes"
        }
        val out = ByteArray(len)
        var produced = 0
        while (produced < len) {
            if (keystreamPos == keystreamLen) refillKeystream()
            val available = minOf(keystreamLen - keystreamPos, len - produced)
            var j = 0
            while (j < available) {
                out[produced + j] = (data[off + produced + j].toInt() xor keystream[keystreamPos + j].toInt()).toByte()
                j++
            }
            keystreamPos += available
            produced += available
        }
        return out
    }

    /** Rewinds to the initial counter, discarding any partial block. */
    fun reset() {
        System.arraycopy(initialCounter, 0, counter, 0, BLOCK_SIZE)
        keystreamPos = 0
        keystreamLen = 0
    }

    private fun refillKeystream() {
        for (block in 0 until BLOCKS_PER_REFILL) {
            val base = block * BLOCK_SIZE
            System.arraycopy(counter, 0, counterBatch, base, BLOCK_SIZE)
            incrementCounter(counter)
        }
        cipher.doFinal(counterBatch, 0, counterBatch.size, keystream, 0)
        keystreamPos = 0
        keystreamLen = keystream.size
    }

    private fun incrementCounter(bytes: ByteArray) {
        for (i in BLOCK_SIZE - 1 downTo 0) {
            val next = (bytes[i].toInt() and 0xff) + 1
            bytes[i] = next.toByte()
            if (next != 0x100) return
        }
    }

    private companion object {
        const val BLOCK_SIZE = 16
        const val BLOCKS_PER_REFILL = 256
    }
}
