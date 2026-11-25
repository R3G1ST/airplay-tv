package com.airplaytv.core.alac

/**
 * Pure-Kotlin decoder for Apple Lossless (ALAC) audio.
 *
 * The decoder accepts complete raw ALAC frames (the payload of one AirPlay
 * RTP packet) and returns interleaved 16-bit PCM. Frames produced by
 * `afconvert` from a CAF/MP4 file use the same element structure and are
 * decoded by the same code path.
 *
 * The implementation follows Apple's reference decoder (`ALACDecoder.cpp`,
 * `ag_dec.c`, `dp_dec.c`, `matrix_dec.c`) including its 32-bit integer
 * wraparound behaviour. A single instance reuses all working buffers and is
 * **not** thread-safe; create one decoder per audio stream.
 *
 * @param config stream configuration; for AirPlay use
 *   `AlacConfig(352, 16, 40, 10, 14, 2, 44100)`
 */
class AlacDecoder(val config: AlacConfig) {

    private val frameLength = config.frameLength
    private val channelCount = config.channels
    private val bitDepth = config.bitDepth

    private val mixU = IntArray(frameLength)
    private val mixV = IntArray(frameLength)
    private val shiftBuffer = IntArray(frameLength * 2)
    private val coefsU = ShortArray(32)
    private val coefsV = ShortArray(32)
    private val golomb = AlacGolombDecoder(frameLength)

    /**
     * Decodes one raw ALAC frame.
     *
     * @param frame raw frame bytes, exactly as received over RTP or read from a
     *   packet table
     * @param sampleCount samples expected for this frame (the packet table's
     *   count for file input, `config.frameLength` otherwise)
     * @return interleaved PCM16 with `sampleCount * config.channels` values
     * @throws IllegalArgumentException if the frame is malformed or does not
     *   fit the configuration
     */
    fun decode(frame: ByteArray, sampleCount: Int = config.frameLength): ShortArray {
        require(sampleCount in 1..frameLength) {
            "sampleCount $sampleCount outside 1..$frameLength"
        }
        if (frame.isEmpty()) throw IllegalArgumentException("empty ALAC frame")

        val output = ShortArray(sampleCount * channelCount)
        val reader = AlacBitReader(frame)

        var numSamples = sampleCount
        var channelIndex = 0
        var done = false

        while (!done) {
            if (reader.bitsRemaining < 3) {
                throw IllegalArgumentException("ALAC frame ended before END element")
            }

            when (val tag = reader.readBits(3)) {
                ID_SCE, ID_LFE -> {
                    numSamples = decodeSingle(reader, frame, output, channelIndex, numSamples, sampleCount)
                    channelIndex++
                }

                ID_CPE -> {
                    if (channelIndex + 2 > channelCount) {
                        // Apple's decoder jumps to NoMoreChannels here.
                        done = true
                    } else {
                        numSamples = decodePair(reader, frame, output, channelIndex, numSamples, sampleCount)
                        channelIndex += 2
                    }
                }

                ID_CCE, ID_PCE -> throw IllegalArgumentException(
                    "unsupported ALAC element tag $tag",
                )

                ID_DSE -> skipDataStreamElement(reader)

                ID_FIL -> skipFillElement(reader)

                ID_END -> {
                    reader.byteAlign()
                    done = true
                }

                else -> throw IllegalArgumentException("unknown ALAC element tag $tag")
            }

            // Apple's release build stops as soon as all channels are decoded.
            if (!done && channelIndex >= channelCount) done = true
        }

        return output
    }

    // -------------------------------------------------------------- elements

    private fun decodeSingle(
        reader: AlacBitReader,
        frame: ByteArray,
        output: ShortArray,
        channelIndex: Int,
        requestedSamples: Int,
        requestedCapacity: Int,
    ): Int {
        reader.readSmall(4) // element instance tag
        requireZero(reader.readBits(12), "SCE unused header")

        val headerByte = reader.readBits(4)
        val partialFrame = headerByte ushr 3
        var bytesShifted = (headerByte ushr 1) and 0x3
        if (bytesShifted == 3) {
            throw IllegalArgumentException("invalid ALAC shift-off flag")
        }
        val escapeFlag = headerByte and 0x1
        var chanBits = bitDepth - bytesShifted * 8

        var numSamples = requestedSamples
        if (partialFrame != 0) {
            numSamples = (reader.readBits(16) shl 16) or reader.readBits(16)
            validatePartial(numSamples, requestedCapacity)
        }

        var mixBits = 0
        var mixRes = 0
        var applyShiftBytes = bytesShifted

        if (escapeFlag == 0) {
            mixBits = reader.readBits(8)
            mixRes = reader.readBits(8).toByte().toInt()

            var hb = reader.readBits(8)
            val modeU = hb ushr 4
            val denShiftU = hb and 0xf

            hb = reader.readBits(8)
            val pbFactorU = hb ushr 5
            val numU = hb and 0x1f
            for (i in 0 until numU) {
                coefsU[i] = reader.readBits(16).toShort()
            }

            var shiftStart = 0
            if (bytesShifted != 0) {
                shiftStart = reader.bitPosition
                reader.advance(bytesShifted * 8 * numSamples)
            }

            golomb.setParams(config.mb, (config.pb * pbFactorU) / 4, config.kb)
            val consumed = golomb.decode(frame, reader.bitPosition, numSamples, chanBits)
            reader.advance(consumed)

            if (modeU == 0) {
                AlacPredictor.unpcBlock(
                    golomb.residuals, mixU, numSamples, coefsU, numU, chanBits, denShiftU,
                )
            } else {
                // numActive == 31 can be reconstructed in place.
                AlacPredictor.unpcBlock(
                    golomb.residuals, golomb.residuals, numSamples, null, 31, chanBits, 0,
                )
                AlacPredictor.unpcBlock(
                    golomb.residuals, mixU, numSamples, coefsU, numU, chanBits, denShiftU,
                )
            }

            if (bytesShifted != 0) {
                readShiftValues(reader, shiftStart, numSamples, bytesShifted * 8)
            }
        } else {
            // Uncompressed ("escape") frame: samples are stored verbatim.
            val shift = 32 - chanBits
            if (chanBits <= 16) {
                for (i in 0 until numSamples) {
                    val value = reader.readBits(chanBits)
                    mixU[i] = (value shl shift) shr shift
                }
            } else {
                val extraBits = chanBits - 16
                for (i in 0 until numSamples) {
                    val value = reader.readBits(16)
                    mixU[i] = ((value shl 16) shr shift) or reader.readBits(extraBits)
                }
            }
            mixBits = 0
            mixRes = 0
            bytesShifted = 0
            applyShiftBytes = 0
        }

        writeMono(output, channelIndex, numSamples, applyShiftBytes)
        return numSamples
    }

    private fun decodePair(
        reader: AlacBitReader,
        frame: ByteArray,
        output: ShortArray,
        channelIndex: Int,
        requestedSamples: Int,
        requestedCapacity: Int,
    ): Int {
        reader.readSmall(4) // element instance tag
        requireZero(reader.readBits(12), "CPE unused header")

        val headerByte = reader.readBits(4)
        val partialFrame = headerByte ushr 3
        var bytesShifted = (headerByte ushr 1) and 0x3
        if (bytesShifted == 3) {
            throw IllegalArgumentException("invalid ALAC shift-off flag")
        }
        val escapeFlag = headerByte and 0x1
        var chanBits = bitDepth - bytesShifted * 8 + 1

        var numSamples = requestedSamples
        if (partialFrame != 0) {
            numSamples = (reader.readBits(16) shl 16) or reader.readBits(16)
            validatePartial(numSamples, requestedCapacity)
        }

        var mixBits = 0
        var mixRes = 0
        var applyShiftBytes = bytesShifted

        if (escapeFlag == 0) {
            mixBits = reader.readBits(8)
            mixRes = reader.readBits(8).toByte().toInt()

            var hb = reader.readBits(8)
            val modeU = hb ushr 4
            val denShiftU = hb and 0xf

            hb = reader.readBits(8)
            val pbFactorU = hb ushr 5
            val numU = hb and 0x1f
            for (i in 0 until numU) {
                coefsU[i] = reader.readBits(16).toShort()
            }

            hb = reader.readBits(8)
            val modeV = hb ushr 4
            val denShiftV = hb and 0xf

            hb = reader.readBits(8)
            val pbFactorV = hb ushr 5
            val numV = hb and 0x1f
            for (i in 0 until numV) {
                coefsV[i] = reader.readBits(16).toShort()
            }

            var shiftStart = 0
            if (bytesShifted != 0) {
                shiftStart = reader.bitPosition
                reader.advance(bytesShifted * 8 * 2 * numSamples)
            }

            golomb.setParams(config.mb, (config.pb * pbFactorU) / 4, config.kb)
            val consumedU = golomb.decode(frame, reader.bitPosition, numSamples, chanBits)
            reader.advance(consumedU)

            if (modeU == 0) {
                AlacPredictor.unpcBlock(
                    golomb.residuals, mixU, numSamples, coefsU, numU, chanBits, denShiftU,
                )
            } else {
                AlacPredictor.unpcBlock(
                    golomb.residuals, golomb.residuals, numSamples, null, 31, chanBits, 0,
                )
                AlacPredictor.unpcBlock(
                    golomb.residuals, mixU, numSamples, coefsU, numU, chanBits, denShiftU,
                )
            }

            golomb.setParams(config.mb, (config.pb * pbFactorV) / 4, config.kb)
            val consumedV = golomb.decode(frame, reader.bitPosition, numSamples, chanBits)
            reader.advance(consumedV)

            if (modeV == 0) {
                AlacPredictor.unpcBlock(
                    golomb.residuals, mixV, numSamples, coefsV, numV, chanBits, denShiftV,
                )
            } else {
                AlacPredictor.unpcBlock(
                    golomb.residuals, golomb.residuals, numSamples, null, 31, chanBits, 0,
                )
                AlacPredictor.unpcBlock(
                    golomb.residuals, mixV, numSamples, coefsV, numV, chanBits, denShiftV,
                )
            }

            if (bytesShifted != 0) {
                readShiftValues(reader, shiftStart, numSamples * 2, bytesShifted * 8)
            }
        } else {
            // Uncompressed pair; each channel is bitDepth wide.
            chanBits = bitDepth
            val shift = 32 - chanBits
            if (chanBits <= 16) {
                for (i in 0 until numSamples) {
                    var value = reader.readBits(chanBits)
                    mixU[i] = (value shl shift) shr shift
                    value = reader.readBits(chanBits)
                    mixV[i] = (value shl shift) shr shift
                }
            } else {
                val extraBits = chanBits - 16
                for (i in 0 until numSamples) {
                    var value = reader.readBits(16)
                    mixU[i] = ((value shl 16) shr shift) or reader.readBits(extraBits)
                    value = reader.readBits(16)
                    mixV[i] = ((value shl 16) shr shift) or reader.readBits(extraBits)
                }
            }
            mixBits = 0
            mixRes = 0
            bytesShifted = 0
            applyShiftBytes = 0
        }

        writeStereo(output, channelIndex, numSamples, mixBits, mixRes, applyShiftBytes)
        return numSamples
    }

    // --------------------------------------------------------------- output

    private fun writeMono(output: ShortArray, channelIndex: Int, numSamples: Int, shiftedBytes: Int) {
        val stride = channelCount
        when (bitDepth) {
            16 -> {
                for (i in 0 until numSamples) {
                    output[channelIndex + i * stride] = mixU[i].toShort()
                }
            }

            20 -> {
                for (i in 0 until numSamples) {
                    output[channelIndex + i * stride] = (mixU[i] shr 4).toShort()
                }
            }

            24 -> {
                if (shiftedBytes != 0) {
                    val shift = shiftedBytes * 8
                    for (i in 0 until numSamples) {
                        val value = (mixU[i] shl shift) or shiftBuffer[i]
                        output[channelIndex + i * stride] = (value shr 8).toShort()
                    }
                } else {
                    for (i in 0 until numSamples) {
                        output[channelIndex + i * stride] = (mixU[i] shr 8).toShort()
                    }
                }
            }

            else -> {
                if (shiftedBytes != 0) {
                    val shift = shiftedBytes * 8
                    for (i in 0 until numSamples) {
                        val value = (mixU[i] shl shift) or shiftBuffer[i]
                        output[channelIndex + i * stride] = (value shr 16).toShort()
                    }
                } else {
                    for (i in 0 until numSamples) {
                        output[channelIndex + i * stride] = (mixU[i] shr 16).toShort()
                    }
                }
            }
        }
    }

    private fun writeStereo(
        output: ShortArray,
        channelIndex: Int,
        numSamples: Int,
        mixBits: Int,
        mixRes: Int,
        shiftedBytes: Int,
    ) {
        val stride = channelCount
        if (bitDepth == 16) {
            unmix16(output, channelIndex, numSamples, mixBits, mixRes, stride)
            return
        }

        val downShift = when (bitDepth) {
            20 -> 4
            24 -> 8
            else -> 16
        }
        val shift = shiftedBytes * 8
        val useShift = shiftedBytes != 0 && bitDepth != 20

        for (j in 0 until numSamples) {
            var l: Int
            var r: Int
            if (mixRes != 0) {
                val v = mixV[j]
                l = mixU[j] + v - ((mixRes * v) shr mixBits)
                r = l - v
            } else {
                l = mixU[j]
                r = mixV[j]
            }
            if (useShift) {
                l = (l shl shift) or shiftBuffer[2 * j]
                r = (r shl shift) or shiftBuffer[2 * j + 1]
            }
            val base = channelIndex + j * stride
            output[base] = (l shr downShift).toShort()
            output[base + 1] = (r shr downShift).toShort()
        }
    }

    /** Apple's `unmix16`: matrixed or conventional stereo. */
    private fun unmix16(
        output: ShortArray,
        channelIndex: Int,
        numSamples: Int,
        mixBits: Int,
        mixRes: Int,
        stride: Int,
    ) {
        if (mixRes != 0) {
            for (j in 0 until numSamples) {
                val v = mixV[j]
                val l = mixU[j] + v - ((mixRes * v) shr mixBits)
                val r = l - v
                val base = channelIndex + j * stride
                output[base] = l.toShort()
                output[base + 1] = r.toShort()
            }
        } else {
            for (j in 0 until numSamples) {
                val base = channelIndex + j * stride
                output[base] = mixU[j].toShort()
                output[base + 1] = mixV[j].toShort()
            }
        }
    }

    // ------------------------------------------------------- shift/filler bits

    private fun readShiftValues(
        reader: AlacBitReader,
        startBit: Int,
        count: Int,
        bitsPerValue: Int,
    ) {
        var position = startBit
        for (i in 0 until count) {
            shiftBuffer[i] = reader.peekBits(position, bitsPerValue)
            position += bitsPerValue
        }
    }

    private fun skipFillElement(reader: AlacBitReader) {
        var count = reader.readSmall(4)
        if (count == 15) {
            count += reader.readSmall(8) - 1
        }
        if (count < 0) throw IllegalArgumentException("invalid ALAC fill count")
        reader.advance(count * 8)
    }

    private fun skipDataStreamElement(reader: AlacBitReader) {
        reader.readSmall(4) // element instance tag
        val byteAlignFlag = reader.readOne()
        var count = reader.readSmall(8)
        if (count == 255) {
            count += reader.readSmall(8)
        }
        if (byteAlignFlag != 0) {
            reader.byteAlign()
        }
        reader.advance(count * 8)
    }

    private fun validatePartial(numSamples: Int, requested: Int) {
        if (numSamples <= 0 || numSamples > requested) {
            throw IllegalArgumentException(
                "ALAC partial frame declares $numSamples samples, expected at most $requested",
            )
        }
    }

    private fun requireZero(value: Int, what: String) {
        if (value != 0) throw IllegalArgumentException("$what must be zero, got $value")
    }

    companion object {
        private const val ID_SCE = 0
        private const val ID_CPE = 1
        private const val ID_CCE = 2
        private const val ID_LFE = 3
        private const val ID_DSE = 4
        private const val ID_PCE = 5
        private const val ID_FIL = 6
        private const val ID_END = 7

        private const val CONFIG_SIZE = 24
        private const val ATOM_HEADER_SIZE = 12

        /**
         * Parses an `ALACSpecificConfig` magic cookie (24 bytes), optionally
         * wrapped in `frma` and `alac` atoms as used by MP4/CAF files.
         *
         * @throws IllegalArgumentException if the cookie is truncated or has an
         *   unsupported version
         */
        fun fromMagicCookie(cookie: ByteArray): AlacDecoder {
            var offset = 0
            if (hasAtomTag(cookie, offset, 'f', 'r', 'm', 'a')) {
                offset += ATOM_HEADER_SIZE
            }
            if (hasAtomTag(cookie, offset, 'a', 'l', 'a', 'c')) {
                offset += ATOM_HEADER_SIZE
            }
            if (offset < 0 || cookie.size - offset < CONFIG_SIZE) {
                throw IllegalArgumentException("ALAC magic cookie is too short")
            }

            val frameLength = be32(cookie, offset)
            val compatibleVersion = u8(cookie, offset + 4)
            val bitDepth = u8(cookie, offset + 5)
            val pb = u8(cookie, offset + 6)
            val mb = u8(cookie, offset + 7)
            val kb = u8(cookie, offset + 8)
            val channels = u8(cookie, offset + 9)
            val sampleRate = be32(cookie, offset + 20)

            if (compatibleVersion > 0) {
                throw IllegalArgumentException(
                    "unsupported ALAC compatible version $compatibleVersion",
                )
            }

            return AlacDecoder(
                AlacConfig(
                    frameLength = frameLength,
                    bitDepth = bitDepth,
                    pb = pb,
                    mb = mb,
                    kb = kb,
                    channels = channels,
                    sampleRate = sampleRate,
                ),
            )
        }

        private fun hasAtomTag(
            data: ByteArray,
            offset: Int,
            c0: Char,
            c1: Char,
            c2: Char,
            c3: Char,
        ): Boolean {
            if (offset + 8 > data.size) return false
            return data[offset + 4].toInt() == c0.code &&
                data[offset + 5].toInt() == c1.code &&
                data[offset + 6].toInt() == c2.code &&
                data[offset + 7].toInt() == c3.code
        }

        private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xff

        private fun be32(data: ByteArray, offset: Int): Int =
            (u8(data, offset) shl 24) or
                (u8(data, offset + 1) shl 16) or
                (u8(data, offset + 2) shl 8) or
                u8(data, offset + 3)
    }
}
