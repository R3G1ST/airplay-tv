package com.airplaytv.core.fairplay

/**
 * The obfuscation stage of Apple's `sap_hash`, known as "garble" in the
 * reference implementation. It is a long sequence of byte-wise arithmetic,
 * rotations and bit mixing. Every statement mirrors one statement of the C /
 * Python reference; all intermediate values are kept in `0..255` by masking,
 * exactly like the reference.
 *
 * Kotlin gives every infix function (`and`, `or`, `xor`, `shl`, `ushr`) the
 * same precedence, unlike C/Python, so parentheses below are intentional and
 * follow the reference semantics rather than Kotlin's default grouping.
 */
internal object FairPlayGarble {

    fun garble(b0: IntArray, b1: IntArray, b2: IntArray, b3: IntArray, b4: IntArray) {
        b2[12] = (0x14 + (((b1[64] and 92) or ((b1[99] / 3) and 35)) and
            b4[rol8x(b4[b1[206] % 21], 4) % 21])) and 0xff

        b1[4] = (((b1[99] / 5) * (b1[99] / 5)) * 2) and 0xff

        b2[34] = 0xb8

        b1[153] = ((b1[153] and 0xff) xor
            ((b2[b1[203] % 35] * b2[b1[203] % 35] * b1[190]) and 0xff)) and 0xff

        b0[3] = (b0[3] - (((b4[b1[205] % 21] ushr 1) and 80) or 0xe6440)) and 0xff

        b0[16] = 0x93
        b0[13] = 0x62

        b1[33] = (b1[33] - (b4[b1[36] % 21] and 0xf6)) and 0xff

        val tmp2 = b2[b1[67] % 35]
        b2[12] = 0x07

        val tmp = b0[b1[181] % 20]

        b1[2] = (b1[2] - 3136) and 0xff

        b0[19] = b4[b1[58] % 21] and 0xff

        b3[0] = (92 - b2[b1[32] % 35]) and 0xff
        b3[4] = (b2[b1[15] % 35] + 0x9e) and 0xff

        b1[34] = (b1[34] + (b4[((b2[b1[15] % 35] + 0x9e) and 0xff) % 21] / 5)) and 0xff

        b0[19] = (b0[19] + 0xfffffee6.toInt() -
            ((b0[b3[4] % 20] ushr 1) and 102)) and 0xff

        b1[15] = ((3 * (((b1[72] ushr (b4[b1[190] % 21] and 7)) xor
            (b1[72] shl ((7 - (b4[b1[190] % 21] - 1)) and 7))) -
            (3 * b4[b1[126] % 21]))) xor b1[15]) and 0xff

        b0[15] = (b0[15] xor
            (b2[b1[181] % 35] * b2[b1[181] % 35] * b2[b1[181] % 35])) and 0xff

        b2[4] = (b2[4] xor (b1[202] / 3)) and 0xff

        val a1 = (92 - b0[b3[0] % 20]) and 0xff
        val e1 = ((a1 and 0xc6) or (b1[105].inv() and 0xc6) or
            (a1 and b1[105].inv())) and 0xff
        b2[1] = (b2[1] + (e1 * e1 * e1)) and 0xff

        b0[19] = (b0[19] xor (((224 or (b4[b1[92] % 21] and 27)) *
            b2[b1[41] % 35]) / 3)) and 0xff

        b1[140] = (b1[140] + weirdRor8(92, b1[5] and 7)) and 0xff

        b2[12] = (b2[12] + ((((b1[4].inv() xor b2[b1[12] % 35]) or b1[182]) and 192) or
            (((b1[4].inv() xor b2[b1[12] % 35]) and b1[182])))) and 0xff

        b1[36] = (b1[36] + 125) and 0xff

        b1[124] = rol8x(((((74 and b1[138]) or ((74 or b1[138]) and b0[15])) and
            b0[b1[43] % 20]) or (((74 and b1[138]) or ((74 or b1[138]) and b0[15]) or
            b0[b1[43] % 20]) and 95)), 4) and 0xff

        b3[8] = (((((b0[b3[4] % 20] and 95) and ((b4[b1[68] % 21] and 46) shl 1)) or 16)) xor 92) and 0xff

        val a2 = b1[177] + b4[b1[79] % 21]
        val d2 = (((a2 ushr 1) or ((3 * b1[148]) / 5)) and b2[1]) or
            ((a2 ushr 1) and ((3 * b1[148]) / 5))
        b3[12] = (-34 - d2) and 0xff

        val a3 = (8 - (b2[22] and 7)) and 0xff
        val bb3 = (b1[33] ushr (a3 and 7)) and 0xff
        val c3 = (b1[33] shl (b2[22] and 7)) and 0xff

        b2[16] = (b2[16] + ((((b2[b3[0] % 35] and 159) or b0[b3[4] % 20] or 8) -
            (((bb3 xor c3) or 128))))) and 0xff

        b0[14] = (b0[14] xor b2[b3[12] % 35]) and 0xff

        val a4 = weirdrol8(b4[b0[b1[201] % 20] % 21],
            ((b2[b1[112] % 35] shl 1) and 7)) and 0xff
        val d4 = ((b0[b1[208] % 20] and 131) or (b0[b1[164] % 20] and 124)) and 0xff
        b1[19] = (b1[19] + ((a4 and (d4 / 5)) or ((a4 or (d4 / 5)) and 37))) and 0xff

        b2[8] = weirdRor8(140,
            (((b4[b1[45] % 21] + 92) * (b4[b1[45] % 21] + 92)) and 7)) and 0xff

        b1[190] = 56

        b2[8] = (b2[8] xor b3[0]) and 0xff

        b1[53] = ((b0[b1[83] % 20] or 204) / 5).inv() and 0xff

        b0[13] = (b0[13] + b0[b1[41] % 20]) and 0xff

        b0[10] = ((((b2[b3[0] % 35] and b1[2]) or
            ((b2[b3[0] % 35] or b1[2]) and b3[12]))) / 15) and 0xff

        val a5 = (((((56 or (b4[b1[2] % 21] and 68)) or b2[b3[8] % 35]) and 42) or
            (((b4[b1[2] % 21] and 68) or 56) and b2[b3[8] % 35]))) and 0xff
        b3[16] = (((a5 * a5) + 110)) and 0xff
        b3[20] = (202 - b3[16]) and 0xff
        b3[24] = b1[151]

        b2[13] = (b2[13] xor b4[b3[0] % 21]) and 0xff

        val bb6 = (((b2[b1[179] % 35] - 38) and 177) or (b3[12] and 177)) and 0xff
        val c6 = ((b2[b1[179] % 35] - 38) and b3[12]) and 0xff
        b3[28] = (30 + ((bb6 or c6) * (bb6 or c6))) and 0xff
        b3[32] = (b3[28] + 62) and 0xff

        val a6 = (((b3[20] + (b3[0] and 74)) or b4[b3[0] % 21].inv()) and 121) and 0xff
        val bb7 = ((b3[20] + (b3[0] and 74)) and b4[b3[0] % 21].inv()) and 0xff

        val tmp3 = ((a6 or bb7)) and 0xff

        val c7 = ((((((a6 or bb7) xor 0xffffffa6.toInt()) or b3[0]) and 4) or
            ((((a6 or bb7) xor 0xffffffa6.toInt()) and b3[0])))) and 0xff
        b1[47] = (((b2[b1[89] % 35] + c7) xor b1[47])) and 0xff

        b3[36] = ((((rol8(((tmp and 179) + 68), 2) and b0[3]) or
            (tmp2 and b0[3].inv()))) - 15) and 0xff

        b1[123] = (b1[123] xor 221) and 0xff

        val a8 = (((b4[b3[0] % 21] / 3) - b2[b3[4] % 35])) and 0xff
        val c8 = (((((b3[0] and 163) + 92) and 246) or (b3[0] and 92))) and 0xff
        val e8 = ((((c8 or b3[24]) and 54) or (c8 and b3[24]))) and 0xff
        b3[40] = (a8 - e8) and 0xff

        b3[44] = (tmp3 xor 81 xor (((b3[0] ushr 1) and 101) + 26)) and 0xff

        b3[48] = (b2[b3[4] % 35] and 27) and 0xff
        b3[52] = 27
        b3[56] = 199

        run {
            val x1 = ((((b3[40] or b3[24]) and 177) or (b3[40] and b3[24]))) and 0xff
            val y1 = (((((b4[b3[0] % 20] and 177) or 176)) or
                (b4[b3[0] % 21] and 3.inv()))) and 0xff
            val z1 = (((((b3[40] and b3[24]) or ((b3[40] or b3[24]) and 177)) and 199))) and 0xff
            val w1 = ((((((b4[b3[0] % 21] and 1) and 0xff) + 176)) or
                (b4[b3[0] % 21] and 3.inv()))) and 0xff
            val v1 = ((z1 or (w1 and b3[56]))) and 0xff
            val main = ((((x1 and y1) or v1) and b3[52].inv()) or b3[48]) and 0xff
            b3[64] = (b3[4] + main) and 0xff
        }

        b2[33] = (b2[33] xor b1[26]) and 0xff

        b1[106] = (b1[106] xor (b3[20] xor 133)) and 0xff

        b2[30] = (((b3[64] / 3) - (275 or (b3[0] and 247))) xor
            b0[b1[122] % 20]) and 0xff

        b1[22] = (((b2[b1[90] % 35] and 95) or 68)) and 0xff

        val a9 = (((b4[b3[36] % 21] and 184) or
            (b2[b3[44] % 35] and 184.inv()))) and 0xff
        b2[18] = (b2[18] + ((a9 * a9 * a9) ushr 1)) and 0xff

        b2[5] = (b2[5] - b4[b1[92] % 21]) and 0xff

        val a10 = (((((b1[41] and 0xff) and 24.inv()) or (b2[b1[183] % 35] and 24)) and
            (b3[16] + 53)) or (b3[20] and b2[b3[20] % 35])) and 0xff
        val bb10 = (((b1[17] and b3[44].inv()) or
            (b0[b1[59] % 20] and b3[44]))) and 0xff
        b2[18] = (b2[18] xor (a10 * bb10)) and 0xff

        val a11 = (weirdRor8(b1[11], b2[b1[28] % 35] and 7) and 7) and 0xff
        val bb11 = (((((b0[b1[93] % 20] and b0[14].inv()) or (b0[14] and 150)) and 28.inv()) or
            (b1[7] and 28))) and 0xff
        b2[22] = (((((bb11 or weirdrol8(b2[b3[0] % 35], a11)) and b2[33]) or
            (bb11 and weirdrol8(b2[b3[0] % 35], a11))) + 74) and 0xff) and 0xff

        val a12 = (b4[(b0[b1[39] % 20] xor 217) % 21] and 0xff) and 0xff
        b0[15] = (b0[15] - (
            ((((((b3[20] or b3[0]) and 214) or (b3[20] and b3[0])) and a12) or
                (((((b3[20] or b3[0]) and 214) or (b3[20] and b3[0]) or a12) and b3[32]))))
            and 0xff)) and 0xff

        val bb13 = (((((b2[b1[57] % 35] and b0[b3[64] % 20]) or
            ((b0[b3[64] % 20] or b2[b1[57] % 35]) and 95) or (b3[64] and 45) or 82)) and 32)) and 0xff
        val c13 = (((((b2[b1[57] % 35] and b0[b3[64] % 20]) or
            ((b2[b1[57] % 35] or b0[b3[64] % 20]) and 95))) and
            ((b3[64] and 45) or 82))) and 0xff
        val d13 = ((((b3[0] / 3) - (b3[64] or b1[22])) xor (b3[28] + 62) xor
            (bb13 or c13))) and 0xff
        val t13 = (b0[(d13 and 0xff) % 20] and 0xff) and 0xff

        b3[68] = (((b0[b1[99] % 20] * b0[b1[99] % 20] * b0[b1[99] % 20] *
            b0[b1[99] % 20]) or b2[b3[64] % 35])) and 0xff

        val u14 = b0[b1[50] % 20]
        val w14 = b2[b1[138] % 35]
        val x14 = b4[b1[39] % 21]
        val y14 = b0[b1[4] % 20]
        val z14 = b4[b1[202] % 21]
        val v14 = b0[b1[151] % 20]
        val s14 = b2[b1[14] % 35]
        val r14 = b0[b1[145] % 20]

        val a15 = (((b2[b3[68] % 35] and b0[b1[209] % 20]) or
            ((b2[b3[68] % 35] or b0[b1[209] % 20]) and 24))) and 0xff
        val bb15 = weirdrol8(b4[b1[127] % 21], b2[b3[68] % 35] and 7) and 0xff
        val c15 = (((a15 and b0[10]) or (bb15 and b0[10].inv()))) and 0xff
        val d15 = ((7 xor (b4[b2[b3[36] % 35] % 21] shl 1))) and 0xff
        b3[72] = (((c15 and 71) or (d15 and 71.inv()))) and 0xff

        run {
            val p2 = (((b0[b3[20] % 20] shl 1) and 159) or
                (b4[b1[190] % 21] and 159.inv())) and 0xff
            val q2 = (((((b4[b3[64] % 21] and 110) or
                (b0[b1[25] % 20] and 110.inv())) and 150.inv()) or
                (b1[25] and 150))) and 0xff
            b2[2] = (b2[2] + ((p2 and q2))) and 0xff
        }

        run {
            val x14b = (((b2[b3[20] % 35] and (b3[72] xor b2[b1[100] % 35])) and 34.inv())) and 0xff
            val y14b = (b1[97] and 34) and 0xff
            b2[14] = (b2[14] - ((x14b or y14b))) and 0xff
        }

        b0[17] = 115

        run {
            val p23 = ((((b4[b1[17] % 21] or b0[b3[20] % 20]) and b3[72]) or
                (b4[b1[17] % 21] and b0[b3[20] % 20]))) and 0xff
            val q23 = ((p23 and (b1[50] / 3)) or
                ((p23 or (b1[50] / 3)) and 246)) and 0xff
            b1[23] = ((b1[23] xor (q23 shl 1))) and 0xff
        }

        b0[13] = (((((((b0[b3[40] % 20] or b1[10]) and 82) or
            (b0[b3[40] % 20] and b1[10]))) and 209) or
            ((b0[b1[39] % 20] shl 1) and 46)) ushr 1) and 0xff

        b2[33] = (b2[33] - (b1[113] and 9)) and 0xff

        b2[28] = (b2[28] - (((((2 or (b1[110] and 222)) ushr 1) and 223.inv()) or
            (b3[20] and 223)))) and 0xff

        val j16 = weirdrol8((v14 or z14), (u14 and 7))
        val a16 = (((b2[16] and t13) or (w14 and b2[16].inv()))) and 0xff
        val bb16 = (((b1[33] and 17) or (x14 and 17.inv()))) and 0xff
        val e16 = ((((y14 or ((a16 + bb16) / 5)) and 147) or
            (y14 and ((a16 + bb16) / 5)))) and 0xff
        val m16 = ((b3[40] and b4[((b3[8] + j16 + e16) and 0xff) % 21]) or
            ((b3[40] or b4[((b3[8] + j16 + e16) and 0xff) % 21]) and b2[23])) and 0xff

        b0[15] = ((((((b4[b3[20] % 21] - 48) and b1[184].inv()) or
            ((b4[b3[20] % 21] - 48) and 189) or (189 and b1[184].inv()))) and
            (m16 * m16 * m16))) and 0xff

        b2[22] = (b2[22] + b1[183]) and 0xff

        b3[76] = (((3 * b4[b1[1] % 21]) xor b3[0])) and 0xff

        val a17 = (b2[((b3[8] + (j16 + e16)) and 0xff) % 35] and 0xff) and 0xff
        val f17 = (((((b4[b1[178] % 21] and a17) or
            ((b4[b1[178] % 21] or a17) and 209))) * b0[b1[13] % 20]) *
            (b4[b1[26] % 21] ushr 1))
        val g17 = (((f17 + 0x733ffff9.toInt()) * 198) -
            (((((f17 + 0x733ffff9.toInt()) * 396) + 212) and 212))) + 85
        b3[80] = (b3[36] + (g17 xor 148) + ((g17 xor 107) shl 1) - 127) and 0xff

        b3[84] = (((b2[b3[64] % 35] and 245) or (b2[b3[20] % 35] and 10))) and 0xff

        val a18 = (b0[b3[68] % 20] or 81) and 0xff
        b2[18] = (b2[18] - ((((a18 * a18 * a18) and b0[15].inv()) or
            ((b3[80] / 15) and b0[15])))) and 0xff

        b3[88] = (b3[8] + j16 + e16 - b0[b1[160] % 20] +
            (b4[b0[((b3[8] + j16 + e16) and 255) % 20] % 21] / 3)) and 0xff

        val bb19 = ((((r14 xor b3[72]) and 198.inv()) or ((s14 * s14) and 198))) and 0xff
        val f19 = (((b4[b1[69] % 21] and b1[172]) or
            ((b4[b1[69] % 21] or b1[172]) and ((b3[12] - bb19) + 77)))) and 0xff
        b0[16] = (147 - (((b3[72] and ((f19 and 251) or 1)) or
            (((f19 and 250) or b3[72]) and 198)))) and 0xff

        val c20 = ((((b4[b1[168] % 21] and b0[b1[29] % 20]) and 7) or
            ((b4[b1[168] % 21] or b0[b1[29] % 20]) and 6))) and 0xff
        val f20 = (((b4[b1[155] % 21] and b1[105]) or
            ((b4[b1[155] % 21] or b1[105]) and 141))) and 0xff
        b0[3] = (b0[3] - b4[weirdRol32(f20, c20) % 21]) and 0xff

        b1[5] = ((weirdRor8(b0[12], ((b0[b1[61] % 20] / 5) and 7))) xor
            (((b2[b3[84] % 35].inv().toLong() and 0xFFFFFFFFL) / 5).toInt())) and 0xff

        b1[198] = (b1[198] + b1[3]) and 0xff

        val a21 = (162 or b2[b3[64] % 35]) and 0xff
        b1[164] = (b1[164] + ((a21 * a21) / 5)) and 0xff

        val g22 = weirdRor8(139, (b3[80] and 7))
        val c22 = ((((b4[b3[64] % 21] * b4[b3[64] % 21] * b4[b3[64] % 21]) and 95) or
            (b0[b3[40] % 20] and 95.inv()))) and 0xff
        b3[92] = (((g22 and 12) or (b0[b3[20] % 20] and 12) or
            (g22 and b0[b3[20] % 20]) or c22)) and 0xff

        b2[12] = (b2[12] + (((((b1[103] and 32) or (b3[92] and (b1[103] or 60)) or 16)) / 3))
            and 0xff) and 0xff

        b3[96] = b1[143]
        b3[100] = 27

        b3[104] = (((((b3[40] and b2[8].inv()) or (b1[35] and b2[8])) and b3[64])) xor 119) and 0xff

        b3[108] = (238 and (((((b3[40] and b2[8].inv()) or (b1[35] and b2[8])) and b3[64]) shl 1))) and 0xff

        b3[112] = (((b3[64].inv() and (b3[84] / 3)) xor 49)) and 0xff

        b3[116] = (98 and (((b3[64].inv() and (b3[84] / 3)) shl 1))) and 0xff

        val a23 = (((b1[35] and b2[8]) or (b3[40] and b2[8].inv()))) and 0xff

        val bb23 = (((a23 and b3[64]) or ((b3[84] / 3) and b3[64].inv()))) and 0xff
        b1[143] = (b3[96] - (
            ((bb23 and (86 + ((b1[172] and 64) ushr 1))) or
                ((((((b1[172] and 65) ushr 1) xor 86) or
                    (((b3[64].inv() and (b3[84] / 3)) or
                        (((b3[40] and b2[8].inv()) or (b1[35] and b2[8])) and b3[64])))) and b3[100]))))
            and 0xff) and 0xff

        b2[29] = 162

        val a24 = ((((b4[b3[88] % 21] and 160) or (b0[b1[125] % 20] and 95)) ushr 1)) and 0xff
        val bb24 = (b2[b1[149] % 35] xor ((b1[43] * b1[43]) and 0xff)) and 0xff
        b0[15] = (b0[15] + (((bb24 and a24) or ((a24 or bb24) and 115)))) and 0xff

        b3[120] = (b3[64] - b0[b3[40] % 20]) and 0xff

        b1[95] = b4[b3[20] % 21]

        val a25 = weirdRor8(b2[b3[80] % 35],
            ((b2[b1[17] % 35] * b2[b1[17] % 35] * b2[b1[17] % 35]) and 7))

        b0[7] = (b0[7] - (a25 * a25)) and 0xff

        b2[8] = (b2[8] - b1[184] +
            (b4[b1[202] % 21] * b4[b1[202] % 21] * b4[b1[202] % 21])) and 0xff

        b0[16] = (((b2[b1[102] % 35] shl 1) and 132)) and 0xff

        b3[124] = (((b4[b3[40] % 21] ushr 1) xor b3[68])) and 0xff

        b0[7] = (b0[7] - (b0[b1[191] % 20] - ((((b4[b1[80] % 21] shl 1) and 177.inv()) or
            (b4[b4[b3[88] % 21] % 21] and 177))))) and 0xff

        b0[6] = b0[b1[119] % 20]

        val a26 = (((b4[b1[190] % 21] and 209.inv()) or (b1[118] and 209))) and 0xff
        val bb26 = b0[b3[120] % 20] * b0[b3[120] % 20]
        b0[12] = (((b0[b3[84] % 20] xor (b2[b1[71] % 35] + b2[b1[15] % 35])) and
            (((a26 and bb26) or ((a26 or bb26) and 27))))) and 0xff

        val bb27 = (((b1[32] and b2[b3[88] % 35]) or
            ((b1[32] or b2[b3[88] % 35]) and 23))) and 0xff
        val d27 = ((((b4[b1[57] % 21] * 231) and 169) or (bb27 and 86))) and 0xff
        val f27 = (((((b0[b1[82] % 20] and 29.inv()) or
            (b4[b3[124] % 21] and 29))) and 190) or
            (b4[(d27 / 5) % 21] and 190.inv())) and 0xff
        val h27 = b0[b3[40] % 20] * b0[b3[40] % 20] * b0[b3[40] % 20]
        val k27 = (((h27 and b1[82]) or (h27 and 92) or (b1[82] and 92))) and 0xff

        b3[128] = ((((f27 and k27) or ((f27 or k27) and 192)) xor (d27 / 5))) and 0xff

        b2[25] = (b2[25] xor ((((b0[b3[120] % 20] shl 1) * b1[5]) -
            ((weirdrol8(b3[76], (b4[b3[124] % 21] and 7))) and (b3[20] + 110))))) and 0xff
    }

    private fun rol8(input: Int, count: Int): Int =
        ((((input shl count) and 0xff) or ((input and 0xff) ushr (8 - count)))) and 0xff

    private fun rol8x(input: Int, count: Int): Int =
        ((input shl count) or (input ushr (8 - count)))

    private fun weirdRor8(input: Int, count: Int): Int {
        if (count == 0) return 0
        return (((input ushr count) and 0xff) or ((input and 0xff) shl (8 - count)))
    }

    private fun weirdrol8(input: Int, count: Int): Int {
        if (count == 0) return 0
        return (((input shl count) and 0xff) or ((input and 0xff) ushr (8 - count)))
    }

    private fun weirdRol32(input: Int, count: Int): Int {
        if (count == 0) return 0
        return ((input shl count) xor (input ushr (8 - count)))
    }
}
