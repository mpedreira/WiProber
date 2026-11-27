package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verdict in the HTML report and in wiprober-performance.json (additive). */
class VerdictReportTest {
    private val t0 = 1_700_000_000_000L
    private val floorPlanId = "11111111-1111-4111-8111-111111111111"
    private val imageId = "22222222-2222-4222-8222-222222222222"
    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", emptyList(), "")
    private fun iperf(ssid: String, down: Double) = ThroughputTestResult("corporate", ThroughputTestResult.METHOD_IPERF3_TCP, 5, t0, targetId = TargetIds.IPERF,
        category = TargetCategory.PRIVATE_IPERF, ssid = ssid, downMbps = down, upMbps = 20.0, latencyMs = 5.0, bssidStart = "aa:00:00:00:00:01")

    private val points = listOf(
        ScanPoint(1L, 10f, 10f, listOf(net("Ops", "aa:00:00:00:00:01", -60), net("Guest", "bb:00:00:00:00:01", -50)), pointId = "a", scanId = "s1"),
        ScanPoint(2L, 50f, 10f, listOf(net("Ops", "aa:00:00:00:00:02", -62), net("Guest", "bb:00:00:00:00:01", -50)), pointId = "b", scanId = "s2"))
    private val runs = listOf(
        ActiveRun("run-a", "a", "s1", t0, t0 + 10_000, RunStatus.COMPLETED, results = PointThroughput(t0, listOf(iperf("Ops", 80.0)))),
        ActiveRun("run-b", "b", "s2", t0 + 20_000, t0 + 30_000, RunStatus.COMPLETED, results = PointThroughput(t0, listOf(iperf("Ops", 30.0)))))
    private val survey = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1, points, emptyList(), emptyList(), activeRuns = runs,
        plannedPoints = listOf(PlannedPoint("c", "Muelle 3", 90f, 90f)))
    private val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(
        FloorExportSnapshot("g", "Baja", File("g.png"), floorPlanId, imageId, false, null, survey)))
    private val requirements = Requirements(throughput = listOf(ThroughputRequirement("corporate", minDownMbps = 50.0)))

    @Test
    fun theSummaryAndTheFailingPointsAreReadable() {
        val model = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67, requirements = requirements)
        val html = SurveyReport.html(model)
        assertFalse("<script" in html)
        assertTrue("<h2>Veredicto</h2>" in html)
        // Ops: a passes, b fails (30 < 50), the planned point has no data.
        assertTrue(html, "1 de 2 con datos (50 %)" in html)
        assertTrue("<td>1 de 3</td>" in html)
        assertTrue("Qué no cumple" in html && "iPerf3 corporate bajada" in html && ">30 Mbit/s<" in html && ">50 Mbit/s<" in html && "run-b" in html)
        assertTrue("Muelle 3" in html && "punto planificado sin medir" in html)
        assertTrue("Requisitos aplicados" in html && "bajada ≥ 50 Mbit/s" in html)
        // Guest is judged on its own: no tests on it, so its points lack data; it does not borrow Ops' runs.
        val guest = model.floors.single().ssids.single { it.ssid == "Guest" }.verdict!!
        assertEquals(VerdictState.MISSING, guest.state)
        assertEquals(0, guest.withData)
    }

    @Test
    fun outagesOnTheFloorAreReportedEvenWithoutARoamingRequirement() {
        val route = ContinuousScanSession("r1", t0 + 100_000, t0 + 110_000, listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(10_000, 90f, 0f)),
            emptyList(), roaming = RoamingLog("gw", 500, 10, 3, 3.0, listOf(RoamingEvent(RoamingEvent.REACHABILITY_LOSS, 4_000, fromSsid = "Ops", toSsid = "Ops",
                outageLowerBoundMs = 800, outageUpperBoundMs = 1_200)), schema = 2, method = RoamingTracker.METHOD))
        val withRoute = snapshot.copy(floors = snapshot.floors.map { it.copy(survey = it.survey.copy(continuousScanSessions = listOf(route))) })
        val model = SurveyReport.build(withRoute, t0, "Ops", weakDbm = -67, requirements = requirements)
        val ops = model.floors.single().ssids.single { it.ssid == "Ops" }.verdict!!
        assertEquals(1, ops.outagesOnFloor)
        assertTrue(SurveyReport.html(model).contains("1 pérdidas de alcance</b> en esta planta (sin requisito de roaming configurado"))
        // With a roaming requirement it decides.
        val strict = SurveyReport.build(withRoute, t0, "Ops", weakDbm = -67, requirements = requirements.copy(roaming = RoamingRequirement(maxOutageMs = 500)))
        assertEquals(VerdictState.FAIL, strict.floors.single().ssids.single { it.ssid == "Ops" }.verdict!!.floorChecks.single().state)
    }

    @Test
    fun withoutRequirementsTheReportAndTheJsonAreUnchanged() {
        val plain = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67)
        val empty = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67, requirements = Requirements())
        assertEquals(SurveyReport.html(plain), SurveyReport.html(empty))
        assertFalse("Veredicto" in SurveyReport.html(plain))
        assertTrue(plain.floors.single().ssids.all { it.verdict == null })
        val before = PerformanceExport.build(null, "Site.esx", t0, snapshot)
        val after = PerformanceExport.build(null, "Site.esx", t0, snapshot, plain)
        assertEquals(before, after)
        assertFalse(after.has("verdicts") || after.has("requirements"))
    }

    @Test
    fun theJsonAddsRequirementsAndVerdictsWithoutChangingTheRest() {
        val model = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67, requirements = requirements)
        val before = PerformanceExport.build(null, "Site.esx", t0, snapshot)
        val json = PerformanceExport.build(null, "Site.esx", t0, snapshot, model)
        // Additive: every key of before is there with the same value.
        before.keySet().forEach { assertEquals(it, before[it], json[it]) }
        assertEquals(before.keySet() + setOf("requirements", "verdicts"), json.keySet())
        val applied = json.getAsJsonObject("requirements")
        assertEquals(50.0, applied.getAsJsonArray("throughput")[0].asJsonObject["minDownMbps"].asDouble, 0.0)
        assertEquals(-67, applied["rssiThresholdDbm"].asInt)
        val ops = json.getAsJsonArray("verdicts").map { it.asJsonObject }.single { it["ssid"].asString == "Ops" }
        assertEquals(VerdictState.FAIL, ops["state"].asString)
        assertEquals(floorPlanId, ops["floorPlanId"].asString)
        assertEquals(listOf(1, 1, 1, 2), listOf("pointsPassing", "pointsFailing", "pointsMissingData", "pointsWithData").map { ops[it].asInt })
        assertEquals(50.0, ops["percentPassing"].asDouble, 0.0)
        val b = ops.getAsJsonArray("points").map { it.asJsonObject }.single { it["pointId"].asString == "b" }
        val failed = b.getAsJsonArray("checks").map { it.asJsonObject }.single { it["state"].asString == VerdictState.FAIL }
        assertEquals(listOf("iperf3:corporate:download", "30.0", "50.0", "run-b"), listOf("requirement", "value", "threshold", "runId").map { failed[it].asString })
        val planned = ops.getAsJsonArray("points").map { it.asJsonObject }.single { it["pointId"].asString == "c" }
        assertTrue("null is written as null, not 0", planned.getAsJsonArray("checks").all { it.asJsonObject["value"].isJsonNull })
    }
}
