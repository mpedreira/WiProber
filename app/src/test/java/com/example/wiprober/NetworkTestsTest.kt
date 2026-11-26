package com.example.wiprober

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import javax.net.SocketFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTestsTest {
    private val ap = WifiConnectionInfo("Corp", "aa:bb:cc:dd:ee:01", -60, 400)

    private fun runner(
        context: NetworkContext = NetworkContext(),
        connection: () -> WifiConnectionInfo? = { ap },
        ping: ((String, Int, String?) -> String)? = null
    ) = NetworkTestRunner(
        http = TimedHttpClient(),
        socketFactory = SocketFactory.getDefault(),
        resolve = InetAddress::getAllByName,
        datagramSocket = { DatagramSocket() },
        context = context,
        connection = connection,
        systemPing = ping,
        clock = { 5L }
    )

    @Test
    fun parsesAndroidPingOutputWithLoss() {
        val output = """
            PING 10.0.0.1 (10.0.0.1) 56(84) bytes of data.
            64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time=2.10 ms
            64 bytes from 10.0.0.1: icmp_seq=3 ttl=64 time=4.30 ms
            64 bytes from 10.0.0.1: icmp_seq=4 ttl=64 time=3.20 ms

            --- 10.0.0.1 ping statistics ---
            4 packets transmitted, 3 received, 25% packet loss, time 604ms
            rtt min/avg/max/mdev = 2.100/3.200/4.300/0.898 ms
        """.trimIndent()

        val stats = PingOutputParser.parse(output)!!

        assertEquals(listOf(2.1, null, 4.3, 3.2), stats.samples)
        assertEquals(25.0, stats.lossPct, 0.0)
        assertEquals(2.1, stats.min)
        assertEquals(3.2, stats.avg)
        assertEquals(1.65, stats.jitter!!, 0.051) // answered 2.1, 4.3, 3.2 → differences 2.2 and 1.1
    }

    @Test
    fun pingFallsBackToTcpAndCountsRefusedAsAReply() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val result = runner(ping = { _, _, _ -> "ping: socket: Operation not permitted" })
            .run(NetworkTest(name = "gw", type = "ping", host = "127.0.0.1", port = closedPort, count = 3))

        assertEquals("tcp", result.method)
        assertEquals(3, result.received)
        assertEquals(0.0, result.lossPct)
        assertTrue(result.ok)
        assertEquals("aa:bb:cc:dd:ee:01", result.bssid)
    }

    @Test
    fun icmpPingIsUsedWhenTheSystemPingWorks() {
        val result = runner(
            context = NetworkContext(gateway = "10.0.0.1", interfaceName = "wlan0"),
            ping = { host, count, iface ->
                assertEquals("10.0.0.1", host)
                assertEquals("wlan0", iface)
                (1..count).joinToString("\n") { "64 bytes from $host: icmp_seq=$it ttl=64 time=1.0 ms" } +
                    "\n$count packets transmitted, $count received, 0% packet loss"
            }
        ).run(NetworkTest(name = "gw", type = "ping", host = "\${gateway}", count = 4))

        assertEquals("icmp", result.method)
        assertEquals("10.0.0.1", result.target)
        assertEquals(4, result.sent)
        assertTrue(result.ok)
    }

    @Test
    fun gatewayVariableWithoutGatewayIsAnError() {
        val result = runner().run(NetworkTest(name = "gw", type = "ping", host = "\${gateway}"))
        assertEquals("no default gateway", result.error)
        assertTrue(!result.ok)
    }

    @Test
    fun tcpPortTestFailsOnAClosedPort() {
        ServerSocket(0).use { open ->
            val ok = runner().run(NetworkTest(name = "db", type = "tcp", host = "127.0.0.1", port = open.localPort, count = 2))
            assertTrue(ok.ok)
            assertTrue(ok.connectMs != null)
        }
        val closed = ServerSocket(0).use { it.localPort }
        val failed = runner().run(NetworkTest(name = "db", type = "tcp", host = "127.0.0.1", port = closed, count = 2))
        assertTrue(!failed.ok)
        assertEquals(100.0, failed.lossPct)
        assertEquals("no reply", failed.error)
    }

    @Test
    fun httpTestReportsTheTimingBreakdownAndChecks() {
        MiniHttpServer { MiniHttpServer.Response(200, "status: UP") }.use { server ->
            val result = runner().run(
                NetworkTest(name = "web", type = "http", url = "${server.base}/health?key=secret", expectContains = "UP")
            )
            assertTrue(result.error, result.ok)
            assertEquals("127.0.0.1/health", result.target) // query string never exported
            assertEquals(200, result.status)
            assertTrue(result.dnsMs != null && result.connectMs != null && result.ttfbMs != null && result.totalMs != null)
            assertNull(result.tlsMs)
        }
    }

    @Test
    fun withoutWifiNothingIsSent() {
        val result = runner(connection = { null }).run(NetworkTest(name = "dns", type = "dns", host = "example.com"))
        assertEquals(ThroughputRunner.NOT_ON_WIFI, result.error)
        assertNull(result.target)
    }

    @Test
    fun dnsQueryGoesToTheNetworksDnsServer() {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            server.receive(packet)
            val reply = answer(buffer.copyOf(packet.length), byteArrayOf(10, 1, 2, 3))
            server.send(DatagramPacket(reply, reply.size, packet.socketAddress))
        }
        // Port 53 is fixed in the runner; route the test through a socket that rewrites it.
        val result = NetworkTestRunner(
            http = TimedHttpClient(),
            socketFactory = SocketFactory.getDefault(),
            resolve = InetAddress::getAllByName,
            datagramSocket = { RedirectingDatagramSocket(server.localPort) },
            context = NetworkContext(dnsServers = listOf(InetAddress.getLoopbackAddress())),
            connection = { ap }
        ).run(NetworkTest(name = "dns", type = "dns", host = "app.example.com"))
        server.close()

        assertTrue(result.error, result.ok)
        assertEquals("udp", result.method)
        assertEquals("10.1.2.3", result.resolvedAddress)
        assertEquals("127.0.0.1", result.dnsServer)
        assertTrue(result.dnsMs != null)
    }

    @Test
    fun dnsErrorsAreNamed() {
        val query = DnsWire.query(0x1234, "missing.example.com")
        val reply = query.copyOf().also { it[3] = (it[3].toInt() or 0x03).toByte() } // RCODE 3
        try {
            DnsWire.firstAddress(reply, reply.size, 0x1234)
            throw AssertionError("expected NXDOMAIN")
        } catch (e: java.io.IOException) {
            assertEquals("NXDOMAIN", e.message)
        }
    }

    /** Builds a reply with one compressed A answer for the query. */
    private fun answer(query: ByteArray, ip: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(query.size + 16)
        buffer.put(query)
        buffer.putShort(2, 0x8180.toShort()) // response, no error
        buffer.putShort(6, 1)                 // ANCOUNT
        buffer.putShort(0xC00C.toShort()).putShort(1).putShort(1).putInt(60).putShort(4).put(ip)
        return buffer.array()
    }

    /** Sends to the fake server's port instead of 53. */
    private class RedirectingDatagramSocket(private val port: Int) : DatagramSocket() {
        override fun send(packet: DatagramPacket) {
            super.send(DatagramPacket(packet.data, packet.length, packet.address, port))
        }
    }

}
