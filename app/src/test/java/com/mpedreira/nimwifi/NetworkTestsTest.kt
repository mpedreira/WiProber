package com.mpedreira.nimwifi

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTestsTest {
    private fun runner(
        network: MeasurementNetwork? = FakeMeasurementNetwork(),
        ping: ((String, Int, String, MeasurementControl) -> String)? = null,
        dnsPort: Int = 53
    ) = NetworkTestRunner(
        http = TimedHttpClient(resolve = { host -> network!!.resolve(host).toTypedArray() }),
        network = network,
        systemPing = ping,
        clock = { 5L },
        dnsPort = dnsPort
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
        val network = FakeMeasurementNetwork(link = NetworkContext(interfaceName = "wlan0", localAddresses = listOf(InetAddress.getByName("127.0.0.1"))))
        val result = runner(network, ping = { _, _, _, _ -> "ping: socket: Operation not permitted" })
            .run(NetworkTest(name = "gw", type = "ping", host = "127.0.0.1", port = closedPort, count = 3))

        assertEquals("tcp", result.method)
        assertEquals(3, result.received)
        assertEquals(0.0, result.lossPct)
        assertTrue(result.ok)
        assertEquals("aa:bb:cc:dd:ee:01", result.bssid)
    }

    @Test
    fun pingWithoutAnAddressOnTheWifiInterfaceNeverUsesTheSystemPing() {
        val result = runner(
            FakeMeasurementNetwork(link = NetworkContext(interfaceName = "wlan0")),
            ping = { _, _, _, _ -> throw AssertionError("system ping must not run without a Wi-Fi source address") }
        ).run(NetworkTest(name = "gw", type = "ping", host = "127.0.0.1", port = ServerSocket(0).use { it.localPort }, count = 2))
        assertEquals("tcp", result.method)
    }

    @Test
    fun icmpPingIsUsedWhenTheSystemPingWorks() {
        val network = FakeMeasurementNetwork(
            hosts = mapOf("gw.internal" to listOf(InetAddress.getByName("10.0.0.1"))),
            link = NetworkContext(
                gateway = "gw.internal", interfaceName = "wlan0",
                localAddresses = listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("10.0.0.23"))
            )
        )
        val result = runner(
            network,
            ping = { ip, count, source, _ ->
                assertEquals("10.0.0.1", ip) // resolved on the Wi-Fi before pinging
                assertEquals("10.0.0.23", source) // the Wi-Fi address of the same family, not the interface name
                (1..count).joinToString("\n") { "64 bytes from $ip: icmp_seq=$it ttl=64 time=1.0 ms" } +
                    "\n$count packets transmitted, $count received, 0% packet loss"
            }
        ).run(NetworkTest(name = "gw", type = "ping", host = "\${gateway}", count = 4))

        assertEquals("icmp", result.method)
        assertEquals("gw.internal", result.target)
        assertEquals("10.0.0.1", result.resolvedAddress)
        assertEquals(4, result.sent)
        assertTrue(result.ok)
    }

    @Test
    fun systemPingBindsToTheSourceAddress() {
        assertEquals(listOf("ping", "-n", "-c", "3", "-i", "0.2", "-W", "2", "-I", "10.0.0.23", "10.0.0.1"),
            NetworkTestRunner.systemPingCommand("10.0.0.1", 3, "10.0.0.23"))
        assertEquals("ping6", NetworkTestRunner.systemPingCommand("fd00::1", 1, "fd00::23").first())
    }

    @Test
    fun gatewayVariableWithoutGatewayIsAnError() {
        val result = runner().run(NetworkTest(name = "gw", type = "ping", host = "\${gateway}"))
        assertEquals(ErrorCodes.NO_GATEWAY, result.errorCode)
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
        assertEquals(ErrorCodes.NO_REPLY, failed.errorCode)
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
        val result = runner(network = null).run(NetworkTest(name = "dns", type = "dns", host = "example.com"))
        assertEquals(ThroughputRunner.NOT_ON_WIFI, result.error)
        assertNull(result.target)
    }

    @Test
    fun dnsQueryGoesToTheNetworksDnsServerOnASocketBoundToIt() {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            server.receive(packet)
            val reply = answer(buffer.copyOf(packet.length), byteArrayOf(10, 1, 2, 3))
            server.send(DatagramPacket(reply, reply.size, packet.socketAddress))
        }
        val network = FakeMeasurementNetwork(link = NetworkContext(dnsServers = listOf(InetAddress.getLoopbackAddress())))
        val result = runner(network, dnsPort = server.localPort).run(NetworkTest(name = "dns", type = "dns", host = "app.example.com"))
        server.close()

        assertTrue(result.error, result.ok)
        assertEquals("udp", result.method)
        assertEquals("10.1.2.3", result.resolvedAddress)
        assertEquals("127.0.0.1", result.dnsServer)
        assertEquals(1, network.boundDatagrams.size) // the UDP socket was bound to the Wi-Fi network
        assertTrue(result.dnsMs != null)
    }

    @Test
    fun internalNamesResolveOnlyThroughTheWifiResolver() {
        ServerSocket(0).use { open ->
            val network = FakeMeasurementNetwork(hosts = mapOf("db.internal" to listOf(FakeMeasurementNetwork.LOOPBACK)))
            val result = runner(network).run(NetworkTest(name = "db", type = "tcp", host = "db.internal", port = open.localPort, count = 1))
            assertTrue(result.error ?: "", result.ok)
            assertEquals(listOf("db.internal"), network.resolved)
        }
    }

    @Test
    fun losingTheWifiBeforeATestReportsWifiLost() {
        val result = runner(FakeMeasurementNetwork(available = { false })).run(NetworkTest(name = "dns", type = "dns", host = "a.example"))
        assertEquals(ErrorCodes.WIFI_LOST, result.errorCode)
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


}
