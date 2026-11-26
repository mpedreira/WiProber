package com.example.wiprober

import java.net.ServerSocket
import javax.net.SocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputRunnerTest {
    private val closedPort = ServerSocket(0).use { it.localPort }
    private val ap = WifiConnectionInfo(
        "CORP", "aa:bb:cc:dd:ee:01", -58, 573,
        rxLinkSpeedMbps = 649, frequencyMhz = 5_500, channelWidthMhz = 80,
        wifiStandard = "802.11ax (Wi-Fi 6/6E)", bssLoad = BssLoad(stationCount = 12, channelUtilizationPct = 31)
    )

    private fun runner(connection: () -> WifiConnectionInfo?) = ThroughputRunner(
        iperf = Iperf3Client(connectTimeoutMillis = 1_000),
        http = HttpSpeedTest(),
        socketFactory = SocketFactory.getDefault(),
        connection = connection,
        device = DeviceInfo("Zebra TC52", "13"),
        clock = { 1_000L }
    )

    @Test
    fun withoutWifiEveryEnabledTargetIsUnmeasuredWithAReason() {
        val result = runner { null }.measure(
            ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", internetEnabled = true)
        )

        assertEquals(listOf("corporate", "internet"), result.tests.map { it.target })
        result.tests.forEach { test ->
            assertEquals(ThroughputRunner.NOT_ON_WIFI, test.error)
            assertNull(test.downMbps)
            assertNull(test.upMbps)
            assertNull(test.latencyMs)
            assertNull(test.bssidStart)
        }
    }

    @Test
    fun disabledTargetsProduceNoRow() {
        assertTrue(runner { ap }.measure(ThroughputSettings()).tests.isEmpty())
    }

    @Test
    fun enabledIperfWithoutServerReportsMissingConfiguration() {
        val test = runner { ap }.measure(ThroughputSettings(iperfEnabled = true, iperfLabel = " site ")).tests.single()

        assertEquals("site", test.target)
        assertEquals(ThroughputTestResult.METHOD_IPERF3_TCP, test.method)
        assertEquals("iPerf3 server not configured", test.error)
        assertNull(test.downMbps)
    }

    @Test
    fun unreachableServerFailsFastKeepsNullsAndRecordsRoaming() {
        val aps = ArrayDeque(listOf(ap, ap.copy(bssid = "aa:bb:cc:dd:ee:02")))
        val started = System.nanoTime()
        val test = runner { aps.removeFirst() }.measure(
            ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", iperfPort = closedPort, iperfDurationSeconds = 5)
        ).tests.single()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("took $elapsedMillis ms", elapsedMillis < 2_000) // no 5 s transfers attempted

        assertEquals("CORP", test.ssid)
        assertEquals(-58, test.rssi)
        assertEquals(573, test.linkMbps)
        assertEquals("aa:bb:cc:dd:ee:01", test.bssidStart)
        assertEquals("aa:bb:cc:dd:ee:02", test.bssidEnd)
        assertNull(test.latencyMs)
        assertNull(test.downMbps)
        assertNull(test.upMbps)
        val error = test.error.orEmpty()
        assertTrue(error, error.startsWith("latency: ") && error.endsWith("download/upload skipped: server unreachable"))
    }

    @Test
    fun internetWithoutUploadUrlSaysSo() {
        // Reachable (latency works) but every HTTP request is cut: download fails, upload is unset.
        val server = ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().close() }.onFailure { return@thread }
        }
        val test = server.use {
            runner { ap }.measure(
            ThroughputSettings(
                internetEnabled = true,
                internetDownloadUrl = "http://127.0.0.1:${server.localPort}/__down?bytes={bytes}",
                internetUploadUrl = " ",
                internetDurationSeconds = 1
            )
        ).tests.single()
        }

        assertEquals("internet", test.target)
        assertEquals(ThroughputTestResult.METHOD_HTTP, test.method)
        assertNull(test.upMbps)
        assertTrue(test.latencyMs != null)
        assertNull(test.downMbps)
        assertTrue(test.error.orEmpty(), test.error.orEmpty().startsWith("download: "))
        assertTrue(test.error.orEmpty(), test.error.orEmpty().endsWith("upload: not configured"))
    }

    @Test
    fun reportsEachPhaseAsItStartsWithStepNumbersAndFinishedValues() {
        val events = mutableListOf<ThroughputProgress>()
        runner { ap }.measure(
            ThroughputSettings(
                iperfEnabled = true, iperfHost = "127.0.0.1", iperfPort = closedPort,
                internetEnabled = true,
                internetDownloadUrl = "http://127.0.0.1:$closedPort/__down?bytes={bytes}",
                internetDurationSeconds = 2
            )
        ) { events += it }

        // Both servers are unreachable: only the latency phases start, transfers are skipped.
        assertEquals(listOf(1, 4), events.map { it.step })
        assertTrue(events.all { it.totalSteps == 6 && it.phase == ThroughputPhase.LATENCY && it.phaseSeconds == null })
        assertEquals(listOf("corporate", "internet"), events.map { it.target })
        assertEquals(
            listOf(ThroughputPhase.LATENCY, ThroughputPhase.DOWNLOAD, ThroughputPhase.UPLOAD),
            events[1].finished.map { it.phase }
        )
        assertTrue(events[1].finished.all { it.value == null })
    }

    @Test
    fun rowsCarryLinkBeaconDeviceAndLatencyStatistics() {
        // Reachable but every HTTP request is cut: latency is measured, transfers fail.
        val server = ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().close() }.onFailure { return@thread }
        }
        val test = server.use {
            runner { ap }.measure(
                ThroughputSettings(
                    internetEnabled = true,
                    internetDownloadUrl = "http://127.0.0.1:${server.localPort}/__down?bytes={bytes}",
                    internetDurationSeconds = 1
                )
            ).tests.single()
        }

        assertEquals(649, test.rxLinkMbps)
        assertEquals(5_500, test.frequencyMhz)
        assertEquals(100, test.channel)
        assertEquals(80, test.channelWidthMhz)
        assertEquals("802.11ax (Wi-Fi 6/6E)", test.wifiStandard)
        assertEquals(12, test.bssStationCount)
        assertEquals(31, test.bssChannelUtilizationPct)
        assertEquals("Zebra TC52", test.deviceModel)
        assertEquals("13", test.androidVersion)
        assertEquals(10, test.latencySamples)
        val min = test.latencyMs!!
        val avg = test.latencyAvgMs!!
        assertTrue("min $min avg $avg", min <= avg)
        assertTrue(test.jitterMs!! >= 0.0)
    }

    @Test
    fun networkTestsAndScriptsRunAfterTheTargetsWithProgress() {
        MiniHttpServer { MiniHttpServer.Response(200, "ok") }.use { server ->
            val tests = listOf(
                NetworkTest(name = "web", type = "http", url = "${server.base}/health"),
                NetworkTest(name = "off", type = "http", url = "${server.base}/x", enabled = false)
            )
            val scripts = listOf(
                HttpScript("flow", steps = listOf(
                    HttpScriptStep(name = "a", url = "${server.base}/a"),
                    HttpScriptStep(name = "b", url = "${server.base}/b")
                ))
            )
            val events = mutableListOf<ThroughputProgress>()
            val result = ThroughputRunner(
                iperf = Iperf3Client(),
                http = HttpSpeedTest(),
                socketFactory = SocketFactory.getDefault(),
                connection = { ap },
                networkTests = NetworkTestRunner(
                    TimedHttpClient(), SocketFactory.getDefault(), java.net.InetAddress::getAllByName,
                    { java.net.DatagramSocket() }, NetworkContext(), { ap }
                ),
                scriptRunner = ScriptRunner(TimedHttpClient()),
                clock = { 1L }
            ).measure(ThroughputSettings(), tests, scripts) { events += it }

            assertEquals(listOf("web"), result.networkTests!!.map { it.name })
            assertTrue(result.networkTests!!.single().ok)
            assertEquals(listOf("a", "b"), result.scripts!!.single().steps.map { it.name })
            assertEquals("aa:bb:cc:dd:ee:01", result.scripts!!.single().bssidEnd)
            assertEquals(listOf(1, 2, 3), events.map { it.step })
            assertTrue(events.all { it.totalSteps == 3 })
            assertEquals(listOf("http", "a", "b"), events.map { it.detail })
            assertEquals(2, events.last().finished.size)
        }
    }

    @Test
    fun scriptsWithoutWifiAreSkippedWithTheReason() {
        val run = runner { null }.measure(
            ThroughputSettings(),
            scripts = listOf(HttpScript("flow", steps = listOf(HttpScriptStep(name = "a", url = "http://127.0.0.1:9/a"))))
        ).scripts!!.single()
        assertEquals(ThroughputRunner.NOT_ON_WIFI, run.error)
        assertTrue(run.steps.isEmpty())
    }
}
