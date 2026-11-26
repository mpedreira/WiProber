package com.example.wiprober

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerformanceExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val network = WifiNetworkInfo("Corp", "00:11:22:33:44:55", -50, 5_180, "WPA2", listOf("AC"), "")
    private val internet = ThroughputTestResult(
        target = "internet", method = "http", durationSeconds = 5, measuredAt = 1_700_000_000_000L,
        ssid = "Corp", bssidStart = "00:11:22:33:44:55", bssidEnd = "00:11:22:33:44:55",
        rssi = -50, linkMbps = 866, downMbps = 95.3, upMbps = null, latencyMs = 14.8,
        error = "upload: HTTP 403 from speed.cloudflare.com"
    )
    private val corporate = internet.copy(target = "corporate", method = "iperf3-tcp", upMbps = 148.0, error = null)

    private val floorPlanId = "11111111-1111-4111-8111-111111111111"
    private val imageId = "22222222-2222-4222-8222-222222222222"

    private fun run(runId: String, point: ScanPoint, results: PointThroughput?, status: String = RunStatus.COMPLETED) =
        ActiveRun(runId, point.pointId!!, point.scanId, 1_700_000_000_000L, 1_700_000_001_000L, status, results = results)

    /** Builds the ESX report and the export of one floor holding [points] and [runs]. */
    private fun export(points: List<ScanPoint>, runs: List<ActiveRun>): Pair<EkahauReport, com.google.gson.JsonObject> {
        val map = MapInfo("g.png", 1000, 800)
        val report = EkahauReportBuilder().build(
            "Site", listOf(EkahauFloorInput("Ground", map, 0.1, points, emptyList(), emptyList(), floorPlanId, imageId))
        )
        val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(
            FloorExportSnapshot("f", "Ground", File("g.png"), floorPlanId, imageId, false, null,
                ProjectSnapshot(map, 0.1, points, emptyList(), emptyList(), activeRuns = runs))
        ))
        return report to PerformanceExport.build(report, "Site.esx", 0L, snapshot)
    }

    @Test
    fun rowsPointAtTheSurveyAndCoordinatesOfTheirPoint() {
        val measured = ScanPoint(1L, 812.5f, 433.25f, listOf(network), pointId = "point-1", scanId = "scan-1")
        val plain = ScanPoint(2L, 10f, 20f, listOf(network), pointId = "point-2", scanId = "scan-2")
        val (report, json) = export(listOf(plain, measured), listOf(run("run-1", measured, PointThroughput(1L, listOf(corporate, internet)))))
        val rows = json.getAsJsonArray("measurements").map { it.asJsonObject }

        assertEquals("wiprober-performance", json["format"].asString)
        assertEquals(2, json["version"].asInt)
        assertEquals("Site.esx", json["esxFile"].asString)
        assertEquals(listOf("corporate", "internet"), rows.map { it["target"].asString })
        rows.forEach { row ->
            val survey = report.surveys.getValue(row["surveyId"].asString).surveys.single()
            val location = survey.routePoints[0][0].location
            assertEquals(survey.floorPlanId, row["floorPlanId"].asString)
            assertEquals(location.x, row["x"].asDouble, 0.0)
            assertEquals(location.y, row["y"].asDouble, 0.0)
            assertEquals("Ground", row["floorName"].asString)
            assertEquals("point-1", row["pointId"].asString)
            assertEquals("scan-1", row["scanId"].asString)
            assertEquals("run-1", row["runId"].asString)
            assertEquals(RunStatus.COMPLETED, row["runStatus"].asString)
            assertEquals("2023-11-14T22:13:20.000Z", row["measuredAt"].asString)
        }
    }

    @Test
    fun unmeasuredValuesAreWrittenAsExplicitNulls() {
        val point = ScanPoint(1L, 1f, 2f, listOf(network), pointId = "p", scanId = "s")
        val (_, json) = export(listOf(point), listOf(run("r", point, PointThroughput(1L, listOf(internet)))))
        val text = String(PerformanceExport.toBytes(json), StandardCharsets.UTF_8)
        val row = JsonParser.parseString(text).asJsonObject.getAsJsonArray("measurements")[0].asJsonObject

        assertTrue(row.has("upMbps") && row["upMbps"].isJsonNull)
        assertEquals(95.3, row["downMbps"].asDouble, 0.0)
        assertEquals("upload: HTTP 403 from speed.cloudflare.com", row["error"].asString)
        listOf("rxLinkMbps", "channel", "channelWidthMhz", "bssChannelUtilizationPct", "jitterMs", "deviceModel",
            "downloadStatus", "upBytes", "latencyFailures")
            .forEach { field -> assertTrue(field, row.has(field) && row[field].isJsonNull) }
    }

    @Test
    fun theSamePointMeasuredTwiceKeepsItsIdAndCoordinatesWithDistinctRuns() {
        val point = ScanPoint(1L, 300f, 200f, listOf(network), pointId = "dock-3", scanId = "scan-a")
        val second = point.copy(timestamp = 2L, scanId = "scan-b")
        val cancelled = run("run-c", second, null, RunStatus.CANCELLED).copy(scanId = "scan-b", statusReason = ErrorCodes.CANCELLED)
        val (_, json) = export(listOf(point, second), listOf(
            run("run-a", point, PointThroughput(1L, listOf(corporate))),
            run("run-b", second, PointThroughput(2L, listOf(corporate.copy(downMbps = 50.0)))).copy(scanId = "scan-b"),
            cancelled
        ))

        val rows = json.getAsJsonArray("measurements").map { it.asJsonObject }
        assertEquals(listOf("run-a", "run-b"), rows.map { it["runId"].asString })
        assertEquals(setOf("dock-3"), rows.map { it["pointId"].asString }.toSet())
        assertEquals(setOf(300.0), rows.map { it["x"].asDouble }.toSet())
        assertTrue(rows[0]["surveyId"].asString != rows[1]["surveyId"].asString) // one ESX survey per scan
        val runs = json.getAsJsonArray("activeRuns").map { it.asJsonObject }
        assertEquals(listOf("run-a", "run-b", "run-c"), runs.map { it["runId"].asString })
        assertEquals(RunStatus.CANCELLED, runs[2]["status"].asString)
        assertEquals(ErrorCodes.CANCELLED, runs[2]["statusReason"].asString)
    }

    @Test
    fun withoutReportTheFileIsStillWellFormed() {
        val json = PerformanceExport.build(null, "Site.esx", 0L)
        assertEquals(0, json.getAsJsonArray("measurements").size())
    }

    @Test
    fun bundleHoldsTheUntouchedEsxAndThePerformanceFile() {
        val esx = File(temp.root, "export.esx").apply { writeBytes(ByteArray(10_000) { (it % 251).toByte() }) }
        val output = ByteArrayOutputStream()

        PerformanceExport.writeBundle(output, "Site.esx", esx, "{}".toByteArray())

        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("Site.esx", PerformanceExport.FILE_NAME), entries.keys.toList())
        assertArrayEquals(esx.readBytes(), entries.getValue("Site.esx"))
    }

    @Test
    fun networkTestsAndScriptStepsGetTheirOwnArraysWithPosition() {
        val point = ScanPoint(1L, 7f, 9f, listOf(network), pointId = "p", scanId = "s")
        val results = PointThroughput(
            1L, emptyList(),
            networkTests = listOf(NetworkTestResult("gw", "ping", "10.0.0.1", "icmp", 1L, ok = true, rttAvgMs = 2.5)),
            scripts = listOf(ScriptRun("flow", 1L, steps = listOf(
                ScriptStepResult("login", "POST", "app.example.com", "/login", status = 200, ok = true, totalMs = 80.0),
                ScriptStepResult("item", "GET", "app.example.com", "/items", error = "status 500", errorCode = ErrorCodes.HTTP_STATUS)
            )))
        )
        val (_, json) = export(listOf(point), listOf(run("r", point, results)))

        assertEquals(0, json.getAsJsonArray("measurements").size())
        val test = json.getAsJsonArray("networkTests").single().asJsonObject
        assertEquals("gw", test["name"].asString)
        assertEquals("icmp-echo", test["rttKind"].asString)
        assertEquals(2.5, test["rttAvgMs"].asDouble, 0.0)
        assertEquals(7.0, test["x"].asDouble, 0.0)
        assertEquals("r", test["runId"].asString)
        val steps = json.getAsJsonArray("scriptSteps").map { it.asJsonObject }
        assertEquals(listOf(1, 2), steps.map { it["stepIndex"].asInt })
        assertEquals(ErrorCodes.HTTP_STATUS, steps[1]["errorCode"].asString)
        assertTrue(steps[1]["status"].isJsonNull)
    }

    @Test
    fun roamingEventsArePlacedOnTheMarkedRoute() {
        val session = ContinuousScanSession(
            id = "6f1c7a52-0000-4000-8000-000000000001", startTime = 1_700_000_000_000L, endTime = 1_700_000_010_000L,
            waypoints = listOf(RoutePointWrapper(0, 0f, 50f), RoutePointWrapper(10_000, 100f, 50f)),
            scanResults = listOf(ScanResultWrapper(5_000, 500, listOf(network))),
            roaming = RoamingLog(
                target = "gateway:443", intervalMillis = 200, probes = 50, lost = 2, avgRttMs = 3.1,
                events = listOf(RoamingEvent(RoamingEvent.ROAM, 2_500, "aa:01", "aa:02", -75, -60, 120, 1))
            )
        )
        val report = EkahauReportBuilder().build(
            "Site", listOf(EkahauFloorInput("G", MapInfo("g.png", 100, 80), 0.1, emptyList(), listOf(session), emptyList()))
        )

        val json = PerformanceExport.build(report, "Site.esx", 0L)

        val summary = json.getAsJsonArray("roamingSessions").single().asJsonObject
        assertEquals(session.id, summary["surveyId"].asString)
        assertTrue(report.surveys.containsKey(session.id)) // same survey as in the ESX
        assertEquals(4.0, summary["lossPct"].asDouble, 0.0)
        assertEquals(1, summary["roams"].asInt)
        val event = json.getAsJsonArray("roamingEvents").single().asJsonObject
        assertEquals(25.0, event["x"].asDouble, 0.0) // 2.5 s of a 10 s straight route
        assertEquals(50.0, event["y"].asDouble, 0.0)
        assertEquals("2023-11-14T22:13:22.500Z", event["at"].asString)
        assertEquals(120, event["outageMs"].asInt)
        assertEquals("aa:02", event["toBssid"].asString)
    }
}
