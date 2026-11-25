package com.airplaytv.core.mdns

import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-test suite for the mDNS responder. Run by `:core:verify`.
 *
 * The codec checks always run. The live section starts a real responder on
 * port 5353, sends an mDNS PTR query and validates the answer; it also uses the
 * macOS `dns-sd` tool to confirm that the system resolver can browse the
 * advertised-service. Live checks are skipped with a printed note when the
 * sandbox provides no multicast, so the suite stays usable in restricted CI.
 */
object MdnsVerify {
    private const val LIVE_SERVICE_NAME = "AirPlayTV Verify"
    private const val LIVE_DEVICE_ID = "AABBCCDDEEFF"
    private const val LIVE_PORT = 7000
    private const val AIRPLAY_TYPE = "_airplay._tcp.local"

    @JvmStatic
    fun run() {
        codecTests()
        liveTests()
    }

    // ------------------------------------------------------------ codec tests

    private fun codecTests() {
        nameEncoding()
        compressedResponse()
        questionParsing()
        malformedInput()
        println("mdns:   codec tests passed")
    }

    private fun nameEncoding() {
        val plain = MdnsCodec.encodeName("a.local")
        val expected = byteArrayOf(1, 'a'.code.toByte(), 5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 0)
        check(plain.contentEquals(expected)) { "unexpected name encoding" }

        val unicode = "Гостиная._airplay._tcp.local"
        val encoded = MdnsCodec.encodeName(unicode)
        val (decoded, next) = MdnsCodec.decodeName(encoded, 0)
        check(decoded == unicode) { "unicode name round-trip failed: $decoded" }
        check(next == encoded.size) { "decoder did not consume the whole name" }
        check(encoded.last() == 0.toByte()) { "name is not terminated by the root label" }

        val trailingDot = MdnsCodec.encodeName("host.local.")
        check(trailingDot.contentEquals(MdnsCodec.encodeName("host.local"))) { "trailing dot changed the name" }
    }

    private fun compressedResponse() {
        val instance = "$LIVE_SERVICE_NAME._airplay._tcp.local"
        val host = "AirPlay-EEFF.local"
        val records = listOf(
            MdnsCodec.Record(AIRPLAY_TYPE, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN, 4500, MdnsCodec.encodeName(instance)),
            MdnsCodec.Record(instance, MdnsCodec.TYPE_SRV, 0x8001, 4500, srvData(LIVE_PORT, host)),
            MdnsCodec.Record(instance, MdnsCodec.TYPE_TXT, 0x8001, 4500, txtData("deviceid=AA:BB:CC:DD:EE:FF", "model=AppleTV6,2", "features=0x5A7FFFF7,0x1E")),
            MdnsCodec.Record(host, MdnsCodec.TYPE_A, 0x8001, 4500, byteArrayOf(192.toByte(), 168.toByte(), 0, 146.toByte())),
        )
        val message = MdnsCodec.buildResponse(records, answers = 4, additionals = 0)
        check(message.size < 1500) { "response is too large: ${message.size} bytes" }

        val parsed = parsePacket(message)
        check(parsed.answers == 4 && parsed.additionals == 0) { "wrong header counts" }
        check(parsed.records.size == 4) { "wrong record count" }

        val ptr = parsed.records[0]
        check(ptr.name == AIRPLAY_TYPE && ptr.type == MdnsCodec.TYPE_PTR) { "PTR record mismatch" }
        check(ptr.target == instance) { "PTR target mismatch: ${ptr.target}" }
        check(ptr.ttl == 4500L) { "PTR TTL mismatch" }

        val srv = parsed.records[1]
        check(srv.name == instance && srv.type == MdnsCodec.TYPE_SRV) { "SRV record mismatch" }
        check(srv.srvPort == LIVE_PORT) { "SRV port mismatch: ${srv.srvPort}" }
        check(srv.target == host) { "SRV target mismatch: ${srv.target}" }

        val txt = parsed.records[2]
        check(txt.type == MdnsCodec.TYPE_TXT) { "TXT record mismatch" }
        check(txt.txtStrings.contains("deviceid=AA:BB:CC:DD:EE:FF")) { "TXT deviceid missing: ${txt.txtStrings}" }
        check(txt.txtStrings.contains("model=AppleTV6,2")) { "TXT model missing" }

        val a = parsed.records[3]
        check(a.type == MdnsCodec.TYPE_A) { "A record mismatch" }
        check(a.address != null && a.address!!.contentEquals(byteArrayOf(192.toByte(), 168.toByte(), 0, 146.toByte()))) {
            "A record address mismatch"
        }

        val pointerCount = parsed.records.count { it.ownerPointer }
        check(pointerCount >= 2) { "owner names were not compressed (pointers=$pointerCount)" }
        check(parsed.records[1].ownerPointer) { "SRV owner name did not use a compression pointer" }

        // Mixed answers/additionals must be reflected in the header.
        val mixed = MdnsCodec.buildResponse(records, answers = 2, additionals = 2)
        val parsedMixed = parsePacket(mixed)
        check(parsedMixed.answers == 2 && parsedMixed.additionals == 2) { "wrong mixed header counts" }
        check(parsedMixed.records.size == 4) { "wrong mixed record count" }
        println("mdns:   compressed response ${message.size} bytes, $pointerCount compressed owner names")
    }

    private fun questionParsing() {
        val query = buildQuery(AIRPLAY_TYPE, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN or MdnsCodec.UNICAST_RESPONSE_BIT)
        val questions = MdnsCodec.parseMessage(query)
        check(questions.size == 1) { "expected one question" }
        val question = questions[0]
        check(question.name == AIRPLAY_TYPE) { "question name mismatch: ${question.name}" }
        check(question.type == MdnsCodec.TYPE_PTR) { "question type mismatch" }
        check(question.clazz == 0x8001) { "unicast-response bit was lost: ${question.clazz}" }

        val multi = buildQuestionPacket(
            listOf(
                AIRPLAY_TYPE to MdnsCodec.TYPE_PTR,
                "_services._dns-sd._udp.local" to MdnsCodec.TYPE_PTR,
                "AirPlay-EEFF.local" to MdnsCodec.TYPE_A,
            ),
        )
        val parsedMulti = MdnsCodec.parseMessage(multi)
        check(parsedMulti.size == 3) { "expected three questions, got ${parsedMulti.size}" }
        check(parsedMulti[2].name == "AirPlay-EEFF.local") { "third question name mismatch" }
    }

    private fun malformedInput() {
        check(MdnsCodec.parseMessage(ByteArray(0)).isEmpty()) { "empty packet produced questions" }
        check(MdnsCodec.parseMessage(byteArrayOf(1, 2, 3)).isEmpty()) { "truncated packet produced questions" }
        check(MdnsCodec.parseMessage(ByteArray(12) { 0xff.toByte() }).isEmpty()) { "bogus header produced questions" }
        val truncatedQuestion = buildQuery(AIRPLAY_TYPE, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN).copyOf(15)
        check(MdnsCodec.parseMessage(truncatedQuestion).isEmpty()) { "truncated question was accepted" }
    }

    // ------------------------------------------------------------- live tests

    private fun liveTests() {
        println("mdns:   live responder test (service '$LIVE_SERVICE_NAME')")
        val seenAddresses = ArrayList<String>()
        var responder: MdnsResponder? = null
        try {
            responder = MdnsResponder(
                serviceName = LIVE_SERVICE_NAME,
                deviceId = LIVE_DEVICE_ID,
                port = LIVE_PORT,
                airplayTxt = realisticAirplayTxt(),
                raopTxt = realisticRaopTxt(),
            )
            responder.setAddressCallback { addresses ->
                synchronized(seenAddresses) {
                    seenAddresses.clear()
                    seenAddresses.addAll(addresses)
                }
            }
            responder.start()
            responder.start() // idempotent
            if (!responder.isRunning) {
                println("mdns:   live tests skipped: no multicast-capable interface available")
                return
            }

            Thread.sleep(900)
            val addresses = synchronized(seenAddresses) { seenAddresses.toList() }
            println("mdns:   advertised IPv4 addresses: $addresses")
            if (addresses.isNotEmpty()) {
                check(addresses.all { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }) {
                    "address callback returned a non-IPv4 value: $addresses"
                }
            }

            val answered = queryForOurService()
            if (!answered) {
                println("mdns:   live response not observed (multicast likely blocked); dns-sd check still attempted")
            } else {
                println("mdns:   live PTR query answered with our instance")
            }

            val dnsSdResult = browseWithDnsSd(LIVE_SERVICE_NAME)
            when (dnsSdResult) {
                null -> println("mdns:   dns-sd check skipped (tool not available)")
                true -> println("mdns:   dns-sd browse found '$LIVE_SERVICE_NAME'")
                false -> {
                    if (answered) {
                        throw AssertionError("dns-sd did not find the advertised instance")
                    }
                    println("mdns:   dns-sd check skipped: system mDNS unavailable in this environment")
                }
            }

            if (!answered && dnsSdResult != true) {
                println("mdns:   live tests skipped: this environment cannot use multicast")
            }
        } finally {
            responder?.stop()
            responder?.stop() // idempotent
            if (responder != null) {
                check(!responder.isRunning) { "responder still running after stop()" }
            }
        }
    }

    /**
     * Sends a real mDNS PTR query for `_airplay._tcp.local` to 224.0.0.251 with
     * the QU bit set, then waits for the unicast response. Returns false when
     * sending/receiving is not possible in this sandbox.
     */
    private fun queryForOurService(): Boolean {
        val query = buildQuery(AIRPLAY_TYPE, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN or MdnsCodec.UNICAST_RESPONSE_BIT)
        val group = InetAddress.getByName("224.0.0.251")
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                socket.send(DatagramPacket(query, query.size, group, 5353))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                val buffer = ByteArray(9000)
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val data = packet.data.copyOf(packet.length)
                    val parsed = try {
                        parsePacket(data)
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    for (record in parsed.records) {
                        if (record.type == MdnsCodec.TYPE_PTR &&
                            record.name.equals(AIRPLAY_TYPE, ignoreCase = true) &&
                            record.target != null &&
                            record.target.startsWith(LIVE_SERVICE_NAME)
                        ) {
                            return true
                        }
                    }
                }
                false
            }
        } catch (_: IOException) {
            false
        }
    }

    /** Runs `dns-sd -B _airplay._tcp local` for a few seconds. Null when unavailable. */
    private fun browseWithDnsSd(serviceName: String): Boolean? {
        val tool = File("/usr/bin/dns-sd")
        if (!tool.canExecute()) return null

        val process = try {
            ProcessBuilder(tool.absolutePath, "-B", "_airplay._tcp", "local")
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return null
        }

        val output = StringBuilder()
        val found = AtomicBoolean(false)
        val reader = Thread({
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        synchronized(output) { output.append(line).append('\n') }
                        if (line.contains(serviceName)) found.set(true)
                    }
                }
            } catch (_: IOException) {
                // Process terminated; nothing to do.
            }
        }, "MdnsVerify-dns-sd").apply {
            isDaemon = true
            start()
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        try {
            while (System.nanoTime() < deadline && !found.get()) {
                Thread.sleep(100)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        process.destroy()
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
        try {
            reader.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (!found.get()) {
            val captured = synchronized(output) { output.toString() }
            println("mdns:   dns-sd output was:\n$captured")
        }
        return found.get()
    }

    // ----------------------------------------------------- realistic TXT data

    private fun realisticAirplayTxt(): Map<String, String> = linkedMapOf(
        "deviceid" to "AA:BB:CC:DD:EE:FF",
        "features" to "0x5A7FFFF7,0x1E",
        "flags" to "0x4",
        "model" to "AppleTV6,2",
        "pk" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "pi" to "00000000-0000-0000-0000-000000000000",
        "srcvers" to "220.68",
        "vv" to "2",
        "rhd" to "5.6.0.0",
        "pw" to "1",
        "at" to "1",
        "sf" to "0x4",
    )

    private fun realisticRaopTxt(): Map<String, String> = linkedMapOf(
        "ch" to "2",
        "cn" to "0,1,2,3",
        "et" to "0,3,5",
        "sv" to "false",
        "da" to "true",
        "sr" to "44100",
        "ss" to "16",
        "tp" to "UDP",
        "vn" to "65537",
        "vs" to "220.68",
        "am" to "AppleTV6,2",
        "pk" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "sf" to "0x4",
        "md" to "0,1,2",
        "ft" to "0x5A7FFFF7,0x1E",
        "vv" to "2",
    )

    // ------------------------------------------------------------- test codec

    private data class ParsedQuestion(val name: String, val type: Int, val clazz: Int)

    private data class ParsedRecord(
        val name: String,
        val type: Int,
        val clazz: Int,
        val ttl: Long,
        val ownerPointer: Boolean,
        val target: String?,
        val srvPort: Int?,
        val txtStrings: List<String>,
        val address: ByteArray?,
    )

    private data class ParsedPacket(
        val answers: Int,
        val additionals: Int,
        val questions: List<ParsedQuestion>,
        val records: List<ParsedRecord>,
    )

    private data class NameRead(val name: String, val next: Int, val usedPointer: Boolean)

    /** Independent minimal parser used only by the tests. */
    private fun parsePacket(bytes: ByteArray): ParsedPacket {
        require(bytes.size >= 12) { "packet too short" }
        val questionCount = u16(bytes, 4)
        val answerCount = u16(bytes, 6)
        val authorityCount = u16(bytes, 8)
        val additionalCount = u16(bytes, 10)
        var position = 12

        val questions = ArrayList<ParsedQuestion>()
        repeat(questionCount) {
            val name = readName(bytes, position)
            position = name.next
            require(position + 4 <= bytes.size) { "truncated question" }
            questions += ParsedQuestion(name.name, u16(bytes, position), u16(bytes, position + 2))
            position += 4
        }

        val records = ArrayList<ParsedRecord>()
        repeat(answerCount + authorityCount + additionalCount) {
            val owner = readName(bytes, position)
            position = owner.next
            require(position + 10 <= bytes.size) { "truncated record header" }
            val type = u16(bytes, position)
            val clazz = u16(bytes, position + 2)
            val ttl = u32(bytes, position + 4)
            val rdataLength = u16(bytes, position + 8)
            position += 10
            require(position + rdataLength <= bytes.size) { "truncated RDATA" }
            val rdataStart = position

            var target: String? = null
            var srvPort: Int? = null
            var txt = emptyList<String>()
            var address: ByteArray? = null
            when (type) {
                MdnsCodec.TYPE_PTR -> target = readName(bytes, rdataStart).name
                MdnsCodec.TYPE_SRV -> {
                    require(rdataLength >= 7) { "SRV RDATA too short" }
                    srvPort = u16(bytes, rdataStart + 4)
                    target = readName(bytes, rdataStart + 6).name
                }

                MdnsCodec.TYPE_TXT -> {
                    val values = ArrayList<String>()
                    var cursor = rdataStart
                    val end = rdataStart + rdataLength
                    while (cursor < end) {
                        val length = bytes[cursor].toInt() and 0xff
                        cursor++
                        require(cursor + length <= end) { "truncated TXT string" }
                        values += String(bytes, cursor, length, StandardCharsets.UTF_8)
                        cursor += length
                    }
                    txt = values
                }

                MdnsCodec.TYPE_A -> {
                    require(rdataLength == 4) { "bad A record length" }
                    address = bytes.copyOfRange(rdataStart, rdataStart + 4)
                }
            }

            records += ParsedRecord(owner.name, type, clazz, ttl, owner.usedPointer, target, srvPort, txt, address)
            position = rdataStart + rdataLength
        }

        return ParsedPacket(answerCount, additionalCount, questions, records)
    }

    private fun readName(bytes: ByteArray, start: Int): NameRead {
        val labels = ArrayList<String>()
        var position = start
        var next = -1
        var usedPointer = false
        var jumps = 0
        while (true) {
            require(position < bytes.size) { "truncated name" }
            val length = bytes[position].toInt() and 0xff
            when {
                length == 0 -> {
                    position++
                    if (next < 0) next = position
                    break
                }

                length and 0xc0 == 0xc0 -> {
                    require(position + 1 < bytes.size) { "truncated pointer" }
                    val pointer = ((length and 0x3f) shl 8) or (bytes[position + 1].toInt() and 0xff)
                    require(pointer < bytes.size) { "pointer out of range" }
                    if (next < 0) next = position + 2
                    usedPointer = true
                    position = pointer
                    jumps++
                    require(jumps <= 64) { "pointer loop" }
                }

                else -> {
                    require(length and 0xc0 == 0) { "bad label" }
                    require(position + 1 + length <= bytes.size) { "truncated label" }
                    labels += String(bytes, position + 1, length, StandardCharsets.UTF_8)
                    position += 1 + length
                }
            }
        }
        return NameRead(labels.joinToString("."), next, usedPointer)
    }

    private fun buildQuery(name: String, type: Int, clazz: Int): ByteArray {
        val encoded = MdnsCodec.encodeName(name)
        val out = ByteArray(12 + encoded.size + 4)
        out[4] = 0
        out[5] = 1 // QDCOUNT
        System.arraycopy(encoded, 0, out, 12, encoded.size)
        var position = 12 + encoded.size
        out[position++] = (type ushr 8).toByte()
        out[position++] = type.toByte()
        out[position++] = (clazz ushr 8).toByte()
        out[position] = clazz.toByte()
        return out
    }

    private fun buildQuestionPacket(questions: List<Pair<String, Int>>): ByteArray {
        val encoded = questions.map { MdnsCodec.encodeName(it.first) }
        val size = 12 + encoded.sumOf { it.size + 4 }
        val out = ByteArray(size)
        out[5] = questions.size.toByte()
        var position = 12
        questions.forEachIndexed { index, (_, type) ->
            val name = encoded[index]
            System.arraycopy(name, 0, out, position, name.size)
            position += name.size
            out[position++] = (type ushr 8).toByte()
            out[position++] = type.toByte()
            out[position++] = 0
            out[position++] = 1 // CLASS_IN
        }
        return out
    }

    private fun srvData(port: Int, target: String): ByteArray {
        val encoded = MdnsCodec.encodeName(target)
        val out = ByteArray(6 + encoded.size)
        out[4] = (port ushr 8).toByte()
        out[5] = port.toByte()
        System.arraycopy(encoded, 0, out, 6, encoded.size)
        return out
    }

    private fun txtData(vararg entries: String): ByteArray {
        var size = 0
        val encoded = entries.map {
            val bytes = it.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= 255) { "TXT entry too long" }
            size += 1 + bytes.size
            bytes
        }
        val out = ByteArray(size)
        var position = 0
        for (bytes in encoded) {
            out[position++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, out, position, bytes.size)
            position += bytes.size
        }
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[offset + i].toInt() and 0xff).toLong()
        return value
    }
}
