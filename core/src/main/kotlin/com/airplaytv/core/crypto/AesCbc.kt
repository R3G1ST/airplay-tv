package com.airplaytv.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-128-CBC encryption without padding (input must be block aligned). */
object AesCbcEnc {
    /**
     * Encrypts [data] with AES-128-CBC and no padding.
     *
     * @throws IllegalArgumentException if the key/IV sizes are wrong or [data]
     *   is not a multiple of 16 bytes.
     */
    fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        require(iv.size == 16) { "AES-CBC IV must be 16 bytes, got ${iv.size}" }
        require(data.size % 16 == 0) { "AES-CBC input must be block aligned, got ${data.size} bytes" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}

/** AES-128-CBC decryption without padding (input must be block aligned). */
object AesCbc {
    /**
     * Decrypts [data] with AES-128-CBC and no padding.
     *
     * @throws IllegalArgumentException if the key/IV sizes are wrong or [data]
     *   is not a multiple of 16 bytes.
     */
    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        require(iv.size == 16) { "AES-CBC IV must be 16 bytes, got ${iv.size}" }
        require(data.size % 16 == 0) { "AES-CBC input must be block aligned, got ${data.size} bytes" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
