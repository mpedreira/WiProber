package com.mpedreira.nimwifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 2: one capture session owns scanner, probe and service; the UI can come and go. */
class ContinuousCaptureTest {
    private var utc = 1_700_000_000_000L
    private var mono = 1_000L
    private val net = WifiNetworkInfo("Corp", "aa:01", -60, 5_180, "WPA2", emptyList(), "")

    private class FakeRf : RfScanSource {
        val results = Channel<RfScan>(Channel.UNLIMITED)
        var starts = 0
        var closes = 0
        var enabled = true
        override fun start() { starts++ }
        override fun isWifiEnabled() = enabled
        override suspend fun scan(): RfScan = results.receive()
        override fun close() { closes++ }
    }

    private class FakeForeground(val problem: String? = null) : CaptureForeground {
        var held = false
        var starts = 0
        override fun start(): String? { starts++; held = true; return problem }
        override fun stop() { held = false }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun trackingViewModel() = MainViewModel({ utc }, { "id-${mono++}" }, scope, { mono }).apply {
        loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
        setScanMode(SurveyScanMode.CONTINUOUS)
        armContinuousStart()
        requestContinuousStart(1f, 1f)
        grantPendingScanAccess()
    }

    @Test
    fun aRecreatedScreenReattachesWithoutASecondScanner() = runBlocking {
        val vm = trackingViewModel()
        val rf = FakeRf()
        val foreground = FakeForeground()
        vm.startCapture(CaptureSources(rf, foreground))
        rf.results.send(RfScan.Success(listOf(net)))
        // Rotation: the new Activity asks again with its own sources; they are closed unused.
        val rf2 = FakeRf()
        vm.startCapture(CaptureSources(rf2, FakeForeground()))
        assertEquals(1, rf.starts)
        assertEquals(0, rf2.starts)
        assertEquals(1, rf2.closes)
        assertEquals(1, foreground.starts)
        rf.results.send(RfScan.Success(listOf(net)))
        withTimeout(2_000) { while (vm.captureStatus.value.rfScans < 2) kotlinx.coroutines.delay(5) }
        assertEquals(2, vm.screenState.value.activeContinuousScanCount)
    }

    @Test
    fun closingTheSessionCancelsTheWorkAndReleasesReceiverAndLocks() = runBlocking {
        val vm = trackingViewModel()
        val rf = FakeRf()
        val foreground = FakeForeground()
        var probe: Job? = null
        vm.startCapture(CaptureSources(rf, foreground, roaming = RoamingProbeSource { s, _ -> Job().also { probe = it } }, roamingTarget = "gw:443"))
        assertTrue(foreground.held)
        assertTrue(vm.captureStatus.value.roamingEnabled)
        val session = vm.stopContinuousTrack(5f, 5f)!!
        assertFalse(foreground.held)
        assertEquals(1, rf.closes)
        assertTrue(probe!!.isCancelled)
        assertFalse(vm.captureStatus.value.running)
        assertEquals(session.id, vm.screenState.value.continuousScanSessions.single().id)
    }

    @Test
    fun aFatalScanFailureStopsTheTrackAndPausesBecomeUnobservedSpans() = runBlocking {
        val vm = trackingViewModel()
        val rf = FakeRf()
        vm.startCapture(CaptureSources(rf, FakeForeground(problem = "ForegroundServiceStartNotAllowedException")))
        assertEquals("ForegroundServiceStartNotAllowedException", vm.captureStatus.value.serviceProblem)
        rf.results.send(RfScan.Success(listOf(net)))
        mono += 1_000
        rf.results.send(RfScan.Cancelled("lifecycle_stopped"))
        mono += 30_000 // screen off without the service: nothing observed for 30 s
        rf.results.send(RfScan.Success(listOf(net)))
        mono += 500
        rf.results.send(RfScan.Failure("permission_denied", fatal = true))
        withTimeout(2_000) { while (vm.screenState.value.isTracking) kotlinx.coroutines.delay(5) }
        val session = vm.screenState.value.continuousScanSessions.single()
        assertEquals("permission_denied", vm.captureStatus.value.stoppedBecause)
        assertEquals(listOf(ScanWindow.SUCCESS, ScanWindow.CANCELLED, ScanWindow.SUCCESS, ScanWindow.FAILED),
            session.scanWindows!!.map { it.outcome })
        assertTrue("gaps=${session.rfGaps}", session.rfGaps!!.any { it.toMs - it.fromMs >= 30_000 })
    }

    @Test
    fun aLateCallbackOfAnEarlierSessionIsRejected() {
        val vm = trackingViewModel()
        val first = vm.activeRouteDraft("f")!!.sessionId
        vm.stopContinuousTrack(2f, 2f)
        vm.armContinuousStart(); vm.requestContinuousStart(3f, 3f); vm.grantPendingScanAccess()
        vm.recordRoamingAttempt(first, mono, mono + 5, ProbeResult.CONNECTED, null)
        assertNull(vm.screenState.value.continuousScanSessions.single().roaming)
        assertTrue(vm.activeRouteDraft("f")!!.segments.single().probeAttempts.isEmpty())
    }
}
