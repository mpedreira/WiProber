package com.example.wiprober

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ThroughputRunnerTest {
    private val closedPort = ServerSocket(0).use { it.localPort }
    private val beacons = mapOf("aa:bb:cc:dd:ee:01" to BeaconInfo(80, BssLoad(stationCount = 12, channelUtilizationPct = 31)))

    private fun runner(network: MeasurementNetwork?, http: HttpSpeedTest = HttpSpeedTest()) = ThroughputRunner(
        iperf = Iperf3Client(connectTimeoutMillis = 1_000),
        http = http,
        network = network,
        beacons = beacons,
        device = DeviceInfo("Zebra TC52", "13"),
        networkTests = network?.let { NetworkTestRunner(TimedHttpClient(resolve = { host -> it.resolve(host).toTypedArray() }), it) },
        scriptRunner = ScriptRunner(TimedHttpClient(resolve = { host -> network!!.resolve(host).toTypedArray() })),
        clock = { 1_000L }
    )

    @Test
    fun withoutWifiEveryEnabledTargetIsUnmeasuredWithAReason() {
        val result = runner(null).measure(
            ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", internetEnabled = true)
        )

        assertEquals(listOf("corporate", "internet"), result.tests.map { it.target })
        assertEquals(listOf(TargetIds.IPERF, TargetIds.INTERNET), result.tests.map { it.targetId })
        assertEquals(listOf(TargetCategory.PRIVATE_IPERF, TargetCategory.INTERNET), result.tests.map { it.category })
        result.tests.forEach { test ->
            assertEquals(ThroughputRunner.NOT_ON_WIFI, test.error)
            assertNull(test.downMbps)
            assertNull(test.upMbps)
            assertNull(test.latencyMs)
            assertNull(test.bssidStart)
        }
    }

    @Test
    fun disabledTargetsProduceNoRowAndCloudflareIsOffByDefault() {
        assertTrue(runner(FakeMeasurementNetwork()).measure(ThroughputSettings()).tests.isEmpty())
        assertTrue(!ThroughputSettings().internetEnabled)
    }

    @Test
    fun enabledIperfWithoutServerReportsMissingConfiguration() {
        val test = runner(FakeMeasurementNetwork()).measure(ThroughputSettings(iperfEnabled = true, iperfLabel = " site ")).tests.single()

        assertEquals("site", test.target)
        assertEquals(ThroughputTestResult.METHOD_IPERF3_TCP, test.method)
        assertEquals("iPerf3 server not configured", test.error)
        assertNull(test.downMbps)
    }

    @Test
    fun latencyResolvesWithTheMeasuredNetworksResolver() {
        ServerSocket(0).use { server ->
            // "iperf.internal" only exists in this network's resolver, as an internal DNS name would.
            val network = FakeMeasurementNetwork(hosts = mapOf("iperf.internal" to listOf(FakeMeasurementNetwork.LOOPBACK)))
            val test = runner(network).measure(
                ThroughputSettings(iperfEnabled = true, iperfHost = "iperf.internal", iperfPort = server.localPort, iperfDurationSeconds = 1)
            ).tests.single()

            assertTrue(network.resolved.contains("iperf.internal"))
            assertEquals("127.0.0.1", test.resolvedAddress)
            assertTrue(test.latencyMs != null)
            assertEquals("wifi-1", test.networkId)
            assertEquals("only", test.networkSelection)
        }
    }

    @Test
    fun addressPolicyTriesIpv4FirstAndAtMostTwoAddresses() {
        val v6 = InetAddress.getByName("::1")
        val a = InetAddress.getByName("10.0.0.1")
        val b = InetAddress.getByName("10.0.0.2")
        val c = InetAddress.getByName("10.0.0.3")
        assertEquals(listOf(a, b), AddressPolicy.candidates(listOf(v6, a, b, c)))
        assertEquals(listOf(v6), AddressPolicy.candidates(listOf(v6)))
    }

    @Test
    fun aSecondAddressIsUsedWhenTheFirstDoesNotAnswer() {
        ServerSocket(0, 50, FakeMeasurementNetwork.LOOPBACK).use { server ->
            val dead = InetAddress.getByName("127.0.0.2") // the server only listens on 127.0.0.1
            val network = FakeMeasurementNetwork(hosts = mapOf("svc.internal" to listOf(dead, FakeMeasurementNetwork.LOOPBACK)))
            val test = runner(network).measure(
                ThroughputSettings(iperfEnabled = true, iperfHost = "svc.internal", iperfPort = server.localPort, iperfDurationSeconds = 1)
            ).tests.single()
            assertEquals("127.0.0.1", test.resolvedAddress)
        }
    }

    @Test
    fun unresolvableHostIsAResolutionFailure() {
        val test = runner(FakeMeasurementNetwork()).measure(
            ThroughputSettings(iperfEnabled = true, iperfHost = "missing.internal", iperfDurationSeconds = 1)
        ).tests.single()
        assertTrue(test.error!!, test.error!!.startsWith("latency: ${ErrorCodes.RESOLUTION_FAILED}"))
        assertTrue(test.error!!.endsWith("download/upload skipped: server unreachable"))
    }

    @Test
    fun unreachableServerFailsFastKeepsNullsAndRecordsTheApChange() {
        val aps = ArrayDeque(listOf(FakeMeasurementNetwork.DEFAULT_AP, FakeMeasurementNetwork.DEFAULT_AP.copy(bssid = "aa:bb:cc:dd:ee:02")))
        val network = FakeMeasurementNetwork(associationProvider = { if (aps.size > 1) aps.removeFirst() else aps.first() })
        val started = System.nanoTime()
        val test = runner(network).measure(
            ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", iperfPort = closedPort, iperfDurationSeconds = 5)
        ).tests.single()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("took $elapsedMillis ms", elapsedMillis < 2_000)
        assertEquals("CORP", test.ssid)
        assertEquals("CORP", test.ssidEnd)
        assertEquals(-58, test.rssi)
        assertEquals(80, test.channelWidthMhz)
        assertEquals(31, test.bssChannelUtilizationPct)
        assertEquals("aa:bb:cc:dd:ee:01", test.bssidStart)
        assertEquals("aa:bb:cc:dd:ee:02", test.bssidEnd)
        assertEquals(AssociationChange.BSSID_CHANGED, test.associationChange)
        assertNull(test.latencyMs)
        assertNull(test.downMbps)
        assertTrue(test.error!!, test.error!!.startsWith("latency: ${ErrorCodes.CONNECTION_REFUSED}"))
    }

    @Test
    fun associationChangeDistinguishesSsidChangeAndUnknown() {
        val ap = FakeMeasurementNetwork.DEFAULT_AP
        assertEquals(AssociationChange.SAME, AssociationChange.between(ap, ap))
        assertEquals(AssociationChange.SSID_CHANGED, AssociationChange.between(ap, ap.copy(ssid = "GUEST", bssid = "aa:bb:cc:dd:ee:09")))
        assertEquals(AssociationChange.BSSID_CHANGED, AssociationChange.between(ap, ap.copy(bssid = "aa:bb:cc:dd:ee:09")))
        assertEquals(AssociationChange.UNKNOWN, AssociationChange.between(ap, Association.UNKNOWN))
        assertEquals(AssociationChange.UNKNOWN, AssociationChange.between(ap, ap.copy(bssid = null)))
    }

    @Test
    fun unknownAssociationIsRecordedAsUnknownNotAsNoWifi() {
        ServerSocket(0).use { server ->
            val network = FakeMeasurementNetwork(associationProvider = { Association.UNKNOWN })
            val test = runner(network).measure(
                ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", iperfPort = server.localPort, iperfDurationSeconds = 1)
            ).tests.single()
            assertNull(test.ssid)
            assertNull(test.bssidStart)
            assertEquals(Association.SOURCE_UNKNOWN, test.associationSource)
            assertEquals(AssociationChange.UNKNOWN, test.associationChange)
            assertTrue(test.latencyMs != null) // the network is still measured
        }
    }

    @Test
    fun losingTheWifiMidRunEndsTheRemainingPhasesWithWifiLost() {
        ServerSocket(0).use { server ->
            val checks = AtomicInteger()
            // Available for the latency pre-check, gone before the transfers.
            val network = FakeMeasurementNetwork(available = { checks.incrementAndGet() <= 2 })
            val test = runner(network).measure(
                ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", iperfPort = server.localPort, iperfDurationSeconds = 1)
            ).tests.single()
            assertTrue(test.latencyMs != null)
            assertNull(test.downMbps)
            assertNull(test.upMbps)
            assertTrue(test.error!!, test.error!!.contains("download: ${ErrorCodes.WIFI_LOST}"))
            assertTrue(test.error!!.contains("upload: ${ErrorCodes.WIFI_LOST}"))
        }
    }

    @Test
    fun iperf3UsesTheAssignedResolverAgainstARealServer() {
        val binary = System.getenv("IPERF3_BIN")?.let(::File)
        assumeTrue("IPERF3_BIN not set", binary?.canExecute() == true)
        val port = ServerSocket(0).use { it.localPort }
        val server = ProcessBuilder(binary!!.path, "-s", "-p", port.toString()).redirectErrorStream(true).start()
        try {
            Thread.sleep(500)
            val network = FakeMeasurementNetwork(hosts = mapOf("iperf.internal" to listOf(FakeMeasurementNetwork.LOOPBACK)))
            val test = runner(network).measure(
                ThroughputSettings(iperfEnabled = true, iperfHost = "iperf.internal", iperfPort = port, iperfDurationSeconds = 1)
            ).tests.single()
            assertTrue(test.error ?: "", test.downMbps != null && test.upMbps != null)
            assertEquals(listOf("iperf.internal"), network.resolved.distinct())
        } finally {
            server.destroy()
            server.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun networkTestsAndScriptsRunAfterTheTargetsWithProgress() {
        MiniHttpServer { MiniHttpServer.Response(200, "ok") }.use { server ->
            val tests = listOf(
                NetworkTest(name = "web", type = "http", url = "${server.base}/health", alias = "Health"),
                NetworkTest(name = "off", type = "http", url = "${server.base}/x", enabled = false)
            )
            val scripts = listOf(
                HttpScript("flow", steps = listOf(
                    HttpScriptStep(name = "a", url = "${server.base}/a"),
                    HttpScriptStep(name = "b", url = "${server.base}/b")
                ))
            )
            val events = mutableListOf<ThroughputProgress>()
            val result = runner(FakeMeasurementNetwork()).measure(ThroughputSettings(), tests, scripts) { events += it }

            val web = result.networkTests!!.single()
            assertEquals("web", web.name)
            assertEquals("Health", web.alias)
            assertEquals(TargetCategory.INTERNAL, web.category)
            assertTrue(web.error ?: "", web.ok)
            assertEquals(listOf("a", "b"), result.scripts!!.single().steps.map { it.name })
            assertEquals("aa:bb:cc:dd:ee:01", result.scripts!!.single().bssidEnd)
            assertEquals(listOf(1, 2, 3), events.map { it.step })
            assertTrue(events.all { it.totalSteps == 3 })
            assertEquals(listOf("http", "a", "b"), events.map { it.detail })
        }
    }

    @Test
    fun scriptsWithoutWifiAreSkippedWithTheReason() {
        val run = runner(null).measure(
            ThroughputSettings(),
            scripts = listOf(HttpScript("flow", steps = listOf(HttpScriptStep(name = "a", url = "http://127.0.0.1:9/a"))))
        ).scripts!!.single()
        assertEquals(ThroughputRunner.NOT_ON_WIFI, run.error)
        assertTrue(run.steps.isEmpty())
    }
}
