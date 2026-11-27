package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 9: same alias, different destination, port, direction or method: different series. */
class SeriesTest {
    private fun internet(endpoint: String, method: String = "http", down: Double = 50.0) = ThroughputTestResult(
        "Internet", method, 5, 1L, targetId = TargetIds.INTERNET, category = TargetCategory.INTERNET, ssid = "Corp",
        downloadEndpointId = endpoint, uploadEndpointId = endpoint, downMbps = down
    )

    @Test
    fun theSameAliasOnAnotherEndpointPortDirectionOrMethodIsAnotherSeries() {
        val a = internet("https://speed.example.com:443")
        val otherPort = internet("https://speed.example.com:8443")
        val otherHost = internet("https://cdn.example.com:443")
        assertNotEquals(Series.throughput(a, Series.DOWNLOAD).id, Series.throughput(otherPort, Series.DOWNLOAD).id)
        assertNotEquals(Series.throughput(a, Series.DOWNLOAD).id, Series.throughput(otherHost, Series.DOWNLOAD).id)
        assertNotEquals(Series.throughput(a, Series.DOWNLOAD).id, Series.throughput(a, Series.UPLOAD).id)
        assertEquals(Series.throughput(a, Series.DOWNLOAD).id, Series.throughput(a.copy(downMbps = 90.0), Series.DOWNLOAD).id)

        val icmp = NetworkTestResult("gw", NetworkTest.PING, "10.0.0.1", "icmp", 1L, ok = true, alias = "Gateway", rttAvgMs = 2.0)
        val tcp = icmp.copy(method = "tcp", rttAvgMs = 9.0)
        assertNotEquals(Series.networkTest(icmp).id, Series.networkTest(tcp).id)

        val v1 = HttpScript("portal", steps = listOf(HttpScriptStep("a", url = "https://portal.example.com/a")))
        val v2 = v1.copy(steps = listOf(HttpScriptStep("a", url = "https://portal.example.com/b")))
        assertNotEquals(Series.definitionHash(v1), Series.definitionHash(v2))
    }

    @Test
    fun legacyResultsKeepAnIdentityMarkedNotComparable() {
        val legacy = ThroughputTestResult("corporate", "iperf3-tcp", 5, 1L, downMbps = 100.0)
        val series = Series.throughput(legacy, Series.DOWNLOAD)
        assertTrue(series.id.startsWith("legacy:"))
        assertFalse(series.comparable)
    }

    @Test
    fun theReportAndTheJsonTellTheSeriesApart() {
        val point = ScanPoint(1L, 10f, 10f, emptyList(), pointId = "p", scanId = "s")
        fun run(id: String, at: Long, test: ThroughputTestResult) = ActiveRun(id, "p", "s", at, at + 1, RunStatus.COMPLETED, results = PointThroughput(at, listOf(test)))
        val survey = ProjectSnapshot(MapInfo("m.png", 100, 100), 0.1, listOf(point), emptyList(), emptyList(),
            activeRuns = listOf(run("r1", 1L, internet("https://speed.example.com:443", down = 50.0)), run("r2", 2L, internet("https://speed.example.com:8443", down = 90.0))))
        val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(FloorExportSnapshot("f", "G", java.io.File("m.png"),
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, survey)))
        val downloads = SurveyReport.build(snapshot, 0L, "Corp", SurveyReport.RunSelection.ALL).floors.single().ssids.single()
            .metrics.filter { it.measure == "download" }
        assertEquals(2, downloads.size)
        assertEquals(setOf(50.0, 90.0), downloads.map { it.median }.toSet())
        val html = SurveyReport.html(SurveyReport.build(snapshot, 0L, "Corp", SurveyReport.RunSelection.ALL))
        assertTrue("speed.example.com:8443" in html && "speed.example.com:443 " in html)
        val rows = PerformanceExport.build(null, "Site.esx", 0L, snapshot).getAsJsonArray("measurements").map { it.asJsonObject["seriesDownload"].asString }
        assertEquals(2, rows.toSet().size)
    }
}
