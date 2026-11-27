package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.4, 5 and 6 GHz are told apart inside each SSID. */
class BandsReportTest {
    private val t0 = 1_700_000_000_000L
    private fun net(ssid: String, bssid: String, level: Int, mhz: Int) = WifiNetworkInfo(ssid, bssid, level, mhz, "WPA2", emptyList(), "")

    @Test
    fun bandsFromFrequencies() {
        assertEquals(listOf("2.4", "2.4", "5", "5", "6", "6", null, null),
            listOf(2412, 2484, 5180, 5885, 5935, 7115, 0, 900).map(WifiBands::of))
        assertEquals("5 GHz c36", WifiBands.radio(5180))
        assertEquals("2,4 GHz c6", WifiBands.radio(2437))
        assertNull(WifiBands.radio(0))
    }

    private val ap24 = "aa:00:00:00:00:24"
    private val ap5 = "aa:00:00:00:00:50"

    private fun model(): SurveyReport.Model {
        // Good on 2.4 GHz, weak on 5 GHz: the point looks covered, the per-band view says otherwise.
        val point = ScanPoint(1L, 10f, 10f, listOf(net("Ops", ap24, -55, 2437), net("Ops", ap5, -75, 5180)), pointId = "a", scanId = "s1")
        val other = ScanPoint(2L, 50f, 10f, listOf(net("Ops", ap24, -60, 2437)), pointId = "b", scanId = "s2")
        val route = ContinuousScanSession("r1", t0 + 100_000, t0 + 110_000, listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(10_000, 90f, 0f)), emptyList(),
            roaming = RoamingLog("gw", 500, 10, 0, 3.0, listOf(RoamingEvent(RoamingEvent.BSSID_CHANGE, 4_000, ap5, ap24, fromSsid = "Ops", toSsid = "Ops",
                outageObserved = false, outageUpperBoundMs = 200)), schema = 2, method = RoamingTracker.METHOD))
        val survey = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1, listOf(point, other), listOf(route), emptyList())
        val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(FloorExportSnapshot("g", "Baja", File("g.png"),
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, survey)))
        return SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67)
    }

    @Test
    fun eachPointKeepsTheStrongestApPerBand() {
        val ops = model().floors.single().ssids.single { it.ssid == "Ops" }
        val a = ops.points.single { it.pointId == "a" }
        assertEquals(PointState.OK, a.state)
        assertEquals(listOf("2.4" to -55, "5" to -75), a.bands.map { it.band to it.rssi })
        assertEquals("2,4 GHz: buena 2, débil 0, sin oír 0 de 2 · 5 GHz: buena 0, débil 1, sin oír 1 de 2",
            SurveyReport.bandSummary(ops.points, -67))
    }

    @Test
    fun theHtmlShowsBandAndChannelAndMarksABandChange() {
        val html = SurveyReport.html(model())
        assertTrue("Por banda (último escaneo válido de cada punto): 2,4 GHz: buena 2" in html)
        assertTrue("roaming labels", "$ap5 (5 GHz c36)" in html && "$ap24 (2,4 GHz c6)" in html)
        assertTrue("cambio de banda 5 GHz → 2,4 GHz" in html)
        val labels = ApLabels(ApDirectory(listOf(ApName(ap5, "AP-P3-04"))), mapOf(ap5 to 5180, ap24 to 2437))
        assertEquals("AP-P3-04 ($ap5 · 5 GHz c36)", labels.label(ap5))
        assertEquals("entre AP-P3-04 y $ap24 · cambio de banda 5 GHz → 2,4 GHz", labels.between(ap5, ap24))
        assertNull("same band: no band change", labels.bandChange(ap5, ap5))
    }
}
