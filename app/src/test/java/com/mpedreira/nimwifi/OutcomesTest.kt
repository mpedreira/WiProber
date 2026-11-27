package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 10: planned, valid, partial, failed, skipped, cancelled and not configured, from sheet to HTML and JSON. */
class OutcomesTest {
    private fun internet(ssid: String?, down: Double?, downStatus: String, up: String, upCode: String?) = ThroughputTestResult(
        "Internet", "http", 5, 1L, targetId = TargetIds.INTERNET, category = TargetCategory.INTERNET, ssid = ssid,
        downloadEndpointId = "https://speed.example.com:443", uploadEndpointId = "https://speed.example.com:443",
        downMbps = down, downloadStatus = downStatus, uploadStatus = up, uploadErrorCode = upCode,
        downloadErrorCode = if (down == null) ErrorCodes.WIFI_LOST else null
    )

    private val points = listOf("a", "b", "c", "d").mapIndexed { i, id -> ScanPoint(i + 1L, 10f * i, 10f, emptyList(), pointId = id, scanId = "s$id") }
    private val runs = listOf(
        // Upload disabled: not configured.
        ActiveRun("r1", "a", "sa", 10L, 11L, RunStatus.COMPLETED, results = PointThroughput(10L, listOf(
            internet("Ops", 80.0, RunStatus.COMPLETED, RunStatus.SKIPPED, ErrorCodes.NOT_CONFIGURED)))),
        // Connection lost before the SSID was known: unknown association.
        ActiveRun("r2", "b", "sb", 20L, 21L, RunStatus.COMPLETED, results = PointThroughput(20L, listOf(
            internet(null, null, RunStatus.FAILED, RunStatus.SKIPPED, ErrorCodes.SKIPPED_UNREACHABLE)))),
        // Cancelled with the download saved.
        ActiveRun("r3", "c", "sc", 30L, 31L, RunStatus.CANCELLED, statusReason = ErrorCodes.CANCELLED, results = PointThroughput(30L, listOf(
            internet("Ops", 60.0, RunStatus.COMPLETED, RunStatus.CANCELLED, ErrorCodes.CANCELLED)))),
        // A script with its second step pending.
        ActiveRun("r4", "d", "sd", 40L, 41L, RunStatus.CANCELLED, statusReason = ErrorCodes.CANCELLED, results = PointThroughput(40L, emptyList(),
            scripts = listOf(ScriptRun("portal", 40L, alias = "Portal", stepsTotal = 2, errorCode = ErrorCodes.CANCELLED, ssid = "Ops", definitionHash = "abc",
                steps = listOf(ScriptStepResult("home", "GET", "portal.example.com", "/", 200, ok = true, totalMs = 50.0))))))
    )
    private val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(FloorExportSnapshot(
        "f", "G", File("m.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null,
        ProjectSnapshot(MapInfo("m.png", 100, 100), 0.1, points, emptyList(), emptyList(), activeRuns = runs)
    )))

    @Test
    fun theFourCasesAreReportedWithTheirOwnStates() {
        val floor = SurveyReport.build(snapshot, 0L, "Ops").floors.single()
        val ops = floor.ssids.single { it.ssid == "Ops" }
        val download = ops.metrics.single { it.measure == "download" }
        assertEquals(mapOf(SurveyReport.Outcome.VALID to 2), download.outcomes)
        val upload = ops.metrics.single { it.measure == "upload" }
        assertEquals(mapOf(SurveyReport.Outcome.NOT_CONFIGURED to 1, SurveyReport.Outcome.CANCELLED to 1), upload.outcomes)
        val script = ops.metrics.single { it.measure == "script" }
        assertEquals(mapOf(SurveyReport.Outcome.PARTIAL to 1), script.outcomes)
        assertEquals(0, script.samples) // a partial run is not a comparable total

        val unknown = floor.unknownAssociation!!
        assertEquals(listOf("r2"), unknown.runs.map { it.runId })
        assertEquals(mapOf(SurveyReport.Outcome.FAILED to 1), unknown.metrics.single { it.measure == "download" }.outcomes)

        val html = SurveyReport.html(SurveyReport.build(snapshot, 0L, "Ops"))
        assertTrue("Unknown association" in html)
        assertTrue("not_configured 1" in html && "cancelled 1" in html && "partial 1" in html)
        assertTrue("Denominators" in html)

        // JSON: the script run is partial with a pending step; the sheet shows the codes.
        val scriptRow = PerformanceExport.build(null, "Site.esx", 0L, snapshot).getAsJsonArray("scriptRuns").single().asJsonObject
        assertEquals("partial", scriptRow["status"].asString)
        assertEquals(1, scriptRow["stepsPending"].asInt)
        val sheet = PointDetails.resultLines(runs[2]).single()
        assertTrue(sheet, "↓ 60.0" in sheet && "cancelled" in sheet)
        assertTrue(PointDetails.resultLines(runs[0]).single().contains("not_configured"))
    }
}
