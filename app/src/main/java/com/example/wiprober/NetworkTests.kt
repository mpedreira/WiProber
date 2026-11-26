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
import javax.net.SocketFactory
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
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
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
    val error: String? = null
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
 * Runs [NetworkTest]s at a point. Blocking; Android binds every socket to the Wi-Fi network
 * through the factories it passes in.
 */
class NetworkTestRunner(
    private val http: TimedHttpClient,
    private val socketFactory: SocketFactory,
    private val resolve: (String) -> Array<InetAddress>,
    private val datagramSocket: () -> DatagramSocket,
    private val context: NetworkContext,
    private val connection: () -> WifiConnectionInfo?,
    /** ICMP ping through the system `ping`; null where unavailable (falls back to TCP). */
    private val systemPing: ((host: String, count: Int, interfaceName: String?) -> String)? = null,
    private val clock: MillisClock = SystemMillisClock
) {
    fun run(test: NetworkTest): NetworkTestResult {
        val ap = connection()
        val base = NetworkTestResult(
            name = test.name, type = test.type, target = null, method = null, measuredAt = clock.now(),
            ssid = ap?.ssid, bssid = ap?.bssid, rssi = ap?.rssi
        )
        if (ap == null) return base.copy(error = ThroughputRunner.NOT_ON_WIFI)
        return try {
            when (test.type) {
                NetworkTest.DNS -> dns(test, base)
                NetworkTest.PING -> ping(test, base)
                NetworkTest.TCP -> tcp(test, base)
                NetworkTest.HTTP -> http(test, base)
                else -> base.copy(error = "unknown test type ${test.type}")
            }
        } catch (e: Exception) {
            base.copy(error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun dns(test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val host = substitute(test.host!!)
        val server = context.dnsServers.firstOrNull()
        if (server == null) {
            // No DNS server known: time the system resolver (may answer from its cache).
            val start = System.nanoTime()
            val address = resolve(host).first()
            return base.copy(
                target = host, method = "system", ok = true,
                resolvedAddress = address.hostAddress, dnsMs = elapsed(start)
            )
        }
        val id = SecureRandom().nextInt(0x10000)
        val request = DnsWire.query(id, host)
        datagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            val start = System.nanoTime()
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(server, 53)))
            val reply = ByteArray(1_500)
            val packet = DatagramPacket(reply, reply.size)
            socket.receive(packet)
            val millis = elapsed(start)
            val base2 = base.copy(target = host, method = "udp", dnsServer = server.hostAddress, dnsMs = millis)
            val address = DnsWire.firstAddress(reply, packet.length, id)
                ?: return base2.copy(error = "no IPv4 address in the answer")
            return base2.copy(ok = true, resolvedAddress = address)
        }
    }

    private fun ping(test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val host = substitute(test.host!!)
        val count = test.count ?: DEFAULT_PING_COUNT
        val icmp = systemPing?.let { ping ->
            runCatching { PingOutputParser.parse(ping(host, count, context.interfaceName)) }.getOrNull()
        }
        val stats = icmp ?: tcpProbes(host, test.port ?: DEFAULT_TCP_PING_PORT, count, refusedCounts = true)
        return probeResult(base.copy(target = host, method = if (icmp != null) "icmp" else "tcp"), stats)
    }

    private fun tcp(test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val host = substitute(test.host!!)
        val stats = tcpProbes(host, test.port!!, test.count ?: DEFAULT_TCP_COUNT, refusedCounts = false)
        return probeResult(base.copy(target = "$host:${test.port}", method = "tcp"), stats).let { result ->
            result.copy(connectMs = result.rttMinMs)
        }
    }

    private fun http(test: NetworkTest, base: NetworkTestResult): NetworkTestResult {
        val url = URI(substitute(test.url!!))
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
                test.expectContains?.let { if (it !in String(response.body, Charsets.UTF_8)) add("response does not contain \"$it\"") }
            }
            withTiming(base).copy(
                status = response.status, bytes = response.bytes,
                ok = problems.isEmpty(), error = problems.joinToString("; ").ifEmpty { null }
            )
        } catch (e: Exception) {
            withTiming(base).copy(error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * TCP handshake round trips. For a ping, a refused connection still proves the host answered
     * (like `tcping`), so it counts as a reply; for a port test it is a failure.
     */
    private fun tcpProbes(host: String, port: Int, count: Int, refusedCounts: Boolean): ProbeStats {
        val address = resolve(host).first()
        return ProbeStats((1..count).map {
            val start = System.nanoTime()
            try {
                socketFactory.createSocket().use { it.connect(InetSocketAddress(address, port), PROBE_TIMEOUT_MILLIS) }
                elapsed(start)
            } catch (e: ConnectException) {
                if (refusedCounts && "refused" in e.message.orEmpty().lowercase()) elapsed(start) else null
            } catch (e: IOException) {
                null
            }.also { if (it == null || count > 1) Thread.sleep(PROBE_GAP_MILLIS) }
        })
    }

    private fun probeResult(base: NetworkTestResult, stats: ProbeStats) = base.copy(
        ok = stats.received == stats.sent,
        sent = stats.sent, received = stats.received, lossPct = stats.lossPct,
        rttMinMs = stats.min, rttAvgMs = stats.avg, rttMaxMs = stats.max, jitterMs = stats.jitter,
        error = when {
            stats.received == 0 -> "no reply"
            stats.received < stats.sent -> "${stats.sent - stats.received} of ${stats.sent} lost"
            else -> null
        }
    )

    private fun substitute(text: String): String = text
        .replace("\${gateway}", context.gateway ?: if ("\${gateway}" in text) throw IOException("no default gateway") else "")
        .replace("\${dns}", context.dnsServers.firstOrNull()?.hostAddress
            ?: if ("\${dns}" in text) throw IOException("no DNS server") else "")

    private fun elapsed(start: Long): Double = round1((System.nanoTime() - start) / 1_000_000.0)

    companion object {
        const val DEFAULT_PING_COUNT = 5
        const val DEFAULT_TCP_COUNT = 3
        const val DEFAULT_TCP_PING_PORT = 443
        private const val DNS_TIMEOUT_MILLIS = 2_000
        private const val PROBE_TIMEOUT_MILLIS = 2_000
        private const val PROBE_GAP_MILLIS = 100L

        /** System ping bound to the Wi-Fi interface: 0.2 s apart (the unprivileged minimum). */
        fun systemPingCommand(host: String, count: Int, interfaceName: String?): List<String> = buildList {
            addAll(listOf("ping", "-n", "-c", count.toString(), "-i", "0.2", "-W", "2"))
            interfaceName?.let { addAll(listOf("-I", it)) }
            add(host)
        }

        /** Runs [systemPingCommand]; returns its output (also when some probes were lost). */
        fun runSystemPing(host: String, count: Int, interfaceName: String?): String {
            require(host.matches(Regex("[A-Za-z0-9.:\\-]+"))) { "invalid ping host" }
            val process = ProcessBuilder(systemPingCommand(host, count, interfaceName)).redirectErrorStream(true).start()
            if (!process.waitFor(count * 1L + 5L, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("ping timed out")
            }
            return process.inputStream.bufferedReader().readText()
        }
    }
}
