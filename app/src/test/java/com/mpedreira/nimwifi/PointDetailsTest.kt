package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointDetailsTest {
    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", emptyList(), "")

    @Test
    fun aPointShowsItsScansAndRunsNewestFirst() {
        val state = SurveyScreenState(
            mapInfo = MapInfo("m.png", 100, 100),
            scanPoints = listOf(
                ScanPoint(1L, 10f, 20f, listOf(net("Corp", "aa:01", -70)), pointId = "p", scanId = "s1"),
                ScanPoint(5L, 10f, 20f, listOf(net("Corp", "aa:02", -55), net("Guest", "bb:01", -40)), pointId = "p", scanId = "s2"),
                ScanPoint(3L, 50f, 50f, emptyList(), pointId = "other", scanId = "s3")
            ),
            activeRuns = listOf(
                ActiveRun("r1", "p", "s1", 2L, 3L, RunStatus.COMPLETED, results = PointThroughput(2L, listOf(
                    ThroughputTestResult("corporate", "iperf3-tcp", 5, 2L, downMbps = 120.0, upMbps = 80.0, latencyMs = 2.0,
                        rssi = -60, bssidStart = "aa:01", associationChange = AssociationChange.BSSID_CHANGED)),
                    networkTests = listOf(NetworkTestResult("dns", "dns", "dns", "udp", 2L, ok = false, errorCode = ErrorCodes.TIMEOUT)))),
                ActiveRun("r2", "p", null, 6L, 7L, RunStatus.SKIPPED, statusReason = ErrorCodes.SKIPPED_BY_USER)
            )
        )
        val details = PointDetails.of(state, "p", "Corp")!!
        assertEquals(listOf("s2", "s1"), details.scans.map { it.scanId })
        assertEquals(-55, details.scans[0].strongestRssi)
        assertEquals(2, details.scans[0].networks)
        assertEquals(listOf("r2", "r1"), details.runs.map { it.runId })
        val lines = PointDetails.resultLines(details.runs[1])
        assertTrue(lines[0], lines[0].startsWith("corporate: ↓ 120.0 Mbps ↑ 80.0 Mbps · 2.0 ms · -60 dBm · aa:01 · bssid_changed"))
        assertEquals("dns (dns/udp): ⚠ timeout", lines[1])
        assertTrue(PointDetails.resultLines(details.runs[0]).isEmpty())
        assertNull(PointDetails.of(state, "missing", null))
    }
}
