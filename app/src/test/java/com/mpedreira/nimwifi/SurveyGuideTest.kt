package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyGuideTest {
    // 0.1 m per pixel: 10 m spacing = 100 px cells, 5 m radius = 50 px.
    private val scale = 0.1
    private val settings = SurveyGuideSettings()

    private fun suggest(
        measured: List<SurveyCoordinate>,
        skipped: List<SurveyCoordinate> = emptyList(),
        metersPerUnit: Double? = scale,
        config: SurveyGuideSettings = settings
    ) = SurveyGuide.suggestNext(1_000, 500, metersPerUnit, config, measured, skipped)

    @Test
    fun needsAScaleAFirstPointAndTheGuideEnabled() {
        val first = listOf(SurveyCoordinate(50f, 50f))
        assertNull(suggest(first, metersPerUnit = null))
        assertNull(suggest(emptyList()))
        assertNull(suggest(first, config = settings.copy(enabled = false)))
    }

    @Test
    fun suggestsTheNeighbouringCellAndKeepsTheWalkingDirection() {
        // Walking right along the first row: the next cell to the right wins over the one below.
        val next = suggest(listOf(SurveyCoordinate(50f, 50f), SurveyCoordinate(150f, 50f)))
        assertEquals(SurveyCoordinate(250f, 50f), next)
    }

    @Test
    fun gridFollowsTheScale() {
        // 0.2 m per pixel: the same 10 m are 50 px cells.
        val next = SurveyGuide.suggestNext(1_000, 500, 0.2, settings, listOf(SurveyCoordinate(25f, 25f), SurveyCoordinate(75f, 25f)), emptyList())
        assertEquals(SurveyCoordinate(125f, 25f), next)
    }

    @Test
    fun skippedCellsAreNeverSuggestedAgain() {
        val measured = listOf(SurveyCoordinate(50f, 50f), SurveyCoordinate(150f, 50f))
        val skipped = listOf(SurveyCoordinate(260f, 40f)) // anywhere inside the cell
        val next = suggest(measured, skipped)
        assertNotEquals(SurveyCoordinate(250f, 50f), next)
        assertTrue(next != null)
    }

    @Test
    fun aPointMeasuredOffGridStillCoversItsCell() {
        val measured = listOf(SurveyCoordinate(50f, 50f), SurveyCoordinate(130f, 80f), SurveyCoordinate(230f, 20f))
        val next = suggest(measured)
        assertEquals(SurveyCoordinate(350f, 50f), next)
    }

    @Test
    fun returnsNullWhenEverythingIsCoveredOrSkipped() {
        val measured = (0 until 10).flatMap { c -> (0 until 5).map { r -> SurveyCoordinate(c * 100f + 50f, r * 100f + 50f) } }
        assertNull(suggest(measured))
    }

    @Test
    fun continuousScansArePlacedAlongTheRouteByTime() {
        val session = ContinuousScanSession(
            id = "s", startTime = 1_000, endTime = 5_000,
            waypoints = listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(4_000, 400f, 0f)),
            scanResults = listOf(ScanResultWrapper(1_000, 500, listOf(net("Corp", -60))))
        )
        val samples = SurveyGuide.coverageSamples(emptyList(), listOf(session))
        assertEquals(listOf(CoverageSample(100f, 0f, -60)), samples)
    }

    @Test
    fun coverageUsesTheStrongestNetworkOfTheChosenSsid() {
        val point = ScanPoint(1, 5f, 6f, listOf(net("Corp", -70), net("Guest", -40), net("Corp", -64)))
        assertEquals(-40, SurveyGuide.coverageSamples(listOf(point), emptyList()).single().bestRssi)
        assertEquals(-64, SurveyGuide.coverageSamples(listOf(point), emptyList(), "Corp").single().bestRssi)
        assertNull(SurveyGuide.coverageSamples(listOf(point), emptyList(), "Other").single().bestRssi)
    }

    @Test
    fun colourGoesFromRedThroughYellowToGreen() {
        fun rgb(color: Int) = Triple((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        val (r1, g1, _) = rgb(SurveyGuide.rssiColor(-90))
        val (r2, g2, _) = rgb(SurveyGuide.rssiColor(-70))
        val (r3, g3, _) = rgb(SurveyGuide.rssiColor(-50))
        assertTrue(r1 > 200 && g1 < 60)        // red
        assertTrue(r2 > 200 && g2 > 200)       // yellow
        assertTrue(r3 < 60 && g3 > 200)        // green
        assertEquals(SurveyGuide.rssiColor(null), SurveyGuide.rssiColor(-85)) // nothing heard = weakest
    }

    @Test
    fun measurementOrderMergesPointsAndRoutesByTime() {
        val points = listOf(ScanPoint(10, 1f, 1f, emptyList()), ScanPoint(30, 3f, 3f, emptyList()))
        val session = ContinuousScanSession("s", 15, 20, listOf(RoutePointWrapper(5, 2f, 2f)), emptyList())
        assertEquals(
            listOf(SurveyCoordinate(1f, 1f), SurveyCoordinate(2f, 2f), SurveyCoordinate(3f, 3f)),
            SurveyGuide.measurementOrder(points, listOf(session))
        )
    }

    @Test
    fun gridUsesTheSpacingThroughTheScale() {
        assertEquals(GuideGrid(100f, 1_000, 500), SurveyGuide.grid(1_000, 500, scale, settings))
        assertEquals(40f, SurveyGuide.cellSizePx(0.025, SurveyGuideSettings(spacingMeters = 1.0))!!, 0.001f)
    }

    @Test
    fun gridNeedsAFinitePositiveScale() {
        assertNull(SurveyGuide.grid(1_000, 500, null, settings))
        assertNull(SurveyGuide.grid(1_000, 500, 0.0, settings))
        assertNull(SurveyGuide.grid(1_000, 500, Double.NaN, settings))
    }

    @Test
    fun gridIsLimitedLikeTheSuggestion() {
        // 1 m cells at 1 m per pixel (1 px cells) on a 1 000 x 1 000 px plan: 1 000 000 cells.
        assertNull(SurveyGuide.grid(1_000, 1_000, 1.0, SurveyGuideSettings(spacingMeters = 1.0)))
    }

    @Test
    fun suggestionIsTheCentreOfADrawnCell() {
        val grid = SurveyGuide.grid(1_000, 500, scale, settings)!!
        val next = suggest(listOf(SurveyCoordinate(50f, 50f)))!!
        assertEquals(0.5f, (next.x / grid.cellPx) % 1f, 0.001f)
        assertEquals(0.5f, (next.y / grid.cellPx) % 1f, 0.001f)
    }

    private fun net(ssid: String, level: Int) = WifiNetworkInfo(ssid, "00:11:22:33:44:${level + 100}", level, 5_180, "WPA2", emptyList(), "")
}
