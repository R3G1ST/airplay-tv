package com.airplaytv.core.alac

/**
 * MSB-first bit reader with the same semantics as Apple's `BitBuffer`.
 *
 * Positions are absolute bit offsets into [data]. Reads of fewer than 32 bits
 * zero-extend; the ALAC bitstream itself always stores fields MSB-first.
 *
 * This class is internal to the decoder and is not thread-safe.
 */
internal class AlacBitReader(private val data: ByteArray) {

    var bitPosition: Int = 0
        private set

    val totalBits: Int = data.size * 8

    val bitsRemaining: Int
        get() = totalBits - bitPosition

    /** Reads [count] bits (0..32) and advances. Throws if the frame is too short. */
    fun readBits(count: Int): Int {
        val value = peekBits(bitPosition, count)
        bitPosition += count
        return value
    }

    /** Reads 0..8 bits, as `BitBufferReadSmall` does. */
    fun readSmall(count: Int): Int = readBits(count)

    /** Reads one bit, as `BitBufferReadOne` does. */
    fun readOne(): Int = readBits(1)

    /** Peeks [count] bits at an absolute position without changing the cursor. */
    fun peekBits(position: Int, count: Int): Int {
        if (count < 0 || count > 32) {
            throw IllegalArgumentException("invalid bit count: $count")
        }
        if (count == 0) return 0
        if (position < 0 || position + count > totalBits) {
            throw IllegalArgumentException(
                "ALAC frame truncated: need $count bits at $position of $totalBits",
            )
        }
        var result = 0
        var pos = position
        var left = count
        while (left > 0) {
            val byteIndex = pos ushr 3
            val bitOffset = pos and 7
            val byte = data[byteIndex].toInt() and 0xff
            val take = minOf(8 - bitOffset, left)
            val shift = 8 - bitOffset - take
            val mask = (1 shl take) - 1
            result = (result shl take) or ((byte ushr shift) and mask)
            pos += take
            left -= take
        }
        return result
    }

    /**
     * Advances by [count] bits (used to skip the interleaved shift buffer and
     * filler/data-stream payloads).
     */
    fun advance(count: Int) {
        if (count < 0) throw IllegalArgumentException("negative bit advance: $count")
        val next = bitPosition + count
        if (next > totalBits) {
            throw IllegalArgumentException(
                "ALAC frame truncated: advance $count bits at $bitPosition of $totalBits",
            )
        }
        bitPosition = next
    }

    /** Skips to the next byte boundary, like `BitBufferByteAlign(bits, false)`. */
    fun byteAlign() {
        val misaligned = bitPosition and 7
        if (misaligned != 0) {
            bitPosition += 8 - misaligned
        }
    }
}
