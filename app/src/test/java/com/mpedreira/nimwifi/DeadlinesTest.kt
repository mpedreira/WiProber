package com.mpedreira.nimwifi

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 7: monotonic deadlines, a finite budget per point and bounded blocking calls. */
class DeadlinesTest {
    @Test
    fun aValueReturnedAfterTheDeadlineIsATimeout() {
        val control = MeasurementControl()
        try {
            control.phase(5) { Thread.sleep(100); 42 }
            throw AssertionError("expected timeout")
        } catch (e: MeasurementException) {
            assertEquals(ErrorCodes.TIMEOUT, e.code)
        }
        // The control stays usable for the next phase.
        assertEquals(7, control.phase(1_000) { 7 })
    }

    @Test
    fun aPhaseThatDoesNotFitTheRemainingBudgetIsSkipped() {
        val control = MeasurementControl()
        control.startRunDeadline(1_000)
        try {
            control.checkBudget(15_000)
            throw AssertionError("expected budget_exhausted")
        } catch (e: MeasurementException) {
            assertEquals(ErrorCodes.BUDGET_EXHAUSTED, e.code)
        }
        control.clearRunDeadline()
        control.checkBudget(15_000) // no budget: nothing to check
    }

    @Test
    fun aSlowDnsLookupIsBoundedAndPendingLookupsAreCapped() {
        val release = CountDownLatch(1)
        val calls = BoundedCalls(timeoutMillis = 50, maxPending = 1)
        val network = BoundedResolveNetwork(FakeMeasurementNetwork(), calls)
        val slow = BoundedResolveNetwork(object : MeasurementNetwork by FakeMeasurementNetwork() {
            override fun resolve(host: String): List<InetAddress> { release.await(); return listOf(FakeMeasurementNetwork.LOOPBACK) }
        }, calls)
        val started = System.nanoTime()
        try {
            slow.resolve("slow.example.com"); throw AssertionError("expected timeout")
        } catch (e: MeasurementException) {
            assertEquals(ErrorCodes.TIMEOUT, e.code)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        // The slow lookup is still pending: no more piling up.
        try {
            network.resolve("127.0.0.1"); throw AssertionError("expected cap")
        } catch (e: MeasurementException) {
            assertTrue(e.message!!.contains("pending"))
        }
        release.countDown()
        Thread.sleep(50)
        assertEquals(listOf(FakeMeasurementNetwork.LOOPBACK), network.resolve("127.0.0.1"))
    }

    @Test
    fun anUploadThatStopsProgressingEndsAtItsPhaseDeadline() {
        ServerSocket(0).use { silent ->
            // Accepted sockets stay referenced: a collected Socket closes and would end the upload early.
            val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
            thread(isDaemon = true) { while (!silent.isClosed) runCatching { accepted += silent.accept() } }
            val control = MeasurementControl()
            val started = System.nanoTime()
            try {
                control.phase(300) { HttpSpeedTest(control = control).upload("http://127.0.0.1:${silent.localPort}/up", 30) }
                throw AssertionError("expected timeout")
            } catch (e: MeasurementException) {
                assertEquals(ErrorCodes.TIMEOUT, e.code)
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }
    }

    /** A connection opened after the phase deadline is closed at once instead of blocking on it. */
    @Test
    fun aResourceTrackedAfterThePhaseDeadlineIsClosedImmediately() {
        ServerSocket(0).use { server ->
            val accepted = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
            thread(isDaemon = true) { while (!server.isClosed) runCatching { accepted += server.accept() } }
            val control = MeasurementControl()
            val late = java.net.Socket()
            val started = System.nanoTime()
            try {
                control.phase(100) {
                    Thread.sleep(300) // the deadline passes while "between requests"
                    control.track(late).connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 1_000)
                    late.getInputStream().read() // would block for ever without the fix
                }
                throw AssertionError("expected timeout")
            } catch (e: MeasurementException) {
                assertEquals(ErrorCodes.TIMEOUT, e.code)
            }
            assertTrue(late.isClosed)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
            // The next phase is not affected.
            assertEquals(7, control.phase(1_000) { control.track(java.net.Socket()).use { 7 } })
        }
    }

    @Test
    fun twoCancellationsInARowLeaveTheNextPointFreeToStart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var ids = 0
        val vm = MainViewModel({ 1_000L }, { "id-${ids++}" }, scope).apply {
            loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
        }
        val phases = listOf(PhaseRecord("target:internet-http:download", "download"))
        fun scan(x: Float): ScanPoint {
            vm.requestStopAndGoScan(x, 1f); vm.grantPendingScanAccess(); return vm.completeStopAndGoScan(emptyList())!!
        }
        val partial = PointThroughput(1L, listOf(ThroughputTestResult("internet", "http", 5, 1L, downMbps = 50.0, downloadStatus = RunStatus.COMPLETED)))
        listOf(scan(1f), scan(2f)).forEach { point ->
            val gate = CompletableDeferred<Unit>()
            val runId = vm.startActiveRun(point.pointId!!, point.scanId, phases, null, budgetMillis = 30_000) { _, listener ->
                listener.onPhaseResult(phases[0].key, RunStatus.COMPLETED, null, partial)
                gate.await(); partial
            }!!
            vm.cancelActiveRun()
            val run = vm.screenState.value.activeRuns.single { it.runId == runId }
            assertEquals(RunStatus.CANCELLED, run.status)
            assertEquals(50.0, run.results!!.tests.single().downMbps)
            assertEquals(30_000L, run.budgetMillis)
        }
        val third = scan(3f)
        assertNotNull(vm.startActiveRun(third.pointId!!, third.scanId, phases, null) { _, _ -> partial })
        assertEquals(RunStatus.COMPLETED, vm.screenState.value.activeRuns.last().status)
    }
}
