package com.mpedreira.nimwifi

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC4 · 5: the decision at the top of each floor/SSID section, the points with their reasons,
 * Internet servers identified and kept apart, one plan image per floor, and the report streamed
 * into a multi-floor ZIP that opens offline with SSIDs kept apart.
 */
class DecisionReportTest {
    private val t0 = 1_700_000_000_000L
    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", emptyList(), "")

    private fun internet(ssid: String, at: Long, down: Double?, serverId: String, label: String, url: String, colo: String? = null, error: String? = null) =
        ThroughputTestResult("internet", "http", 5, at, targetId = TargetIds.INTERNET, category = TargetCategory.INTERNET, ssid = ssid,
            downMbps = down, downloadErrorCode = error, downloadEndpoint = url, downloadEndpointId = url,
            internetServerId = serverId, internetServerLabel = label, serverLocation = colo,
            downloadHttpStatus = if (error == ErrorCodes.RATE_LIMITED) 429 else 200)

    private fun app(ssid: String, at: Long, ok: Boolean) = NetworkTestResult("app", "http", "app.example.com", "GET", at, ok = ok, alias = "acceso a la aplicación",
        ssid = ssid, totalMs = if (ok) 120.0 else null, status = if (ok) 200 else 503, errorCode = if (ok) null else ErrorCodes.HTTP_STATUS,
        proxyConfig = ProxyKind.NONE, route = HttpRoute.DIRECT)

    private val cloudflare = InternetServers.cloudflare
    private val ams = InternetServers.byId("librespeed-ams-clouvider")!!

    private fun floor(id: String, name: String, planId: String) = run {
        val points = listOf(
            // P17: good signal and a second AP, but the application fails.
            ScanPoint(1L, 10f, 10f, listOf(net("Ops", "aa:00:00:00:00:01", -55), net("Ops", "aa:00:00:00:00:02", -65), net("Guest", "bb:00:00:00:00:01", -50)), pointId = "$id-17", scanId = "$id-s17"),
            // P18: a valid scan that did not hear Ops.
            ScanPoint(2L, 50f, 10f, listOf(net("Guest", "bb:00:00:00:00:01", -52)), pointId = "$id-18", scanId = "$id-s18"),
            // P19: good signal, Internet limited by the server (429).
            ScanPoint(3L, 80f, 10f, listOf(net("Ops", "aa:00:00:00:00:01", -58), net("Ops", "aa:00:00:00:00:02", -66)), pointId = "$id-19", scanId = "$id-s19")
        )
        val runs = listOf(
            ActiveRun("$id-r17", "$id-17", "$id-s17", t0, t0 + 10_000, RunStatus.COMPLETED, results = PointThroughput(t0,
                listOf(internet("Ops", t0, 90.0, cloudflare.id, cloudflare.label, cloudflare.downloadUrl, colo = "MAD")), networkTests = listOf(app("Ops", t0, ok = false)))),
            ActiveRun("$id-r19", "$id-19", "$id-s19", t0 + 20_000, t0 + 30_000, RunStatus.COMPLETED, results = PointThroughput(t0 + 20_000,
                listOf(internet("Ops", t0 + 20_000, null, ams.id, ams.label, ams.downloadUrl, error = ErrorCodes.RATE_LIMITED))))
        )
        val survey = ProjectSnapshot(MapInfo("$id.png", 100, 100), 0.1, points, emptyList(), emptyList(), activeRuns = runs,
            plannedPoints = listOf(PlannedPoint("$id-22", "P22", 90f, 90f)))
        FloorExportSnapshot(id, name, File("$id.png"), planId, "img-$id", false, null, survey)
    }

    private val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(
        floor("g", "Baja", "11111111-1111-4111-8111-111111111111"),
        floor("f1", "Primera", "33333333-3333-4333-8333-333333333333")))

    private fun labelled(model: SurveyReport.Model, floor: Int, ssid: String) = model.floors[floor].ssids.single { it.ssid == ssid }

    private val plans = snapshot.floors.associate { it.esxFloorPlanId to SurveyReport.PlanImage("data:image/jpeg;base64,PLAN-${it.id}", 100, 100) }

    @Test
    fun eachSectionOpensWithItsDecisionAndThePointsWithTheirReasons() {
        val model = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67, planImages = plans)
        val ops = labelled(model, 0, "Ops")
        // Signal decides (P18 does not hear Ops in a valid scan); P22 is not measured.
        assertEquals(VerdictState.FAIL, ops.verdict!!.state)
        val reasons = ops.reasons.associate { it.pointId to it.text }
        // Labels: P1.. in the order points were first measured; the planned one keeps its own.
        val p17 = reasons.getValue("g-17")
        assertTrue(p17, p17.contains("buena señal (-55 dBm)") && p17.contains("falla «acceso a la aplicación» (http_status, HTTP 503)"))
        assertTrue(reasons.getValue("g-18"), "SSID ausente en un escaneo válido" in reasons.getValue("g-18"))
        assertTrue(reasons.getValue("g-19"), "«internet» limitado por el servidor (HTTP 429)" in reasons.getValue("g-19"))
        assertEquals("P22 · sin medición local (planificado)", reasons.getValue("g-22"))
        // Failing points first.
        assertEquals("g-18", ops.reasons.first().pointId)

        val html = SurveyReport.html(model)
        val section = html.substringAfter("SSID «Ops» · Baja").substringBefore("SSID «Guest» · Baja")
        assertTrue(section.trimStart().startsWith("</h3><div class=\"box\"><p style=\"font-size:20px;margin:4px 0\"><b class=\"bad\">No cumple</b>"))
        assertTrue("P22 · sin medición local (planificado)" in section)
        assertTrue("Sin criterios declarados de rendimiento, servicios, roaming" in section)
    }

    @Test
    fun pointsOnTheReportMapAreColouredByVerdictAndKeepTheirSignal() {
        val model = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67, planImages = plans)
        val marks = labelled(model, 0, "Ops").marks.associateBy { it.pointId }
        assertEquals(VerdictState.PASS, marks.getValue("g-17").verdict) // service failures are listed, not judged without criteria
        assertEquals(VerdictState.FAIL, marks.getValue("g-18").verdict)
        assertEquals(VerdictState.MISSING, marks.getValue("g-22").verdict)
        val html = SurveyReport.html(model)
        assertTrue(html.contains("fill=\"#b00020\"") && html.contains("Puntos por veredicto"))
        assertTrue(">-55</text>" in html) // the signal is still written on the point
    }

    @Test
    fun theAppMapColoursPointsByVerdictOnlyWhenAskedAndKeepsCoverageBySignal() {
        val survey = snapshot.floors.first().survey
        val state = SurveyScreenState(mapInfo = survey.mapInfo, metersPerUnit = survey.metersPerUnit, scanPoints = survey.scanPoints,
            activeRuns = survey.activeRuns, plannedPoints = survey.plannedPoints)
        val settings = SurveyGuideSettings(ssidFilter = "Ops")
        val d = MapDerivations()
        assertTrue(d.markers(state, settings).all { it.verdict == null })
        val byVerdict = d.markers(state, settings, byVerdict = true)
        assertEquals(setOf(VerdictState.PASS, VerdictState.FAIL, VerdictState.MISSING), byVerdict.mapNotNull { it.verdict }.toSet())
        assertEquals(PointState.SSID_NOT_DETECTED, byVerdict.first { it.verdict == VerdictState.FAIL }.state) // the signal state is kept
        val coverage = d.coverage(state, settings)
        d.markers(state, settings, byVerdict = true)
        assertEquals(1, d.verdictComputations)
        assertTrue(coverage === d.coverage(state, settings))
        // A declared requirement changes the verdict: P17's application is now judged.
        val strict = d.markers(state, settings, byVerdict = true,
            requirements = Requirements(networkTests = listOf(ServiceRequirement("acceso a la aplicación", maxMedianMs = 500.0))))
        assertEquals(VerdictState.MISSING, strict.first { it.x == 10f }.verdict)
    }

    @Test
    fun internetServersAreIdentifiedAndTheirSeriesKeptApart() {
        val model = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67)
        val downloads = labelled(model, 0, "Ops").metrics.filter { it.category == TargetCategory.INTERNET && it.measure == "download" }
        assertEquals(2, downloads.size) // Cloudflare and LibreSpeed Ámsterdam are two series
        val cf = downloads.single { it.server == cloudflare.label }
        assertEquals(listOf("MAD"), cf.locations)
        assertEquals(90.0, cf.median!!, 0.0)
        val limited = downloads.single { it.server == ams.label }
        // The 429 is not a value, not a network failure: a separate outcome.
        assertEquals(null, limited.median)
        assertEquals(mapOf(SurveyReport.Outcome.RATE_LIMITED to 1), limited.outcomes)
        val html = SurveyReport.html(model)
        assertTrue("LibreSpeed · Ámsterdam · Clouvider" in html && "centro de datos observado: MAD" in html && "limitadas por el servidor (429) 1" in html)
    }

    @Test
    fun eachFloorPlanIsEmbeddedOnceAndTheStreamedReportIsTheSame() {
        val model = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67, planImages = plans)
        val html = SurveyReport.html(model)
        snapshot.floors.forEach { f -> assertEquals(1, Regex(Regex.escape("PLAN-${f.id}")).findAll(html).count()) }
        // Two SSID maps per floor, each referring to its floor's plan.
        assertEquals(2, Regex("<use href=\"#plan-0\"/>").findAll(html).count())
        assertEquals(2, Regex("<use href=\"#plan-1\"/>").findAll(html).count())
        val streamed = java.io.StringWriter().also { SurveyReport.writeTo(model, it) }.toString()
        assertEquals(html, streamed)
    }

    @Test
    fun aMultiFloorZipOpensOfflineAndKeepsSsidsApart() {
        val dir = Files.createTempDirectory("bundle").toFile()
        try {
            val model = SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67, planImages = plans)
            val esx = File(dir, "Site.esx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val report = File(dir, SurveyReport.FILE_NAME).also { f -> f.writer(Charsets.UTF_8).use { SurveyReport.writeTo(model, it) } }
            val zipFile = File(dir, "bundle.zip")
            zipFile.outputStream().use { PerformanceExport.writeBundle(it, "Site.esx", esx, "{}".toByteArray(), report) }
            StagedExport.verifyZip(zipFile, setOf("Site.esx", PerformanceExport.FILE_NAME, SurveyReport.FILE_NAME))
            val html = ZipFile(zipFile).use { zip -> zip.getInputStream(zip.getEntry(SurveyReport.FILE_NAME)).readBytes().toString(Charsets.UTF_8) }
            assertEquals(SurveyReport.html(model), html)
            // Offline: no script and nothing loaded from the network (only embedded data and in-page references).
            assertFalse("<script" in html)
            assertFalse(Regex("(src|href)=\"(https?:)?//").containsMatchIn(html))
            // Both floors, each with Ops and Guest judged on their own.
            listOf("Baja", "Primera").forEach { floor ->
                assertTrue("SSID «Ops» · $floor" in html && "SSID «Guest» · $floor" in html)
            }
            val guest = labelled(model, 1, "Guest")
            assertTrue(guest.reasons.none { "acceso a la aplicación" in it.text }) // Ops' tests never leak into Guest
        } finally {
            dir.deleteRecursively()
        }
    }
}
