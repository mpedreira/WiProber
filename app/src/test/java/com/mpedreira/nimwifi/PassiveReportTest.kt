package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 8: a report with plans for a passive floor and a Continuous-only floor, no active tests. */
class PassiveReportTest {
    private fun net(level: Int) = WifiNetworkInfo("Ops", "aa:01", level, 5_180, "WPA2", emptyList(), "")
    private val image = SurveyReport.PlanImage("data:image/jpeg;base64,AAAA", 1_000, 800)

    private val passive = ProjectSnapshot(MapInfo("a.png", 1_000, 800), 0.1,
        listOf(ScanPoint(1L, 100f, 100f, listOf(net(-58)), pointId = "p1", scanId = "s1"),
               ScanPoint(2L, 300f, 100f, listOf(WifiNetworkInfo("Guest", "bb:01", -40, 5_180, "WPA2", emptyList(), "")), pointId = "p2", scanId = "s2")),
        emptyList(), emptyList(), plannedPoints = listOf(PlannedPoint("pl", "Dock 4", 900f, 700f)))
    private val continuous = ProjectSnapshot(MapInfo("b.png", 1_000, 800), 0.1, emptyList(),
        listOf(ContinuousScanSession("t", 1_000L, 21_000L,
            waypoints = listOf(RoutePointWrapper(0, 0f, 400f), RoutePointWrapper(10_000, 1_000f, 400f)),
            scanResults = listOf(
                ScanResultWrapper(5_000, 300, listOf(net(-84))),   // on the route: weak, drawn
                ScanResultWrapper(15_000, 300, listOf(net(-60)))   // after the last waypoint: not drawn
            ))),
        emptyList())
    private val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(
        FloorExportSnapshot("a", "Ground", File("a.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, passive),
        FloorExportSnapshot("b", "First", File("b.png"), "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444", false, null, continuous)
    ))

    @Test
    fun bothFloorsGetTheirMapAndFiguresWithoutActiveTests() {
        val model = SurveyReport.build(snapshot, 0L, "Ops", weakDbm = -67,
            planImages = mapOf("11111111-1111-4111-8111-111111111111" to image, "33333333-3333-4333-8333-333333333333" to image))
        val ground = model.floors[0].ssids.single { it.ssid == "Ops" }
        assertEquals(listOf(MapMarkView(MapMark.STOP, PointState.OK), MapMarkView(MapMark.STOP, PointState.SSID_NOT_DETECTED),
            MapMarkView(MapMark.PLANNED, PointState.NOT_MEASURED)), ground.marks.map { MapMarkView(it.source, it.state) })

        val first = model.floors[1].ssids.single { it.ssid == "Ops" }
        val sources = first.sources!!
        assertEquals(1, sources.continuousPlaced)
        assertEquals(1, sources.continuousWithoutGeometry)
        assertEquals(1, sources.continuousWeak)
        assertEquals(-84, sources.continuousWorst)
        val mark = first.marks.single()
        assertEquals(PointState.WEAK, mark.state)
        assertEquals(-84, mark.rssi)
        assertTrue(mark.approximate)
        assertEquals(500f, mark.x)

        val html = SurveyReport.html(model)
        // One plan per floor and SSID: Ops on both floors and the guest SSID heard on the ground floor.
        assertEquals(3, Regex("<svg viewBox").findAll(html).count())
        assertTrue("-84 dBm (umbral -67)" in html)
        assertTrue("posición aproximada" in html)
        assertTrue("sin geometría de ruta suficiente (no se dibujan)" in html)
        assertTrue("Dock 4: Sin medición local" in html)
        // The bundle also exists without runs.
        assertTrue(PerformanceExport.build(null, "Site.esx", 0L, snapshot).getAsJsonArray("activeRuns").size() == 0)
    }

    private data class MapMarkView(val source: String, val state: String)
}
private typealias MapMark = SurveyReport.MapMark
