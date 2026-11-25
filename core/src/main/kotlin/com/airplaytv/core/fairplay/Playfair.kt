package com.airplaytv.core.fairplay

/**
 * Apple FairPlay ("playfair") key decryption.
 *
 * This is a faithful port of the reference C/Python implementation used by
 * AirPlay receivers (RPiPlay / OmgHax). It performs no AES encryption: the
 * algorithm is a custom cipher built from lookup tables, a tweaked MD5
 * compression function and a custom hash ("sap_hash").
 *
 * All arithmetic is done in [Int] with two's-complement wrapping, which matches
 * the `uint32_t` semantics of the C implementation. Byte values are always
 * masked with `0xff` before use.
 */
object Playfair {

    /** Length of the FPLY message captured during the second `fp-setup` request. */
    const val SETUP_MESSAGE_SIZE = 164

    /** Length of the encrypted AES key ("ekey") carried in the AirPlay SETUP plist. */
    const val ENCRYPTED_KEY_SIZE = 72

    /**
     * Decrypts the 72-byte FairPlay `ekey` into the 16-byte AES key used for
     * the AirPlay mirroring stream.
     *
     * @param setupMessage the 164-byte FPLY message from the second `fp-setup` request
     * @param encryptedKey the 72-byte `ekey` value from the AirPlay SETUP plist
     * @return the 16-byte AES key
     * @throws IllegalArgumentException if either input has the wrong length
     */
    fun decryptAesKey(setupMessage: ByteArray, encryptedKey: ByteArray): ByteArray {
        require(setupMessage.size == SETUP_MESSAGE_SIZE) {
            "FairPlay setup message must be $SETUP_MESSAGE_SIZE bytes, got ${setupMessage.size}"
        }
        require(encryptedKey.size == ENCRYPTED_KEY_SIZE) {
            "FairPlay encrypted key must be $ENCRYPTED_KEY_SIZE bytes, got ${encryptedKey.size}"
        }

        // chunk1 is cipherText[16..31], chunk2 is cipherText[56..71].
        val chunk1 = encryptedKey.copyOfRange(16, 32)
        val chunk2 = encryptedKey.copyOfRange(56, 72)

        val sapKey = generateSessionKey(FairPlayTables.defaultSap(), setupMessage)
        val keySchedule = generateKeySchedule(sapKey)

        var blockIn = ByteArray(16) { i -> (u8(chunk2, i) xor FairPlayTables.zKey(i)).toByte() }
        blockIn = cycle(blockIn, keySchedule)

        val keyOut = ByteArray(16) { i -> (u8(blockIn, i) xor u8(chunk1, i)).toByte() }
        xorWithKey(keyOut, FairPlayTables::xKey)
        xorWithKey(keyOut, FairPlayTables::zKey)
        return keyOut
    }

    // ------------------------------------------------------------------
    // Session key derivation
    // ------------------------------------------------------------------

    private fun generateSessionKey(oldSap: ByteArray, messageIn: ByteArray): ByteArray {
        val decryptedMessage = decryptMessage(messageIn)
        val newSap = ByteArray(320)
        var pos = 0
        for (i in 0 until 17) newSap[pos++] = FairPlayTables.staticSource1(i).toByte()
        for (i in 0 until 128) newSap[pos++] = decryptedMessage[i]
        for (i in 0 until 128) newSap[pos++] = oldSap[0x80 + i]
        for (i in 0 until 47) newSap[pos++] = FairPlayTables.staticSource2(i).toByte()

        var sessionKey = ByteArray(16) { FairPlayTables.initialSessionKey(it).toByte() }
        for (round in 0 until 5) {
            val base = newSap.copyOfRange(round * 64, round * 64 + 64)
            val md5 = modifiedMd5(base, sessionKey)
            val hash = sapHash(base)
            sessionKey = ByteArray(16)
            for (word in 0 until 4) {
                val off = word * 4
                putLe32(sessionKey, off, le32(hash, off) + le32(md5, off))
            }
        }

        // Endianness byte swap inside every dword.
        for (i in 0 until 16 step 4) {
            val t0 = sessionKey[i]
            sessionKey[i] = sessionKey[i + 3]
            sessionKey[i + 3] = t0
            val t1 = sessionKey[i + 1]
            sessionKey[i + 1] = sessionKey[i + 2]
            sessionKey[i + 2] = t1
        }
        for (i in 0 until 16) {
            sessionKey[i] = (u8(sessionKey, i) xor 121).toByte()
        }
        return sessionKey
    }

    /**
     * The "decryptMessage" stage: 8 blocks of message table lookups, 9 rounds of
     * a 4x32-bit diffusion step and a final byte permutation.
     */
    private fun decryptMessage(messageIn: ByteArray): ByteArray {
        val mode = u8(messageIn, 12)
        require(mode in 0..3) { "unsupported FairPlay message mode $mode" }

        val decryptedMessage = ByteArray(128)
        var buffer = ByteArray(16)

        fun messageTable(index: Int, value: Int): Int =
            FairPlayTables.s2((((97 * index) % 144) shl 8) + value)

        fun key(index: Int): Int = FairPlayTables.messageKey(mode, index)

        for (i in 0 until 8) {
            for (j in 0 until 16) {
                buffer[j] = if (mode == 3) {
                    messageIn[(0x80 - 0x10 * i) + j]
                } else {
                    messageIn[(0x10 * (i + 1)) + j]
                }
            }

            for (j in 0 until 9) {
                val base = 0x80 - 0x10 * j

                buffer[0x0] = ((messageTable(base + 0x0, u8(buffer, 0x0)) xor key(base + 0x0)) and 0xff).toByte()
                buffer[0x4] = ((messageTable(base + 0x4, u8(buffer, 0x4)) xor key(base + 0x4)) and 0xff).toByte()
                buffer[0x8] = ((messageTable(base + 0x8, u8(buffer, 0x8)) xor key(base + 0x8)) and 0xff).toByte()
                buffer[0xc] = ((messageTable(base + 0xc, u8(buffer, 0xc)) xor key(base + 0xc)) and 0xff).toByte()

                var tmp = u8(buffer, 0x0d)
                buffer[0xd] = ((messageTable(base + 0xd, u8(buffer, 0x9)) xor key(base + 0xd)) and 0xff).toByte()
                buffer[0x9] = ((messageTable(base + 0x9, u8(buffer, 0x5)) xor key(base + 0x9)) and 0xff).toByte()
                buffer[0x5] = ((messageTable(base + 0x5, u8(buffer, 0x1)) xor key(base + 0x5)) and 0xff).toByte()
                buffer[0x1] = ((messageTable(base + 0x1, tmp) xor key(base + 0x1)) and 0xff).toByte()

                tmp = u8(buffer, 0x02)
                buffer[0x2] = ((messageTable(base + 0x2, u8(buffer, 0xa)) xor key(base + 0x2)) and 0xff).toByte()
                buffer[0xa] = ((messageTable(base + 0xa, tmp) xor key(base + 0xa)) and 0xff).toByte()
                tmp = u8(buffer, 0x06)
                buffer[0x6] = ((messageTable(base + 0x6, u8(buffer, 0xe)) xor key(base + 0x6)) and 0xff).toByte()
                buffer[0xe] = ((messageTable(base + 0xe, tmp) xor key(base + 0xe)) and 0xff).toByte()

                tmp = u8(buffer, 0x3)
                buffer[0x3] = ((messageTable(base + 0x3, u8(buffer, 0x7)) xor key(base + 0x3)) and 0xff).toByte()
                buffer[0x7] = ((messageTable(base + 0x7, u8(buffer, 0xb)) xor key(base + 0x7)) and 0xff).toByte()
                buffer[0xb] = ((messageTable(base + 0xb, u8(buffer, 0xf)) xor key(base + 0xb)) and 0xff).toByte()
                buffer[0xf] = ((messageTable(base + 0xf, tmp) xor key(base + 0xf)) and 0xff).toByte()

                val words = IntArray(4)
                words[0] = FairPlayTables.s9(0x000 + u8(buffer, 0x0)) xor
                    FairPlayTables.s9(0x100 + u8(buffer, 0x1)) xor
                    FairPlayTables.s9(0x200 + u8(buffer, 0x2)) xor
                    FairPlayTables.s9(0x300 + u8(buffer, 0x3))
                words[1] = FairPlayTables.s9(0x000 + u8(buffer, 0x4)) xor
                    FairPlayTables.s9(0x100 + u8(buffer, 0x5)) xor
                    FairPlayTables.s9(0x200 + u8(buffer, 0x6)) xor
                    FairPlayTables.s9(0x300 + u8(buffer, 0x7))
                words[2] = FairPlayTables.s9(0x000 + u8(buffer, 0x8)) xor
                    FairPlayTables.s9(0x100 + u8(buffer, 0x9)) xor
                    FairPlayTables.s9(0x200 + u8(buffer, 0xA)) xor
                    FairPlayTables.s9(0x300 + u8(buffer, 0xB))
                words[3] = FairPlayTables.s9(0x000 + u8(buffer, 0xC)) xor
                    FairPlayTables.s9(0x100 + u8(buffer, 0xD)) xor
                    FairPlayTables.s9(0x200 + u8(buffer, 0xE)) xor
                    FairPlayTables.s9(0x300 + u8(buffer, 0xF))
                buffer = leBytes(words)
            }

            // Second permutation, using table_s10.
            buffer[0x0] = FairPlayTables.s10((0x0 shl 8) + u8(buffer, 0x0)).toByte()
            buffer[0x4] = FairPlayTables.s10((0x4 shl 8) + u8(buffer, 0x4)).toByte()
            buffer[0x8] = FairPlayTables.s10((0x8 shl 8) + u8(buffer, 0x8)).toByte()
            buffer[0xc] = FairPlayTables.s10((0xc shl 8) + u8(buffer, 0xc)).toByte()

            var tmp = u8(buffer, 0x0d)
            buffer[0xd] = FairPlayTables.s10((0xd shl 8) + u8(buffer, 0x9)).toByte()
            buffer[0x9] = FairPlayTables.s10((0x9 shl 8) + u8(buffer, 0x5)).toByte()
            buffer[0x5] = FairPlayTables.s10((0x5 shl 8) + u8(buffer, 0x1)).toByte()
            buffer[0x1] = FairPlayTables.s10((0x1 shl 8) + tmp).toByte()

            tmp = u8(buffer, 0x02)
            buffer[0x2] = FairPlayTables.s10((0x2 shl 8) + u8(buffer, 0xa)).toByte()
            buffer[0xa] = FairPlayTables.s10((0xa shl 8) + tmp).toByte()
            tmp = u8(buffer, 0x06)
            buffer[0x6] = FairPlayTables.s10((0x6 shl 8) + u8(buffer, 0xe)).toByte()
            buffer[0xe] = FairPlayTables.s10((0xe shl 8) + tmp).toByte()

            tmp = u8(buffer, 0x3)
            buffer[0x3] = FairPlayTables.s10((0x3 shl 8) + u8(buffer, 0x7)).toByte()
            buffer[0x7] = FairPlayTables.s10((0x7 shl 8) + u8(buffer, 0xb)).toByte()
            buffer[0xb] = FairPlayTables.s10((0xb shl 8) + u8(buffer, 0xf)).toByte()
            buffer[0xf] = FairPlayTables.s10((0xf shl 8) + tmp).toByte()

            if (mode == 2 || mode == 1 || mode == 0) {
                val xorWith = if (i > 0) {
                    messageIn.copyOfRange(0x10 * i, 0x10 * i + 16)
                } else {
                    messageIv(mode)
                }
                for (k in 0 until 16) {
                    decryptedMessage[0x10 * i + k] = (u8(buffer, k) xor u8(xorWith, k)).toByte()
                }
            } else { // mode == 3
                val dst = 0x70 - 0x10 * i
                val xorWith = if (i < 7) {
                    messageIn.copyOfRange(0x70 - 0x10 * i, 0x70 - 0x10 * i + 16)
                } else {
                    messageIv(mode)
                }
                for (k in 0 until 16) {
                    decryptedMessage[dst + k] = (u8(buffer, k) xor u8(xorWith, k)).toByte()
                }
            }
        }
        return decryptedMessage
    }

    private fun messageIv(mode: Int): ByteArray =
        ByteArray(16) { FairPlayTables.messageIv(mode, it).toByte() }

    // ------------------------------------------------------------------
    // Key schedule
    // ------------------------------------------------------------------

    private fun generateKeySchedule(keyMaterial: ByteArray): Array<IntArray> {
        val keySchedule = Array(11) { IntArray(4) { DEADBEEF } }
        val buffer = ByteArray(16) { i -> (u8(keyMaterial, i) xor FairPlayTables.tKey(i)).toByte() }
        var ti = 0

        for (round in 0..10) {
            val keyData = leWords(buffer)
            keySchedule[round][0] = keyData[0]

            val table1 = ((31 * ti) % 0x28) shl 8
            val table2 = ((31 * (ti + 1)) % 0x28) shl 8
            val table3 = ((31 * (ti + 2)) % 0x28) shl 8
            val table4 = ((31 * (ti + 3)) % 0x28) shl 8
            ti += 4

            buffer[0] = (u8(buffer, 0) xor FairPlayTables.s1(table1 + u8(buffer, 0x0d)) xor
                FairPlayTables.indexMangle(round)).toByte()
            buffer[1] = (u8(buffer, 1) xor FairPlayTables.s1(table2 + u8(buffer, 0x0e))).toByte()
            buffer[2] = (u8(buffer, 2) xor FairPlayTables.s1(table3 + u8(buffer, 0x0f))).toByte()
            buffer[3] = (u8(buffer, 3) xor FairPlayTables.s1(table4 + u8(buffer, 0x0c))).toByte()

            val words = leWords(buffer)
            keySchedule[round][1] = words[1]
            words[1] = words[1] xor words[0]
            keySchedule[round][2] = words[2]
            words[2] = words[2] xor words[1]
            keySchedule[round][3] = words[3]
            words[3] = words[3] xor words[2]
            writeLeWords(buffer, words)
        }
        return keySchedule
    }

    // ------------------------------------------------------------------
    // Cipher core ("cycle")
    // ------------------------------------------------------------------

    private fun cycle(blockIn: ByteArray, keySchedule: Array<IntArray>): ByteArray {
        val blockWords = leWords(blockIn)
        for (i in 0 until 4) blockWords[i] = blockWords[i] xor keySchedule[10][i]

        var block = permuteBlock1(leBytes(blockWords))

        for (round in 0 until 9) {
            val key = leBytes(keySchedule[9 - round])
            val b = block

            val w0 = FairPlayTables.s5(u8(b, 3) xor u8(key, 3)) xor
                FairPlayTables.s6(u8(b, 2) xor u8(key, 2)) xor
                FairPlayTables.s8(u8(b, 0) xor u8(key, 0)) xor
                FairPlayTables.s7(u8(b, 1) xor u8(key, 1))

            val w1 = FairPlayTables.s5(u8(b, 7) xor u8(key, 7)) xor
                FairPlayTables.s6(u8(b, 6) xor u8(key, 6)) xor
                FairPlayTables.s7(u8(b, 5) xor u8(key, 5)) xor
                FairPlayTables.s8(u8(b, 4) xor u8(key, 4))

            val w2 = FairPlayTables.s5(u8(b, 11) xor u8(key, 11)) xor
                FairPlayTables.s6(u8(b, 10) xor u8(key, 10)) xor
                FairPlayTables.s7(u8(b, 9) xor u8(key, 9)) xor
                FairPlayTables.s8(u8(b, 8) xor u8(key, 8))

            val w3 = FairPlayTables.s5(u8(b, 15) xor u8(key, 15)) xor
                FairPlayTables.s6(u8(b, 14) xor u8(key, 14)) xor
                FairPlayTables.s7(u8(b, 13) xor u8(key, 13)) xor
                FairPlayTables.s8(u8(b, 12) xor u8(key, 12))

            block = permuteBlock2(leBytes(intArrayOf(w0, w1, w2, w3)), 8 - round)
        }

        val words = leWords(block)
        for (i in 0 until 4) words[i] = words[i] xor keySchedule[0][i]
        return leBytes(words)
    }

    private fun permuteBlock1(blockIn: ByteArray): ByteArray {
        val block = blockIn.copyOf()
        block[0] = FairPlayTables.s3(0x000 + u8(block, 0)).toByte()
        block[4] = FairPlayTables.s3(0x400 + u8(block, 4)).toByte()
        block[8] = FairPlayTables.s3(0x800 + u8(block, 8)).toByte()
        block[12] = FairPlayTables.s3(0xc00 + u8(block, 12)).toByte()

        var tmp = u8(block, 13)
        block[13] = FairPlayTables.s3(0x100 + u8(block, 9)).toByte()
        block[9] = FairPlayTables.s3(0xd00 + u8(block, 5)).toByte()
        block[5] = FairPlayTables.s3(0x900 + u8(block, 1)).toByte()
        block[1] = FairPlayTables.s3(0x500 + tmp).toByte()

        tmp = u8(block, 2)
        block[2] = FairPlayTables.s3(0xa00 + u8(block, 10)).toByte()
        block[10] = FairPlayTables.s3(0x200 + tmp).toByte()
        tmp = u8(block, 6)
        block[6] = FairPlayTables.s3(0xe00 + u8(block, 14)).toByte()
        block[14] = FairPlayTables.s3(0x600 + tmp).toByte()

        tmp = u8(block, 3)
        block[3] = FairPlayTables.s3(0xf00 + u8(block, 7)).toByte()
        block[7] = FairPlayTables.s3(0x300 + u8(block, 11)).toByte()
        block[11] = FairPlayTables.s3(0x700 + u8(block, 15)).toByte()
        block[15] = FairPlayTables.s3(0xb00 + tmp).toByte()
        return block
    }

    private fun permuteBlock2(blockIn: ByteArray, rounds: Int): ByteArray {
        val block = blockIn.copyOf()

        fun table(index: Int, value: Int): Int =
            FairPlayTables.s4((((71 * index) % 144) shl 8) + value)

        block[0] = table(rounds * 16 + 0, u8(block, 0)).toByte()
        block[4] = table(rounds * 16 + 4, u8(block, 4)).toByte()
        block[8] = table(rounds * 16 + 8, u8(block, 8)).toByte()
        block[12] = table(rounds * 16 + 12, u8(block, 12)).toByte()

        var tmp = u8(block, 13)
        block[13] = table(rounds * 16 + 13, u8(block, 9)).toByte()
        block[9] = table(rounds * 16 + 9, u8(block, 5)).toByte()
        block[5] = table(rounds * 16 + 5, u8(block, 1)).toByte()
        block[1] = table(rounds * 16 + 1, tmp).toByte()

        tmp = u8(block, 2)
        block[2] = table(rounds * 16 + 2, u8(block, 10)).toByte()
        block[10] = table(rounds * 16 + 10, tmp).toByte()
        tmp = u8(block, 6)
        block[6] = table(rounds * 16 + 6, u8(block, 14)).toByte()
        block[14] = table(rounds * 16 + 14, tmp).toByte()

        tmp = u8(block, 3)
        block[3] = table(rounds * 16 + 3, u8(block, 7)).toByte()
        block[7] = table(rounds * 16 + 7, u8(block, 11)).toByte()
        block[11] = table(rounds * 16 + 11, u8(block, 15)).toByte()
        block[15] = table(rounds * 16 + 15, tmp).toByte()
        return block
    }

    // ------------------------------------------------------------------
    // Modified MD5 compression function
    // ------------------------------------------------------------------

    private fun modifiedMd5(originalBlockIn: ByteArray, keyIn: ByteArray): ByteArray {
        val blockWords = leWords(originalBlockIn)
        val keyWords = leWords(keyIn)

        var a = keyWords[0]
        var b = keyWords[1]
        var c = keyWords[2]
        var d = keyWords[3]

        for (i in 0 until 64) {
            val j = when {
                i < 16 -> i
                i < 32 -> (5 * i + 1) % 16
                i < 48 -> (3 * i + 5) % 16
                else -> (7 * i) % 16
            }

            // blockIn holds little-endian dwords; the reference reads the bytes
            // back as a big-endian dword, which is a byte swap.
            val input = Integer.reverseBytes(blockWords[j])
            var z = a + input + SIN_CONSTANTS[i]

            z = when {
                i < 16 -> rotl(z + f(b, c, d), MD5_SHIFTS[i])
                i < 32 -> rotl(z + g(b, c, d), MD5_SHIFTS[i])
                i < 48 -> rotl(z + h(b, c, d), MD5_SHIFTS[i])
                else -> rotl(z + jFun(b, c, d), MD5_SHIFTS[i])
            }

            z += b
            val tmp = d
            d = c
            c = b
            b = z
            a = tmp

            if (i == 31) {
                swap(blockWords, a and 15, b and 15)
                swap(blockWords, c and 15, d and 15)
                swap(blockWords, (a and 0xF0) ushr 4, (b and 0xF0) ushr 4)
                swap(blockWords, (a and 0xF00) ushr 8, (b and 0xF00) ushr 8)
                swap(blockWords, (a and 0xF000) ushr 12, (b and 0xF000) ushr 12)
            }
        }

        val out = ByteArray(16)
        putLe32(out, 0, keyWords[0] + a)
        putLe32(out, 4, keyWords[1] + b)
        putLe32(out, 8, keyWords[2] + c)
        putLe32(out, 12, keyWords[3] + d)
        return out
    }

    private fun f(b: Int, c: Int, d: Int): Int = (b and c) or (b.inv() and d)
    private fun g(b: Int, c: Int, d: Int): Int = (b and d) or (c and d.inv())
    private fun h(b: Int, c: Int, d: Int): Int = b xor c xor d
    private fun jFun(b: Int, c: Int, d: Int): Int = c xor (b or d.inv())

    private fun rotl(input: Int, count: Int): Int = (input shl count) or (input ushr (32 - count))

    private fun swap(words: IntArray, a: Int, b: Int) {
        val tmp = words[a]
        words[a] = words[b]
        words[b] = tmp
    }

    // ------------------------------------------------------------------
    // sap_hash
    // ------------------------------------------------------------------

    private fun sapHash(blockIn: ByteArray): ByteArray {
        val blockWords = leWords(blockIn)
        val buffer0 = IntArray(20) {
            intArrayOf(
                0x96, 0x5F, 0xC6, 0x53, 0xF8, 0x46, 0xCC, 0x18, 0xDF, 0xBE,
                0xB2, 0xF8, 0x38, 0xD7, 0xEC, 0x22, 0x03, 0xD1, 0x20, 0x8F,
            )[it]
        }
        val buffer1 = IntArray(210)
        val buffer2 = intArrayOf(
            0x43, 0x54, 0x62, 0x7A, 0x18, 0xC3, 0xD6, 0xB3, 0x9A, 0x56,
            0xF6, 0x1C, 0x14, 0x3F, 0x0C, 0x1D, 0x3B, 0x36, 0x83, 0xB1,
            0x39, 0x51, 0x4A, 0xAA, 0x09, 0x3E, 0xFE, 0x44, 0xAF, 0xDE,
            0xC3, 0x20, 0x9D, 0x42, 0x3A,
        )
        val buffer3 = IntArray(132)
        val buffer4 = intArrayOf(
            0xED, 0x25, 0xD1, 0xBB, 0xBC, 0x27, 0x9F, 0x02, 0xA2, 0xA9,
            0x11, 0x00, 0x0C, 0xB3, 0x52, 0xC0, 0xBD, 0xE3, 0x1B, 0x49,
            0xC7,
        )
        val i0Index = intArrayOf(18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30)

        // Load the input, cycling through the 64-byte block with the byte order
        // reversed relative to the little-endian dword view.
        for (i in 0 until 210) {
            val inWord = blockWords[(i % 64) ushr 2]
            buffer1[i] = (inWord ushr ((3 - (i % 4)) shl 3)) and 0xff
        }

        // Scramble 840 times, using unsigned 32-bit arithmetic for the indices.
        for (i in 0 until 840) {
            val x = buffer1[unsignedMod(i - 155, 210)]
            val y = buffer1[unsignedMod(i - 57, 210)]
            val z = buffer1[unsignedMod(i - 13, 210)]
            val w = buffer1[i % 210]
            buffer1[i % 210] = (rol8(y, 5) + (rol8(z, 3) xor w) - rol8(x, 7)) and 0xff
        }

        FairPlayGarble.garble(buffer0, buffer1, buffer2, buffer3, buffer4)

        val keyOut = IntArray(16) { 0xE1 }
        for (i in 0 until 11) {
            if (i == 3) {
                keyOut[i] = 0x3d
            } else {
                keyOut[i] = (keyOut[i] + buffer3[i0Index[i] * 4]) and 0xff
            }
        }
        for (i in 0 until 20) keyOut[i % 16] = keyOut[i % 16] xor buffer0[i]
        for (i in 0 until 35) keyOut[i % 16] = keyOut[i % 16] xor buffer2[i]
        for (i in 0 until 210) keyOut[i % 16] = keyOut[i % 16] xor buffer1[i]

        // Reverse-scramble.
        for (j in 0 until 16) {
            for (i in 0 until 16) {
                val x = keyOut[(i - 7) and 15]
                val y = keyOut[i]
                val z = keyOut[(i - 37) and 15]
                val w = keyOut[(i - 177) and 15]
                keyOut[i] = (rol8(x, 1) xor y xor rol8(z, 6) xor rol8(w, 5)) and 0xff
            }
        }

        return ByteArray(16) { keyOut[it].toByte() }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun u8(b: ByteArray, index: Int): Int = b[index].toInt() and 0xff
    private fun le32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or
            ((b[offset + 1].toInt() and 0xff) shl 8) or
            ((b[offset + 2].toInt() and 0xff) shl 16) or
            ((b[offset + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value ushr 8).toByte()
        b[offset + 2] = (value ushr 16).toByte()
        b[offset + 3] = (value ushr 24).toByte()
    }

    private fun leWords(b: ByteArray): IntArray = IntArray(b.size / 4) { le32(b, it * 4) }

    private fun leBytes(words: IntArray): ByteArray {
        val out = ByteArray(words.size * 4)
        for (i in words.indices) putLe32(out, i * 4, words[i])
        return out
    }

    private fun writeLeWords(out: ByteArray, words: IntArray) {
        for (i in words.indices) putLe32(out, i * 4, words[i])
    }

    private fun xorWithKey(block: ByteArray, key: (Int) -> Int) {
        for (i in block.indices) block[i] = (u8(block, i) xor key(i)).toByte()
    }

    private fun rol8(input: Int, count: Int): Int =
        (((input shl count) and 0xff) or ((input and 0xff) ushr (8 - count))) and 0xff

    /** Mirrors C's `((i - offset) & 0xffffffff) % modulus` for non-negative moduli. */
    private fun unsignedMod(i: Int, modulus: Int): Int =
        (((i.toLong() and 0xFFFFFFFFL) % modulus).toInt())

    private const val DEADBEEF = -0x21524111

    private val MD5_SHIFTS = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** `floor(2^32 * abs(sin(i + 1)))` for i = 0..63. */
    private val SIN_CONSTANTS = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
    )
}
