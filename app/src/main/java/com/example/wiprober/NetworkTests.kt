package com.example.wiprober

import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Result of one [NetworkTest] at a point. Fields that do not apply to the test type, or were not
 * reached, are null. Times in ms. [method] says how it was measured (e.g. `icmp` or `tcp` ping,
 * `udp` or `system` DNS).
 */
@JsonModel
data class NetworkTestResult(
    val name: String,
    val type: String,
    val target: String?,
    val method: String?,
    val measuredAt: Long,
    val ok: Boolean = false,
    val alias: String? = null,
    val category: String? = null,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val bssidEnd: String? = null,
    val associationChange: String? = null,
    val associationSource: String? = null,
    val networkId: String? = null,
    val dnsServer: String? = null,
    val resolvedAddress: String? = null,
    val dnsMs: Double? = null,
    val connectMs: Double? = null,
    val tlsMs: Double? = null,
    val ttfbMs: Double? = null,
    val downloadMs: Double? = null,
    val totalMs: Double? = null,
    val status: Int? = null,
    val bytes: Long? = null,
    val sent: Int? = null,
    val received: Int? = null,
    val lossPct: Double? = null,
    val rttMinMs: Double? = null,
    val rttAvgMs: Double? = null,
    val rttMaxMs: Double? = null,
    val jitterMs: Double? = null,
    val error: String? = null,
    val errorCode: String? = null
)

/** Addresses the Wi-Fi network's DHCP/RA gave the phone. */
data class NetworkContext(
    val gateway: String? = null,
    val dnsServers: List<InetAddress> = emptyList(),
    val interfaceName: String? = null
)

/** Round-trip samples of a ping-like test; null entries are lost probes. */
data class ProbeStats(val samples: List<Double?>) {
    val sent: Int get() = samples.size
    val received: Int get() = samples.count { it != null }
    private val ok: List<Double> get() = samples.filterNotNull()
    val lossPct: Double get() = if (sent == 0) 100.0 else round1((sent - received) * 100.0 / sent)
    val min: Double? get() = ok.minOrNull()?.let(::round1)
    val avg: Double? get() = ok.takeIf { it.isNotEmpty() }?.average()?.let(::round1)
    val max: Double? get() = ok.maxOrNull()?.let(::round1)
    /** Mean difference between consecutive answered probes, as ping tools report jitter. */
    val jitter: Double? get() = ok.zipWithNext { a, b -> abs(b - a) }.takeIf { it.isNotEmpty() }?.average()?.let(::round1)
}

internal fun round1(value: Double): Double = (value * 10).roundToLong() / 10.0

/** Parses `ping` output (iputils/toybox): per-reply `time=` values and the transmitted count. */
object PingOutputParser {
    private val REPLY = Regex("""icmp_seq=(\d+).*?time[=<]([\d.]+)\s*ms""")
    private val SUMMARY = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received""")

    fun parse(output: String): ProbeStats? {
        val summary = SUMMARY.find(output) ?: return null
        val sent = summary.groupValues[1].toInt()
        val replies = REPLY.findAll(output).associate { it.groupValues[1].toInt() to it.groupValues[2].toDouble() }
        // icmp_seq starts at 1; a missing sequence number is a lost probe.
        return ProbeStats((1..sent).map { replies[it] })
    }
}

/** Minimal DNS A query over UDP, sent straight to the network's DNS server (no OS cache). */
object DnsWire {
    fun query(id: Int, host: String): ByteArray {
        val labels = host.trimEnd('.').split('.')
        require(labels.all { it.isNotEmpty() && it.length <= 63 }) { "invalid host name $host" }
        val buffer = ByteBuffer.allocate(12 + labels.sumOf { it.length + 1 } + 5)
        buffer.putShort(id.toShort()).putShort(0x0100) // standard query, recursion desired
        buffer.putShort(1).putShort(0).putShort(0).putShort(0)
        labels.forEach { label -> buffer.put(label.length.toByte()).put(label.toByteArray(Charsets.US_ASCII)) }
        buffer.put(0).putShort(1).putShort(1) // QTYPE A, QCLASS IN
        return buffer.array()
    }

    /** First IPv4 answer; throws with the DNS error name on failure codes. */
    fun firstAddress(response: ByteArray, length: Int, id: Int): String? {
        val buffer = ByteBuffer.wrap(response, 0, length)
        if (length < 12 || (buffer.short.toInt() and 0xFFFF) != id) throw IOException("unexpected DNS reply")
        val flags = buffer.short.toInt() and 0xFFFF
        when (flags and 0xF) {
            0 -> Unit
            2 -> throw IOException("SERVFAIL")
            3 -> throw IOException("NXDOMAIN")
            5 -> throw IOException("REFUSED")
            else -> throw IOException("DNS error ${flags and 0xF}")
        }
        val questions = buffer.short.toInt() and 0xFFFF
        val answers = buffer.short.toInt() and 0xFFFF
        buffer.position(12)
        repeat(questions) { skipName(buffer); buffer.position(buffer.position() + 4) }
        repeat(answers) {
            skipName(buffer)
            val type = buffer.short.toInt() and 0xFFFF
            buffer.position(buffer.position() + 6) // class + TTL
            val dataLength = buffer.short.toInt() and 0xFFFF
            if (type == 1 && dataLength == 4) {
                val ip = ByteArray(4).also(buffer::get)
                return ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            buffer.position(buffer.position() + dataLength)
        }
        return null
    }

    private fun skipName(buffer: ByteBuffer) {
        while (true) {
            val length = buffer.get().toInt() and 0xFF
            when {
                length == 0 -> return
                length and 0xC0 == 0xC0 -> { buffer.get(); return } // compression pointer
                else -> buffer.position(buffer.position() + length)
            }
        }
    }
}

/**
 * Runs [NetworkTest]s at a point on one [MeasurementNetwork]: names resolve on that network,
 * sockets and HTTP are bound to it and `${gateway}`/`${dns}` come from its link. Without a
 * network nothing is sent. Blocking.
 */
class NetworkTestRunner(
    private val http: TimedHttpClient,
    private val network: MeasurementNetwork?,
    /**
     * ICMP ping through the system `ping`, given an already resolved IP and the Wi-Fi interface
     * name; null where unavailable (a TCP probe bound to the Wi-Fi network is used instead).
     */
    private val systemPing: ((ip: String, count: Int, interfaceName: String, control: MeasurementControl) -> String)? = null,
    private val clock: MillisClock = SystemMillisClock,
    /** DNS server port; tests point it at a local fake server. */
    private val dnsPort: Int = 53,
    /** Tracks sockets and ping processes so cancelling or a deadline closes them. */
    private val control: MeasurementControl = MeasurementControl()
) {
    fun run(test: NetworkTest): NetworkTestResult {
        val start = network?.association()
        val base = NetworkTestResult(
            name = test.name, type = test.type, target = null, method = null, measuredAt = clock.now(),
            alias = test.displayAlias, category = TargetCategory.INTERNAL,
            ssid = start?.ssid, bssid = start?.bssid, rssi = start?.rssi,
            networkId = network?.id, associationSource = start?.source
        )
        val net = network ?: return base.copy(error = ThroughputRunner.NOT_ON_WIFI, errorCode = ErrorCodes.NOT_ON_WIFI)
        if (!net.isAvailable()) return base.copy(error = ErrorCodes.WIFI_LOST, errorCode = ErrorCodes.WIFI_LOST)
        val result = try {
            when (test.type) {
                NetworkTest.DNS -> dns(net, test, base)
                NetworkTest.PING -> ping(net, test, base)
                NetworkTest.TCP -> tcp(net, test, base)
                NetworkTest.HTTP -> http(test, base)
                else -> base.copy(error = "unknown test type ${test.type}", errorCode = ErrorCodes.INVALID_TARGET)
            }
        } catch (e: Exception) {
            val code = if (!net.isAvailable()) ErrorCodes.WIFI_LOST else ErrorClassifier.code(e)
            base.copy(error = "$code: ${ErrorClassifier.detail(e)}", errorCode = code)
        }
        val end = net.association()
        return result.copy(bssidEnd = end.bssid, associationChange = AssociationChange.between(start, end))
    }

    private fun dns(net: MeasurementNetwork, test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val link = net.link()
        val host = substitute(test.host!!, link)
        val server = link.dnsServers.firstOrNull()
        if (server == null) {
            // No DNS server in the link properties: time this network's resolver (may answer from cache).
            val start = System.nanoTime()
            val address = net.resolve(host).first()
            return base.copy(
                target = host, method = "system", ok = true,
                resolvedAddress = address.hostAddress, dnsMs = elapsed(start)
            )
        }
        val id = SecureRandom().nextInt(0x10000)
        val request = DnsWire.query(id, host)
        control.using(DatagramSocket()) { socket ->
            net.bind(socket)
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            val start = System.nanoTime()
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(server, dnsPort)))
            val reply = ByteArray(1_500)
            val packet = DatagramPacket(reply, reply.size)
            socket.receive(packet)
            val millis = elapsed(start)
            val base2 = base.copy(target = host, method = "udp", dnsServer = server.hostAddress, dnsMs = millis)
            val address = try {
                DnsWire.firstAddress(reply, packet.length, id)
            } catch (e: IOException) {
                return base2.copy(error = "${ErrorCodes.DNS_ERROR}: ${e.message}", errorCode = ErrorCodes.DNS_ERROR)
            } ?: return base2.copy(error = "no IPv4 address in the answer", errorCode = ErrorCodes.DNS_ERROR)
            return base2.copy(ok = true, resolvedAddress = address)
        }
    }

    private fun ping(net: MeasurementNetwork, test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val link = net.link()
        val host = substitute(test.host!!, link)
        val address = resolveFirst(net, host)
        val count = test.count ?: DEFAULT_PING_COUNT
        // ICMP only with a known Wi-Fi interface and an address resolved on that network.
        val iface = link.interfaceName
        val pinger = systemPing
        val icmp = if (pinger != null && iface != null) {
            runCatching { PingOutputParser.parse(pinger(address.hostAddress!!, count, iface, control)) }.getOrNull()
        } else {
            null
        }
        val stats = icmp ?: tcpProbes(net, address, test.port ?: DEFAULT_TCP_PING_PORT, count, refusedCounts = true)
        return probeResult(
            base.copy(target = host, resolvedAddress = address.hostAddress, method = if (icmp != null) "icmp" else "tcp"),
            stats
        )
    }

    private fun tcp(net: MeasurementNetwork, test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val port = test.port ?: return base.copy(error = ErrorCodes.PORT_NOT_CONFIGURED, errorCode = ErrorCodes.PORT_NOT_CONFIGURED)
        val host = substitute(test.host!!, net.link())
        val address = resolveFirst(net, host)
        val stats = tcpProbes(net, address, port, test.count ?: DEFAULT_TCP_COUNT, refusedCounts = false)
        return probeResult(base.copy(target = "$host:$port", resolvedAddress = address.hostAddress, method = "tcp"), stats)
            .let { result -> result.copy(connectMs = result.rttMinMs) }
    }

    private fun http(test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val url = URI(substitute(test.url!!, network!!.link()))
        val timing = TimedHttpClient.Timing()
        val start = System.nanoTime()
        val target = "${url.host}${url.rawPath?.ifEmpty { "/" } ?: "/"}" // no query: it may hold secrets
        fun withTiming(result: NetworkTestResult) = result.copy(
            target = target, method = url.scheme.lowercase(),
            dnsMs = timing.dnsMs, connectMs = timing.connectMs, tlsMs = timing.tlsMs,
            ttfbMs = timing.ttfbMs, downloadMs = timing.downloadMs, totalMs = elapsed(start)
        )
        return try {
            val response = http.execute("GET", url, emptyMap(), null, timing)
            val problems = buildList {
                val expected = test.expectStatus
                if (expected != null && response.status != expected) add("status ${response.status}, expected $expected")
                if (expected == null && response.status !in 200..399) add("status ${response.status}")
                test.expectContains?.let { if (it !in String(response.body, Charsets.UTF_8)) add("expected text not found") }
            }
            withTiming(base).copy(
                status = response.status, bytes = response.bytes,
                ok = problems.isEmpty(), error = problems.joinToString("; ").ifEmpty { null },
                errorCode = when {
                    problems.isEmpty() -> null
                    problems.any { it.startsWith("status") } -> ErrorCodes.HTTP_STATUS
                    else -> ErrorCodes.EXPECTATION_FAILED
                }
            )
        } catch (e: Exception) {
            val code = ErrorClassifier.code(e)
            withTiming(base).copy(error = "$code: ${ErrorClassifier.detail(e)}", errorCode = code)
        }
    }

    private fun resolveFirst(net: MeasurementNetwork, host: String): InetAddress =
        AddressPolicy.candidates(net.resolve(host)).firstOrNull()
            ?: throw MeasurementException(ErrorCodes.RESOLUTION_FAILED, "no address for the host")

    /**
     * TCP handshake round trips on the measured network. For a ping, a refused connection still
     * proves the host answered (like `tcping`), so it counts as a reply; for a port test it is a failure.
     */
    private fun tcpProbes(net: MeasurementNetwork, address: InetAddress, port: Int, count: Int, refusedCounts: Boolean): ProbeStats =
        ProbeStats((1..count).map {
            val start = System.nanoTime()
            try {
                control.using(net.socketFactory.createSocket()) { it.connect(InetSocketAddress(address, port), PROBE_TIMEOUT_MILLIS) }
                elapsed(start)
            } catch (e: ConnectException) {
                if (refusedCounts && "refused" in e.message.orEmpty().lowercase()) elapsed(start) else null
            } catch (e: IOException) {
                null
            }.also { if (it == null || count > 1) Thread.sleep(PROBE_GAP_MILLIS) }
        })

    private fun probeResult(base: NetworkTestResult, stats: ProbeStats) = base.copy(
        ok = stats.received == stats.sent,
        sent = stats.sent, received = stats.received, lossPct = stats.lossPct,
        rttMinMs = stats.min, rttAvgMs = stats.avg, rttMaxMs = stats.max, jitterMs = stats.jitter,
        error = when {
            stats.received == 0 -> "no reply"
            stats.received < stats.sent -> "${stats.sent - stats.received} of ${stats.sent} lost"
            else -> null
        },
        errorCode = when {
            stats.received == 0 -> ErrorCodes.NO_REPLY
            stats.received < stats.sent -> ErrorCodes.PARTIAL_LOSS
            else -> null
        }
    )

    /** Placeholders are only evaluated when present, so a literal host never needs them. */
    private fun substitute(text: String, link: NetworkContext): String {
        var result = text
        if ("\${gateway}" in result) {
            result = result.replace("\${gateway}", link.gateway ?: throw MeasurementException(ErrorCodes.NO_GATEWAY, "no default gateway"))
        }
        if ("\${dns}" in result) {
            result = result.replace("\${dns}", link.dnsServers.firstOrNull()?.hostAddress
                ?: throw MeasurementException(ErrorCodes.NO_DNS_SERVER, "no DNS server"))
        }
        return result
    }

    private fun elapsed(start: Long): Double = round1((System.nanoTime() - start) / 1_000_000.0)

    companion object {
        const val DEFAULT_PING_COUNT = 5
        const val DEFAULT_TCP_COUNT = 3
        const val DEFAULT_TCP_PING_PORT = 443
        private const val DNS_TIMEOUT_MILLIS = 2_000
        private const val PROBE_TIMEOUT_MILLIS = 2_000
        private const val PROBE_GAP_MILLIS = 100L

        /** System ping bound to the Wi-Fi interface: 0.2 s apart (the unprivileged minimum). */
        fun systemPingCommand(ip: String, count: Int, interfaceName: String): List<String> =
            listOf("ping", "-n", "-c", count.toString(), "-i", "0.2", "-W", "2", "-I", interfaceName, ip)

        /** Runs [systemPingCommand] on an IP literal; returns its output (also when probes were lost). */
        fun runSystemPing(ip: String, count: Int, interfaceName: String, control: MeasurementControl): String {
            require(ip.matches(Regex("[0-9a-fA-F.:]+"))) { "ping target must be an IP address" }
            require(interfaceName.matches(Regex("[A-Za-z0-9_.-]+"))) { "invalid interface name" }
            val process = control.track(ProcessBuilder(systemPingCommand(ip, count, interfaceName)).redirectErrorStream(true).start())
            try {
                if (!process.waitFor(count * 1L + 5L, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw MeasurementException(ErrorCodes.TIMEOUT, "ping timed out")
                }
                control.checkActive()
                return process.inputStream.bufferedReader().readText()
            } finally {
                control.untrack(process)
            }
        }
    }
}
