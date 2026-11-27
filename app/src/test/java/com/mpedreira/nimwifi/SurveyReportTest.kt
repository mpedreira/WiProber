package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two floors, two SSIDs, the same points measured twice. */
class SurveyReportTest {
    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", listOf("AX"), "")
    private val op1 = net("Operativa", "00:00:00:00:00:01", -60)
    private val op1weak = net("Operativa", "00:00:00:00:00:01", -80)
    private val guest = net("Invitados", "00:00:00:00:00:09", -35)

    private fun iperf(at: Long, down: Double?, ssid: String = "Operativa", change: String = AssociationChange.SAME) =
        ThroughputTestResult("Core <iperf>", ThroughputTestResult.METHOD_IPERF3_TCP, 5, at, category = TargetCategory.PRIVATE_IPERF,
            ssid = ssid, bssidStart = "00:00:00:00:00:01", bssidEnd = if (change == AssociationChange.SAME) "00:00:00:00:00:01" else "00:00:00:00:00:02",
            associationChange = change, downMbps = down, upMbps = down?.div(2), latencyMs = 3.0, latencyMethod = "tcp-connect",
            downloadErrorCode = if (down == null) ErrorCodes.TIMEOUT else null)

    private fun internet(at: Long, down: Double) =
        ThroughputTestResult("Internet", "http", 5, at, category = TargetCategory.INTERNET, ssid = "Operativa", downMbps = down, upMbps = 20.0, latencyMs = 15.0)

    private fun dns(ok: Boolean) = NetworkTestResult("dns", NetworkTest.DNS, "dns", "udp", 1L, ok = ok, alias = "Corporate DNS",
        category = TargetCategory.INTERNAL, ssid = "Operativa", dnsMs = if (ok) 4.0 else null, errorCode = if (ok) null else ErrorCodes.TIMEOUT)

    private fun run(id: String, pointId: String, at: Long, results: PointThroughput) =
        ActiveRun(id, pointId, null, at, at + 10, RunStatus.COMPLETED, results = results)

    private val ground = ProjectSnapshot(
        MapInfo("g.png", 100, 100), 0.1,
        listOf(
            ScanPoint(1L, 10f, 10f, listOf(op1, guest), pointId = "a", scanId = "a1"),
            ScanPoint(2L, 50f, 10f, listOf(guest), pointId = "b", scanId = "b1"),
            ScanPoint(3L, 10f, 10f, listOf(op1weak, guest), pointId = "a", scanId = "a2") // same point, again
        ),
        emptyList(), emptyList(),
        activeRuns = listOf(
            run("r1", "a", 100L, PointThroughput(100L, listOf(iperf(100L, 300.0), internet(100L, 90.0)), listOf(dns(true)))),
            run("r2", "a", 200L, PointThroughput(200L, listOf(iperf(200L, null, change = AssociationChange.BSSID_CHANGED), internet(200L, 80.0)), listOf(dns(false)))),
            run("r3", "b", 300L, PointThroughput(300L, listOf(iperf(300L, 100.0)), emptyList()))
        ),
        plannedPoints = listOf(PlannedPoint("p", "Dock 7", 90f, 90f))
    )
    private val first = ProjectSnapshot(
        MapInfo("f.png", 100, 100), 0.1,
        listOf(ScanPoint(4L, 5f, 5f, listOf(guest), pointId = "c", scanId = "c1")), emptyList(), emptyList()
    )
    private val snapshot = ProjectExportSnapshot("p", "Warehouse & <Site>", null, listOf(
        FloorExportSnapshot("g", "Ground", File("g.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, ground),
        FloorExportSnapshot("f", "First", File("f.png"), "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444", false, null, first)
    ))

    @Test
    fun reportsEachFloorAndSsidSeparately() {
        val model = SurveyReport.build(snapshot, 0L, chosenSsid = "Operativa")
        val groundOperativa = model.floors[0].ssids.single { it.ssid == "Operativa" }
        assertEquals(listOf("Operativa", "Invitados"), model.floors[0].ssids.map { it.ssid })
        assertEquals(1, groundOperativa.count(PointState.WEAK)) // a: latest scan -80
        assertEquals(1, groundOperativa.count(PointState.SSID_NOT_DETECTED)) // b: only the guest SSID
        assertEquals(1, groundOperativa.count(PointState.NOT_MEASURED)) // the planned point
        assertEquals(3, model.floors[0].runs)
        assertEquals(1, model.floors[0].remeasuredPoints) // "a": two scans and two runs, one physical point

        // First floor: the chosen SSID was never seen there.
        assertEquals(PointState.SSID_NOT_DETECTED, model.floors[1].ssids.single { it.ssid == "Operativa" }.points.single().state)
    }

    @Test
    fun servicesAreSeparatedByCategoryAndAliasWithUnitsMethodsSamplesAndFailures() {
        val latest = SurveyReport.build(snapshot, 0L, "Operativa").floors[0].ssids.single { it.ssid == "Operativa" }
        val iperfDown = latest.metrics.single { it.category == TargetCategory.PRIVATE_IPERF && it.measure == "download" }
        // Last valid value per point within the SSID: r1 (300) for "a" — its last attempt r2 failed — and r3 (100) for "b".
        assertEquals(2, iperfDown.samples)
        assertEquals(1, iperfDown.failures)
        assertEquals(200.0, iperfDown.median)
        val pointA = iperfDown.points.single { it.pointId == "a" }
        assertEquals("r2", pointA.lastAttempt.runId)
        assertEquals("r1", pointA.lastValid!!.runId)
        assertEquals("Mbit/s", iperfDown.unit)
        assertEquals(ThroughputTestResult.METHOD_IPERF3_TCP, iperfDown.method)
        val internetDown = latest.metrics.single { it.category == TargetCategory.INTERNET && it.measure == "download" }
        assertEquals(80.0, internetDown.median)
        val dnsMetric = latest.metrics.single { it.alias == "Corporate DNS" }
        assertEquals(TargetCategory.INTERNAL, dnsMetric.category)
        assertEquals(1, dnsMetric.samples) // r1's valid value is kept…
        assertEquals(1, dnsMetric.failures) // …and the failed last attempt (r2) is counted
        assertEquals(1, latest.apChanges.size)

        val all = SurveyReport.build(snapshot, 0L, "Operativa", SurveyReport.RunSelection.ALL).floors[0].ssids.single { it.ssid == "Operativa" }
        val allDown = all.metrics.single { it.category == TargetCategory.PRIVATE_IPERF && it.measure == "download" }
        assertEquals(2, allDown.samples)
        assertEquals(200.0, allDown.median) // 100 and 300
        assertEquals(2, all.metrics.single { it.alias == "Corporate DNS" }.samples + all.metrics.single { it.alias == "Corporate DNS" }.failures)
    }

    @Test
    fun htmlIsSelfContainedAndEscaped() {
        val html = SurveyReport.html(SurveyReport.build(snapshot, 0L, "Operativa"))
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("Warehouse &amp; &lt;Site&gt;" in html)
        assertTrue("Core &lt;iperf&gt;" in html)
        assertFalse("<script" in html)
        assertTrue("Señal débil medida" in html)
        assertTrue("SSID no detectado en un escaneo válido" in html)
        assertTrue("Sin medición local" in html)
        assertTrue("iPerf3 privado" in html && "Internet" in html && "Servicios internos" in html)
        assertTrue("Dock 7" in html)
        assertTrue("Planta: Ground" in html && "Planta: First" in html)
    }
}
