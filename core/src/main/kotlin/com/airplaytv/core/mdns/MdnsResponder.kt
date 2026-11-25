package com.airplaytv.core.mdns

import com.airplaytv.core.mdns.MdnsCodec.Record
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.MembershipKey
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Multicast DNS responder (RFC 6762) advertising AirPlay (RFC 6763 DNS-SD)
 * services so that iOS and macOS devices show this receiver in the
 * "Screen Mirroring" / AirPlay target list.
 *
 * Two services are published on the local link:
 *
 *  * `_airplay._tcp.local` with instance `<serviceName>`, carrying [airplayTxt]
 *  * `_raop._tcp.local` with instance `<deviceId>@<serviceName>`, carrying
 *    [raopTxt]
 *
 * Both SRV records point at the same synthetic host name derived from
 * [deviceId] (`AirPlay-XXXXXX.local`), for which one A record per non-loopback
 * IPv4 address is published. The standard `_services._dns-sd._udp.local`
 * enumeration PTRs are included as well.
 *
 * The responder owns a daemon receive thread, answers multicast and unicast
 * (QU) queries with compressed responses, announces at 1 s, 2 s and 4 s after
 * start and refreshes every 60 s, and re-resolves interfaces on every refresh
 * so Wi-Fi changes are handled without a restart. [stop] sends TTL=0 goodbye
 * packets twice.
 *
 * This class uses only `java.net` / `java.nio` and has no Android dependency.
 * If no multicast-capable IPv4 interface is available the responder logs and
 * leaves [isRunning] `false` instead of crashing.
 */
class MdnsResponder(
    /** Instance label for `_airplay._tcp.local` (may contain UTF-8). */
    val serviceName: String,
    /** 12-character device identifier, used for the RAOP instance and host name. */
    val deviceId: String,
    /** RTSP/HTTP port advertised through SRV records, usually 7000. */
    val port: Int,
    /** TXT key/value pairs for `_airplay._tcp`. Empty values are encoded as a bare key. */
    val airplayTxt: Map<String, String>,
    /** TXT key/value pairs for `_raop._tcp`. Empty values are encoded as a bare key. */
    val raopTxt: Map<String, String>,
) {
    private val running = AtomicBoolean(false)

    private val sendLock = Any()

    @Volatile
    private var transport: Transport? = null

    @Volatile
    private var receiveThread: Thread? = null

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var records: List<Record> = emptyList()

    @Volatile
    private var advertisedAddresses: List<String> = emptyList()

    @Volatile
    private var localAddresses: Set<String> = emptySet()

    @Volatile
    private var addressCallback: ((List<String>) -> Unit)? = null

    private val recentlySent = ArrayDeque<SentPacket>()

    private val hostLabel: String = buildHostLabel(deviceId)
    private val hostName: String = "$hostLabel.local"
    private val airplayType: String = "_airplay._tcp.local"
    private val raopType: String = "_raop._tcp.local"
    private val enumerationType: String = "_services._dns-sd._udp.local"
    private val airplayInstance: String = "${instanceLabel(serviceName)}.$airplayType"
    private val raopInstance: String = "${instanceLabel("${deviceId.uppercase(Locale.ROOT)}@$serviceName")}.$raopType"

    private val airplayTxtData: ByteArray = encodeTxt(airplayTxt)
    private val raopTxtData: ByteArray = encodeTxt(raopTxt)

    /** True while the receive thread is alive and the socket is open. */
    val isRunning: Boolean
        get() = running.get() && transport != null

    /**
     * Starts advertising. Idempotent: calling it while already running does
     * nothing.
     *
     * @throws IOException if no mDNS socket can be opened at all (for example
     *     when binding port 5353 is forbidden). Missing multicast
     *     capabilities are *not* fatal: the responder logs the problem, closes
     *     its socket and leaves [isRunning] `false`.
     */
    @Synchronized
    fun start() {
        if (running.get()) return

        val newTransport = openTransport()
        transport = newTransport

        try {
            refreshNetwork(forceNotify = true)
            if (newTransport.syncGroups(resolveInterfaces().map { it.nif }) == 0) {
                LOG.warning("mDNS: no multicast-capable IPv4 interface, responder disabled")
                newTransport.close()
                transport = null
                running.set(false)
                return
            }

            running.set(true)
            receiveThread = Thread({ receiveLoop(newTransport) }, "MdnsResponder-rx").apply {
                isDaemon = true
                start()
            }

            val newScheduler = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "MdnsResponder-scheduler").apply { isDaemon = true }
            }
            scheduler = newScheduler
            newScheduler.schedule({ safeAnnounce() }, 1, TimeUnit.SECONDS)
            newScheduler.schedule({ safeAnnounce() }, 2, TimeUnit.SECONDS)
            newScheduler.schedule({ safeAnnounce() }, 4, TimeUnit.SECONDS)
            newScheduler.scheduleAtFixedRate({ safeAnnounce() }, ANNOUNCE_INTERVAL_SECONDS, ANNOUNCE_INTERVAL_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            running.set(false)
            transport = null
            try {
                newTransport.close()
            } catch (_: IOException) {
                // Nothing useful to do while unwinding a failed start.
            }
            throw if (failure is IOException) failure else IOException("failed to start mDNS responder", failure)
        }
    }

    /**
     * Stops advertising: sends all records once as TTL=0 goodbyes (twice, as
     * recommended), closes the sockets and stops the background threads.
     * Idempotent and safe to call from any thread except the receive thread
     * itself.
     */
    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        try {
            sendGoodbyes()
        } catch (failure: Throwable) {
            LOG.fine("mDNS: goodbye send failed: ${failure.message}")
        }

        val oldTransport = transport
        transport = null
        try {
            oldTransport?.close()
        } catch (failure: IOException) {
            LOG.fine("mDNS: close failed: ${failure.message}")
        }

        scheduler?.shutdownNow()
        scheduler = null

        val reader = receiveThread
        receiveThread = null
        if (reader != null && reader !== Thread.currentThread()) {
            reader.interrupt()
            try {
                reader.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Registers a callback invoked with the currently advertised IPv4 address
     * list. The callback is called immediately with the current snapshot and
     * again whenever the set of addresses changes (usually on refresh while
     * the responder runs). Exceptions thrown by the callback are swallowed.
     */
    fun setAddressCallback(cb: (List<String>) -> Unit) {
        addressCallback = cb
        deliverAddresses(advertisedAddresses)
    }

    // ---------------------------------------------------------------- receive

    private fun receiveLoop(transport: Transport) {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        while (running.get()) {
            val received = try {
                transport.receive(buffer)
            } catch (_: SocketTimeoutException) {
                null
            } catch (failure: IOException) {
                if (!running.get() || transport.isClosed) break
                LOG.fine("mDNS: receive failed: ${failure.message}")
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (received == null) {
                if (transport.isClosed) break
                continue
            }

            try {
                handlePacket(received)
            } catch (failure: Throwable) {
                // A malformed or hostile packet must never kill the loop.
                LOG.fine("mDNS: ignoring packet: ${failure.message}")
            }
        }
    }

    private fun handlePacket(packet: Received) {
        if (packet.data.size < 12) return
        val flags = ((packet.data[2].toInt() and 0xff) shl 8) or (packet.data[3].toInt() and 0xff)
        if (flags and 0x8000 != 0) return // it is a response, not a query

        val questions = MdnsCodec.parseMessage(packet.data)
        if (questions.isEmpty()) return

        if (isOwnPacket(packet.address, packet.data)) return

        // A query from an ephemeral port is a legacy unicast query; the QU bit
        // requests a unicast answer explicitly. Everything else is answered to
        // the multicast group.
        val wantsUnicast = packet.port != MDNS_PORT ||
            questions.any { it.clazz and MdnsCodec.UNICAST_RESPONSE_BIT != 0 }

        val (answers, additionals) = responseRecords(questions)
        if (answers.isEmpty() && additionals.isEmpty()) return

        if (!running.get()) return
        val packets = pack(answers, additionals)
        for (bytes in packets) {
            try {
                if (wantsUnicast) {
                    sendDatagram(bytes, packet.address, packet.port)
                } else {
                    sendDatagram(bytes, GROUP, MDNS_PORT)
                }
            } catch (failure: IOException) {
                LOG.fine("mDNS: response send failed: ${failure.message}")
            }
        }
    }

    private fun responseRecords(questions: List<MdnsCodec.Question>): Pair<List<Record>, List<Record>> {
        val available = records
        val answers = LinkedHashSet<Record>()
        val additionals = LinkedHashSet<Record>()

        for (question in questions) {
            if (question.name.isEmpty()) continue
            val wantedClass = question.clazz and 0x7fff
            if (wantedClass != MdnsCodec.CLASS_IN && wantedClass != MdnsCodec.CLASS_ANY) continue
            for (record in available) {
                if (!record.name.equals(question.name, ignoreCase = true)) continue
                if (question.type != MdnsCodec.TYPE_ANY && record.type != question.type) continue
                answers += record
            }
        }

        for (answer in answers.toList()) {
            when (answer.type) {
                MdnsCodec.TYPE_PTR -> {
                    val target = decodeRdataName(answer.data, 0) ?: continue
                    for (record in available) {
                        if (!record.name.equals(target, ignoreCase = true)) continue
                        if (record.type == MdnsCodec.TYPE_SRV || record.type == MdnsCodec.TYPE_TXT) {
                            additionals += record
                        }
                        if (record.type == MdnsCodec.TYPE_SRV) {
                            val host = srvTarget(record.data) ?: continue
                            for (a in available) {
                                if (a.type == MdnsCodec.TYPE_A && a.name.equals(host, ignoreCase = true)) {
                                    additionals += a
                                }
                            }
                        }
                    }
                }

                MdnsCodec.TYPE_SRV -> {
                    val host = srvTarget(answer.data) ?: continue
                    for (record in available) {
                        if (record.type == MdnsCodec.TYPE_A && record.name.equals(host, ignoreCase = true)) {
                            additionals += record
                        }
                    }
                }
            }
        }

        additionals.removeAll(answers)
        return answers.toList() to additionals.toList()
    }

    // ------------------------------------------------------------ announcing

    private fun safeAnnounce() {
        try {
            announce()
        } catch (failure: Throwable) {
            LOG.fine("mDNS: announce failed: ${failure.message}")
        }
    }

    @Synchronized
    private fun announce() {
        if (!running.get()) return
        refreshNetwork(forceNotify = false)

        val all = records
        if (all.isEmpty()) return
        val answers = all.filter { it.type == MdnsCodec.TYPE_PTR }
        val additionals = all.filter { it.type != MdnsCodec.TYPE_PTR }
        sendAll(pack(answers, additionals), null)
    }

    @Synchronized
    private fun sendGoodbyes() {
        val all = records
        if (all.isEmpty()) return
        val goodbyes = all.map { if (it.ttl == 0L) it else it.copy(ttl = 0) }
        val answers = goodbyes.filter { it.type == MdnsCodec.TYPE_PTR }
        val additionals = goodbyes.filter { it.type != MdnsCodec.TYPE_PTR }
        val packets = pack(answers, additionals)
        repeat(2) { round ->
            sendAll(packets, null)
            if (round == 0) {
                try {
                    Thread.sleep(GOODBYE_GAP_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun sendAll(packets: List<ByteArray>, target: InetSocketAddress?) {
        for (bytes in packets) {
            try {
                if (target != null) {
                    sendDatagram(bytes, target.address, target.port)
                } else {
                    sendDatagram(bytes, GROUP, MDNS_PORT)
                }
            } catch (failure: IOException) {
                LOG.fine("mDNS: send failed: ${failure.message}")
            }
        }
    }

    // -------------------------------------------------------------- networking

    /**
     * Re-resolves interfaces and addresses, rebuilds the record set and joins
     * or leaves multicast groups to match. Also publishes address changes.
     */
    private fun refreshNetwork(forceNotify: Boolean) {
        val interfaces = resolveInterfaces()
        val physicalInterfaces = interfaces.map { it.nif }

        val ordered = orderAddresses(interfaces.flatMap { it.addresses })
        val newAddresses = ordered.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }

        records = buildRecords(ordered)
        localAddresses = (newAddresses + "127.0.0.1").toSet()

        transport?.syncGroups(physicalInterfaces)

        val changed = newAddresses != advertisedAddresses
        advertisedAddresses = newAddresses
        if (forceNotify || changed) deliverAddresses(newAddresses)
    }

    private fun deliverAddresses(addresses: List<String>) {
        val callback = addressCallback ?: return
        try {
            callback(addresses)
        } catch (failure: Throwable) {
            LOG.fine("mDNS: address callback failed: ${failure.message}")
        }
    }

    private fun resolveInterfaces(): List<InterfaceInfo> {
        val result = ArrayList<InterfaceInfo>()
        val enumeration = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return result

        while (enumeration.hasMoreElements()) {
            val nif = enumeration.nextElement()
            try {
                if (!nif.isUp || nif.isLoopback) continue
                val ipv4 = ArrayList<Inet4Address>()
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isMulticastAddress) {
                        ipv4 += address
                    }
                }
                if (ipv4.isNotEmpty()) result += InterfaceInfo(nif, ipv4)
            } catch (_: SocketException) {
                // Interface disappeared while enumerating; skip it.
            }
        }
        result.sortBy { it.nif.name }
        return result
    }

    /** Puts the address of the default-route interface first, then the rest. */
    private fun orderAddresses(addresses: List<Inet4Address>): List<Inet4Address> {
        val unique = LinkedHashMap<String, Inet4Address>()
        for (address in addresses) {
            unique.putIfAbsent(address.hostAddress ?: continue, address)
        }
        val primary = primaryAddress()
        val values = unique.values.toList()
        if (primary == null) return values
        val primaryKey = primary.hostAddress
        return values.sortedBy { it.hostAddress != primaryKey }
    }

    private fun primaryAddress(): Inet4Address? {
        return try {
            DatagramSocket().use { probe ->
                probe.connect(GROUP, 53)
                probe.localAddress as? Inet4Address
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isOwnPacket(source: InetAddress, data: ByteArray): Boolean {
        if (source.hostAddress !in localAddresses) return false
        val now = System.nanoTime()
        synchronized(sendLock) {
            pruneSent(now)
            return recentlySent.any { it.data.contentEquals(data) }
        }
    }

    private fun sendDatagram(data: ByteArray, address: InetAddress, port: Int) {
        val current = transport ?: throw IOException("mDNS socket is closed")
        synchronized(sendLock) {
            current.send(data, address, port)
            val now = System.nanoTime()
            pruneSent(now)
            recentlySent.addLast(SentPacket(data.copyOf(), now))
            while (recentlySent.size > MAX_SENT_HISTORY) recentlySent.removeFirst()
        }
    }

    private fun pruneSent(now: Long) {
        while (recentlySent.isNotEmpty() && now - recentlySent.first().timestampNanos > SENT_HISTORY_NANOS) {
            recentlySent.removeFirst()
        }
    }

    // ---------------------------------------------------------------- records

    private fun buildRecords(addresses: List<Inet4Address>): List<Record> {
        val result = ArrayList<Record>(10 + addresses.size)
        result += Record(enumerationType, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN, TTL, MdnsCodec.encodeName(airplayType))
        result += Record(enumerationType, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN, TTL, MdnsCodec.encodeName(raopType))
        result += Record(airplayType, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN, TTL, MdnsCodec.encodeName(airplayInstance))
        result += Record(raopType, MdnsCodec.TYPE_PTR, MdnsCodec.CLASS_IN, TTL, MdnsCodec.encodeName(raopInstance))
        result += Record(airplayInstance, MdnsCodec.TYPE_SRV, UNIQUE_CLASS, TTL, srvData(port, hostName))
        result += Record(airplayInstance, MdnsCodec.TYPE_TXT, UNIQUE_CLASS, TTL, airplayTxtData)
        result += Record(raopInstance, MdnsCodec.TYPE_SRV, UNIQUE_CLASS, TTL, srvData(port, hostName))
        result += Record(raopInstance, MdnsCodec.TYPE_TXT, UNIQUE_CLASS, TTL, raopTxtData)
        for (address in addresses) {
            result += Record(hostName, MdnsCodec.TYPE_A, UNIQUE_CLASS, TTL, address.address)
        }
        return result
    }

    // ---------------------------------------------------------------- packets

    /**
     * Packs answers followed by additionals into as many datagrams as needed,
     * keeping each one under [MAX_PACKET_SIZE] when possible.
     */
    private fun pack(answers: List<Record>, additionals: List<Record>): List<ByteArray> {
        val all = answers + additionals
        if (all.isEmpty()) return emptyList()
        val packets = ArrayList<ByteArray>()
        var start = 0
        while (start < all.size) {
            var low = 1
            var high = all.size - start
            var best = 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val answerCount = answerCountIn(start, mid, answers.size)
                val candidate = try {
                    MdnsCodec.buildResponse(all.subList(start, start + mid), answerCount, mid - answerCount)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (candidate != null && candidate.size <= MAX_PACKET_SIZE) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            val answerCount = answerCountIn(start, best, answers.size)
            val bytes = MdnsCodec.buildResponse(
                all.subList(start, start + best),
                answerCount,
                best - answerCount,
            )
            packets += bytes
            start += best
        }
        return packets
    }

    private fun answerCountIn(start: Int, count: Int, totalAnswers: Int): Int {
        if (start >= totalAnswers) return 0
        return minOf(count, totalAnswers - start)
    }

    // ---------------------------------------------------------------- helpers

    private fun decodeRdataName(data: ByteArray, offset: Int): String? {
        return try {
            MdnsCodec.decodeName(data, offset).first
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun srvTarget(data: ByteArray): String? {
        if (data.size < 7) return null
        return decodeRdataName(data, 6)
    }

    private fun srvData(port: Int, target: String): ByteArray {
        val out = ByteArrayOutputStream(16)
        out.write(0) // priority
        out.write(0)
        out.write(0) // weight
        out.write(0)
        out.write((port ushr 8) and 0xff)
        out.write(port and 0xff)
        out.write(MdnsCodec.encodeName(target))
        return out.toByteArray()
    }

    private fun encodeTxt(values: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream(64)
        for ((key, value) in values) {
            if (key.isEmpty()) continue
            val text = if (value.isEmpty()) key else "$key=$value"
            val bytes = utf8Prefix(text, TXT_STRING_MAX)
            out.write(bytes.size)
            out.write(bytes)
        }
        if (out.size() == 0) out.write(0) // empty TXT record: one zero-length string
        return out.toByteArray()
    }

    private fun buildHostLabel(id: String): String {
        val hex = id.uppercase(Locale.ROOT).filter { it in '0'..'9' || it in 'A'..'F' }
        val tail = hex.takeLast(6).padStart(6, '0')
        return "AirPlay-$tail"
    }

    private fun instanceLabel(label: String): String {
        val bytes = label.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= 63) return label
        var end = 63
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }

    private class SentPacket(val data: ByteArray, val timestampNanos: Long)

    private data class Received(val data: ByteArray, val address: InetAddress, val port: Int)

    private data class InterfaceInfo(val nif: NetworkInterface, val addresses: List<Inet4Address>)

    // -------------------------------------------------------------- transports

    /**
     * A socket abstraction. The primary implementation is a [MulticastSocket]
     * bound with SO_REUSEADDR; the fallback is a [DatagramChannel] bound with
     * SO_REUSEPORT, which is required to coexist with the system mDNS
     * responder on macOS (where port 5353 is already taken).
     */
    private abstract class Transport {
        protected val joined = LinkedHashMap<String, NetworkInterface>()

        abstract val isClosed: Boolean

        abstract fun tryJoin(nif: NetworkInterface): Boolean
        abstract fun tryLeave(nif: NetworkInterface)

        abstract fun send(data: ByteArray, address: InetAddress, port: Int)

        /** Blocks up to ~750 ms; returns null on timeout. */
        abstract fun receive(buffer: ByteArray): Received?

        abstract fun close()

        /** Joins/leaves groups so that exactly [interfaces] are members. Returns member count. */
        fun syncGroups(interfaces: List<NetworkInterface>): Int {
            val desired = LinkedHashMap<String, NetworkInterface>()
            for (nif in interfaces) desired[nif.name] = nif

            for ((name, nif) in joined.toList()) {
                if (name !in desired) {
                    tryLeave(nif)
                    joined.remove(name)
                }
            }
            for ((name, nif) in desired) {
                if (name !in joined && tryJoin(nif)) joined[name] = nif
            }
            return joined.size
        }

        fun leaveAll() {
            for ((_, nif) in joined.toList()) tryLeave(nif)
            joined.clear()
        }

        protected fun interfaceKey(nif: NetworkInterface): String = nif.name
    }

    private class MulticastTransport(private val socket: MulticastSocket) : Transport() {
        private val memberships = HashMap<String, Pair<InetSocketAddress, NetworkInterface>>()

        init {
            socket.soTimeout = RECEIVE_TIMEOUT_MS
        }

        override val isClosed: Boolean
            get() = socket.isClosed

        override fun tryJoin(nif: NetworkInterface): Boolean {
            val key = interfaceKey(nif)
            if (key in memberships) return true
            return try {
                val group = InetSocketAddress(GROUP, MDNS_PORT)
                socket.joinGroup(group, nif)
                memberships[key] = group to nif
                true
            } catch (failure: IOException) {
                LOG.fine("mDNS: cannot join ${GROUP.hostAddress} on ${nif.name}: ${failure.message}")
                false
            }
        }

        override fun tryLeave(nif: NetworkInterface) {
            val membership = memberships.remove(interfaceKey(nif)) ?: return
            try {
                socket.leaveGroup(membership.first, membership.second)
            } catch (failure: IOException) {
                LOG.fine("mDNS: leaveGroup on ${nif.name} failed: ${failure.message}")
            }
        }

        override fun send(data: ByteArray, address: InetAddress, port: Int) {
            val packet = DatagramPacket(data, data.size, address, port)
            socket.send(packet)
        }

        override fun receive(buffer: ByteArray): Received? {
            val packet = DatagramPacket(buffer, buffer.size)
            return try {
                socket.receive(packet)
                val address = packet.address ?: return null
                Received(packet.data.copyOf(packet.length), address, packet.port)
            } catch (_: SocketTimeoutException) {
                null
            }
        }

        override fun close() {
            leaveAll()
            socket.close()
        }
    }

    private class ChannelTransport(private val channel: DatagramChannel) : Transport() {
        private val memberships = HashMap<String, MembershipKey>()

        init {
            channel.configureBlocking(false)
        }

        override val isClosed: Boolean
            get() = !channel.isOpen

        override fun tryJoin(nif: NetworkInterface): Boolean {
            val key = interfaceKey(nif)
            if (key in memberships) return true
            return try {
                memberships[key] = channel.join(GROUP, nif)
                true
            } catch (failure: Exception) {
                LOG.fine("mDNS: cannot join ${GROUP.hostAddress} on ${nif.name}: ${failure.message}")
                false
            }
        }

        override fun tryLeave(nif: NetworkInterface) {
            memberships.remove(interfaceKey(nif))?.drop()
        }

        override fun send(data: ByteArray, address: InetAddress, port: Int) {
            val target = InetSocketAddress(address, port)
            val buffer = ByteBuffer.wrap(data)
            var attempts = 0
            while (buffer.hasRemaining()) {
                val sent = channel.send(buffer, target)
                if (sent == 0) {
                    if (++attempts > 200) throw IOException("mDNS send buffer full")
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("interrupted while sending")
                    }
                }
            }
        }

        override fun receive(buffer: ByteArray): Received? {
            val byteBuffer = ByteBuffer.wrap(buffer)
            val deadline = System.nanoTime() + RECEIVE_TIMEOUT_MS * 1_000_000L
            while (true) {
                byteBuffer.clear()
                val source = try {
                    channel.receive(byteBuffer)
                } catch (_: java.nio.channels.ClosedChannelException) {
                    return null
                }
                if (source != null) {
                    val inet = source as? InetSocketAddress ?: return null
                    val address = inet.address ?: return null
                    return Received(buffer.copyOf(byteBuffer.position()), address, inet.port)
                }
                if (System.nanoTime() >= deadline) return null
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }

        override fun close() {
            leaveAll()
            channel.close()
        }
    }

    private fun openTransport(): Transport {
        try {
            return openMulticastSocket()
        } catch (failure: IOException) {
            LOG.fine("mDNS: MulticastSocket unavailable (${failure.message}); trying DatagramChannel")
        }
        return openChannel()
    }

    private fun openMulticastSocket(): Transport {
        val socket = MulticastSocket(null as SocketAddress?)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(MDNS_PORT))
            socket.timeToLive = 255
            return MulticastTransport(socket)
        } catch (failure: Throwable) {
            try {
                socket.close()
            } catch (_: IOException) {
                // ignore
            }
            throw if (failure is IOException) failure else IOException("cannot open mDNS socket", failure)
        }
    }

    private fun openChannel(): Transport {
        var channel: DatagramChannel? = null
        try {
            val opened = DatagramChannel.open()
            channel = opened
            opened.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
            try {
                opened.setOption(java.net.StandardSocketOptions.SO_REUSEPORT, true)
            } catch (_: Throwable) {
                // Not supported on every JVM/Android level; SO_REUSEADDR may suffice.
            }
            opened.bind(InetSocketAddress(MDNS_PORT))
            try {
                opened.setOption(java.net.StandardSocketOptions.IP_MULTICAST_TTL, 255)
            } catch (_: IOException) {
                LOG.fine("mDNS: cannot set multicast TTL on channel")
            }
            return ChannelTransport(opened)
        } catch (failure: Throwable) {
            try {
                channel?.close()
            } catch (_: IOException) {
                // ignore
            }
            throw if (failure is IOException) failure else IOException("cannot open mDNS channel", failure)
        }
    }

    private companion object {
        const val MDNS_PORT = 5353
        const val RECEIVE_BUFFER_SIZE = 9000
        const val RECEIVE_TIMEOUT_MS = 750
        const val MAX_PACKET_SIZE = 1400
        const val TTL = 4500L
        const val UNIQUE_CLASS = MdnsCodec.CLASS_IN or MdnsCodec.CACHE_FLUSH_BIT
        const val ANNOUNCE_INTERVAL_SECONDS = 60L
        const val GOODBYE_GAP_MS = 120L
        const val TXT_STRING_MAX = 255
        const val MAX_SENT_HISTORY = 64
        const val SENT_HISTORY_NANOS = 3_000_000_000L

        val GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
        val LOG: Logger = Logger.getLogger("AirPlayTV.mdns")
    }
}

/** Truncates UTF-8 [text] to at most [maxBytes] without splitting a code point. */
private fun utf8Prefix(text: String, maxBytes: Int): ByteArray {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
    return bytes.copyOf(end)
}
