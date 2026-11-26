package com.example.wiprober

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measuring again at existing local, imported and planned points; per-SSID states; templates. */
class PointReuseTest {
    private var now = 1_000L
    private var ids = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val phases = listOf(PhaseRecord("target:iperf3-private:download", "corporate · download"))

    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", listOf("AX"), "")
    private val operativaWeak = net("Operativa", "00:00:00:00:00:01", -78)
    private val operativaGood = net("Operativa", "00:00:00:00:00:02", -55)
    private val invitados = net("Invitados", "00:00:00:00:00:09", -40)

    private val imported = ScanPoint(5L, 300f, 400f, emptyList(), importedFromEsx = true, pointId = "imported-1", scanId = "imported-scan-1")

    private fun viewModel(points: List<ScanPoint> = listOf(imported), planned: List<PlannedPoint> = emptyList()) =
        MainViewModel({ now }, { "id-${ids++}" }, scope).apply {
            loadProject(MapInfo("m.png", 1000, 800), 0.1, points, emptyList(), emptyList(), plannedPoints = planned)
        }

    @Test
    fun measuringAtAnImportedPointReusesItsIdAndExactPositionAndKeepsTheOriginal() {
        val vm = viewModel()
        assertTrue(vm.requestMeasureAtPoint("imported-1"))
        vm.grantPendingScanAccess()
        now = 2_000L
        val scan = vm.completeStopAndGoScan(listOf(operativaGood))!!

        assertEquals("imported-1", scan.pointId)
        assertEquals(300f, scan.x)
        assertEquals(400f, scan.y)
        assertTrue(scan.scanId != imported.scanId)
        assertEquals(listOf(imported, scan), vm.screenState.value.scanPoints)

        // Undo removes only the local scan; the imported point stays as it was.
        assertEquals(LastAction.SCAN, vm.undoLastAction())
        assertEquals(listOf(imported), vm.screenState.value.scanPoints)
    }

    @Test
    fun activeTestsOnlyAtAPlannedPointAreSavedWithItsPositionAndCanBeUndone() {
        val planned = PlannedPoint("planned-1", "Dock 1", 120f, 80f, batch = "b1")
        val vm = viewModel(emptyList(), listOf(planned))
        val runId = vm.startActiveRun("planned-1", null, phases, "device-settings") { _, _ ->
            PointThroughput(now, listOf(ThroughputTestResult("corporate", "iperf3-tcp", 5, now, downMbps = 90.0)))
        }
        val run = vm.screenState.value.activeRuns.single { it.runId == runId }
        assertEquals(RunStatus.COMPLETED, run.status)
        assertNull(run.scanId)
        assertEquals(120f, run.x)
        assertEquals(80f, run.y)

        assertEquals(LastAction.ACTIVE_RUN, vm.undoLastAction())
        assertTrue(vm.screenState.value.activeRuns.isEmpty())
        assertEquals(listOf(planned), vm.screenState.value.plannedPoints)
        assertNull(vm.startActiveRun("unknown", null, phases, null) { _, _ -> error("never") })
    }

    @Test
    fun plannedPointsAreAddedAndUndoneAsOneBatch() {
        val vm = viewModel(emptyList())
        val batch = listOf(PlannedPoint("a", "A", 1f, 1f, "b1"), PlannedPoint("b", "B", 2f, 2f, "b1"))
        assertTrue(vm.addPlannedPoints(batch))
        assertTrue(vm.addPlannedPoints(listOf(PlannedPoint("c", "C", 3f, 3f, "b2"), PlannedPoint("a", "A", 1f, 1f, "b2"))))
        assertEquals(listOf("a", "b", "c"), vm.screenState.value.plannedPoints.map { it.pointId })
        assertFalse(vm.addPlannedPoints(listOf(PlannedPoint("a", "A", 1f, 1f, "b3"))))

        assertEquals(LastAction.PLANNED_POINTS, vm.undoLastAction())
        assertEquals(listOf("a", "b"), vm.screenState.value.plannedPoints.map { it.pointId })
        assertEquals(batch, vm.projectSnapshot()!!.plannedPoints)
    }

    @Test
    fun eachSsidIsJudgedOnItsOwn() {
        val points = listOf(
            ScanPoint(1L, 10f, 10f, listOf(operativaWeak, invitados), pointId = "p1", scanId = "s1"),
            ScanPoint(2L, 20f, 10f, listOf(invitados), pointId = "p2", scanId = "s2"),
            ScanPoint(3L, 30f, 10f, emptyList(), pointId = "p3", scanId = "s3"), // empty scan: not valid
            ScanPoint(4L, 10f, 10f, listOf(operativaGood), pointId = "p1", scanId = "s4"), // measured again later
            imported
        )
        val planned = listOf(PlannedPoint("planned-1", "Dock 1", 50f, 50f))

        val operativa = SsidCoverage.points(points, planned, "Operativa").associateBy { it.pointId }
        assertEquals(PointState.OK, operativa.getValue("p1").state) // latest valid scan
        assertEquals("00:00:00:00:00:02", operativa.getValue("p1").strongestBssid)
        assertEquals(2, operativa.getValue("p1").scans)
        // A strong guest network never makes the operational SSID look covered.
        assertEquals(PointState.SSID_NOT_DETECTED, operativa.getValue("p2").state)
        assertEquals(PointState.NOT_MEASURED, operativa.getValue("p3").state)
        assertEquals(PointState.NOT_MEASURED, operativa.getValue("imported-1").state)
        assertTrue(operativa.getValue("imported-1").imported)
        assertEquals(PointState.NOT_MEASURED, operativa.getValue("planned-1").state)
        assertEquals("Dock 1", operativa.getValue("planned-1").label)

        val onlyFirst = SsidCoverage.points(points.take(3), emptyList(), "Operativa").associateBy { it.pointId }
        assertEquals(PointState.WEAK, onlyFirst.getValue("p1").state)

        val guests = SsidCoverage.points(points, planned, "Invitados").associateBy { it.pointId }
        assertEquals(PointState.SSID_NOT_DETECTED, guests.getValue("p1").state) // latest scan of p1 saw no Invitados
        assertEquals(PointState.OK, guests.getValue("p2").state)

        assertEquals(listOf("Invitados", "Operativa"), SsidCoverage.ssids(points))
        assertEquals("Operativa", SsidCoverage.selected(" Operativa ", points, emptyList()))
        assertEquals("Invitados", SsidCoverage.selected("", points, emptyList()))
        assertEquals("p1", SsidCoverage.pointAt(points, planned, 12f, 11f, 5f))
        assertEquals("planned-1", SsidCoverage.pointAt(points, planned, 50f, 52f, 5f))
        assertNull(SsidCoverage.pointAt(points, planned, 500f, 500f, 5f))
    }

    @Test
    fun templatesAreScaledToThePlanAndRepeatable() {
        val json = """
            {"format": "wiprober-points", "version": 1, "image": {"width": 2000, "height": 1600},
             "points": [{"id": "A1", "label": "Dock 1", "x": 200, "y": 160},
                        {"id": "A2", "label": "Dock 2", "x": 1999, "y": 1599},
                        {"id": "OUT", "x": 2500, "y": 10}]}
        """.trimIndent()
        val preview = PointTemplateParser.parse(json, 1000, 800, "floor-1", "batch-1")
        assertEquals(0.5, preview.scale, 1e-9)
        assertEquals(1, preview.outside)
        assertEquals(listOf("Dock 1", "Dock 2"), preview.points.map { it.label })
        assertEquals(100f, preview.points[0].x)
        assertEquals(80f, preview.points[0].y)
        assertTrue(preview.points.all { it.batch == "batch-1" })

        val again = PointTemplateParser.parse(json, 1000, 800, "floor-1", "batch-2", existing = preview.points.map { it.pointId }.toSet())
        assertEquals(2, again.duplicates)
        assertTrue(again.points.isEmpty())
        val otherFloor = PointTemplateParser.parse(json, 1000, 800, "floor-2", "batch-3")
        assertTrue(otherFloor.points.map { it.pointId }.none { it in preview.points.map(PlannedPoint::pointId) })

        val csv = "# image 1000x800\nid;label;x;y\nB1;Office;10,5;20\n".replace("10,5", "10.5")
        val fromCsv = PointTemplateParser.parse(csv, 1000, 800, "floor-1", "b")
        assertEquals(10.5f, fromCsv.points.single().x)
        assertEquals("Office", fromCsv.points.single().label)
        assertEquals(1, PointTemplateParser.parse("label,x,y\nA,1,2", 1000, 800, "f", "b").points.size)

        listOf(
            json.replace("1600", "1000"), // different proportions
            """{"format": "wiprober-points", "version": 1, "points": []}""",
            "x,y\nfoo,bar"
        ).forEach { text ->
            try {
                PointTemplateParser.parse(text, 1000, 800, "f", "b")
                throw AssertionError("expected ConfigException for $text")
            } catch (expected: ConfigException) {
                assertNotNull(expected.message)
            }
        }
    }

    @Test
    fun runsAndPlannedPointsOnAnImportedFloorKeepTheSourceEsxUntouched() {
        val run = ActiveRun("r", "imported-1", null, 1L, 2L, RunStatus.COMPLETED, x = 300f, y = 400f,
            results = PointThroughput(1L, listOf(ThroughputTestResult("corporate", "iperf3-tcp", 5, 1L, downMbps = 50.0))))
        val floor = FloorExportSnapshot(
            "f", "Ground", File("g.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
            importedFromSource = true, sourceMetersPerUnit = 0.1,
            survey = ProjectSnapshot(MapInfo("g.png", 1000, 800), 0.1, listOf(imported), emptyList(), emptyList(),
                activeRuns = listOf(run), plannedPoints = listOf(PlannedPoint("planned-1", "Dock", 1f, 1f)))
        )
        val snapshot = ProjectExportSnapshot("p", "Site", File("base.esx"), listOf(floor))
        assertFalse(EsxExportService.hasSourceOverlayChanges(snapshot))

        // The run still exports, at the imported point's exact coordinates.
        val row = PerformanceExport.build(null, "Site.esx", 0L, snapshot).getAsJsonArray("measurements").single().asJsonObject
        assertEquals(300.0, row["x"].asDouble, 0.0)
        assertEquals(400.0, row["y"].asDouble, 0.0)
        assertEquals("imported-1", row["pointId"].asString)
        assertTrue(row["surveyId"].isJsonNull)
    }
}
