package com.airplaytv.core.plist

/**
 * Entry point for property-list encoding and decoding.
 *
 * AirPlay peers send both binary (`bplist00`) and XML plists, depending on the
 * request and the OS version. [parse] sniffs the format; [encodeBinary] always
 * produces the compact binary representation Apple's own stack emits.
 */
object Plist {

    /** Parses a binary or XML property list, auto-detecting the format. */
    fun parse(bytes: ByteArray): PVal = if (looksBinary(bytes)) parseBinary(bytes) else parseXml(bytes)

    /** Parses an Apple binary property list (`bplist00`). */
    fun parseBinary(bytes: ByteArray): PVal = BinaryPlist.parse(bytes)

    /** Parses an Apple XML property list. */
    fun parseXml(bytes: ByteArray): PVal = XmlPlist.parse(bytes)

    /** Encodes [value] as an Apple binary property list (`bplist00`). */
    fun encodeBinary(value: PVal): ByteArray = BinaryPlist.encode(value)

    private fun looksBinary(bytes: ByteArray): Boolean {
        val signature = "bplist"
        if (bytes.size < signature.length) return false
        for (i in signature.indices) {
            if (bytes[i] != signature[i].code.toByte()) return false
        }
        return true
    }
}
