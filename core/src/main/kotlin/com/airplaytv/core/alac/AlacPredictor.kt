package com.airplaytv.core.alac

/**
 * Dynamic predictor reconstruction, a port of Apple's `unpc_block` from
 * `dp_dec.c`.
 *
 * Only the generic predictor loop plus the `numactive == 0` and
 * `numactive == 31` shortcuts are implemented. Apple's 4- and 8-tap loops are
 * arithmetic-identical optimisations of the generic loop, so their results are
 * reproduced exactly. Coefficients are kept in a [ShortArray] to preserve the
 * C code's `int16_t` wraparound.
 */
internal object AlacPredictor {

    /**
     * Reconstructs [num] samples from the residual buffer [pc1] into [out].
     *
     * [coefs] may be null only for the `numactive == 31` shortcut. [out] and
     * [pc1] may be the same array for that shortcut.
     */
    fun unpcBlock(
        pc1: IntArray,
        out: IntArray,
        num: Int,
        coefs: ShortArray?,
        numactive: Int,
        chanBits: Int,
        denShift: Int,
    ) {
        val chanShift = 32 - chanBits
        val denHalf = 1 shl (denShift - 1)

        out[0] = pc1[0]

        if (numactive == 0) {
            if (num > 1 && pc1 !== out) {
                System.arraycopy(pc1, 1, out, 1, num - 1)
            }
            return
        }

        if (numactive == 31) {
            var prev = out[0]
            for (j in 1 until num) {
                val del = pc1[j] + prev
                prev = (del shl chanShift) shr chanShift
                out[j] = prev
            }
            return
        }

        val taps = coefs ?: throw IllegalArgumentException("missing predictor coefficients")
        val lim = numactive + 1
        if (num < lim) {
            throw IllegalArgumentException("ALAC predictor needs at least $lim samples, got $num")
        }

        // Seed the first numactive samples with a first-order predictor.
        for (j in 1..numactive) {
            val del = pc1[j] + out[j - 1]
            out[j] = (del shl chanShift) shr chanShift
        }

        for (j in lim until num) {
            val top = out[j - lim]

            var sum = 0
            for (k in 0 until numactive) {
                sum += taps[k] * (out[j - 1 - k] - top)
            }

            var del = pc1[j]
            var del0 = del
            val sg = signOfInt(del)
            del += top + ((sum + denHalf) shr denShift)
            out[j] = (del shl chanShift) shr chanShift

            if (sg > 0) {
                for (k in numactive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = signOfInt(dd)
                    taps[k] = (taps[k] - sgn).toShort()
                    del0 -= (numactive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                }
            } else if (sg < 0) {
                for (k in numactive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = signOfInt(dd)
                    taps[k] = (taps[k] + sgn).toShort()
                    del0 -= (numactive - k) * (((-sgn) * dd) shr denShift)
                    if (del0 >= 0) break
                }
            }
        }
    }

    /**
     * `sign_of_int`: 1 for positive values, -1 for negative values, 0 for
     * zero. Note that the C implementation computes this with unsigned
     * negation, so positive inputs yield 1 (not 0).
     */
    private fun signOfInt(i: Int): Int = when {
        i > 0 -> 1
        i < 0 -> -1
        else -> 0
    }
}
