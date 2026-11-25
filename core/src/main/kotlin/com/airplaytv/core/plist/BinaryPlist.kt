package com.airplaytv.core.plist

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Reader and writer for Apple's binary property-list format (`bplist00`).
 *
 * Format overview: an 8-byte signature, a flat object table, an offset table
 * (one big-endian offset per object) and a 32-byte trailer describing the table
 * sizes, the object count, the top object and the offset-table position.
 */
internal object BinaryPlist {

    private val MAGIC = byteArrayOf('b'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(),
        's'.code.toByte(), 't'.code.toByte(), '0'.code.toByte(), '0'.code.toByte())

    private const val TRAILER_SIZE = 32
    private const val MAX_DEPTH = 512

    /** Seconds between the Unix epoch (1970-01-01) and the plist epoch (2001-01-01). */
    private const val DATE_EPOCH_OFFSET = 978307200.0

    fun isBinary(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return false
        }
        return true
    }

    fun parse(bytes: ByteArray): PVal {
        if (!isBinary(bytes)) {
            throw IllegalArgumentException("binary plist: missing bplist00 signature")
        }
        return try {
            Reader(bytes).read()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("binary plist: malformed input (${e.message})", e)
        }
    }

    fun encode(value: PVal): ByteArray = Writer().encode(value)

    // ------------------------------------------------------------------ reader

    private class Reader(private val bytes: ByteArray) {
        private var offsetTableOffset = 0
        private var offsetIntSize = 0
        private var objectRefSize = 0
        private var numObjects = 0
        private var topObject = 0
        private val cache = HashMap<Int, PVal>()
        private val inProgress = HashSet<Int>()

        fun read(): PVal {
            if (bytes.size < MAGIC.size + TRAILER_SIZE) {
                fail("file too short (${bytes.size} bytes)")
            }
            val trailer = bytes.size - TRAILER_SIZE
            offsetIntSize = u8(trailer + 6)
            objectRefSize = u8(trailer + 7)
            if (offsetIntSize !in 1..8) fail("invalid offset integer size $offsetIntSize")
            if (objectRefSize !in 1..8) fail("invalid object reference size $objectRefSize")

            val objectCount = readUnsignedLong(trailer + 8, 8)
            val top = readUnsignedLong(trailer + 16, 8)
            val tableOffset = readUnsignedLong(trailer + 24, 8)
            if (objectCount <= 0) fail("object count is zero")
            if (objectCount > Int.MAX_VALUE) fail("object count $objectCount is too large")
            if (top < 0 || top >= objectCount) fail("top object $top outside 0..${objectCount - 1}")
            if (tableOffset < MAGIC.size || tableOffset > trailer) {
                fail("offset table position $tableOffset outside the object area")
            }
            if (objectCount > (trailer - tableOffset) / offsetIntSize) {
                fail("offset table does not fit in the file")
            }
            numObjects = objectCount.toInt()
            topObject = top.toInt()
            offsetTableOffset = tableOffset.toInt()
            return readObject(topObject, 0)
        }

        private fun readObject(ref: Int, depth: Int): PVal {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH levels")
            cache[ref]?.let { return it }
            if (!inProgress.add(ref)) fail("cyclic object reference at object $ref")
            try {
                val value = parseObject(objectOffset(ref), depth)
                cache[ref] = value
                return value
            } finally {
                inProgress.remove(ref)
            }
        }

        private fun objectOffset(ref: Int): Int {
            if (ref < 0 || ref >= numObjects) fail("object reference $ref out of range")
            val pos = offsetTableOffset.toLong() + ref.toLong() * offsetIntSize
            if (pos + offsetIntSize > bytes.size - TRAILER_SIZE) fail("offset table truncated")
            val offset = readUnsignedLong(pos.toInt(), offsetIntSize)
            if (offset < MAGIC.size || offset >= offsetTableOffset) {
                fail("object $ref has invalid offset $offset")
            }
            return offset.toInt()
        }

        private fun parseObject(offset: Int, depth: Int): PVal {
            val marker = u8(offset)
            val type = marker ushr 4
            val info = marker and 0x0f
            return when (type) {
                0x0 -> when (info) {
                    0x0, 0xf -> PStr("") // null / fill: no protocol value is represented
                    0x8 -> PBool(false)
                    0x9 -> PBool(true)
                    else -> fail("unknown simple marker 0x${marker.toString(16)}")
                }

                0x1 -> {
                    val size = 1 shl info
                    if (size > 16) fail("integer width $size is unsupported")
                    parseInt(offset + 1, size)
                }

                0x2 -> when (info) {
                    2 -> {
                        requireAvailable(offset + 1, 4)
                        PReal(Float.fromBits(readInt(offset + 1, 4)).toDouble())
                    }

                    3 -> {
                        requireAvailable(offset + 1, 8)
                        PReal(Double.fromBits(readLong(offset + 1, 8)))
                    }

                    else -> fail("real with ${1 shl info} bytes is unsupported")
                }

                0x3 -> {
                    if (info != 3) fail("unknown date marker 0x${marker.toString(16)}")
                    requireAvailable(offset + 1, 8)
                    PDate(Double.fromBits(readLong(offset + 1, 8)) + DATE_EPOCH_OFFSET)
                }

                0x4 -> {
                    val (count, start) = length(offset, info)
                    val size = checkedInt(count, start)
                    requireAvailable(start, size)
                    PData(bytes.copyOfRange(start, start + size))
                }

                0x5 -> {
                    val (count, start) = length(offset, info)
                    val size = checkedInt(count, start)
                    requireAvailable(start, size)
                    PStr(String(bytes, start, size, Charsets.ISO_8859_1))
                }

                0x6 -> {
                    val (count, start) = length(offset, info)
                    val size = checkedInt(count, start, 2)
                    requireAvailable(start, size * 2)
                    PStr(String(bytes, start, size * 2, Charsets.UTF_16BE))
                }

                0x8 -> {
                    val size = info + 1
                    if (size > 8) fail("UID width $size is unsupported")
                    requireAvailable(offset + 1, size)
                    PInt(readUnsignedLong(offset + 1, size))
                }

                0xa, 0xc -> { // array (and set, read as an array)
                    val (count, start) = length(offset, info)
                    val countInt = checkedInt(count, start, objectRefSize)
                    requireAvailable(start, countInt * objectRefSize)
                    val items = ArrayList<PVal>(countInt)
                    for (i in 0 until countInt) {
                        items.add(readObject(readRef(start + i * objectRefSize), depth + 1))
                    }
                    PArray(items)
                }

                0xd -> {
                    val (count, start) = length(offset, info)
                    val countInt = checkedInt(count, start, objectRefSize * 2)
                    requireAvailable(start, countInt * objectRefSize * 2)
                    val map = LinkedHashMap<String, PVal>(countInt * 2)
                    for (i in 0 until countInt) {
                        val key = readObject(readRef(start + i * objectRefSize), depth + 1)
                        val keyString = (key as? PStr)?.value
                            ?: fail("dictionary key at index $i is not a string")
                        val valueRef = start + (countInt + i) * objectRefSize
                        map[keyString] = readObject(readRef(valueRef), depth + 1)
                    }
                    PDict(map)
                }

                else -> fail("unknown object marker 0x${marker.toString(16)} at offset $offset")
            }
        }

        private fun parseInt(start: Int, size: Int): PInt {
            requireAvailable(start, size)
            return when {
                size <= 4 -> PInt(readUnsignedLong(start, size))
                size == 8 -> PInt(readLong(start, 8))
                else -> {
                    val value = BigInteger(bytes.copyOfRange(start, start + size))
                    if (value.bitLength() > 63) fail("integer does not fit in 64 bits")
                    PInt(value.toLong())
                }
            }
        }

        /** Decodes the count of a variable-length object and returns it with the payload offset. */
        private fun length(offset: Int, info: Int): Pair<Long, Int> {
            if (info != 0x0f) return info.toLong() to offset + 1
            val marker = u8(offset + 1)
            if (marker ushr 4 != 0x1) fail("expected integer length marker at offset ${offset + 1}")
            val size = 1 shl (marker and 0x0f)
            if (size > 8) fail("length integer width $size is unsupported")
            requireAvailable(offset + 2, size)
            val count = readUnsignedLong(offset + 2, size)
            return count to offset + 2 + size
        }

        private fun readRef(pos: Int): Int {
            val ref = readUnsignedLong(pos, objectRefSize)
            if (ref < 0 || ref >= numObjects) fail("object reference $ref out of range")
            return ref.toInt()
        }

        private fun checkedInt(count: Long, start: Int, unit: Int = 1): Int {
            if (count < 0) fail("negative length $count")
            if (count > (bytes.size - start) / unit) fail("length $count exceeds remaining input")
            return count.toInt()
        }

        private fun requireAvailable(offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                fail("unexpected end of input at offset $offset (+$length bytes)")
            }
        }

        private fun u8(offset: Int): Int {
            if (offset < 0 || offset >= bytes.size) fail("read past end of input at offset $offset")
            return bytes[offset].toInt() and 0xff
        }

        /** Reads [size] (1..8) bytes as an unsigned big-endian quantity. */
        private fun readUnsignedLong(offset: Int, size: Int): Long {
            requireAvailable(offset, size)
            var value = 0L
            for (i in 0 until size) {
                value = (value shl 8) or u8(offset + i).toLong()
            }
            return value
        }

        /** Reads exactly [size] bytes as a signed big-endian integer. */
        private fun readLong(offset: Int, size: Int): Long {
            requireAvailable(offset, size)
            var value = if (size == 8) readUnsignedLong(offset, 8) else readUnsignedLong(offset, size)
            if (size == 8) return value
            // Sign-extend widths below 8 bytes; callers only use this for reals/dates at 8.
            val shift = 64 - size * 8
            value = value shl shift shr shift
            return value
        }

        private fun readInt(offset: Int, size: Int): Int = readUnsignedLong(offset, size).toInt()

        private fun fail(message: String): Nothing = throw IllegalArgumentException("binary plist: $message")
    }

    // ------------------------------------------------------------------ writer

    private class Writer {
        private val objects = ArrayList<PVal>()
        private val containerIndex = IdentityHashMap<PVal, Int>()
        private val leafIndex = HashMap<PVal, Int>()
        private val visiting = Collections.newSetFromMap(IdentityHashMap<PVal, Boolean>())

        fun encode(root: PVal): ByteArray {
            collect(root, 0)
            val count = objects.size
            val refSize = byteCount((count - 1).toLong())
            val bodies = ArrayList<ByteArray>(count)
            for (value in objects) bodies.add(encodeObject(value, refSize))

            var objectAreaSize = MAGIC.size
            for (body in bodies) objectAreaSize += body.size
            val offsetSize = byteCount(objectAreaSize.toLong())
            val offsetTableSize = count * offsetSize
            val totalSize = objectAreaSize + offsetTableSize + TRAILER_SIZE

            val out = ByteArray(totalSize)
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
            var pos = MAGIC.size
            val offsets = IntArray(count)
            for (i in bodies.indices) {
                offsets[i] = pos
                System.arraycopy(bodies[i], 0, out, pos, bodies[i].size)
                pos += bodies[i].size
            }
            for (i in offsets.indices) {
                writeUnsigned(out, pos + i * offsetSize, offsets[i].toLong(), offsetSize)
            }

            val trailer = totalSize - TRAILER_SIZE
            // Bytes trailer..trailer+5 stay zero.
            out[trailer + 6] = offsetSize.toByte()
            out[trailer + 7] = refSize.toByte()
            writeUnsigned(out, trailer + 8, count.toLong(), 8)
            writeUnsigned(out, trailer + 16, 0L, 8) // top object is always index 0
            writeUnsigned(out, trailer + 24, objectAreaSize.toLong(), 8)
            return out
        }

        /** Reserves an object-table slot for [value] and recursively collects its children. */
        private fun collect(value: PVal, depth: Int) {
            if (depth > MAX_DEPTH) fail("plist nesting deeper than $MAX_DEPTH levels")
            if (value is PArray || value is PDict) {
                if (visiting.contains(value)) fail("cannot encode a cyclic property list")
                containerIndex[value]?.let { return }
                containerIndex[value] = objects.size
                objects.add(value)
                visiting.add(value)
                try {
                    when (value) {
                        is PArray -> for (item in value.items) collect(item, depth + 1)
                        is PDict -> for ((key, child) in value.map) {
                            collect(PStr(key), depth + 1)
                            collect(child, depth + 1)
                        }

                        else -> Unit
                    }
                } finally {
                    visiting.remove(value)
                }
            } else {
                if (leafIndex.containsKey(value)) return
                leafIndex[value] = objects.size
                objects.add(value)
            }
        }

        private fun indexOf(value: PVal): Int {
            val index = if (value is PArray || value is PDict) containerIndex[value] else leafIndex[value]
            return index ?: fail("internal encoding error: object is not in the table")
        }

        private fun encodeObject(value: PVal, refSize: Int): ByteArray {
            val sink = ByteArrayOutputStream()
            when (value) {
                is PDict -> {
                    markerAndCount(sink, 0xd, value.size)
                    for (key in value.map.keys) writeUnsigned(sink, indexOf(PStr(key)).toLong(), refSize)
                    for (child in value.map.values) writeUnsigned(sink, indexOf(child).toLong(), refSize)
                }

                is PArray -> {
                    markerAndCount(sink, 0xa, value.size)
                    for (item in value.items) writeUnsigned(sink, indexOf(item).toLong(), refSize)
                }

                is PData -> {
                    markerAndCount(sink, 0x4, value.bytes.size)
                    sink.write(value.bytes)
                }

                is PStr -> encodeString(sink, value.value)
                is PInt -> encodeInteger(sink, value.value)
                is PBool -> sink.write(if (value.value) 0x09 else 0x08)
                is PReal -> {
                    sink.write(0x23)
                    writeUnsigned(sink, value.value.toRawBits(), 8)
                }

                is PDate -> {
                    sink.write(0x33)
                    writeUnsigned(sink, (value.epochSeconds - DATE_EPOCH_OFFSET).toRawBits(), 8)
                }
            }
            return sink.toByteArray()
        }

        private fun encodeString(sink: ByteArrayOutputStream, value: String) {
            if (value.all { it.code < 0x80 }) {
                val raw = value.toByteArray(Charsets.US_ASCII)
                markerAndCount(sink, 0x5, raw.size)
                sink.write(raw)
            } else {
                val raw = value.toByteArray(Charsets.UTF_16BE)
                // The length prefix counts UTF-16 code units, not bytes.
                markerAndCount(sink, 0x6, value.length)
                sink.write(raw)
            }
        }

        private fun encodeInteger(sink: ByteArrayOutputStream, value: Long) {
            when {
                value < 0 -> {
                    sink.write(0x13)
                    writeUnsigned(sink, value, 8)
                }

                value <= 0xffL -> {
                    sink.write(0x10)
                    writeUnsigned(sink, value, 1)
                }

                value <= 0xffffL -> {
                    sink.write(0x11)
                    writeUnsigned(sink, value, 2)
                }

                value <= 0xffffffffL -> {
                    sink.write(0x12)
                    writeUnsigned(sink, value, 4)
                }

                else -> {
                    sink.write(0x13)
                    writeUnsigned(sink, value, 8)
                }
            }
        }

        private fun markerAndCount(sink: ByteArrayOutputStream, type: Int, count: Int) {
            if (count < 0x0f) {
                sink.write((type shl 4) or count)
            } else {
                sink.write((type shl 4) or 0x0f)
                encodeInteger(sink, count.toLong())
            }
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("binary plist: $message")
    }

    // ---------------------------------------------------------------- helpers

    private fun byteCount(value: Long): Int = when {
        value <= 0xffL -> 1
        value <= 0xffffL -> 2
        value <= 0xffffffL -> 3
        value <= 0xffffffffL -> 4
        value <= 0xffffffffffL -> 5
        value <= 0xffffffffffffL -> 6
        value <= 0xffffffffffffffL -> 7
        else -> 8
    }

    private fun writeUnsigned(out: ByteArray, offset: Int, value: Long, size: Int) {
        for (i in 0 until size) {
            out[offset + i] = (value ushr ((size - 1 - i) * 8)).toByte()
        }
    }

    private fun writeUnsigned(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in 0 until size) {
            out.write((value ushr ((size - 1 - i) * 8)).toInt() and 0xff)
        }
    }
}
