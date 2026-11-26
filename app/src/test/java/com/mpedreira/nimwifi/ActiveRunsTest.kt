package com.mpedreira.nimwifi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRunsTest {
    private var now = 1_000L
    private var ids = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val phases = listOf(
        PhaseRecord("target:iperf3-private:latency", "corporate · latency"),
        PhaseRecord("target:iperf3-private:download", "corporate · download"),
        PhaseRecord("target:iperf3-private:upload", "corporate · upload")
    )

    private fun viewModel() = MainViewModel({ now }, { "id-${ids++}" }, scope).apply {
        loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
    }

    /** Scans a point; returns it as saved, before any active test. */
    private fun MainViewModel.scanPoint(x: Float = 10f, y: Float = 20f): ScanPoint {
        requestStopAndGoScan(x, y)
        grantPendingScanAccess()
        return completeStopAndGoScan(emptyList())!!
    }

    private fun result(down: Double) = PointThroughput(
        now, listOf(ThroughputTestResult("corporate", "iperf3-tcp", 5, now, downMbps = down))
    )

    @Test
    fun thePointIsSavedBeforeTheTestsAndResultsArriveProgressively() {
        val vm = viewModel()
        val point = vm.scanPoint()
        assertNotNull(point.pointId)
        assertNotNull(point.scanId)
        val gate = CompletableDeferred<Unit>()
        var listener: RunListener? = null
        val runId = vm.startActiveRun(point.pointId!!, point.scanId, phases, "device-settings") { _, l ->
            listener = l
            gate.await()
            result(200.0)
        }!!

        // Running: the point is there, the run is recorded with every phase pending.
        assertEquals(listOf(point), vm.screenState.value.scanPoints)
        assertEquals(SurveyInteraction.ActiveTesting(runId, point.pointId!!), vm.screenState.value.interaction)
        assertTrue(vm.screenState.value.activeRuns.single().phases.all { it.status == RunStatus.PENDING })

        listener!!.onPhase(phases[0].key, RunStatus.COMPLETED, null)
        listener!!.onPartial(result(150.0))
        val partial = vm.screenState.value.activeRuns.single()
        assertEquals(RunStatus.COMPLETED, partial.phases[0].status)
        assertEquals(150.0, partial.results!!.tests.single().downMbps)

        gate.complete(Unit)
        val done = vm.screenState.value.activeRuns.single()
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(SurveyInteraction.Idle, vm.screenState.value.interaction)
    }

    @Test
    fun cancellingKeepsThePointAndFinishedPhasesAndIgnoresLateCallbacks() {
        val vm = viewModel()
        val point = vm.scanPoint()
        var listener: RunListener? = null
        vm.startActiveRun(point.pointId!!, point.scanId, phases, null) { _, l ->
            listener = l
            l.onPhase(phases[0].key, RunStatus.COMPLETED, null)
            l.onPartial(result(90.0))
            CompletableDeferred<PointThroughput>().await() // blocks until cancelled
        }

        vm.cancelActiveRun()

        val run = vm.screenState.value.activeRuns.single()
        assertEquals(RunStatus.CANCELLED, run.status)
        assertEquals(ErrorCodes.CANCELLED, run.statusReason)
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.CANCELLED, RunStatus.CANCELLED), run.phases.map { it.status })
        assertEquals(90.0, run.results!!.tests.single().downMbps)
        assertEquals(1, vm.screenState.value.scanPoints.size)
        assertEquals(SurveyInteraction.Idle, vm.screenState.value.interaction)

        // A late callback of the cancelled run changes nothing.
        listener!!.onPartial(result(999.0))
        listener!!.onPhase(phases[1].key, RunStatus.COMPLETED, null)
        assertEquals(run, vm.screenState.value.activeRuns.single())
    }

    @Test
    fun aRunDeadlineEndsTheRunAsFailedWithTimeout() {
        val vm = viewModel()
        val point = vm.scanPoint()
        vm.startActiveRun(point.pointId!!, point.scanId, phases, null) { _, _ -> throw MeasurementCancelled(ErrorCodes.TIMEOUT) }
        val run = vm.screenState.value.activeRuns.single()
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(ErrorCodes.TIMEOUT, run.statusReason)
    }

    @Test
    fun recreationKeepsTheRunningRunAndDoesNotRestartIt() {
        val vm = viewModel()
        val point = vm.scanPoint()
        var starts = 0
        vm.startActiveRun(point.pointId!!, point.scanId, phases, null) { _, _ ->
            starts++
            CompletableDeferred<PointThroughput>().await()
        }
        assertNull(vm.recoverAfterActivityRecreation())
        assertTrue(vm.screenState.value.interaction is SurveyInteraction.ActiveTesting)
        assertEquals(1, starts)
        // While it runs, nothing else can start.
        assertNull(vm.startActiveRun(point.pointId!!, point.scanId, phases, null) { _, _ -> result(1.0) })
        vm.cancelActiveRun()
    }

    @Test
    fun aRunLeftRunningByADeadProcessIsMarkedInterruptedOnLoad() {
        val point = ScanPoint(5L, 1f, 2f, emptyList(), pointId = "p", scanId = "s")
        val stale = ActiveRun(
            "r", "p", "s", 5L, status = RunStatus.RUNNING,
            phases = listOf(phases[0].copy(status = RunStatus.COMPLETED), phases[1], phases[2])
        )
        val vm = MainViewModel({ now }, { "id" }, scope)
        vm.loadProject(MapInfo("m.png", 100, 100), 0.1, listOf(point), emptyList(), emptyList(), activeRuns = listOf(stale))

        val run = vm.screenState.value.activeRuns.single()
        assertEquals(RunStatus.INTERRUPTED, run.status)
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.INTERRUPTED, RunStatus.INTERRUPTED), run.phases.map { it.status })
        assertEquals(SurveyInteraction.Idle, vm.screenState.value.interaction)
    }

    @Test
    fun undoRemovesTheRunFirstAndThenThePointWithItsRuns() {
        val vm = viewModel()
        val point = vm.scanPoint()
        vm.startActiveRun(point.pointId!!, point.scanId, phases, null) { _, _ -> result(10.0) }

        assertEquals(LastAction.ACTIVE_RUN, vm.undoLastAction())
        assertTrue(vm.screenState.value.activeRuns.isEmpty())
        assertEquals(1, vm.screenState.value.scanPoints.size)
        assertEquals(LastAction.SCAN, vm.undoLastAction())
        assertTrue(vm.screenState.value.scanPoints.isEmpty())
    }

    @Test
    fun migrationIsRepeatableAndTurnsOldResultsIntoRuns() {
        val legacy = PointThroughput(42L, listOf(ThroughputTestResult("corporate", "iperf3-tcp", 5, 42L, downMbps = 80.0)))
        val old = ScanPoint(42L, 3f, 4f, emptyList(), throughput = legacy)

        val (points, runs) = SurveyIds.migrate("floor-a", listOf(old), emptyList())
        val (pointsAgain, runsAgain) = SurveyIds.migrate("floor-a", listOf(old), emptyList())

        assertEquals(points, pointsAgain)
        assertEquals(runs, runsAgain)
        val point = points.single()
        assertNull(point.throughput)
        assertEquals(runs.single().pointId, point.pointId)
        assertEquals(runs.single().scanId, point.scanId)
        assertEquals(RunStatus.COMPLETED, runs.single().status)
        assertEquals(80.0, runs.single().results!!.tests.single().downMbps)
        // Already migrated data is left alone (no duplicated runs).
        assertEquals(runs, SurveyIds.migrate("floor-a", points, runs).second)
        // Another floor never shares ids.
        assertTrue(SurveyIds.pointId("floor-b", old) != point.pointId)
    }
}
