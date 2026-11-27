package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 1: two passes of one point on different SSIDs keep their own figures. */
class PerSsidSelectionTest {
    private fun iperf(ssid: String, down: Double?) = ThroughputTestResult(
        "corporate", ThroughputTestResult.METHOD_IPERF3_TCP, 5, 1L, targetId = TargetIds.IPERF, category = TargetCategory.PRIVATE_IPERF,
        ssid = ssid, downloadEndpointId = "iperf.example.com:5201", uploadEndpointId = "iperf.example.com:5201",
        downMbps = down, downloadStatus = if (down != null) RunStatus.COMPLETED else RunStatus.CANCELLED
    )

    private val point = ScanPoint(1L, 10f, 10f, emptyList(), pointId = "p", scanId = "s")
    private val phases = listOf(PhaseRecord("target:${TargetIds.IPERF}:download", "corporate · download"))
    private val operativa = ActiveRun("r1", "p", "s", 100L, 110L, RunStatus.COMPLETED, results = PointThroughput(100L, listOf(iperf("Operativa", 300.0))), phases = phases.map { it.copy(status = RunStatus.COMPLETED) })
    private val invitados = ActiveRun("r2", "p", null, 200L, 210L, RunStatus.COMPLETED, results = PointThroughput(200L, listOf(iperf("Invitados", 40.0))), phases = phases.map { it.copy(status = RunStatus.COMPLETED) })
    // Third run, on Operativa again, cancelled before any value.
    private val cancelled = ActiveRun("r3", "p", null, 300L, 305L, RunStatus.CANCELLED, statusReason = ErrorCodes.CANCELLED,
        observedSsid = "Operativa", phases = phases.map { it.copy(status = RunStatus.CANCELLED, errorCode = ErrorCodes.CANCELLED) })

    private val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(FloorExportSnapshot(
        "f", "G", File("m.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null,
        ProjectSnapshot(MapInfo("m.png", 100, 100), 0.1, listOf(point), emptyList(), emptyList(), activeRuns = listOf(operativa, invitados, cancelled))
    )))

    @Test
    fun bothNetworksKeepTheirValidFiguresAndTheCancelledRunIsVisible() {
        val floor = SurveyReport.build(snapshot, 0L, "Operativa").floors.single()
        val ops = floor.ssids.single { it.ssid == "Operativa" }
        val guests = floor.ssids.single { it.ssid == "Invitados" }

        val opsDown = ops.metrics.single { it.measure == "download" }
        assertEquals(300.0, opsDown.median)
        val fact = opsDown.points.single()
        assertEquals("r3", fact.lastAttempt.runId)
        assertEquals(RunStatus.CANCELLED, fact.lastAttempt.status)
        assertNull(fact.lastAttempt.value)
        assertEquals("r1", fact.lastValid!!.runId)
        assertEquals(300.0, fact.lastValid!!.value)
        assertEquals(listOf("r1", "r3"), ops.runs.map { it.runId })
        assertEquals(RunStatus.CANCELLED, ops.runs.last().status)

        val guestsDown = guests.metrics.single { it.measure == "download" }
        assertEquals(40.0, guestsDown.median)
        assertEquals(listOf("r2"), guests.runs.map { it.runId })
        assertEquals("r2", guestsDown.points.single().lastValid!!.runId)
    }

    @Test
    fun theReportLinksEachFigureToItsRunInTheJson() {
        val html = SurveyReport.html(SurveyReport.build(snapshot, 0L, "Operativa"))
        assertTrue("run r1" in html && "run r3" in html && "run r2" in html)
        val runIds = PerformanceExport.build(null, "Site.esx", 0L, snapshot).getAsJsonArray("activeRuns").map { it.asJsonObject["runId"].asString }
        assertEquals(listOf("r1", "r2", "r3"), runIds)
        // One pass or one interval can be chosen.
        val onlyFirst = SurveyReport.build(snapshot, 0L, "Operativa", runId = "r1").floors.single().ssids.single { it.ssid == "Operativa" }
        assertEquals(listOf("r1"), onlyFirst.runs.map { it.runId })
        val campaign = SurveyReport.build(snapshot, 0L, "Operativa", fromMs = 150L, toMs = 250L).floors.single()
        assertTrue(campaign.ssids.single { it.ssid == "Operativa" }.runs.isEmpty())
    }
}
