package com.airplaytv.core.plist

import java.io.File
import java.nio.file.Files

/**
 * Self-test suite for the property-list module.
 *
 * Called reflectively by `com.airplaytv.core.Verify`; any failure must surface
 * as an [AssertionError].
 */
object PlistVerify {

    @JvmStatic
    fun run() {
        richRoundTrip()
        largeCollectionRoundTrip()
        handcraftedBinaryForms()
        pythonGeneratedFixture()
        pythonReadsOurEncoding()
        xmlFixture()
        malformedInputIsRejected()
        println("PlistVerify: all checks passed")
    }

    // --------------------------------------------------------------- fixtures

    /** A dict exercising every value type and several encodings. */
    private fun richDict(): PDict = pdict(
        "name" to PS("AirPlay"),
        "russian" to PS("Привет, мир"),
        "emoji" to PS("\uD83C\uDFB5\uD83C\uDFAC"),
        "features" to PI(0x1E5A7FFFF7L),
        "neg" to PI(-42L),
        "small" to PI(255L),
        "big" to PI(Long.MAX_VALUE),
        "flag" to PB(true),
        "off" to PB(false),
        "ratio" to PR(1.25),
        "when" to PDate(1589711400.0),
        "bytes" to PD(byteArrayOf(0x00, 0x01, 0xfe.toByte(), 0xff.toByte())),
        "list" to parr(PI(1), PS("two"), pdict("k" to PS("v")), parr(PR(3.5))),
        "nested" to pdict("a" to pdict("b" to parr(PB(true)))),
        "empty_list" to parr(),
        "empty_dict" to pdict(),
        "empty_str" to PS("")
    )

    /**
     * 431-byte `bplist00` produced by CPython's `plistlib` (see task notes for
     * the generator). It contains sorted keys, a UID, a date, UTF-16 strings and
     * a 64-bit feature mask.
     */
    private val PYTHON_FIXTURE_HEX =
        "62706c6973743030df10120102030405060708090a0b0c0d0e0f101112131415161718191a1b2324252a2b2c" +
            "2d2e2f5362696755627974657355656d6f6a695a656d7074795f646963745a656d7074795f6c69737459656d" +
            "7074795f73747258666561747572657354666c6167546c697374546e616d65536e6567566e6573746564536f" +
            "666655726174696f577275737369616e55736d616c6c53756964547768656e137fffffffffffffff440001fe" +
            "ff64d83cdfb5d83cdfacd0a050130000001e5a7ffff709a41c1d1e2110015374776fd11f20516b5176a12223" +
            "400c00000000000057416972506c617913ffffffffffffffd6d126275161d128295162a11a08233ff4000000" +
            "0000006b041f04400438043204350442002c0020043c0438044010ff804d3341c238a4d40000000008002f00" +
            "330039003f004a0055005f0068006d00720077007b00820086008c0094009a009e00a300ac00b100ba00bb00" +
            "bc00bd00c600c700cc00ce00d200d500d700d900db00e400ec00f500f800fa00fd00ff01010102010b012201" +
            "240126000000000000020100000000000000300000000000000000000000000000012f"

    private val XML_FIXTURE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>name</key><string>AirPlay</string>
            <key>russian</key><string>Привет</string>
            <key>ok</key><true/>
            <key>no</key><false/>
            <key>n</key><integer>42</integer>
            <key>hex</key><integer>0x1E5A7FFFF7</integer>
            <key>neg</key><integer>-7</integer>
            <key>r</key><real>1.5</real>
            <key>when</key><date>2020-05-17T10:30:00Z</date>
            <key>buf</key><data>AAH+/w==</data>
            <key>list</key>
            <array>
                <string>a</string>
                <dict><key>k</key><integer>1</integer></dict>
            </array>
        </dict>
        </plist>
    """.trimIndent()

    // ------------------------------------------------------------------ tests

    private fun richRoundTrip() {
        val rich = richDict()
        val encoded = Plist.encodeBinary(rich)
        assertEquals("bplist00", String(encoded, 0, 8, Charsets.US_ASCII), "binary signature")
        assertEquals(rich, Plist.parseBinary(encoded), "rich dict binary roundtrip")
        assertEquals(rich, Plist.parse(encoded), "rich dict auto-detected roundtrip")

        val parsed = Plist.parseBinary(encoded)
        assertEquals(0x1E5A7FFFF7L, parsed.asDict().int("features"), "64-bit features value")
        assertEquals(255L, parsed.asDict().int("small"), "one-byte integer")
        assertEquals(-42L, parsed.asDict().int("neg"), "negative integer")
        assertEquals("🎵🎬", parsed.asDict().str("emoji"), "surrogate-pair string")

        assertEquals(PS("hello"), Plist.parseBinary(Plist.encodeBinary(PS("hello"))), "top-level string")
        assertEquals(
            parr(PI(1), PS("x"), PB(true)),
            Plist.parseBinary(Plist.encodeBinary(parr(PI(1), PS("x"), PB(true)))),
            "top-level array"
        )
        assertEquals(pdict(), Plist.parseBinary(Plist.encodeBinary(pdict())), "empty top-level dict")
        assertEquals(PD(ByteArray(0)), Plist.parseBinary(Plist.encodeBinary(PD(ByteArray(0)))), "empty data")
    }

    /** Forces 2-byte object references and long-length markers in the writer. */
    private fun largeCollectionRoundTrip() {
        val map = LinkedHashMap<String, PVal>()
        for (i in 0 until 400) {
            map["key_$i"] = pdict("index" to PI(i.toLong()), "text" to PS("value-$i"))
        }
        map["blob"] = PD(ByteArray(700) { (it * 31).toByte() })
        map["long_text"] = PS("x".repeat(300))
        map["long_utf16"] = PS("Привет".repeat(40))
        val big = PDict(map)
        val bytes = Plist.encodeBinary(big)
        assertEquals(big, Plist.parseBinary(bytes), "large dict roundtrip")
        assertEquals(700, Plist.parseBinary(bytes).asDict().data("blob")?.size, "long data length")
        assertEquals(300, Plist.parseBinary(bytes).asDict().str("long_text")?.length, "long ascii length")
        assertEquals(240, Plist.parseBinary(bytes).asDict().str("long_utf16")?.length, "long utf16 length")
    }

    /** Exercises integer width 2/4/16 and 4-byte reals, which our encoder never emits. */
    private fun handcraftedBinaryForms() {
        val array = Plist.parseBinary(
            unhex(
                "62706c6973743030a40102030411123412000f4240223fc00000140000000000000000000000000000002a08" +
                    "0d10151a000000000000010100000000000000050000000000000000000000000000002b"
            )
        ).asArray()
        assertEquals(4, array.size, "handcrafted array size")
        assertEquals(0x1234L, (array[0] as PInt).value, "two-byte integer")
        assertEquals(1_000_000L, (array[1] as PInt).value, "four-byte integer")
        assertEquals(1.5, (array[2] as PReal).value, "four-byte real")
        assertEquals(42L, (array[3] as PInt).value, "sixteen-byte integer")
    }

    private fun pythonGeneratedFixture() {
        val fixture = unhex(PYTHON_FIXTURE_HEX)
        assertEquals(431, fixture.size, "fixture size")
        val dict = Plist.parse(fixture).asDict()
        assertEquals("AirPlay", dict.str("name"), "fixture name")
        assertEquals("Привет, мир", dict.str("russian"), "fixture russian")
        assertEquals("🎵🎬", dict.str("emoji"), "fixture emoji")
        assertEquals(0x1E5A7FFFF7L, dict.int("features"), "fixture features")
        assertEquals(-42L, dict.int("neg"), "fixture negative")
        assertEquals(255L, dict.int("small"), "fixture small int")
        assertEquals(Long.MAX_VALUE, dict.int("big"), "fixture 64-bit int")
        assertEquals(true, dict.bool("flag"), "fixture true")
        assertEquals(false, dict.bool("off"), "fixture false")
        assertEquals(1.25, dict.real("ratio"), "fixture real")
        assertEquals(77L, dict.int("uid"), "fixture UID")
        assertNear(1589711400.0, (dict["when"] as PDate).epochSeconds, 0.001, "fixture date")
        assertBytes(byteArrayOf(0x00, 0x01, 0xfe.toByte(), 0xff.toByte()), dict.data("bytes"), "fixture data")

        val list = dict.array("list") ?: throw AssertionError("fixture list missing")
        assertEquals(4, list.size, "fixture list size")
        assertEquals(1L, (list[0] as PInt).value, "fixture list[0]")
        assertEquals("two", (list[1] as PStr).value, "fixture list[1]")
        assertEquals("v", list[2].asDict().str("k"), "fixture list[2]")
        assertEquals(3.5, (list[3].asArray()[0] as PReal).value, "fixture list[3]")
        assertEquals(true, (dict.dict("nested")?.dict("a")?.array("b")?.get(0) as? PBool)?.value, "fixture nested")
        assertEquals(0, dict.array("empty_list")?.size, "fixture empty list")
        assertEquals(0, dict.dict("empty_dict")?.size, "fixture empty dict")
        assertEquals("", dict.str("empty_str"), "fixture empty string")
    }

    private fun pythonReadsOurEncoding() {
        if (!python3Available()) {
            println("PlistVerify: python3 not found, skipping plistlib cross-check")
            return
        }
        val rich = richDict()
        val output = runPythonOnPlist(rich)
        assertContains(output, "\"name\": \"AirPlay\"", "python read name")
        assertContains(output, "\"russian\": \"Привет, мир\"", "python read russian")
        assertContains(output, "\"emoji\": \"🎵🎬\"", "python read emoji")
        assertContains(output, "\"features\": 130367356919", "python read features")
        assertContains(output, "\"neg\": -42", "python read negative")
        assertContains(output, "\"big\": 9223372036854775807", "python read 64-bit int")
        assertContains(output, "\"small\": 255", "python read 1-byte int")
        assertContains(output, "\"flag\": true", "python read true")
        assertContains(output, "\"off\": false", "python read false")
        assertContains(output, "\"ratio\": 1.25", "python read real")
        assertContains(output, "2020-05-17 10:30:00", "python read date")
        assertContains(output, "\"bytes\": \"b'\\\\x00\\\\x01\\\\xfe\\\\xff'\"", "python read data")

        val map = LinkedHashMap<String, PVal>()
        for (i in 0 until 400) map["key_$i"] = PI(i.toLong())
        val bigOutput = runPythonOnPlist(PDict(map))
        assertContains(bigOutput, "\"key_399\": 399", "python read large table")
    }

    private fun xmlFixture() {
        val parsed = Plist.parse(XML_FIXTURE.toByteArray(Charsets.UTF_8)).asDict()
        assertEquals("AirPlay", parsed.str("name"), "xml name")
        assertEquals("Привет", parsed.str("russian"), "xml russian")
        assertEquals(true, parsed.bool("ok"), "xml true")
        assertEquals(false, parsed.bool("no"), "xml false")
        assertEquals(42L, parsed.int("n"), "xml integer")
        assertEquals(0x1E5A7FFFF7L, parsed.int("hex"), "xml hex integer")
        assertEquals(-7L, parsed.int("neg"), "xml negative")
        assertEquals(1.5, parsed.real("r"), "xml real")
        assertNear(1589711400.0, (parsed["when"] as PDate).epochSeconds, 0.001, "xml date")
        assertBytes(byteArrayOf(0x00, 0x01, 0xfe.toByte(), 0xff.toByte()), parsed.data("buf"), "xml data")
        val list = parsed.array("list") ?: throw AssertionError("xml list missing")
        assertEquals(2, list.size, "xml list size")
        assertEquals("a", (list[0] as PStr).value, "xml list[0]")
        assertEquals(1L, list[1].asDict().int("k"), "xml nested dict")

        assertEquals(PS("hi"), Plist.parseXml("<string>hi</string>".toByteArray()), "xml bare string root")
    }

    private fun malformedInputIsRejected() {
        val fixture = unhex(PYTHON_FIXTURE_HEX)
        expectIllegalArgument("empty input") { Plist.parseBinary(ByteArray(0)) }
        expectIllegalArgument("wrong signature") { Plist.parseBinary("not-a-plist".toByteArray()) }
        expectIllegalArgument("truncated file") { Plist.parseBinary(fixture.copyOf(60)) }
        expectIllegalArgument("zeroed trailer") { Plist.parseBinary(ByteArray(64)) }
        expectIllegalArgument("truncated xml") { Plist.parseXml("<plist><dict>".toByteArray()) }
        expectIllegalArgument("dangling xml key") {
            Plist.parseXml("<plist><dict><key>a</key></dict></plist>".toByteArray())
        }

        val cyclic = pdict()
        cyclic["self"] = cyclic
        expectIllegalArgument("cyclic encode") { Plist.encodeBinary(cyclic) }
    }

    // --------------------------------------------------------------- plumbing

    private fun unhex(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        assertTrue(clean.length % 2 == 0, "hex length is even")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    private fun python3Available(): Boolean = try {
        val process = ProcessBuilder("python3", "-c", "import plistlib")
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    /** Encodes [value], has CPython's plistlib read it back, and returns the JSON dump. */
    private fun runPythonOnPlist(value: PVal): String {
        val file: File = Files.createTempFile("airplay-plist", ".bin").toFile()
        try {
            file.writeBytes(Plist.encodeBinary(value))
            val script = "import plistlib,sys,json;" +
                "d=plistlib.load(open(sys.argv[1],'rb'));" +
                "print(json.dumps(d,default=str,ensure_ascii=False))"
            val process = ProcessBuilder("python3", "-c", script, file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = String(process.inputStream.readBytes(), Charsets.UTF_8)
            val exit = process.waitFor()
            if (exit != 0) {
                throw AssertionError("python3 failed to read our plist (exit $exit):\n$output")
            }
            return output
        } finally {
            file.delete()
        }
    }

    private fun assertEquals(expected: Any?, actual: Any?, what: String) {
        if (expected != actual) {
            throw AssertionError("$what: expected <$expected> but was <$actual>")
        }
    }

    private fun assertTrue(condition: Boolean, what: String) {
        if (!condition) throw AssertionError(what)
    }

    private fun assertBytes(expected: ByteArray?, actual: ByteArray?, what: String) {
        if (expected == null || actual == null || !expected.contentEquals(actual)) {
            throw AssertionError(
                "$what: expected <${expected?.joinToString(",")}> but was <${actual?.joinToString(",")}>"
            )
        }
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String) {
        if (Math.abs(expected - actual) > tolerance) {
            throw AssertionError("$what: expected <$expected> but was <$actual>")
        }
    }

    private fun assertContains(haystack: String, needle: String, what: String) {
        if (!haystack.contains(needle)) {
            throw AssertionError("$what: output does not contain <$needle>\nfull output:\n$haystack")
        }
    }

    private fun expectIllegalArgument(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("$what: expected IllegalArgumentException")
    }
}
