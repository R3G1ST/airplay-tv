package com.airplaytv.core.mdns

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Small, dependency-free DNS wire-format codec used by the mDNS responder.
 *
 * The codec covers exactly what DNS-SD over mDNS needs: names (including
 * compression pointers), the fixed 12-byte header, questions and resource
 * records carrying PTR, SRV, TXT and A data. It is deliberately strict while
 * encoding and tolerant while decoding: malformed inbound packets produce an
 * empty question list instead of throwing.
 *
 * Names are represented as dotted, unescaped domain names (`"a.b.local"`).
 * Labels are encoded as UTF-8, which mDNS permits, so Cyrillic instance names
 * work. A trailing dot is accepted and ignored.
 */
object MdnsCodec {
    /** DNS record type: host address (IPv4). */
    const val TYPE_A = 1

    /** DNS record type: domain-name pointer. */
    const val TYPE_PTR = 12

    /** DNS record type: descriptive text. */
    const val TYPE_TXT = 16

    /** DNS record type: IPv6 host address. */
    const val TYPE_AAAA = 28

    /** DNS record type: service locator. */
    const val TYPE_SRV = 33

    /** DNS query type matching every record type. */
    const val TYPE_ANY = 255

    /** DNS class: Internet. */
    const val CLASS_IN = 1

    /** DNS query class matching every class. */
    const val CLASS_ANY = 255

    /** High bit of the question class: request a unicast response (mDNS QU). */
    const val UNICAST_RESPONSE_BIT = 0x8000

    /** High bit of a record class: cache-flush (mDNS unique records). */
    const val CACHE_FLUSH_BIT = 0x8000

    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_POINTER_JUMPS = 64
    private const val MAX_LABELS = 128

    /**
     * A DNS question as found in an mDNS query.
     *
     * [clazz] is the raw 16-bit class field, so the mDNS unicast-response bit
     * ([UNICAST_RESPONSE_BIT]) is preserved; compare the low 15 bits against
     * [CLASS_IN] or [CLASS_ANY].
     */
    data class Question(val name: String, val type: Int, val clazz: Int)

    /**
     * A resource record. [data] holds the RDATA exactly as it appears on the
     * wire except that names inside PTR, CNAME, NS and SRV records are stored
     * as uncompressed, dotted names encoded with [encodeName].
     */
    data class Record(
        val name: String,
        val type: Int,
        val clazz: Int,
        val ttl: Long,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Record) return false
            return name == other.name &&
                type == other.type &&
                clazz == other.clazz &&
                ttl == other.ttl &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + type
            result = 31 * result + clazz
            result = 31 * result + ttl.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Encodes [name] as an uncompressed sequence of length-prefixed UTF-8
     * labels terminated by the root label. Throws [IllegalArgumentException]
     * for empty labels, oversized labels or an oversized name.
     */
    fun encodeName(name: String): ByteArray {
        val trimmed = name.trimEnd('.')
        val out = ByteArrayOutputStream(trimmed.length + 2)
        if (trimmed.isNotEmpty()) {
            for (label in trimmed.split('.')) {
                require(label.isNotEmpty()) { "empty DNS label in '$name'" }
                val bytes = label.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_LABEL_LENGTH) {
                    "DNS label longer than $MAX_LABEL_LENGTH bytes: '$label'"
                }
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0)
        val encoded = out.toByteArray()
        require(encoded.size <= MAX_NAME_LENGTH) { "DNS name longer than $MAX_NAME_LENGTH bytes: '$name'" }
        return encoded
    }

    /**
     * Decodes a possibly compressed name starting at [offset].
     *
     * Returns the dotted name and the offset of the first byte after the name
     * in the *original* byte stream (pointer chains are followed without
     * affecting the returned offset). Throws [IllegalArgumentException] on
     * malformed input, including pointer loops.
     */
    fun decodeName(bytes: ByteArray, offset: Int): Pair<String, Int> {
        require(offset in 0 until bytes.size) { "name offset out of range" }
        val labels = ArrayList<String>(4)
        var pos = offset
        var next = -1
        var jumps = 0

        while (true) {
            require(pos < bytes.size) { "truncated DNS name" }
            val length = bytes[pos].toInt() and 0xff
            when {
                length == 0 -> {
                    pos++
                    if (next < 0) next = pos
                    break
                }

                length and 0xc0 == 0xc0 -> {
                    require(pos + 1 < bytes.size) { "truncated compression pointer" }
                    val pointer = ((length and 0x3f) shl 8) or (bytes[pos + 1].toInt() and 0xff)
                    require(pointer < bytes.size) { "compression pointer out of range" }
                    if (next < 0) next = pos + 2
                    pos = pointer
                    jumps++
                    require(jumps <= MAX_POINTER_JUMPS) { "compression pointer loop" }
                }

                length and 0xc0 != 0 -> throw IllegalArgumentException("unsupported DNS label type")
                else -> {
                    require(pos + 1 + length <= bytes.size) { "truncated DNS label" }
                    labels += String(bytes, pos + 1, length, StandardCharsets.UTF_8)
                    require(labels.size <= MAX_LABELS) { "too many DNS labels" }
                    pos += 1 + length
                }
            }
        }
        return labels.joinToString(".") to next
    }

    /**
     * Builds a DNS response containing [records] (answers followed by
     * additionals), using name compression.
     *
     * [answers] and [additionals] populate ANCOUNT and ARCOUNT and must sum to
     * `records.size`. The response is authoritative (`AA=1`); the ID is zero,
     * as required for multicast DNS.
     */
    fun buildResponse(records: List<Record>, answers: Int, additionals: Int): ByteArray {
        require(answers >= 0 && additionals >= 0) { "negative record count" }
        require(answers + additionals == records.size) {
            "record counts do not match list size: $answers + $additionals != ${records.size}"
        }

        val out = ByteArrayOutputStream(512)
        writeU16(out, 0) // transaction id: zero for mDNS
        writeU16(out, 0x8400) // QR=1, AA=1, opcode 0, rcode 0
        writeU16(out, 0) // QDCOUNT
        writeU16(out, answers)
        writeU16(out, 0) // NSCOUNT
        writeU16(out, additionals)

        val compression = HashMap<String, Int>()
        for (record in records) {
            writeRecord(out, record, compression)
        }
        return out.toByteArray()
    }

    /**
     * Parses the questions of an mDNS message. Returns an empty list for
     * malformed or truncated packets (or packets without questions) rather
     * than throwing, so a hostile packet can never break the receive loop.
     */
    fun parseMessage(bytes: ByteArray): List<Question> {
        if (bytes.size < 12) return emptyList()
        return try {
            val questionCount = readU16(bytes, 4)
            if (questionCount == 0) return emptyList()
            var pos = 12
            val questions = ArrayList<Question>(questionCount)
            repeat(questionCount) {
                val (name, next) = decodeName(bytes, pos)
                pos = next
                if (pos + 4 > bytes.size) return emptyList()
                val type = readU16(bytes, pos)
                val clazz = readU16(bytes, pos + 2)
                pos += 4
                questions += Question(name, type, clazz)
            }
            questions
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun writeRecord(out: ByteArrayOutputStream, record: Record, compression: MutableMap<String, Int>) {
        writeName(out, record.name, compression)
        writeU16(out, record.type)
        writeU16(out, record.clazz)
        writeU32(out, record.ttl)

        val lengthOffset = out.size()
        writeU16(out, 0)
        val rdataStart = out.size()
        when (record.type) {
            TYPE_PTR -> writeName(out, decodeRdataName(record.data, 0), compression)
            TYPE_SRV -> {
                require(record.data.size >= 7) { "SRV RDATA too short" }
                out.write(record.data, 0, 6)
                writeName(out, decodeRdataName(record.data, 6), compression)
            }

            else -> out.write(record.data)
        }
        val rdataLength = out.size() - rdataStart
        writeU16At(out, lengthOffset, rdataLength)
    }

    private fun decodeRdataName(data: ByteArray, offset: Int): String {
        return try {
            decodeName(data, offset).first
        } catch (_: IllegalArgumentException) {
            // Callers only construct well-formed records; fall back to raw
            // encoding rather than corrupting an outgoing packet.
            val raw = String(data, offset, data.size - offset, StandardCharsets.ISO_8859_1)
            raw.trimEnd('.')
        }
    }

    private fun writeName(out: ByteArrayOutputStream, name: String, compression: MutableMap<String, Int>) {
        val trimmed = name.trimEnd('.')
        val labels = if (trimmed.isEmpty()) emptyList() else trimmed.split('.')
        for (index in labels.indices) {
            val suffix = labels.subList(index, labels.size).joinToString(".")
            val key = suffix.lowercase()
            val existing = compression[key]
            if (existing != null) {
                writeU16(out, 0xc000 or existing)
                return
            }
            if (out.size() < 0x4000) compression[key] = out.size()

            val bytes = labels[index].toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_LABEL_LENGTH) { "DNS label too long: '${labels[index]}'" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xff).toInt())
        out.write(((value ushr 16) and 0xff).toInt())
        out.write(((value ushr 8) and 0xff).toInt())
        out.write((value and 0xff).toInt())
    }

    private fun writeU16At(out: ByteArrayOutputStream, offset: Int, value: Int) {
        val bytes = out.toByteArray()
        bytes[offset] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 1] = (value and 0xff).toByte()
        out.reset()
        out.write(bytes)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }
}
