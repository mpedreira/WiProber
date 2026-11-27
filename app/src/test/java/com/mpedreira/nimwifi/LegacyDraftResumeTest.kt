package com.mpedreira.nimwifi

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** rc.3 · 3: an rc.1 draft (probe only route-wide) resumed against another target keeps its own. */
class LegacyDraftResumeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var utc = 1_700_000_000_000L
    private var mono = 10L
    private var ids = 0
    private fun viewModel() = MainViewModel(clock = { utc }, idSource = { "id-${ids++}" }, monotonicClock = { mono })

    /** As 4.0.0-rc.1 wrote it: no per-stretch probe; route-wide gateway target; two probes; a stretch without probes. */
    private val rc1Json = """
        {"sessionId":"route-1","floorId":"floor-1","startTime":1700000000000,
         "segments":[
           {"startUtc":1700000000000,"timeBase":"monotonic-relative","waypoints":[{"timestamp":0,"x":0.0,"y":0.0},{"timestamp":2000,"x":20.0,"y":0.0}],
            "scanResults":[],"probeAttempts":[
              {"startMs":100,"endMs":105,"result":"connected","ssid":"Ops","bssid":"aa:01","rssi":-60,"associationKnown":true},
              {"startMs":300,"endMs":305,"result":"connected","ssid":"Ops","bssid":"aa:01","rssi":-60,"associationKnown":true}],
            "scanWindows":[],"lastObservedMs":2000},
           {"startUtc":1700000010000,"timeBase":"monotonic-relative","waypoints":[{"timestamp":0,"x":20.0,"y":0.0},{"timestamp":1000,"x":30.0,"y":0.0}],
            "scanResults":[],"probeAttempts":[],"scanWindows":[],"lastObservedMs":1000}],
         "roamingTarget":"gw:443","roamingIntervalMillis":200,"savedAt":1700000011000,"schema":1}
    """.trimIndent()

    private fun rc1Draft(): RouteDraft = Gson().fromJson(rc1Json, RouteDraft::class.java)

    private fun resume(draft: RouteDraft, newProbe: Boolean): ContinuousScanSession {
        utc += 60_000; mono = 10
        val vm = viewModel().apply { loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList(), routeDrafts = listOf(draft)) }
        assertTrue(vm.requestResumeRoute("route-1")); vm.grantPendingScanAccess()
        if (newProbe) {
            vm.roamingMethod = RoamingTracker.METHOD; vm.roamingAlias = "ERP"
            assertEquals("route-1", vm.startRoamingLog("erp:443", 1_000))
            mono += 100; vm.recordRoamingAttempt("route-1", mono, mono + 20, ProbeResult.CONNECTED, null)
        }
        mono += 900; vm.addContinuousWaypoint(40f, 0f)
        // The resumed route is checkpointed, saved and read again before it is closed.
        val store = RouteDraftStore(temp.newFolder())
        store.save(vm.activeRouteDraft("floor-1")!!)
        val reread = store.list().single()
        val reopened = viewModel().apply { loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList(), routeDrafts = listOf(reread)) }
        return reopened.finalizeInterruptedRoute("route-1")!!
    }

    private fun roamingRows(session: ContinuousScanSession): List<com.google.gson.JsonObject> {
        val floorPlanId = "11111111-1111-4111-8111-111111111111"; val imageId = "22222222-2222-4222-8222-222222222222"
        val map = MapInfo("g.png", 100, 100)
        val s = session.copy(id = "55555555-5555-4555-8555-555555555555")
        val report = EkahauReportBuilder().build("Site", listOf(EkahauFloorInput("Ground", map, 0.1, emptyList(), listOf(s), emptyList(), floorPlanId, imageId)))
        val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(FloorExportSnapshot("f", "Ground", File("g.png"), floorPlanId, imageId, false, null,
            ProjectSnapshot(map, 0.1, emptyList(), listOf(s), emptyList()))))
        return PerformanceExport.build(report, "Site.esx", 0L, snapshot).getAsJsonArray("roamingSessions").map { it.asJsonObject }
    }

    @Test
    fun resumedAgainstTheErpEachGroupOfProbesKeepsItsTarget() {
        val rows = roamingRows(resume(rc1Draft(), newProbe = true))
        assertEquals(2, rows.size)
        val (old, new) = rows
        assertEquals(listOf("gw:443", RoamingTracker.METHOD_UNKNOWN, 200, 2), listOf(old["target"].asString, old["method"].asString, old["intervalMs"].asInt, old["probes"].asInt))
        assertEquals(listOf("erp:443", RoamingTracker.METHOD, 1_000, 1), listOf(new["target"].asString, new["method"].asString, new["intervalMs"].asInt, new["probes"].asInt))
    }

    @Test
    fun resumedWithRoamingOffTheOldProbesStillAppear() {
        val session = resume(rc1Draft(), newProbe = false)
        val rows = roamingRows(session)
        assertEquals(1, rows.size)
        assertEquals("gw:443", rows.single()["target"].asString)
        assertEquals(2, rows.single()["probes"].asInt)
        assertNull("one probe only: no stretches", session.roamingStretches)
    }

    @Test
    fun theMigrationIsStableAndLeavesStretchesWithoutProbesAlone() {
        val migrated = RouteDrafts.migrateLegacy(rc1Draft())
        assertEquals(RoamingProfile("gw:443", 200, RoamingTracker.METHOD_UNKNOWN), migrated.segments[0].roaming)
        assertNull("a stretch without probes keeps that state", migrated.segments[1].roaming)
        assertEquals(migrated, RouteDrafts.migrateLegacy(migrated))
        val reread = Gson().fromJson(Gson().toJson(migrated), RouteDraft::class.java)
        assertEquals(migrated, RouteDrafts.migrateLegacy(reread))
        // A route-wide target overwritten later (e.g. by a new stretch) no longer moves old probes.
        assertEquals(migrated.segments, RouteDrafts.migrateLegacy(migrated.copy(roamingTarget = "erp:443")).segments)
    }
}
