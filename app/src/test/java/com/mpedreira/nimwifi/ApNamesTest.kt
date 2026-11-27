package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The user's BSSID → AP table: normalized at the border, display only, never inferred. */
class ApNamesTest {
    private val t0 = 1_700_000_000_000L
    private fun config(section: String) = WiproberConfigParser.parse("""{"format":"wiprober-config","version":1,"apNames":$section}""", "c.json")

    @Test
    fun bssidsAreNormalizedAndMalformedOnesRejected() {
        assertEquals("aa:bb:cc:dd:ee:0f", Bssids.normalize("AA-BB-CC-DD-EE-0F"))
        assertEquals("aa:bb:cc:dd:ee:0f", Bssids.normalize(" aa:BB:cc:dd:ee:0F "))
        assertEquals("aa:bb:cc:dd:ee:0f", Bssids.normalize("aabbccddee0f"))
        listOf("aa:bb:cc:dd:ee", "aa:bb:cc:dd:ee:zz", "aa:bb-cc:dd:ee:ff", "", null).forEach { assertNull(it.toString(), Bssids.normalize(it)) }
        try { config("""[{"bssid":"aa:bb:cc:dd:ee","name":"AP-1"}]"""); fail() } catch (e: ConfigException) { assertTrue(e.message!!.contains("not a BSSID")) }
        try { config("""[{"bssid":"aa:bb:cc:dd:ee:01","name":""}]"""); fail() } catch (_: ConfigException) {}
    }

    @Test
    fun aDuplicateBssidIsAnErrorButSeveralBssidsMayNameOneApExplicitly() {
        try {
            config("""[{"bssid":"aa:bb:cc:dd:ee:01","name":"AP-1"},{"bssid":"AA-BB-CC-DD-EE-01","name":"AP-2"}]"""); fail()
        } catch (e: ConfigException) { assertTrue(e.message!!.contains("already in row 1")) }
        // Two radios of one AP: the user's explicit decision. Each BSSID stays its own entry.
        val table = config("""[{"bssid":"aa:bb:cc:dd:ee:01","name":"AP-P3-04","location":"Pasillo 3","floor":"Baja"},{"bssid":"aa:bb:cc:dd:ee:11","name":"AP-P3-04"}]""").apNames!!
        val directory = ApDirectory(table)
        assertEquals(2, directory.size)
        assertEquals("AP-P3-04", directory.name("aa:bb:cc:dd:ee:11"))
        assertEquals("Pasillo 3", directory.of("AA:BB:CC:DD:EE:01")!!.location)
    }

    @Test
    fun nothingIsInferredForUnmappedBssids() {
        val directory = ApDirectory(listOf(ApName("aa:bb:cc:dd:ee:01", "AP-P3-04")))
        // Same prefix/OUI, adjacent address: not mapped, shown as it is.
        assertNull(directory.name("aa:bb:cc:dd:ee:02"))
        assertEquals("aa:bb:cc:dd:ee:02", directory.label("aa:bb:cc:dd:ee:02"))
        assertEquals("AP-P3-04 (aa:bb:cc:dd:ee:01)", directory.label("aa:bb:cc:dd:ee:01"))
        assertEquals("entre AP-P3-04 y aa:bb:cc:dd:ee:02", directory.between("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"))
    }

    @Test
    fun theCsvOfAConfigurationZipIsOneTableWithTheJson() {
        val csv = "﻿bssid;name;location;floor\nAA-BB-CC-DD-EE-02;\"AP-P3-05\";\"Muelle 2; norte\";Baja\n"
        val zip = WiproberConfigParser.parseZip(mapOf(
            "wiprober-config.json" to """{"format":"wiprober-config","version":1,"apNames":[{"bssid":"aa:bb:cc:dd:ee:01","name":"AP-P3-04"}]}""",
            "ap-names.csv" to csv))
        assertEquals(listOf("AP-P3-04", "AP-P3-05"), zip.apNames!!.map { it.name })
        assertEquals("Muelle 2; norte", zip.apNames!![1].location)
        try {
            WiproberConfigParser.parseZip(mapOf("wiprober-config.json" to """{"format":"wiprober-config","version":1,"apNames":[{"bssid":"aa:bb:cc:dd:ee:02","name":"X"}]}""",
                "ap-names.csv" to csv)); fail()
        } catch (_: ConfigException) {}
        try { ApDirectory.parseCsv("mac,label\naa:bb:cc:dd:ee:01,AP\n"); fail() } catch (_: ConfigException) {}
        // Import preview, saved profile and exported configuration keep the table.
        assertEquals(2, ImportPreview.of(zip, emptySet()).apNames)
        val exported = ConfigExport.toJson(WiproberConfigStore.Imported(apNames = zip.apNames), ThroughputSettings(), SurveyGuideSettings(), emptySet())
        assertEquals(zip.apNames, WiproberConfigParser.parse(exported, "c.json").apNames)
    }

    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", emptyList(), "")
    private val ap4 = "aa:bb:cc:dd:ee:01"
    private val ap5 = "aa:bb:cc:dd:ee:02"
    private val unmapped = "cc:00:00:00:00:09"

    private fun snapshot(): ProjectExportSnapshot {
        val point = ScanPoint(1L, 10f, 10f, listOf(net("Ops", ap4, -75), net("Ops", unmapped, -80)), pointId = "a", scanId = "s1")
        val test = ThroughputTestResult("corporate", ThroughputTestResult.METHOD_IPERF3_TCP, 5, t0, targetId = TargetIds.IPERF, category = TargetCategory.PRIVATE_IPERF,
            ssid = "Ops", bssidStart = ap4, bssidEnd = unmapped, associationChange = AssociationChange.BSSID_CHANGED, downMbps = 80.0)
        val route = ContinuousScanSession("r1", t0 + 100_000, t0 + 110_000, listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(10_000, 90f, 0f)), emptyList(),
            roaming = RoamingLog("gw", 500, 10, 3, 3.0, listOf(RoamingEvent(RoamingEvent.REACHABILITY_LOSS, 4_000, ap4, ap5, fromSsid = "Ops", toSsid = "Ops",
                outageLowerBoundMs = 800, outageUpperBoundMs = 1_200)), schema = 2, method = RoamingTracker.METHOD))
        val survey = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1, listOf(point), listOf(route), emptyList(),
            activeRuns = listOf(ActiveRun("run-1", "a", "s1", t0, t0 + 10_000, RunStatus.COMPLETED, results = PointThroughput(t0, listOf(test)))))
        return ProjectExportSnapshot("p", "Almacén", null, listOf(FloorExportSnapshot("g", "Baja", File("g.png"),
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, survey)))
    }

    private val directory = ApDirectory(listOf(ApName(ap4, "AP-P3-04"), ApName(ap5, "AP-P3-05")))

    @Test
    fun theReportShowsApNamesNextToBssids() {
        val model = SurveyReport.build(snapshot(), t0, "Ops", weakDbm = -67, apNames = directory,
            requirements = Requirements(roaming = RoamingRequirement(maxOutageMs = 500)))
        val html = SurveyReport.html(model)
        // Name, BSSID, band and channel (the scan saw both radios on 5 GHz channel 36).
        assertTrue("worst points", "AP-P3-04 ($ap4 · 5 GHz c36)" in html)
        assertTrue("unmapped stays a BSSID", ">$unmapped (5 GHz c36) -80 dBm<" in html)
        assertTrue("roaming", "Ops · AP-P3-05 ($ap5)" in html)
        assertTrue("verdict", "entre AP-P3-04 y AP-P3-05" in html)
        // Without a table the report is as before.
        val plain = SurveyReport.html(SurveyReport.build(snapshot(), t0, "Ops", weakDbm = -67))
        assertFalse("AP-P3" in plain)
    }

    @Test
    fun theJsonAddsApNamesOnlyForMappedBssids() {
        val before = PerformanceExport.build(null, "Site.esx", t0, snapshot())
        assertEquals(before, PerformanceExport.build(null, "Site.esx", t0, snapshot(), apNames = ApDirectory.EMPTY))
        val json = PerformanceExport.build(null, "Site.esx", t0, snapshot(), apNames = directory)
        val row = json.getAsJsonArray("measurements")[0].asJsonObject
        assertEquals("AP-P3-04", row["apNameStart"].asString)
        assertFalse("unmapped end: no name", row.has("apNameEnd"))
        assertEquals(ap4, row["bssidStart"].asString)
        val table = json.getAsJsonArray("apNames").map { it.asJsonObject["name"].asString }
        assertEquals(listOf("AP-P3-04", "AP-P3-05"), table)
        // Additive: every original field keeps its value.
        before.getAsJsonArray("measurements")[0].asJsonObject.entrySet().forEach { (k, v) -> assertEquals(k, v, row[k]) }
    }
}
