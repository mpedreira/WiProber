package com.mpedreira.nimwifi

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** RC4 · 4: map derivations recompute only on their own inputs; stale background work is dropped; one coherent profile per run. */
class MapDerivationsTest {
    private fun net(ssid: String, level: Int, frequency: Int = 5_180) = WifiNetworkInfo(ssid, "00:00:00:00:00:0${level and 7}", level, frequency, "WPA2", emptyList(), "")

    private val settings = SurveyGuideSettings(enabled = true)
    private val base = SurveyScreenState(
        mapInfo = MapInfo("m.png", 1000, 800),
        metersPerUnit = 0.05,
        scanPoints = listOf(
            ScanPoint(1L, 100f, 100f, listOf(net("Corp", -55), net("Guest", -40, 2_412)), pointId = "p1", scanId = "s1"),
            ScanPoint(2L, 300f, 100f, listOf(net("Corp", -80)), pointId = "p2", scanId = "s2")
        ),
        plannedPoints = listOf(PlannedPoint("planned-1", "P17", 600f, 400f, batch = "b"))
    )

    private fun everything(d: MapDerivations, state: SurveyScreenState, s: SurveyGuideSettings = settings) {
        d.markers(state, s)
        d.coverage(state, s)
        d.suggestion(state, s)
        d.statuses(state)
    }

    @Test
    fun progressOnlyUpdatesReuseMarkersCoverageSuggestionAndStatuses() {
        val d = MapDerivations()
        everything(d, base)
        val markers = d.markers(base, settings)
        val counts = listOf(d.networkComputations, d.markerComputations, d.coverageComputations, d.suggestionComputations, d.statusComputations)
        assertEquals(listOf(1, 1, 1, 1, 1), counts)

        // An active run moving through its phases changes only the progress and the interaction.
        repeat(20) { i ->
            val progress = base.copy(
                activeRunProgress = ThroughputProgress(i, 20, "corporate", ThroughputPhase.DOWNLOAD, 5, emptyList()),
                interaction = SurveyInteraction.ActiveTesting("run", "p1")
            )
            everything(d, progress)
            assertSame(markers, d.markers(progress, settings))
        }
        assertEquals(counts, listOf(d.networkComputations, d.markerComputations, d.coverageComputations, d.suggestionComputations, d.statusComputations))
    }

    @Test
    fun eachDerivationFollowsItsOwnInputs() {
        val d = MapDerivations()
        everything(d, base)
        // A finished run changes statuses and markers, not the coverage samples or the suggestion.
        val withRun = base.copy(activeRuns = listOf(ActiveRun("r", "p1", "s1", 3L, 4L, RunStatus.COMPLETED,
            results = PointThroughput(3L, listOf(ThroughputTestResult("internet", "http", 5, 3L, downMbps = 90.0))))))
        everything(d, withRun)
        assertEquals(2, d.statusComputations)
        assertEquals(2, d.markerComputations)
        assertEquals(1, d.coverageComputations)
        assertEquals(1, d.suggestionComputations)
        assertEquals(PointStatus.PERFORMANCE, d.markers(withRun, settings).first { it.x == 100f }.status)

        // A new scan changes everything that depends on scans.
        val withScan = withRun.copy(scanPoints = withRun.scanPoints + ScanPoint(5L, 500f, 500f, listOf(net("Corp", -60)), pointId = "p3", scanId = "s3"))
        everything(d, withScan)
        assertEquals(2, d.coverageComputations)
        assertEquals(2, d.suggestionComputations)
        assertEquals(3, d.markerComputations)

        // Another primary network (SSID + band) recolours markers and coverage.
        val guest = settings.copy(ssidFilter = "Guest", bandFilter = WifiBands.B24)
        everything(d, withScan, guest)
        assertEquals(MapNetwork("Guest", WifiBands.B24), d.network(withScan, guest))
        assertEquals(3, d.coverageComputations)
        assertEquals(4, d.markerComputations)
        assertEquals(PointState.SSID_NOT_DETECTED, d.markers(withScan, guest).first { it.x == 300f }.state)
    }

    @Test
    fun aHandMovedSuggestionWinsWithoutRecomputing() {
        val d = MapDerivations()
        d.suggestion(base, settings)
        val moved = base.copy(movedSuggestion = SurveyCoordinate(10f, 10f))
        assertEquals(SurveyCoordinate(10f, 10f), d.suggestion(moved, settings))
        assertEquals(1, d.suggestionComputations)
        assertEquals(null, d.suggestion(base.copy(scanMode = SurveyScanMode.CONTINUOUS), settings))
    }

    @Test
    fun onlyTheLatestBackgroundRequestIsKept() {
        val jobs = LatestOnly()
        val floorA = jobs.begin()
        val floorB = jobs.begin() // fast floor or SSID switch while A was drawing
        assertFalse(jobs.isCurrent(floorA))
        assertTrue(jobs.isCurrent(floorB))
    }

    @Test
    fun theProfileSnapshotIsOneCoherentCopyReplacedOnSave() {
        val dir = Files.createTempDirectory("profile").toFile()
        try {
            val store = WiproberConfigStore(dir)
            assertEquals(WiproberConfigStore.Imported(), store.snapshot().imported)
            store.setCredential("TOKEN", "one")
            val first = store.snapshot()
            assertEquals(mapOf("TOKEN" to "one"), first.credentials)
            // Cached: another store of the same directory gets the same copy without reading again.
            assertSame(first, WiproberConfigStore(dir).snapshot())
            store.update { it.copy(roaming = RoamingSettings(enabled = true, host = "probe.example.com")) }
            val second = WiproberConfigStore(dir).snapshot()
            assertNotSame(first, second)
            assertTrue(second.revision != first.revision)
            assertEquals("probe.example.com", second.imported.roaming?.host)
            assertEquals(mapOf("TOKEN" to "one"), second.credentials)
            store.clear()
            assertEquals(WiproberConfigStore.Imported(), store.snapshot().imported)
            assertTrue(store.snapshot().credentials.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
