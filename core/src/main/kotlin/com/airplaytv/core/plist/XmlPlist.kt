package com.airplaytv.core.plist

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.GregorianCalendar
import java.util.TimeZone
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reader for Apple's XML property-list format. Apple still uses XML plists for
 * a few AirPlay requests, so the binary reader is backed by this fallback.
 *
 * The parser never resolves external entities or DTDs; the standard plist
 * `<!DOCTYPE ...>` declaration is accepted but its system identifier is ignored.
 */
internal object XmlPlist {

    private val DATE_PATTERN = Regex(
        """^\s*(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?\s*$"""
    )

    fun parse(input: ByteArray): PVal {
        val bytes = stripBom(input)
        val document = try {
            newBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: SAXException) {
            throw IllegalArgumentException("xml plist: malformed document (${e.message})", e)
        } catch (e: Exception) {
            throw IllegalArgumentException("xml plist: cannot parse document (${e.message})", e)
        }
        val root = document.documentElement
            ?: throw IllegalArgumentException("xml plist: document has no root element")
        val value = if (root.tagName.equals("plist", ignoreCase = true)) {
            val children = childElements(root)
            if (children.size != 1) {
                throw IllegalArgumentException("xml plist: <plist> must contain exactly one value, found ${children.size}")
            }
            children[0]
        } else {
            root
        }
        return convert(value)
    }

    private fun newBuilder(): javax.xml.parsers.DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        try {
            factory.isXIncludeAware = false
        } catch (_: Exception) {
            // Optional JAXP feature.
        }
        factory.isExpandEntityReferences = false
        // Accept the plist doctype but never fetch or expand anything external.
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", false)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        } catch (_: Exception) {
            // Not supported by every Android parser implementation; features above still apply.
        }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit

            override fun error(exception: SAXParseException) {
                throw exception
            }

            override fun fatalError(exception: SAXParseException) {
                throw exception
            }
        })
        return builder
    }

    private fun setFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Exception) {
            // Feature unsupported by this JAXP implementation; ignore.
        }
    }

    private fun convert(element: Element): PVal = when (element.tagName.lowercase()) {
        "dict" -> convertDict(element)
        "array" -> convertArray(element)
        "string" -> PStr(element.textContent ?: "")
        "integer" -> PInt(parseInteger(element.textContent ?: ""))
        "real" -> PReal(parseReal(element.textContent ?: ""))
        "true" -> PBool(true)
        "false" -> PBool(false)
        "date" -> PDate(parseDate(element.textContent ?: ""))
        "data" -> PData(decodeBase64(element.textContent ?: ""))
        else -> throw IllegalArgumentException("xml plist: unsupported element <${element.tagName}>")
    }

    private fun convertDict(element: Element): PDict {
        val map = LinkedHashMap<String, PVal>()
        var pendingKey: String? = null
        for (child in childElements(element)) {
            if (child.tagName.equals("key", ignoreCase = true)) {
                if (pendingKey != null) {
                    throw IllegalArgumentException("xml plist: <key> without a value in <dict>")
                }
                pendingKey = child.textContent ?: ""
            } else {
                val key = pendingKey
                    ?: throw IllegalArgumentException("xml plist: value <${child.tagName}> without a preceding <key>")
                map[key] = convert(child)
                pendingKey = null
            }
        }
        if (pendingKey != null) {
            throw IllegalArgumentException("xml plist: <key> without a value at the end of <dict>")
        }
        return PDict(map)
    }

    private fun convertArray(element: Element): PArray {
        val items = ArrayList<PVal>()
        for (child in childElements(element)) items.add(convert(child))
        return PArray(items)
    }

    private fun parseInteger(text: String): Long {
        val value = text.trim()
        if (value.isEmpty()) throw IllegalArgumentException("xml plist: empty <integer>")
        val number = try {
            when {
                value.startsWith("-0x") || value.startsWith("-0X") ->
                    BigInteger(value.substring(3), 16).negate()

                value.startsWith("0x") || value.startsWith("0X") ->
                    BigInteger(value.substring(2), 16)

                else -> BigInteger(value, 10)
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("xml plist: invalid integer '$value'", e)
        }
        if (number.bitLength() > 63) {
            // Accept the unsigned 64-bit range, matching how binary plists store it.
            if (number.signum() >= 0 && number.bitLength() <= 64) return number.toLong()
            throw IllegalArgumentException("xml plist: integer '$value' does not fit in 64 bits")
        }
        return number.toLong()
    }

    private fun parseReal(text: String): Double {
        val value = text.trim().lowercase()
        return when (value) {
            "nan" -> Double.NaN
            "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
            else -> value.toDoubleOrNull()
                ?: throw IllegalArgumentException("xml plist: invalid real '$value'")
        }
    }

    private fun parseDate(text: String): Double {
        val match = DATE_PATTERN.matchEntire(text)
            ?: throw IllegalArgumentException("xml plist: invalid date '$text'")
        val groups = match.groupValues
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            isLenient = false
            clear()
            set(
                groups[1].toInt(),
                groups[2].toInt() - 1,
                groups[3].toInt(),
                groups[4].toInt(),
                groups[5].toInt(),
                groups[6].toInt()
            )
        }
        val seconds = calendar.timeInMillis / 1000.0
        val fraction = if (groups[7].isEmpty()) 0.0 else ("0.${groups[7]}").toDouble()
        val zone = groups[8]
        val offsetSeconds = when {
            zone.isEmpty() || zone == "Z" -> 0
            else -> {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val hours = digits.substring(0, 2).toInt()
                val minutes = digits.substring(2, 4).toInt()
                sign * (hours * 3600 + minutes * 60)
            }
        }
        return seconds + fraction - offsetSeconds
    }

    /** Decodes base64, ignoring ASCII whitespace, without relying on API-26-only java.util.Base64. */
    private fun decodeBase64(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length / 4 * 3 + 3)
        var buffer = 0
        var bits = 0
        var padding = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            if (ch == '=') {
                padding++
                continue
            }
            if (padding > 0) throw IllegalArgumentException("xml plist: invalid base64 padding")
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> throw IllegalArgumentException("xml plist: invalid base64 character '$ch'")
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        if (bits >= 6) throw IllegalArgumentException("xml plist: truncated base64 data")
        return out.toByteArray()
    }

    private fun childElements(parent: Element): List<Element> {
        val result = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node: Node = nodes.item(i)
            if (node is Element) result.add(node)
        }
        return result
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }
}
