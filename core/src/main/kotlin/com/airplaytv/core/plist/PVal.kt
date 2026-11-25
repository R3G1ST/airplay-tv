package com.airplaytv.core.plist

/**
 * In-memory representation of a property-list value.
 *
 * The model is intentionally small: every value AirPlay exchanges maps onto one
 * of these classes. Binary plists contain null/fill markers but the protocol
 * layer has no use for them, so the reader represents those as [PStr] with an
 * empty value.
 */
sealed interface PVal

/** An ordered string-keyed dictionary. Insertion order is preserved. */
data class PDict(val map: LinkedHashMap<String, PVal> = LinkedHashMap()) : PVal {
    val size: Int get() = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    operator fun get(key: String): PVal? = map[key]

    operator fun set(key: String, value: PVal) {
        map[key] = value
    }

    fun contains(key: String): Boolean = map.containsKey(key)

    fun keys(): Set<String> = map.keys

    override fun toString(): String = map.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }
}

/** A homogeneous-or-not list of values. */
class PArray(val items: MutableList<PVal> = mutableListOf()) : PVal {
    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    operator fun get(index: Int): PVal = items[index]

    fun add(value: PVal) {
        items.add(value)
    }

    override fun equals(other: Any?): Boolean = other is PArray && items == other.items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = items.joinToString(", ", "[", "]")
}

/** Opaque byte blob (`<data>` / binary data marker). */
class PData(val bytes: ByteArray) : PVal {
    override fun equals(other: Any?): Boolean = other is PData && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "data(${bytes.size} bytes)"
}

/** A string value. */
data class PStr(val value: String) : PVal

/** A signed 64-bit integer. */
data class PInt(val value: Long) : PVal

/** A boolean value. */
data class PBool(val value: Boolean) : PVal

/** An IEEE-754 double value. */
data class PReal(val value: Double) : PVal

/** An absolute point in time, expressed as seconds since the Unix epoch (UTC). */
data class PDate(val epochSeconds: Double) : PVal
