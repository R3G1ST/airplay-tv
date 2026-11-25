package com.airplaytv.core.airplay

import com.airplaytv.core.util.Bytes

/** DNS-SD TXT record encoding (length-prefixed key/value pairs). */
object Txt {
    fun encode(map: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((k, v) in map) {
            val entry = if (v.isEmpty()) k else "$k=$v"
            val bytes = entry.toByteArray(Charsets.UTF_8)
            require(bytes.size < 256) { "TXT entry too long: $entry" }
            out.write(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }
}
