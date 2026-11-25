package com.airplaytv.core.crypto

/**
 * A raw asymmetric key pair.
 *
 * For X25519 both arrays are the 32-byte private scalar and the 32-byte
 * u-coordinate. For Ed25519 [privateKey] is the 32-byte RFC 8032 seed and
 * [publicKey] is the 32-byte encoded public key.
 */
class KeyPairRaw(
    /** Raw private key material. */
    val privateKey: ByteArray,
    /** Raw public key material. */
    val publicKey: ByteArray,
)
