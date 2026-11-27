package com.mpedreira.nimwifi

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** rc.2 · 4: each stretch keeps its probe, and the ICMP RTT is the echo time, not the process time. */
class RoamingMethodTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var utc = 1_700_000_000_000L
    private var mono = 50_000L
    private var ids = 0
    private fun viewModel() = MainViewModel(clock = { utc }, idSource = { "id-${ids++}" }, monotonicClock = { mono })

    private val reply2ms = """
        PING 10.0.0.1 (10.0.0.1) from 10.0.0.20 : 56(84) bytes of data.
        64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time=2.00 ms

        --- 10.0.0.1 ping statistics ---
        1 packets transmitted, 1 received, 0% packet loss, time 0ms
    """.trimIndent()

    @Test
    fun anIcmpReplyOf2msInAProcessOf80msExports2ms() {
        val outcome = IcmpProbe.once { reply2ms }
        assertEquals(ProbeResult.REPLIED, outcome.result)
        assertEquals(2.0, outcome.rttMs!!, 0.0)
        val log = RoamingTracker("gw", 500, "Gateway", method = RoamingTracker.METHOD_ICMP).apply {
            add(ProbeAttempt(0, 80, outcome.result, "Ops", "aa:01", -60, true, rttMs = outcome.rttMs))
            add(ProbeAttempt(500, 580, outcome.result, "Ops", "aa:01", -60, true, rttMs = outcome.rttMs))
        }.finish()
        assertEquals(2.0, log.avgRttMs!!, 0.0)
        assertEquals("the full attempt is kept apart", 80.0, log.avgAttemptMs!!, 0.0)

        // Through the export.
        val session = ContinuousScanSession("55555555-5555-4555-8555-555555555555", utc, utc + 1_000,
            listOf(RoutePointWrapper(0, 1f, 1f), RoutePointWrapper(1_000, 9f, 1f)), emptyList(), roaming = log)
        val row = export(session).getAsJsonArray("roamingSessions")[0].asJsonObject
        assertEquals(2.0, row["avgRttMs"].asDouble, 0.0)
        assertEquals(80.0, row["avgAttemptMs"].asDouble, 0.0)
        assertEquals(RoamingTracker.METHOD_ICMP, row["method"].asString)
    }

    @Test
    fun withoutAValidRttTheAverageIsNull() {
        val log = RoamingTracker("gw", 500, method = RoamingTracker.METHOD_ICMP).apply {
            add(ProbeAttempt(0, 1_000, ProbeResult.TIMEOUT))
            // Replied, but the output had no time= (e.g. a ping build that omits it): no RTT invented.
            add(ProbeAttempt(1_000, 1_080, ProbeResult.REPLIED))
        }.finish()
        assertNull(log.avgRttMs)
    }

    @Test
    fun aPingThatNeverRunsIsAProbeErrorNotALostProbe() {
        assertEquals(ProbeResult.PROBE_ERROR, IcmpProbe.once { throw IOException("Cannot run program \"ping\"") }.result)
        assertEquals(ProbeResult.PROBE_ERROR, IcmpProbe.once { "ping: unknown output" }.result)
        assertEquals(ProbeResult.TIMEOUT, IcmpProbe.once { throw MeasurementException(ErrorCodes.TIMEOUT, "no reply") }.result)
        val noReply = "1 packets transmitted, 0 received, 100% packet loss, time 0ms"
        assertEquals(ProbeResult.TIMEOUT, IcmpProbe.once { noReply }.result)

        val log = RoamingTracker("gw", 200, method = RoamingTracker.METHOD_ICMP).apply {
            add(ProbeAttempt(0, 10, ProbeResult.REPLIED, rttMs = 1.0))
            (1..5).forEach { add(ProbeAttempt(it * 200L, it * 200L + 5, ProbeResult.PROBE_ERROR)) }
            add(ProbeAttempt(1_200, 1_210, ProbeResult.REPLIED, rttMs = 1.0))
        }.finish()
        assertEquals(5, log.probeErrors)
        assertEquals("a measurement problem is not loss towards the target", 0, log.lost)
        assertTrue("no reachability loss is invented", log.events.none { it.type == RoamingEvent.REACHABILITY_LOSS })
    }

    @Test
    fun aResumedRouteKeepsEachStretchsProbeAndOldDraftsSayUnknown() {
        // First stretch: ICMP to the gateway, saved as a draft and the process dies.
        val first = viewModel().apply { loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList()) }
        first.setScanMode(SurveyScanMode.CONTINUOUS); first.armContinuousStart(); first.requestContinuousStart(10f, 10f); first.grantPendingScanAccess()
        first.roamingMethod = RoamingTracker.METHOD_ICMP; first.roamingAlias = "Gateway"
        val session = first.startRoamingLog("gw", 500)!!
        mono += 100; first.recordRoamingAttempt(session, mono, mono + 80, ProbeResult.REPLIED, null, rttMs = 2.0)
        mono += 900; first.addContinuousWaypoint(20f, 10f)
        val store = RouteDraftStore(temp.newFolder("drafts"))
        store.save(first.activeRouteDraft("floor-1")!!)
        val reread = store.list().single()
        assertEquals(RoamingProfile("gw", 500, RoamingTracker.METHOD_ICMP, "Gateway"), reread.segments.single().roaming)

        // Reopened and resumed with another probe: TCP to the ERP.
        utc += 60_000; mono = 10
        val second = viewModel().apply { loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList(), routeDrafts = listOf(reread)) }
        assertTrue(second.requestResumeRoute(session)); second.grantPendingScanAccess()
        second.roamingMethod = RoamingTracker.METHOD; second.roamingAlias = "ERP"
        assertEquals(session, second.startRoamingLog("erp:443", 1_000))
        mono += 100; second.recordRoamingAttempt(session, mono, mono + 30, ProbeResult.CONNECTED, null)
        mono += 900
        val route = second.stopContinuousTrack(30f, 10f)!!

        assertNull("never combined under one target", route.roaming)
        val (icmp, tcp) = route.roamingStretches!!
        assertEquals(listOf("gw", RoamingTracker.METHOD_ICMP, "Gateway", 500), listOf(icmp.target, icmp.method, icmp.targetAlias, icmp.intervalMillis))
        assertEquals(listOf("erp:443", RoamingTracker.METHOD, "ERP", 1_000), listOf(tcp.target, tcp.method, tcp.targetAlias, tcp.intervalMillis))
        assertEquals(2.0, icmp.avgRttMs!!, 0.0)
        assertEquals(30.0, tcp.avgRttMs!!, 0.0)
        assertEquals(1, icmp.probes); assertEquals(1, tcp.probes)
        assertTrue(icmp.stretchEndMs!! < tcp.stretchStartMs!!)
        val rows = export(route).getAsJsonArray("roamingSessions").map { it.asJsonObject }
        assertEquals(listOf(1, 2), rows.map { it["stretch"].asInt })

        // A draft written before rc.2 has no per-stretch probe: the method is "unknown", shown as such.
        val legacyJson = Gson().toJson(reread.copy(segments = reread.segments.map { seg ->
            seg.copy(roaming = null, probeAttempts = seg.probeAttempts.map { it.copy(rttMs = null) })
        }))
        val legacy = Gson().fromJson(legacyJson.replace(",\"roaming\":null", ""), RouteDraft::class.java).copy(roamingTarget = "gw", roamingIntervalMillis = 500)
        val old = RouteDrafts.finalize(legacy, recovered = true).roaming!!
        assertEquals(RoamingTracker.METHOD_UNKNOWN, old.method)
        assertNull("an old ICMP attempt's duration is never taken as an RTT", old.avgRttMs)
        assertTrue("método desconocido" in SurveyReport.probeLabel(old))
    }

    private fun export(session: ContinuousScanSession): com.google.gson.JsonObject {
        val floorPlanId = "11111111-1111-4111-8111-111111111111"
        val imageId = "22222222-2222-4222-8222-222222222222"
        val map = MapInfo("g.png", 100, 100)
        val report = EkahauReportBuilder().build("Site", listOf(EkahauFloorInput("Ground", map, 0.1, emptyList(), listOf(session), emptyList(), floorPlanId, imageId)))
        val snapshot = ProjectExportSnapshot("p", "Site", null, listOf(FloorExportSnapshot("f", "Ground", File("g.png"), floorPlanId, imageId, false, null,
            ProjectSnapshot(map, 0.1, emptyList(), listOf(session), emptyList()))))
        return PerformanceExport.build(report, "Site.esx", 0L, snapshot)
    }
}
