package com.airplaytv.core.plist

/** Casts this value to a dictionary or fails with a descriptive error. */
fun PVal.asDict(): PDict = this as? PDict
    ?: throw IllegalArgumentException("expected dict but found ${typeName(this)}")

/** Casts this value to an array or fails with a descriptive error. */
fun PVal.asArray(): PArray = this as? PArray
    ?: throw IllegalArgumentException("expected array but found ${typeName(this)}")

private fun typeName(value: PVal): String = when (value) {
    is PDict -> "dict"
    is PArray -> "array"
    is PData -> "data"
    is PStr -> "string"
    is PInt -> "integer"
    is PBool -> "boolean"
    is PReal -> "real"
    is PDate -> "date"
}

/** Returns the string stored under [key], or null when absent or a different type. */
fun PDict.str(key: String): String? = (map[key] as? PStr)?.value

/** Returns the integer stored under [key], or null when absent or a different type. */
fun PDict.int(key: String): Long? = (map[key] as? PInt)?.value

/** Returns the boolean stored under [key], or null when absent or a different type. */
fun PDict.bool(key: String): Boolean? = (map[key] as? PBool)?.value

/**
 * Returns the real stored under [key]. Integers are accepted as reals because
 * iTunes-style plists occasionally send integral numbers without a decimal point.
 */
fun PDict.real(key: String): Double? = when (val value = map[key]) {
    is PReal -> value.value
    is PInt -> value.value.toDouble()
    else -> null
}

/** Returns the data stored under [key], or null when absent or a different type. */
fun PDict.data(key: String): ByteArray? = (map[key] as? PData)?.bytes

/** Returns the dictionary stored under [key], or null when absent or a different type. */
fun PDict.dict(key: String): PDict? = map[key] as? PDict

/** Returns the array stored under [key], or null when absent or a different type. */
fun PDict.array(key: String): PArray? = map[key] as? PArray

/** Builds a [PDict] from pairs; later duplicates replace earlier values. */
fun pdict(vararg pairs: Pair<String, PVal>): PDict {
    val map = LinkedHashMap<String, PVal>(pairs.size * 2)
    for ((key, value) in pairs) map[key] = value
    return PDict(map)
}

/** Builds a [PArray] from values. */
fun parr(vararg items: PVal): PArray = PArray(items.toMutableList())

/** Creates an integer value. */
fun PI(n: Long): PInt = PInt(n)

/** Creates an integer value. */
fun PI(n: Int): PInt = PInt(n.toLong())

/** Creates a string value. */
fun PS(s: String): PStr = PStr(s)

/** Creates a boolean value. */
fun PB(b: Boolean): PBool = PBool(b)

/** Creates a data value. The array is used as-is and must not be mutated afterwards. */
fun PD(bytes: ByteArray): PData = PData(bytes)

/** Creates a real value. */
fun PR(d: Double): PReal = PReal(d)
