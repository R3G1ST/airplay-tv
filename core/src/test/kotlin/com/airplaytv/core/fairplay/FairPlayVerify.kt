package com.airplaytv.core.fairplay

import com.airplaytv.core.util.Bytes
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * Self-tests for the FairPlay port.
 *
 * The important test is differential: random FPLY messages and `ekey` values
 * are fed both to this Kotlin implementation and to the Python reference, and
 * the resulting AES keys must match byte-for-byte. The Python side is invoked
 * once per batch (25 cases) to keep the suite fast.
 */
object FairPlayVerify {

    private const val CASES = 25

    @JvmStatic
    fun run() {
        knownAnswerTest()
        keyExchangeTest()
        differentialTestAgainstPython()
    }

    // ------------------------------------------------------------------
    // 1. Known-answer / determinism
    // ------------------------------------------------------------------

    private fun knownAnswerTest() {
        val zeroKey = Playfair.decryptAesKey(ByteArray(164), ByteArray(72))
        check(zeroKey.size == 16) { "decryptAesKey returned ${zeroKey.size} bytes, expected 16" }
        // Produced by fairplay3.Fairplay3().decryptAESKey(bytes(164), bytes(72)).
        val expected = "3ba470ef5781b366dbb21a1fa2135dbc"
        check(Bytes.hex(zeroKey) == expected) {
            "known-answer mismatch: got ${Bytes.hex(zeroKey)}, expected $expected"
        }
        val again = Playfair.decryptAesKey(ByteArray(164), ByteArray(72))
        check(zeroKey.contentEquals(again)) { "decryptAesKey is not deterministic" }
        println("  ok  known answer (all-zero inputs) -> ${Bytes.hex(zeroKey)}")
    }

    // ------------------------------------------------------------------
    // 2. fp-setup state machine
    // ------------------------------------------------------------------

    private fun keyExchangeTest() {
        // Unsupported version.
        val badVersion = ByteArray(16).also { it[4] = 0x02 }
        check(FairPlayKeyExchange().setup(badVersion) == null) {
            "setup() accepted request[4] != 0x03"
        }

        // Valid request for every mode.
        for (mode in 0..3) {
            val request = ByteArray(16).also {
                it[4] = 0x03
                it[14] = mode.toByte()
            }
            val reply = FairPlayKeyExchange().setup(request)
            check(reply != null) { "setup() rejected valid request for mode $mode" }
            check(reply.size == 142) { "setup() reply for mode $mode has ${reply.size} bytes, expected 142" }
            check(Bytes.hex(reply.copyOfRange(0, 4)) == "46504c59") {
                "setup() reply for mode $mode does not start with FPLY"
            }
        }

        // decrypt() before handshake().
        check(FairPlayKeyExchange().decrypt(ByteArray(72)) == null) {
            "decrypt() succeeded before handshake()"
        }

        // Handshake reply layout: fp_header(12) + request[144..163].
        val random = SecureRandom()
        val request164 = ByteArray(164).also {
            random.nextBytes(it)
            it[4] = 0x03
            it[12] = 0x03 // FPLY mode (fairplay3.py accepts 0..3)
        }
        val exchange = FairPlayKeyExchange()
        val response = exchange.handshake(request164)
        check(response != null) { "handshake() rejected a valid 164-byte request" }
        check(response.size == 32) { "handshake() reply has ${response.size} bytes, expected 32" }
        val expectedHeader = Bytes.unhex("46504c590301040000000014")
        check(response.copyOfRange(0, 12).contentEquals(expectedHeader)) {
            "handshake() reply header is ${Bytes.hex(response.copyOfRange(0, 12))}, expected ${Bytes.hex(expectedHeader)}"
        }
        check(response.copyOfRange(12, 32).contentEquals(request164.copyOfRange(144, 164))) {
            "handshake() reply tail does not echo request[144..163]"
        }
        check(exchange.handshake(ByteArray(164).also { it[4] = 0x02 }) == null) {
            "handshake() accepted request[4] != 0x03"
        }

        // decrypt() after handshake() agrees with the direct API.
        val ekey = ByteArray(72).also { random.nextBytes(it) }
        val viaExchange = exchange.decrypt(ekey)
        check(viaExchange != null) { "decrypt() returned null after a successful handshake()" }
        val direct = Playfair.decryptAesKey(request164, ekey)
        check(viaExchange.contentEquals(direct)) {
            "decrypt() via FairPlayKeyExchange differs from Playfair.decryptAesKey"
        }
        check(exchange.decrypt(ByteArray(71)) == null) { "decrypt() accepted a 71-byte key" }
        println("  ok  FairPlayKeyExchange setup/handshake/decrypt")
    }

    // ------------------------------------------------------------------
    // 3. Differential test against the Python reference
    // ------------------------------------------------------------------

    private fun differentialTestAgainstPython() {
        val reference = findReference()
        if (reference == null) {
            println("  skip differential test: fairplay3.py reference not found")
            return
        }

        val random = SecureRandom()
        val messages = ArrayList<ByteArray>(CASES)
        val ekeys = ArrayList<ByteArray>(CASES)
        repeat(CASES) { index ->
            val message = ByteArray(164).also { random.nextBytes(it) }
            // fairplay3.py only accepts FPLY mode bytes 0..3 (message[12]);
            // real fp-setup messages always carry one of these. Cycle through
            // all four modes so each decryption branch is exercised.
            message[12] = (index % 4).toByte()
            messages += message
            ekeys += ByteArray(72).also { random.nextBytes(it) }
        }

        val tempDir = Files.createTempDirectory("fairplay-verify").toFile()
        var pythonOutput: String? = null
        var failure: String? = null
        try {
            val referenceCopy = File(tempDir, "fairplay3.py")
            Files.copy(reference.toPath(), referenceCopy.toPath())

            val casesFile = File(tempDir, "cases.txt")
            casesFile.writeText(
                messages.indices.joinToString(separator = "\n") { i ->
                    Bytes.hex(messages[i]) + " " + Bytes.hex(ekeys[i])
                } + "\n"
            )

            val script = File(tempDir, "oracle.py")
            script.writeText(
                """
                import sys
                from fairplay3 import Fairplay3

                oracle = Fairplay3()
                with open(sys.argv[1]) as cases:
                    for line in cases:
                        line = line.strip()
                        if not line:
                            continue
                        message_hex, ekey_hex = line.split()
                        key = oracle.decryptAESKey(bytes.fromhex(message_hex), bytes.fromhex(ekey_hex))
                        print(key.hex(), flush=True)
                """.trimIndent()
            )

            val process = try {
                ProcessBuilder("python3", script.absolutePath, casesFile.absolutePath)
                    .directory(tempDir)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                println("  skip differential test: python3 unavailable (${e.message})")
                return
            }
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                failure = "python3 oracle exited with $exitCode:\n$output"
            } else {
                pythonOutput = output
            }
        } finally {
            tempDir.deleteRecursively()
        }

        if (failure != null) fail(failure)

        val expectedLines = pythonOutput!!.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        check(expectedLines.size == CASES) {
            "python oracle returned ${expectedLines.size} results, expected $CASES"
        }

        var compared = 0
        for (i in 0 until CASES) {
            val kotlin = Playfair.decryptAesKey(messages[i], ekeys[i])
            val expected = expectedLines[i]
            check(kotlin.size == 16) { "case $i: Kotlin returned ${kotlin.size} bytes, expected 16" }
            check(Bytes.hex(kotlin) == expected) {
                "case $i mismatch:\n  message=${Bytes.hex(messages[i])}\n  ekey=${Bytes.hex(ekeys[i])}\n" +
                    "  kotlin=${Bytes.hex(kotlin)}\n  python=$expected"
            }
            compared++
        }
        println("  ok  differential vs Python reference ($compared/$CASES cases)")
    }

    private fun findReference(): File? {
        val candidates = ArrayList<String>()
        System.getenv("FAIRPLAY_REF")?.let { candidates += it }
        val userDir = File(System.getProperty("user.dir"))
        candidates += File(userDir, "../../_ref/fairplay3.py").path
        candidates += File(userDir, "../_ref/fairplay3.py").path
        candidates += File(userDir, "_ref/fairplay3.py").path
        candidates += "/Users/xferant/Documents/Проект по умолчанию/_ref/fairplay3.py"
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile) return file.absoluteFile
        }
        return null
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)
}
