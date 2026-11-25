package com.airplaytv.core.crypto

import com.airplaytv.core.util.Bytes
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Comparator
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Self-tests for the pure-Kotlin crypto primitives.
 *
 * Covers RFC 7748 / RFC 8032 / NIST SP 800-38A known answers plus optional
 * OpenSSL interoperability checks. OpenSSL-dependent parts are skipped when the
 * binary is missing or does not support the requested algorithm.
 */
object CryptoVerify {
    private const val OPENSSL_TIMEOUT_SECONDS = 30L

    @JvmStatic
    fun run() {
        x25519Vectors()
        ed25519Vectors()
        aesCtrVectors()
        aesCbcVectors()
        inputValidation()
        opensslX25519()
        opensslEd25519()
    }

    // ------------------------------------------------------------------ X25519

    private fun x25519Vectors() {
        // RFC 7748 section 5.2.
        assertEquals(
            X25519.sharedSecret(
                Bytes.unhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                Bytes.unhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            ),
            Bytes.unhex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            "RFC 7748 5.2 X25519 vector 1",
        )
        assertEquals(
            X25519.sharedSecret(
                Bytes.unhex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                Bytes.unhex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            ),
            Bytes.unhex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"),
            "RFC 7748 5.2 X25519 vector 2",
        )

        // RFC 7748 section 6.1 Alice/Bob.
        val alicePrivate = Bytes.unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = Bytes.unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPrivate = Bytes.unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPublic = Bytes.unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = Bytes.unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertEquals(X25519.publicKey(alicePrivate), alicePublic, "RFC 7748 6.1 Alice public key")
        assertEquals(X25519.publicKey(bobPrivate), bobPublic, "RFC 7748 6.1 Bob public key")
        assertEquals(X25519.sharedSecret(alicePrivate, bobPublic), shared, "RFC 7748 6.1 Alice shared secret")
        assertEquals(X25519.sharedSecret(bobPrivate, alicePublic), shared, "RFC 7748 6.1 Bob shared secret")

        val generated = X25519.generate()
        assertEquals(generated.privateKey.size, 32, "X25519 generated private key length")
        assertEquals(generated.publicKey.size, 32, "X25519 generated public key length")
        assertEquals(X25519.publicKey(generated.privateKey), generated.publicKey, "X25519 generated key pair")
    }

    // ----------------------------------------------------------------- Ed25519

    private fun ed25519Vectors() {
        // RFC 8032 section 7.1, tests 1-3.
        val vectors = listOf(
            Triple(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                "",
            ),
            Triple(
                "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                "72",
            ),
            Triple(
                "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                "af82",
            ),
        )
        val expectedSignatures = listOf(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        )

        for (i in vectors.indices) {
            val (seedHex, publicHex, messageHex) = vectors[i]
            val seed = Bytes.unhex(seedHex)
            val expectedPublic = Bytes.unhex(publicHex)
            val message = Bytes.unhex(messageHex)
            val expectedSignature = Bytes.unhex(expectedSignatures[i])

            assertEquals(Ed25519.publicKey(seed), expectedPublic, "RFC 8032 7.1 test ${i + 1} public key")
            val signature = Ed25519.sign(seed, message)
            assertEquals(signature, expectedSignature, "RFC 8032 7.1 test ${i + 1} signature")
            assertTrue(
                Ed25519.verify(expectedPublic, message, signature),
                "RFC 8032 7.1 test ${i + 1} signature must verify",
            )

            val tamperedMessage = message.copyOf()
            if (tamperedMessage.isNotEmpty()) {
                tamperedMessage[0] = (tamperedMessage[0].toInt() xor 0x01).toByte()
            } else {
                assertTrue(
                    !Ed25519.verify(expectedPublic, byteArrayOf(0), signature),
                    "RFC 8032 7.1 test ${i + 1} empty-message tamper must fail",
                )
            }
            if (tamperedMessage.isNotEmpty()) {
                assertTrue(
                    !Ed25519.verify(expectedPublic, tamperedMessage, signature),
                    "RFC 8032 7.1 test ${i + 1} tampered message must fail",
                )
            }

            val tamperedSignature = signature.copyOf()
            tamperedSignature[0] = (tamperedSignature[0].toInt() xor 0x01).toByte()
            assertTrue(
                !Ed25519.verify(expectedPublic, message, tamperedSignature),
                "RFC 8032 7.1 test ${i + 1} tampered signature must fail",
            )
        }

        val generated = Ed25519.generate()
        val message = Bytes.utf8("AirPlayTV")
        assertEquals(Ed25519.publicKey(generated.privateKey), generated.publicKey, "Ed25519 generated key pair")
        assertTrue(Ed25519.verify(generated.publicKey, message, Ed25519.sign(generated.privateKey, message)), "Ed25519 roundtrip")
    }

    // ----------------------------------------------------------------- AES-CTR

    private fun aesCtrVectors() {
        // NIST SP 800-38A F.5.1 (CTR-AES128.Encrypt).
        val key = Bytes.unhex("2b7e151628aed2a6abf7158809cf4f3c")
        val counter = Bytes.unhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val plaintext = Bytes.concat(
            Bytes.unhex("6bc1bee22e409f96e93d7e117393172a"),
            Bytes.unhex("ae2d8a571e03ac9c9eb76fac45af8e51"),
            Bytes.unhex("30c81c46a35ce411e5fbc1191a0a52ef"),
            Bytes.unhex("f69f2445df4f9b17ad2b417be66c3710"),
        )
        val ciphertext = Bytes.concat(
            Bytes.unhex("874d6191b620e3261bef6864990db6ce"),
            Bytes.unhex("9806f66b7970fdff8617187bb9fffdff"),
            Bytes.unhex("5ae4df3edbd5d35e5b4f09020db03eab"),
            Bytes.unhex("1e031dda2fbe03d1792170a0f3009cee"),
        )

        val ctr = AesCtr(key, counter)
        assertEquals(ctr.process(plaintext), ciphertext, "NIST SP 800-38A F.5.1 CTR ciphertext")
        ctr.reset()
        assertEquals(ctr.process(plaintext), ciphertext, "NIST SP 800-38A F.5.1 CTR after reset")
        assertEquals(AesCtr(key, counter).process(ciphertext), plaintext, "AES-CTR decrypt equals encrypt")

        // Continuity across non-aligned chunks against an independent JDK
        // AES/CTR oracle and a single whole-buffer call.
        val data = ByteArray(100) { ((it * 37 + 11) and 0xff).toByte() }
        val oracle = aesCtrOracle(key, counter, data)
        assertEquals(AesCtr(key, counter).process(data), oracle, "AES-CTR whole-buffer keystream")

        val chunked = ByteArrayOutputStream()
        val streaming = AesCtr(key, counter)
        val chunks = intArrayOf(1, 3, 7, 11, 16)
        var offset = 0
        for (chunk in chunks) {
            chunked.write(streaming.process(data, offset, chunk))
            offset += chunk
        }
        chunked.write(streaming.process(data, offset, data.size - offset))
        val streamed = chunked.toByteArray()
        assertEquals(streamed, oracle, "AES-CTR chunked continuity")
        assertEquals(streamed, AesCtr(key, counter).process(data), "AES-CTR chunked equals single call")

        // The first 64 bytes of the keystream must match the NIST vector.
        assertEquals(
            AesCtr(key, counter).process(ByteArray(64)),
            Bytes.xor(plaintext, ciphertext),
            "AES-CTR NIST keystream",
        )
    }

    private fun aesCtrOracle(key: ByteArray, counter: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        return cipher.doFinal(data)
    }

    // ----------------------------------------------------------------- AES-CBC

    private fun aesCbcVectors() {
        // NIST SP 800-38A F.2.1 (CBC-AES128.Encrypt).
        val key = Bytes.unhex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = Bytes.unhex("000102030405060708090a0b0c0d0e0f")
        val plaintext = Bytes.concat(
            Bytes.unhex("6bc1bee22e409f96e93d7e117393172a"),
            Bytes.unhex("ae2d8a571e03ac9c9eb76fac45af8e51"),
            Bytes.unhex("30c81c46a35ce411e5fbc1191a0a52ef"),
            Bytes.unhex("f69f2445df4f9b17ad2b417be66c3710"),
        )
        val ciphertext = Bytes.concat(
            Bytes.unhex("7649abac8119b246cee98e9b12e9197d"),
            Bytes.unhex("5086cb9b507219ee95db113a917678b2"),
            Bytes.unhex("73bed6b8e3c1743b7116e69e22229516"),
            Bytes.unhex("3ff1caa1681fac09120eca307586e1a7"),
        )

        assertEquals(AesCbcEnc.encrypt(key, iv, plaintext), ciphertext, "NIST SP 800-38A F.2.1 CBC ciphertext")
        assertEquals(AesCbc.decrypt(key, iv, ciphertext), plaintext, "NIST SP 800-38A F.2.1 CBC plaintext")

        val random = Bytes.random(64)
        assertEquals(AesCbc.decrypt(key, iv, AesCbcEnc.encrypt(key, iv, random)), random, "AES-CBC roundtrip")
    }

    // -------------------------------------------------------------- validation

    private fun inputValidation() {
        expectIllegalArgument("short X25519 private key") {
            X25519.sharedSecret(ByteArray(31), ByteArray(32))
        }
        expectIllegalArgument("short X25519 public key") {
            X25519.sharedSecret(ByteArray(32), ByteArray(31))
        }
        expectIllegalArgument("all-zero X25519 shared secret") {
            X25519.sharedSecret(ByteArray(32) { 1 }, ByteArray(32))
        }
        expectIllegalArgument("short Ed25519 seed") {
            Ed25519.sign(ByteArray(31), ByteArray(0))
        }
        expectIllegalArgument("short Ed25519 public key") {
            Ed25519.verify(ByteArray(31), ByteArray(0), ByteArray(64))
        }
        expectIllegalArgument("short Ed25519 signature") {
            Ed25519.verify(ByteArray(32), ByteArray(0), ByteArray(63))
        }
        expectIllegalArgument("bad AES key size") {
            AesCtr(ByteArray(15), ByteArray(16))
        }
        expectIllegalArgument("bad AES IV size") {
            AesCtr(ByteArray(16), ByteArray(15))
        }
        expectIllegalArgument("unaligned AES-CBC input") {
            AesCbc.decrypt(ByteArray(16), ByteArray(16), ByteArray(15))
        }
    }

    // -------------------------------------------------------- OpenSSL interop

    private fun opensslX25519() {
        if (opensslBinary == null) {
            println("skip  OpenSSL X25519 interop (openssl unavailable)")
            return
        }
        val dir = Files.createTempDirectory("airplaytv-x25519")
        try {
            if (!runOpenssl(dir, "genpkey", "-algorithm", "X25519", "-out", "peer.pem") ||
                !runOpenssl(dir, "pkey", "-in", "peer.pem", "-pubout", "-outform", "DER", "-out", "peer.der")
            ) {
                println("skip  OpenSSL X25519 interop (algorithm unsupported)")
                return
            }
            val peerDer = Files.readAllBytes(dir.resolve("peer.der"))
            if (peerDer.size < 32) {
                println("skip  OpenSSL X25519 interop (unexpected public key encoding)")
                return
            }
            val peerRaw = peerDer.copyOfRange(peerDer.size - 32, peerDer.size)

            val ours = X25519.generate()
            val ourSecret = X25519.sharedSecret(ours.privateKey, peerRaw)

            Files.write(
                dir.resolve("ours.pem"),
                pem("PUBLIC KEY", spki("2b656e", ours.publicKey)).toByteArray(Charsets.US_ASCII),
            )
            if (!runOpenssl(
                    dir,
                    "pkeyutl", "-derive",
                    "-inkey", "peer.pem",
                    "-peerkey", "ours.pem",
                    "-out", "secret.bin",
                )
            ) {
                println("skip  OpenSSL X25519 interop (derive unsupported)")
                return
            }
            val theirSecret = Files.readAllBytes(dir.resolve("secret.bin"))
            assertEquals(ourSecret, theirSecret, "OpenSSL X25519 shared secret")
        } finally {
            deleteRecursively(dir)
        }
    }

    private fun opensslEd25519() {
        if (opensslBinary == null) {
            println("skip  OpenSSL Ed25519 interop (openssl unavailable)")
            return
        }
        val dir = Files.createTempDirectory("airplaytv-ed25519")
        try {
            if (!runOpenssl(dir, "genpkey", "-algorithm", "ED25519", "-out", "peer.pem") ||
                !runOpenssl(dir, "pkey", "-in", "peer.pem", "-pubout", "-outform", "DER", "-out", "peer.der")
            ) {
                println("skip  OpenSSL Ed25519 interop (algorithm unsupported)")
                return
            }
            val peerDer = Files.readAllBytes(dir.resolve("peer.der"))
            if (peerDer.size < 32) {
                println("skip  OpenSSL Ed25519 interop (unexpected public key encoding)")
                return
            }
            val peerRaw = peerDer.copyOfRange(peerDer.size - 32, peerDer.size)

            val message = Bytes.utf8("AirPlayTV Ed25519 interop")
            Files.write(dir.resolve("message.bin"), message)

            // Our signature -> OpenSSL verification.
            val ours = Ed25519.generate()
            val ourSignature = Ed25519.sign(ours.privateKey, message)
            Files.write(
                dir.resolve("ours.pem"),
                pem("PUBLIC KEY", spki("2b6570", ours.publicKey)).toByteArray(Charsets.US_ASCII),
            )
            Files.write(dir.resolve("ours.sig"), ourSignature)
            if (!runOpenssl(
                    dir,
                    "pkeyutl", "-verify",
                    "-pubin", "-inkey", "ours.pem",
                    "-rawin", "-in", "message.bin",
                    "-sigfile", "ours.sig",
                )
            ) {
                println("skip  OpenSSL Ed25519 interop (verification unsupported)")
                return
            }

            // OpenSSL signature -> our verification.
            if (!runOpenssl(
                    dir,
                    "pkeyutl", "-sign",
                    "-inkey", "peer.pem",
                    "-rawin", "-in", "message.bin",
                    "-out", "peer.sig",
                )
            ) {
                println("skip  OpenSSL Ed25519 interop (signing unsupported)")
                return
            }
            val peerSignature = Files.readAllBytes(dir.resolve("peer.sig"))
            assertTrue(
                Ed25519.verify(peerRaw, message, peerSignature),
                "OpenSSL Ed25519 signature must verify",
            )
        } finally {
            deleteRecursively(dir)
        }
    }

    /**
     * Locates an OpenSSL binary, preferring one that implements X25519
     * (LibreSSL reports a version but lacks modern EC support). The
     * `OPENSSL` environment variable and common Homebrew locations are
     * checked before `PATH`.
     */
    private val opensslBinary: String? by lazy {
        val candidates = listOfNotNull(
            System.getenv("OPENSSL"),
            "/opt/homebrew/opt/openssl@3/bin/openssl",
            "/usr/local/opt/openssl@3/bin/openssl",
            "/opt/homebrew/bin/openssl",
            "/usr/local/bin/openssl",
            "openssl",
        )
        val working = candidates.filter { canRunOpenSSL(it) }
        working.firstOrNull { canRunOpenSSL(it, "-algorithm", "X25519") } ?: working.firstOrNull()
    }

    private fun canRunOpenSSL(binary: String, vararg probeArgs: String): Boolean {
        val dir = Files.createTempDirectory("airplaytv-openssl-probe")
        return try {
            if (probeArgs.isEmpty()) {
                runCommand(dir, binary, "version")
            } else {
                runCommand(dir, binary, "genpkey", *probeArgs, "-out", "probe.pem")
            }
        } finally {
            deleteRecursively(dir)
        }
    }

    private fun runOpenssl(dir: Path, vararg args: String): Boolean {
        val binary = opensslBinary ?: return false
        return runCommand(dir, binary, *args)
    }

    private fun runCommand(dir: Path, binary: String, vararg args: String): Boolean {
        return try {
            val command = ArrayList<String>(args.size + 1)
            command.add(binary)
            command.addAll(args)
            val process = ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
            // Drain output so the child cannot block on a full pipe.
            process.inputStream.readBytes()
            if (!process.waitFor(OPENSSL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun pem(type: String, der: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(der)
        val builder = StringBuilder()
        builder.append("-----BEGIN ").append(type).append("-----\n")
        var i = 0
        while (i < base64.length) {
            val end = min(i + 64, base64.length)
            builder.append(base64, i, end).append('\n')
            i = end
        }
        builder.append("-----END ").append(type).append("-----\n")
        return builder.toString()
    }

    private fun spki(oidHex: String, rawPublicKey: ByteArray): ByteArray =
        Bytes.concat(Bytes.unhex("302a30050603${oidHex}032100"), rawPublicKey)

    private fun deleteRecursively(dir: Path) {
        val stream = try {
            Files.walk(dir)
        } catch (_: Exception) {
            return
        }
        try {
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } catch (_: Exception) {
            // Best effort cleanup only.
        } finally {
            stream.close()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun assertEquals(actual: ByteArray, expected: ByteArray, what: String) {
        if (!Bytes.equalsCt(actual, expected)) {
            throw AssertionError("$what: expected ${Bytes.hex(expected)}, got ${Bytes.hex(actual)}")
        }
    }

    private fun assertEquals(actual: Int, expected: Int, what: String) {
        if (actual != expected) throw AssertionError("$what: expected $expected, got $actual")
    }

    private fun assertTrue(condition: Boolean, what: String) {
        if (!condition) throw AssertionError(what)
    }

    private inline fun expectIllegalArgument(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("$what did not throw IllegalArgumentException")
    }
}
