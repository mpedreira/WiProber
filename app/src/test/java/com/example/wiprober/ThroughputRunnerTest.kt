package com.example.wiprober

import java.net.ServerSocket
import javax.net.SocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputRunnerTest {
    private val closedPort = ServerSocket(0).use { it.localPort }
    private val ap = WifiConnectionInfo("CORP", "aa:bb:cc:dd:ee:01", -58, 573)

    private fun runner(connection: () -> WifiConnectionInfo?) = ThroughputRunner(
        iperf = Iperf3Client(connectTimeoutMillis = 1_000),
        http = HttpSpeedTest(),
        socketFactory = SocketFactory.getDefault(),
        connection = connection,
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
    fun unreachableServerKeepsNullsAndRecordsRoaming() {
        val aps = ArrayDeque(listOf(ap, ap.copy(bssid = "aa:bb:cc:dd:ee:02")))
        val test = runner { aps.removeFirst() }.measure(
            ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", iperfPort = closedPort, iperfDurationSeconds = 1)
        ).tests.single()

        assertEquals("CORP", test.ssid)
        assertEquals(-58, test.rssi)
        assertEquals(573, test.linkMbps)
        assertEquals("aa:bb:cc:dd:ee:01", test.bssidStart)
        assertEquals("aa:bb:cc:dd:ee:02", test.bssidEnd)
        assertNull(test.latencyMs)
        assertNull(test.downMbps)
        assertNull(test.upMbps)
        val error = test.error.orEmpty()
        assertTrue(error, error.startsWith("latency: ") && "download: " in error && "upload: " in error)
    }

    @Test
    fun internetWithoutUploadUrlSaysSo() {
        val test = runner { ap }.measure(
            ThroughputSettings(
                internetEnabled = true,
                internetDownloadUrl = "http://127.0.0.1:$closedPort/__down?bytes={bytes}",
                internetUploadUrl = " ",
                internetDurationSeconds = 1
            )
        ).tests.single()

        assertEquals("internet", test.target)
        assertEquals(ThroughputTestResult.METHOD_HTTP, test.method)
        assertNull(test.upMbps)
        assertTrue(test.error.orEmpty(), "upload: not configured" in test.error.orEmpty())
    }
}
