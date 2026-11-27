package com.mpedreira.nimwifi

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Mejora 20: the export bundle against the contract of its consumer (wifianalisys,
 * `nesu/performance.py`): one root `.esx`, `wiprober-performance` version 1 or 2, every
 * measurement with a recognised destination, and every row joinable to the ESX by `surveyId`
 * (survey id) or by `floorPlanId` + exact pixel coordinates. The HTML report names the floors.
 *
 * Set `NIMWIFI_CONSUMER_FIXTURE_OUT` to also write the bundle there, to run the real consumer on it.
 */
class ConsumerContractTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ops = WifiNetworkInfo("Ops", "00:11:22:33:44:01", -58, 5_180, "WPA2", listOf("AX"), "")
    private val t0 = 1_700_000_000_000L
    private val groundPlan = "11111111-1111-4111-8111-111111111111"
    private val firstPlan = "33333333-3333-4333-8333-333333333333"

    private fun iperf(at: Long) = ThroughputTestResult("corporate", ThroughputTestResult.METHOD_IPERF3_TCP, 5, at, targetId = TargetIds.IPERF,
        category = TargetCategory.PRIVATE_IPERF, ssid = "Ops", bssidStart = ops.bssid, bssidEnd = ops.bssid, downMbps = 200.0, upMbps = 150.0, latencyMs = 3.0)
    private fun internet(at: Long) = ThroughputTestResult("internet", "http", 5, at, targetId = TargetIds.INTERNET,
        category = TargetCategory.INTERNET, ssid = "Ops", downMbps = 90.0, upMbps = 20.0, latencyMs = 15.0)
    private fun dns(at: Long) = NetworkTestResult("dns", NetworkTest.DNS, "erp.example.com", "udp", at, ok = true, alias = "DNS", ssid = "Ops", dnsMs = 4.0)

    private fun bundle(): Pair<File, Map<String, Any>> {
        val p1 = ScanPoint(t0, 100f, 200.5f, listOf(ops), pointId = "p1", scanId = "s1")
        val p1again = ScanPoint(t0 + 60_000, 100f, 200.5f, listOf(ops.copy(level = -70)), pointId = "p1", scanId = "s2")
        val p2 = ScanPoint(t0 + 1_000, 300f, 400f, listOf(ops), pointId = "p2", scanId = "s3")
        val route = ContinuousScanSession("55555555-5555-4555-8555-555555555555", t0 + 100_000, t0 + 110_000,
            listOf(RoutePointWrapper(0, 10f, 10f), RoutePointWrapper(10_000, 90f, 10f)),
            listOf(ScanResultWrapper(5_000, 500, listOf(ops))),
            roaming = RoamingTracker("gw.example.com:443", 500).apply {
                add(ProbeAttempt(100_000, 100_005, ProbeResult.CONNECTED, "Ops", "00:11:22:33:44:01", -60, true))
                add(ProbeAttempt(100_500, 100_505, ProbeResult.CONNECTED, "Ops", "00:11:22:33:44:02", -55, true))
            }.finish())
        val runs = listOf(
            ActiveRun("r1", "p1", "s1", t0, t0 + 10, RunStatus.COMPLETED, results = PointThroughput(t0, listOf(iperf(t0), internet(t0)), listOf(dns(t0)))),
            // Active tests only (no scan of its own): joined by floor plan + pixel position.
            ActiveRun("r2", "p2", null, t0 + 2_000, t0 + 2_010, RunStatus.COMPLETED, results = PointThroughput(t0 + 2_000, listOf(iperf(t0 + 2_000)), emptyList()))
        )
        val ground = ProjectSnapshot(MapInfo("g.png", 1000, 800), 0.1, listOf(p1, p1again, p2), listOf(route), emptyList(), activeRuns = runs)
        val first = ProjectSnapshot(MapInfo("f.png", 500, 400), 0.1, listOf(ScanPoint(t0 + 5_000, 50f, 60f, listOf(ops), pointId = "q1", scanId = "s9")), emptyList(), emptyList())
        var nextId = 1
        val report = EkahauReportBuilder(clock = MillisClock { t0 }, idSource = IdSource { "00000000-0000-4000-8000-${nextId++.toString().padStart(12, '0')}" })
            .build("Almacén", listOf(
                EkahauFloorInput("Baja", ground.mapInfo, 0.1, ground.scanPoints, ground.continuousScanSessions, emptyList(), groundPlan, "22222222-2222-4222-8222-222222222222"),
                EkahauFloorInput("Primera", first.mapInfo, 0.1, first.scanPoints, emptyList(), emptyList(), firstPlan, "44444444-4444-4444-8444-444444444444")))
        val maps = listOf("22222222-2222-4222-8222-222222222222", "44444444-4444-4444-8444-444444444444")
            .associate { "image-$it" to File(temp.root, "$it.png").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        val esx = File(temp.root, "Almacen.esx")
        GeneratedEsxArchiveWriter(GsonBuilder().create()).write(esx, report,
            EsxWifiAdapterInformation(name = "Fixture", driverVersion = "1", driverDate = "01-01-2026", revision = "fixture"),
            mapOf("version" to { ByteArrayInputStream("11.8".toByteArray()) }), maps)
        val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(
            FloorExportSnapshot("g", "Baja", File("g.png"), groundPlan, "22222222-2222-4222-8222-222222222222", false, null, ground),
            FloorExportSnapshot("f", "Primera", File("f.png"), firstPlan, "44444444-4444-4444-8444-444444444444", false, null, first)))
        val performance = PerformanceExport.build(report, "Almacen.esx", t0, snapshot)
        val html = SurveyReport.html(SurveyReport.build(snapshot, t0, "Ops"))
        val out = File(temp.root, "bundle.zip")
        out.outputStream().use { PerformanceExport.writeBundle(it, "Almacen.esx", esx, PerformanceExport.toBytes(performance), html.toByteArray()) }
        System.getenv("NIMWIFI_CONSUMER_FIXTURE_OUT")?.takeIf { it.isNotBlank() }?.let { out.copyTo(File(it), overwrite = true) }
        return out to mapOf("report" to report)
    }

    /** The same checks the consumer makes, in the same order. */
    @Test
    fun theBundleMeetsTheConsumerContractAndJoinsByIds() {
        val (file, _) = bundle()
        ZipFile(file).use { zip ->
            val rootEsx = zip.entries().toList().map { it.name }.filter { it.endsWith(".esx", true) && '/' !in it }
            assertEquals(listOf("Almacen.esx"), rootEsx)
            val doc = JsonParser.parseString(zip.getInputStream(zip.getEntry(PerformanceExport.FILE_NAME)).readBytes().decodeToString()).asJsonObject
            assertEquals("wiprober-performance", doc["format"].asString)
            assertTrue(doc["version"].asInt in setOf(1, 2))

            // ESX side: survey ids (one per point / route) and floor plans with their point positions.
            val esxBytes = zip.getInputStream(zip.getEntry("Almacen.esx")).readBytes()
            val esxFile = File(temp.root, "inner.esx").apply { writeBytes(esxBytes) }
            val (surveyIds, plans) = ZipFile(esxFile).use { esx ->
                val surveys = esx.entries().toList().filter { it.name.startsWith("survey-") && it.name.endsWith(".json") }.map { entry ->
                    JsonParser.parseString(esx.getInputStream(entry).readBytes().decodeToString()).asJsonObject.getAsJsonArray("surveys")[0].asJsonObject
                }
                surveys.map { it["id"].asString }.toSet() to surveys.map { it["floorPlanId"].asString }.toSet()
            }
            assertEquals(setOf(groundPlan, firstPlan), plans)

            fun joins(row: JsonObject) = row["surveyId"]?.takeUnless { it.isJsonNull }?.asString in surveyIds ||
                (row["floorPlanId"]?.asString in plans && row["x"]?.isJsonNull == false && row["y"]?.isJsonNull == false)
            val categories = mapOf(TargetCategory.PRIVATE_IPERF to "corporate", TargetCategory.INTERNET to "internet")
            val measurements = doc.getAsJsonArray("measurements").map { it.asJsonObject }
            assertEquals(3, measurements.size)
            measurements.forEach { row ->
                assertTrue("destination recognised: $row", categories.containsKey(row["category"].asString) ||
                    row["targetId"].asString in setOf(TargetIds.IPERF, TargetIds.INTERNET))
                assertTrue("joinable: $row", joins(row))
            }
            val activeOnly = measurements.single { it["runId"].asString == "r2" }
            assertEquals(groundPlan, activeOnly["floorPlanId"].asString)
            assertEquals(300.0, activeOnly["x"].asDouble, 0.51)
            listOf("networkTests", "activeRuns", "roamingSessions").forEach { key ->
                val rows = doc.getAsJsonArray(key).map { it.asJsonObject }
                assertTrue("$key present", rows.isNotEmpty())
                rows.forEach { assertTrue("$key joinable: $it", joins(it) || it["floorPlanId"]?.asString in plans) }
            }
            val roamingSurvey = doc.getAsJsonArray("roamingSessions")[0].asJsonObject["surveyId"].asString
            assertTrue("the roaming session is the route's ESX survey", roamingSurvey in surveyIds)
            doc.getAsJsonArray("roamingEvents").forEach { assertEquals(roamingSurvey, it.asJsonObject["surveyId"].asString) }

            val html = zip.getInputStream(zip.getEntry(SurveyReport.FILE_NAME)).readBytes().decodeToString()
            assertTrue("Planta: Baja" in html && "Planta: Primera" in html)
        }
    }

    /** Version 1 rows (target label, no category) are still readable by the same rules. */
    @Test
    fun versionOneRowsKeepTheirLegacyDestination() {
        val v1 = JsonParser.parseString("""{"format":"wiprober-performance","version":1,"measurements":[
            {"target":"corporate","method":"iperf3-tcp","surveyId":"x"},{"target":"internet","method":"http","surveyId":"y"}]}""").asJsonObject
        val legacy = v1.getAsJsonArray("measurements").map { row ->
            val o = row.asJsonObject
            if (o["target"].asString == "internet") "internet" else if (o["method"].asString == "iperf3-tcp") "corporate" else o["target"].asString
        }
        assertEquals(listOf("corporate", "internet"), legacy)
    }
}
