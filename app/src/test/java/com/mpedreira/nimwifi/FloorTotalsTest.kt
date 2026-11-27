package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** rc.3 · 4: the floor total comes from the floor's events, not from the (limited) sections shown. */
class FloorTotalsTest {
    private fun net(ssid: String, bssid: String) = WifiNetworkInfo(ssid, bssid, -60, 5_180, "WPA2", emptyList(), "")
    private val t0 = 1_700_000_000_000L

    private fun ap(type: String, at: Long, from: String?, to: String?, fromBssid: String = "aa:01", toBssid: String = "aa:02") =
        RoamingEvent(type, at, fromBssid, toBssid, fromSsid = from, toSsid = to)

    private fun floor(events: List<RoamingEvent>, events2: List<RoamingEvent> = emptyList()): SurveyReport.FloorSection {
        fun route(id: String, list: List<RoamingEvent>) = ContinuousScanSession(id, t0, t0 + 20_000,
            listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(20_000, 100f, 0f)), emptyList(),
            roaming = RoamingLog("gw", 500, 10, 0, 3.0, list, schema = 2, method = RoamingTracker.METHOD))
        // Seven SSIDs heard at the point: only the chosen one and the three most detected get sections.
        val ssids = listOf("Operativa", "Invitados", "Voz", "Sensores", "Fuera1", "Fuera2", "Fuera3")
        val points = ssids.mapIndexed { i, s -> ScanPoint(i.toLong() + 1, 10f * i, 10f, listOf(net(s, "c$i:01")) +
            (if (i < 4) ssids.take(4).map { net(it, "d$i:${it.length}") } else emptyList()), pointId = "p$i", scanId = "s$i") }
        val survey = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1, points,
            listOfNotNull(route("r1", events), events2.takeIf { it.isNotEmpty() }?.let { route("r2", it) }), emptyList())
        val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(
            FloorExportSnapshot("g", "Baja", File("g.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, survey)))
        return SurveyReport.build(snapshot, t0, "Operativa").floors.single()
    }

    @Test
    fun everyEventCountsOnceInTheTotalWhateverSectionsAreShown() {
        val onlyDuringRoaming = ap(RoamingEvent.BSSID_CHANGE, 1_000, "Almacen-Movil", "Almacen-Movil")
        val beyondTheLimit = ap(RoamingEvent.BSSID_CHANGE, 2_000, "Fuera3", "Fuera3")
        val betweenVisible = ap(RoamingEvent.SSID_CHANGE, 3_000, "Operativa", "Invitados")
        val floor = floor(listOf(onlyDuringRoaming, beyondTheLimit, betweenVisible))
        val shown = floor.ssids.map { it.ssid }
        assertTrue("Fuera3 has no section: $shown", "Fuera3" !in shown && "Almacen-Movil" !in shown)
        assertTrue("Operativa" in shown && "Invitados" in shown)
        // Shown in both sections…
        assertEquals(1, floor.ssids.single { it.ssid == "Operativa" }.apChanges.size)
        assertEquals(1, floor.ssids.single { it.ssid == "Invitados" }.apChanges.size)
        // …counted once; the two events without a section still count.
        assertEquals(SurveyReport.FloorTotals(apChanges = 3, outages = 0), floor.totals)
        assertNull(floor.unknownAssociation)
    }

    @Test
    fun twoIndependentEventsWithTheSameVisibleDataAreTwo() {
        // Same type, time, APs and SSIDs, in two different routes.
        val same = ap(RoamingEvent.BSSID_CHANGE, 5_000, "Operativa", "Operativa")
        val floor = floor(listOf(same), listOf(same))
        assertEquals(2, floor.ssids.single { it.ssid == "Operativa" }.apChanges.size)
        assertEquals(2, floor.totals.apChanges)
        // And two equal events of one route are two as well.
        assertEquals(2, floor(listOf(same, same)).totals.apChanges)
    }

    @Test
    fun anEventWithoutSsidStaysOnceInUnknown() {
        val loss = RoamingEvent(RoamingEvent.REACHABILITY_LOSS, 4_000, failedAttempts = 3, outageObserved = true, outageLowerBoundMs = 600)
        val floor = floor(listOf(loss, ap(RoamingEvent.SSID_CHANGE, 3_000, "Operativa", "Invitados")))
        assertEquals(1, floor.unknownAssociation!!.outages.size)
        assertTrue(floor.ssids.all { it.outages.isEmpty() })
        assertEquals(SurveyReport.FloorTotals(apChanges = 1, outages = 1), floor.totals)
    }
}
