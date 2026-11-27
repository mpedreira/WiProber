package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Mejora 3: routes left open by a process death are recovered from their drafts. */
class RouteDraftTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var utc = 1_700_000_000_000L
    private var mono = 50_000L
    private var ids = 0
    private val net = WifiNetworkInfo("Corp", "aa:01", -60, 5_180, "WPA2", emptyList(), "")

    private fun viewModel() = MainViewModel(clock = { utc }, idSource = { "id-${ids++}" }, monotonicClock = { mono })

    private fun MainViewModel.load(drafts: List<RouteDraft> = emptyList(), sessions: List<ContinuousScanSession> = emptyList()) =
        loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), sessions, emptyList(), routeDrafts = drafts)

    private fun MainViewModel.startTrack(x: Float, y: Float) {
        setScanMode(SurveyScanMode.CONTINUOUS)
        armContinuousStart()
        requestContinuousStart(x, y)
        grantPendingScanAccess()
    }

    @Test
    fun aRouteKilledMidwayIsRecoveredFinalizedOnceAndTraceable() {
        // First process: a route with a turn, two scans and a probe attempt, then the process dies.
        val first = viewModel().apply { load() }
        first.startTrack(10f, 10f)
        val sessionId = first.startRoamingLog("gw:443", 200)!!
        mono += 1_000; first.addContinuousWaypoint(20f, 10f)
        first.recordRoamingAttempt(sessionId, mono, mono + 5, ProbeResult.CONNECTED, null)
        val attempt = first.beginContinuousScanAttempt()!!
        mono += 500; first.completeContinuousScanAttempt(attempt, listOf(net))
        val draft = first.activeRouteDraft("floor-1")!!
        assertEquals(1, draft.segments.size)

        // The draft survives on disk (atomic replace keeps the previous checkpoint).
        val store = RouteDraftStore(temp.newFolder("drafts"))
        store.save(draft.copy(segments = emptyList()))
        store.save(draft)
        val saved = store.list().single()
        assertEquals(draft, saved)

        // Second process: reopened, the route is offered as interrupted.
        val second = viewModel().apply { load(drafts = store.list()) }
        assertEquals(listOf(sessionId), second.screenState.value.interruptedRoutes.map { it.sessionId })
        val session = second.finalizeInterruptedRoute(sessionId)!!
        // A retry of the same finalization (e.g. its save failed) adds nothing twice.
        assertEquals(session, second.finalizeInterruptedRoute(sessionId))
        val routes = second.screenState.value.continuousScanSessions
        assertEquals(1, routes.size)
        assertEquals(true, routes.single().recoveredAfterInterruption)
        assertEquals(listOf(0L, 1_000L), routes.single().waypoints.map { it.timestamp })
        assertEquals(1, routes.single().scanResults.size)
        assertEquals(1, routes.single().roaming!!.probes)

        // Once the survey with the route is saved, its draft is removed; reopening with a stale
        // draft whose route already exists does not offer it again.
        store.deleteFinalized(routes.map { it.id }.toSet())
        assertTrue(store.list().isEmpty())
        val third = viewModel().apply { load(drafts = listOf(saved), sessions = routes) }
        assertTrue(third.screenState.value.interruptedRoutes.isEmpty())
    }

    @Test
    fun aResumedRouteGetsALinkedStretchEvenWhenTheMonotonicClockRestarted() {
        val draft = RouteDraft(
            sessionId = "route-1", floorId = "f", startTime = utc,
            segments = listOf(
                RouteSegment(utc, waypoints = listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(4_000, 40f, 0f)),
                    scanResults = listOf(ScanResultWrapper(3_000, 500, listOf(net))), lastObservedMs = 5_000)
            )
        )
        // Reboot: the monotonic clock starts again from a small value, 60 s of wall time later.
        utc += 60_000
        mono = 10
        val vm = viewModel().apply { load(drafts = listOf(draft)) }
        assertTrue(vm.requestResumeRoute("route-1"))
        vm.grantPendingScanAccess()
        mono += 2_000
        val attempt = vm.beginContinuousScanAttempt()!!
        mono += 400; vm.completeContinuousScanAttempt(attempt, listOf(net))
        mono += 600
        val session = vm.stopContinuousTrack(80f, 0f)!!

        assertEquals("route-1", session.id)
        assertEquals(2, session.segments)
        assertEquals(listOf(RouteInterruption(5_000, 60_000)), session.interruptions)
        // Times keep increasing across the stretches although the second clock restarted.
        assertEquals(listOf(0L, 4_000L, 60_000L, 63_000L), session.waypoints.map { it.timestamp })
        assertEquals(listOf(3_000L, 62_400L), session.scanResults.map { it.timestamp })
        assertEquals(true, session.recoveredAfterInterruption)
        assertTrue(vm.screenState.value.interruptedRoutes.isEmpty())
    }

    @Test
    fun aStretchAnchoredBeforeThePreviousEndIsPlacedAfterIt() {
        // Wall clock moved backwards between stretches: never reorder the data.
        val draft = RouteDraft("r", "f", 1_000L, listOf(
            RouteSegment(1_000L, waypoints = listOf(RoutePointWrapper(0, 0f, 0f)), lastObservedMs = 9_000),
            RouteSegment(500L, waypoints = listOf(RoutePointWrapper(0, 5f, 0f), RoutePointWrapper(100, 6f, 0f)))
        ))
        val session = RouteDrafts.finalize(draft, recovered = true)
        assertEquals(listOf(0L, 9_001L, 9_101L), session.waypoints.map { it.timestamp })
        assertNull(session.roaming)
    }
}
