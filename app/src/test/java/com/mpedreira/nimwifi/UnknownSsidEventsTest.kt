package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** rc.2 · 5: a roaming event without any SSID is shown once, under «SSID desconocido». */
class UnknownSsidEventsTest {
    private fun net(ssid: String, bssid: String) = WifiNetworkInfo(ssid, bssid, -60, 5_180, "WPA2", emptyList(), "")
    private val t0 = 1_700_000_000_000L

    private fun report(events: List<RoamingEvent>): SurveyReport.Model {
        val log = RoamingLog("gw", 500, 10, 2, 3.0, events, schema = 2, method = RoamingTracker.METHOD)
        val route = ContinuousScanSession("r", t0, t0 + 10_000, listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(10_000, 100f, 0f)),
            emptyList(), roaming = log)
        // No active tests on the floor at all.
        val survey = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1,
            listOf(ScanPoint(1L, 10f, 10f, listOf(net("Operativa", "aa:01"), net("Invitados", "bb:01")), pointId = "p1", scanId = "s1")),
            listOf(route), emptyList())
        val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(
            FloorExportSnapshot("g", "Baja", File("g.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, survey)))
        return SurveyReport.build(snapshot, t0, "Operativa")
    }

    private val withoutSsid = RoamingEvent(RoamingEvent.REACHABILITY_LOSS, 2_000, failedAttempts = 3, outageObserved = true, outageLowerBoundMs = 1_000, outageUpperBoundMs = 1_500)
    private val betweenSsids = RoamingEvent(RoamingEvent.SSID_CHANGE, 5_000, "aa:01", "bb:01", fromSsid = "Operativa", toSsid = "Invitados")
    private val insideOperativa = RoamingEvent(RoamingEvent.BSSID_CHANGE, 7_000, "aa:01", "aa:02", fromSsid = "Operativa", toSsid = "Operativa")

    @Test
    fun anEventWithoutSsidIsShownOnceAndOutsideBothNetworks() {
        val floor = report(listOf(withoutSsid, betweenSsids, insideOperativa)).floors.single()
        val operativa = floor.ssids.single { it.ssid == "Operativa" }
        val invitados = floor.ssids.single { it.ssid == "Invitados" }
        val unknown = floor.unknownAssociation!!

        assertEquals("the SSID-less loss is in neither network", 0, operativa.outages.size + invitados.outages.size)
        assertEquals(1, unknown.outages.size)
        assertEquals(0, unknown.apChanges.size)
        // The change between two known SSIDs keeps its context in both.
        assertEquals(2, operativa.apChanges.size)
        assertEquals(1, invitados.apChanges.size)
        // Floor totals: each event once.
        assertEquals(SurveyReport.FloorTotals(apChanges = 2, outages = 1), floor.totals)

        val html = SurveyReport.html(SurveyReport.Model("Almacén", t0, SurveyReport.RunSelection.LATEST_PER_POINT, -67, floors = listOf(floor)))
        assertTrue("SSID desconocido · Baja" in html)
        assertEquals("the event appears once", 1, Regex("Pérdida de alcance<div").findAll(html).count())
        assertTrue("Total de la planta: 2 cambios de AP · 1 pérdidas de alcance" in html)
    }

    @Test
    fun withoutSuchEventsAndWithoutTestsThereIsNoUnknownSection() {
        assertNull(report(listOf(betweenSsids)).floors.single().unknownAssociation)
    }
}
