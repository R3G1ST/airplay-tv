package com.airplaytv.core.alac

/**
 * Adaptive-Golomb residual decoder, a faithful port of Apple's `ag_dec.c`.
 *
 * All arithmetic deliberately follows C `int32_t`/`uint32_t` wraparound
 * semantics. The public entry point is [decode], which appends exactly
 * [numSamples] residuals to the caller's buffer and returns the number of
 * bitstream bits consumed.
 */
internal class AlacGolombDecoder {

    /** Residual values decoded by [decode], sized to the frame length. */
    val residuals: IntArray

    private val ag = AgParams()

    constructor(frameLength: Int) {
        residuals = IntArray(frameLength)
    }

    /** Sets the per-element parameters (Apple's `set_ag_params`). */
    fun setParams(mb: Int, pb: Int, kb: Int) {
        ag.mb0 = mb and 0xff
        ag.pb = pb and 0xff
        ag.kb = kb and 0xff
        ag.wb = (1 shl ag.kb) - 1
    }

    /**
     * Decodes [numSamples] residuals starting at absolute bit [startBit].
     *
     * @return the number of bits consumed (Apple's `outNumBits`).
     */
    fun decode(
        data: ByteArray,
        startBit: Int,
        numSamples: Int,
        maxSize: Int,
    ): Int {
        val maxPos = data.size * 8
        var bitPos = startBit
        val startPos = startBit

        var mb = ag.mb0
        var zmode = 0
        var c = 0

        while (c < numSamples) {
            if (bitPos >= maxPos) {
                throw IllegalArgumentException("ALAC residual bitstream overran the frame")
            }

            var m = mb ushr QBSHIFT
            var k = lg3a(m)
            k = if (k < ag.kb) k else ag.kb
            m = (1 shl k) - 1

            val header = dynGet32(data, bitPos, m, k, maxSize)
            val n = header.toInt()
            bitPos = (header ushr 32).toInt()

            // Least significant bit is the sign bit.
            val ndecode = n + zmode
            val magnitude = (ndecode + 1) ushr 1
            val del = if ((ndecode and 1) != 0) -magnitude else magnitude
            residuals[c++] = del

            mb = ag.pb * (n + zmode) + mb - ((ag.pb * mb) ushr QBSHIFT)

            // Update mean tracking.
            if (Integer.compareUnsigned(n, N_MAX_MEAN_CLAMP) > 0) {
                mb = N_MEAN_CLAMP_VAL
            }

            zmode = 0

            if (Integer.compareUnsigned(mb shl MMULSHIFT, QB) < 0 && c < numSamples) {
                zmode = 1
                k = lead(mb) - BITOFF + ((mb + MOFF) ushr MDENSHIFT)
                if (k < 0 || k > 30) {
                    throw IllegalArgumentException("invalid ALAC zero-run parameter")
                }
                val mz = ((1 shl k) - 1) and ag.wb

                val runHeader = dynGet(data, bitPos, mz, k)
                val run = runHeader.toInt().toLong() and 0xffffffffL
                bitPos = (runHeader ushr 32).toInt()

                if (c.toLong() + run > numSamples.toLong()) {
                    throw IllegalArgumentException("ALAC zero-run exceeds sample count")
                }

                for (j in 0 until run.toInt()) {
                    residuals[c++] = 0
                }

                if (run >= 65535L) {
                    zmode = 0
                }

                mb = 0
            }
        }

        val consumed = bitPos - startPos
        if (bitPos > maxPos) {
            throw IllegalArgumentException("ALAC residual bitstream overran the frame")
        }
        return consumed
    }

    // --------------------------------------------------------------- primitives

    /** `dyn_get_32bit`; returns (newBitPos << 32) or result. */
    private fun dynGet32(data: ByteArray, startBit: Int, m: Int, k: Int, maxBits: Int): Long {
        var tempbits = startBit
        var streamlong = read32(data, tempbits ushr 3) shl (tempbits and 7)

        var result = lead(streamlong.inv())

        if (result >= MAX_PREFIX_32) {
            result = getStreamBits(data, tempbits + MAX_PREFIX_32, maxBits)
            tempbits += MAX_PREFIX_32 + maxBits
        } else {
            tempbits += result
            tempbits += 1

            if (k != 1) {
                streamlong = streamlong shl (result + 1)
                val v = if (k == 0) 0 else streamlong ushr (32 - k)
                tempbits += k
                tempbits -= 1
                result *= m
                if (v >= 2) {
                    result += v - 1
                    tempbits += 1
                }
            }
        }

        return pack(tempbits, result)
    }

    /** `dyn_get`; returns (newBitPos << 32) or result. */
    private fun dynGet(data: ByteArray, startBit: Int, m: Int, k: Int): Long {
        var tempbits = startBit
        var streamlong = read32(data, tempbits ushr 3) shl (tempbits and 7)

        var pre = lead(streamlong.inv())
        val result: Int

        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16
            tempbits += pre
            streamlong = streamlong shl pre
            result = streamlong ushr (32 - MAX_DATATYPE_BITS_16)
            tempbits += MAX_DATATYPE_BITS_16
        } else {
            tempbits += pre
            tempbits += 1
            streamlong = streamlong shl (pre + 1)
            val v = if (k == 0) 0 else streamlong ushr (32 - k)
            tempbits += k

            var r = pre * m + v - 1
            if (v < 2) {
                r -= v - 1
                tempbits -= 1
            }
            result = r
        }

        return pack(tempbits, result)
    }

    /** `getstreambits`, reading up to 32 bits at an absolute bit offset. */
    private fun getStreamBits(data: ByteArray, bitOffset: Int, numBits: Int): Int {
        val byteOffset = bitOffset ushr 3
        val load1 = read32(data, byteOffset)
        var result: Int

        if (numBits + (bitOffset and 7) > 32) {
            result = load1 shl (bitOffset and 7)
            val load2 = if (byteOffset + 4 < data.size) {
                data[byteOffset + 4].toInt() and 0xff
            } else {
                0
            }
            val load2Shift = 8 - (numBits + (bitOffset and 7) - 32)
            val shifted2 = load2 ushr load2Shift
            result = result ushr (32 - numBits)
            result = result or shifted2
        } else {
            val shift = 32 - numBits - (bitOffset and 7)
            result = if (shift >= 32) 0 else load1 ushr shift
        }

        if (numBits != 32) {
            result = result and ((1 shl numBits) - 1)
        }
        return result
    }

    /** Big-endian 32-bit load, zero-padded past the end of the frame. */
    private fun read32(data: ByteArray, byteOffset: Int): Int {
        var value = 0
        for (i in 0..3) {
            val index = byteOffset + i
            val b = if (index < data.size) data[index].toInt() and 0xff else 0
            value = (value shl 8) or b
        }
        return value
    }

    private fun pack(newBitPos: Int, result: Int): Long =
        (newBitPos.toLong() shl 32) or (result.toLong() and 0xffffffffL)

    /** `lead`: number of leading zero bits in the 32-bit value. */
    private fun lead(value: Int): Int =
        if (value == 0) 32 else Integer.numberOfLeadingZeros(value)

    /** `lg3a`: 31 - lead(x + 3), with C integer wraparound. */
    private fun lg3a(x0: Int): Int {
        val x = x0 + 3
        return 31 - lead(x)
    }

    private class AgParams {
        var mb0 = 0
        var pb = 0
        var kb = 0
        var wb = 0
    }

    private companion object {
        const val QBSHIFT = 9
        const val QB = 1 shl QBSHIFT
        const val MMULSHIFT = 2
        const val MDENSHIFT = QBSHIFT - MMULSHIFT - 1
        const val MOFF = 1 shl (MDENSHIFT - 2)
        const val BITOFF = 24
        const val MAX_PREFIX_16 = 9
        const val MAX_PREFIX_32 = 9
        const val MAX_DATATYPE_BITS_16 = 16
        const val N_MAX_MEAN_CLAMP = 0xffff
        const val N_MEAN_CLAMP_VAL = 0xffff
    }
}
