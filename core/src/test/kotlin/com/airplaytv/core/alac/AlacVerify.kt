package com.airplaytv.core.alac

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bit-exact verification of the Kotlin ALAC decoder against macOS `afconvert`.
 *
 * The fixture is regenerated on every run by `tools/alac_fixture.py`, which
 * encodes a deterministic stereo WAV to CAF with two frame lengths (4096 and
 * 352), decodes it back to WAV, and emits the magic cookie, one file per ALAC
 * packet, the packet sample counts, and the reference PCM.
 *
 * If `python3` or `afconvert` is unavailable the afconvert-dependent checks are
 * skipped; the synthetic AirPlay frame and cookie parsing checks still run.
 */
object AlacVerify {

    /** A silent 352-sample stereo frame captured from a real AirPlay sender. */
    private const val SILENT_AIRPLAY_FRAME =
        "200000040013080981f8c1ff80000013080981f8c1ff800000ff80afbfe02bfc"

    private const val TOOL_TIMEOUT_SECONDS = 120L

    @JvmStatic
    fun run() {
        silentAirplayFrame()
        cookieWrapping()
        verifyAfconvertFixtures()
    }

    // ------------------------------------------------------------- known answers

    private fun silentAirplayFrame() {
        val decoder = AlacDecoder(AlacConfig(352, 16, 40, 10, 14, 2, 44100))
        val frame = hex(SILENT_AIRPLAY_FRAME)
        val pcm = decoder.decode(frame)
        if (pcm.size != 352 * 2) {
            throw AssertionError("silent AirPlay frame: got ${pcm.size} samples, expected 704")
        }
    }

    private fun cookieWrapping() {
        val config = intArrayOf(
            0x00, 0x00, 0x10, 0x00, // frameLength 4096
            0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, // version, depth, pb, mb, kb, channels
            0x00, 0xff, // maxRun
            0x00, 0x00, 0x2c, 0xdb, // maxFrameBytes
            0x00, 0x0c, 0xa7, 0x28, // avgBitRate
            0x00, 0x00, 0xac, 0x44, // sampleRate
        ).map { it.toByte() }.toByteArray()
        val wrapped = ByteArray(12 + 12 + config.size)
        // frma atom: size, 'frma', format ('alac')
        wrapped[3] = 12
        "frma".toByteArray(Charsets.US_ASCII).copyInto(wrapped, 4)
        "alac".toByteArray(Charsets.US_ASCII).copyInto(wrapped, 8)
        // alac atom: size, 'alac', then the config
        wrapped[12 + 3] = 36
        "alac".toByteArray(Charsets.US_ASCII).copyInto(wrapped, 16)
        config.copyInto(wrapped, 24)

        val fromBare = AlacDecoder.fromMagicCookie(config)
        val fromWrapped = AlacDecoder.fromMagicCookie(wrapped)
        if (fromBare.config != fromWrapped.config) {
            throw AssertionError("magic cookie wrapper parsing differs")
        }
        if (fromBare.config.frameLength != 4096 ||
            fromBare.config.bitDepth != 16 ||
            fromBare.config.channels != 2 ||
            fromBare.config.sampleRate != 44100
        ) {
            throw AssertionError("magic cookie parsed incorrectly: ${fromBare.config}")
        }
    }

    // ----------------------------------------------------------- afconvert tests

    private fun verifyAfconvertFixtures() {
        val fixtureDir = File("build/alac-fixture")
        if (!generateFixture(fixtureDir)) return

        val compared = verifyFixture(fixtureDir, "4096", usePrimaryConstructor = false)

        val fp352 = File(fixtureDir, "fp352")
        if (File(fp352, "cookie.bin").isFile) {
            val compared352 = verifyFixture(fp352, "352", usePrimaryConstructor = true)
            println(
                "ALAC verify: $compared samples matched bit-exactly (4096), " +
                    "$compared352 samples matched bit-exactly (352 AirPlay path)",
            )
        } else {
            println("ALAC verify: $compared samples matched bit-exactly (4096)")
            println("ALAC verify: 352-sample reconfigure fixture unavailable, skipped")
        }
    }

    private fun generateFixture(dir: File): Boolean {
        val script = File("../tools/alac_fixture.py").absoluteFile
        if (!script.isFile) {
            println("ALAC verify: tools/alac_fixture.py not found, skipping afconvert tests")
            return false
        }
        val python = findOnPath("python3")
        if (python == null) {
            println("ALAC verify: python3 not found, skipping afconvert tests")
            return false
        }

        dir.deleteRecursively()
        val process = ProcessBuilder(
            python,
            script.path,
            dir.absolutePath,
        )
            .directory(File(".").absoluteFile)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            println("ALAC verify: fixture generation timed out, skipping afconvert tests")
            return false
        }
        if (process.exitValue() != 0) {
            println("ALAC verify: fixture generation skipped - ${output.trim().lines().lastOrNull()}")
            return false
        }
        if (!File(dir, "cookie.bin").isFile) {
            println("ALAC verify: fixture generation produced no cookie, skipping")
            return false
        }
        return true
    }

    /**
     * Decodes every packet and compares the interleaved PCM16 with the output
     * of afconvert's lossless decode. Returns the number of samples compared.
     */
    private fun verifyFixture(dir: File, label: String, usePrimaryConstructor: Boolean): Int {
        val cookie = File(dir, "cookie.bin").readBytes()
        val decoder = if (usePrimaryConstructor) {
            // Exercise the public primary-constructor path used by AirPlay.
            val cfg = configFromCookie(cookie)
            AlacDecoder(
                AlacConfig(
                    frameLength = cfg[0],
                    bitDepth = cfg[1],
                    pb = cfg[2],
                    mb = cfg[3],
                    kb = cfg[4],
                    channels = cfg[5],
                    sampleRate = cfg[6],
                ),
            )
        } else {
            AlacDecoder.fromMagicCookie(cookie)
        }

        if (decoder.config.channels != 2) {
            throw AssertionError("$label fixture is not stereo: ${decoder.config}")
        }

        val counts = File(dir, "samples.txt").readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts[0].toInt() to parts[1].toInt()
            }
        if (counts.isEmpty()) throw AssertionError("$label samples.txt is empty")

        val expected = File(dir, "expected.pcm").readBytes()
        val expectedSamples = expected.size / 2
        val decoded = ShortArray(expectedSamples)
        var outIndex = 0

        for ((index, sampleCount) in counts) {
            val frameFile = File(dir, String.format("frame_%04d.bin", index))
            if (!frameFile.isFile) throw AssertionError("missing $frameFile")
            val pcm = decoder.decode(frameFile.readBytes(), sampleCount)
            if (outIndex + pcm.size > decoded.size) {
                throw AssertionError(
                    "$label: decoded more samples than the reference (frame $index)",
                )
            }
            pcm.copyInto(decoded, outIndex)
            outIndex += pcm.size
        }

        if (outIndex != expectedSamples) {
            throw AssertionError(
                "$label: decoded $outIndex samples, reference has $expectedSamples",
            )
        }

        for (i in 0 until expectedSamples) {
            val lo = expected[i * 2].toInt() and 0xff
            val hi = expected[i * 2 + 1].toInt() and 0xff
            val want = ((hi shl 8) or lo).toShort()
            if (decoded[i] != want) {
                throw AssertionError(
                    "$label: PCM mismatch at sample $i: got ${decoded[i]}, expected $want",
                )
            }
        }

        return expectedSamples
    }

    /** Reads the fields of a 24-byte ALACSpecificConfig (after optional atoms). */
    private fun configFromCookie(cookie: ByteArray): IntArray {
        var offset = 0
        if (cookie.size >= 8 && tag(cookie, offset) == "frma") offset += 12
        if (cookie.size - offset >= 8 && tag(cookie, offset) == "alac") offset += 12
        if (cookie.size - offset < 24) throw AssertionError("short magic cookie")
        fun u8(i: Int) = cookie[offset + i].toInt() and 0xff
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        return intArrayOf(
            be32(0), u8(5), u8(6), u8(7), u8(8), u8(9), be32(20),
        )
    }

    private fun tag(data: ByteArray, offset: Int): String =
        String(data, offset + 4, 4, Charsets.US_ASCII)

    // ------------------------------------------------------------------ helpers

    private fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        for (entry in path.split(File.pathSeparatorChar)) {
            if (entry.isEmpty()) continue
            val candidate = File(entry, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.path
        }
        return null
    }

    private fun hex(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.length % 2 != 0) throw AssertionError("odd hex string")
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) or
                Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }
}
