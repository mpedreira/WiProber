package com.mpedreira.nimwifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC4 · 3: the three point flows (coverage, coverage + performance, active tests only), repeatable
 * point IDs, the action kept across permission round trips and recreation, point states and the
 * last attempt per service in the point sheet.
 */
class PointFlowsTest {
    private var now = 1_000L
    private var ids = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val phases = listOf(PhaseRecord("target:internet:download", "internet · download"))
    private val corp = WifiNetworkInfo("Corp", "00:00:00:00:00:01", -60, 5_180, "WPA2", listOf("AX"), "")
    private val planned = PlannedPoint("planned-17", "P17", 120f, 80f, batch = "b1")

    private fun viewModel(points: List<ScanPoint> = emptyList(), plannedPoints: List<PlannedPoint> = listOf(planned)) =
        MainViewModel({ now }, { "id-${ids++}" }, scope).apply {
            loadProject(MapInfo("m.png", 1000, 800), 0.1, points, emptyList(), emptyList(), plannedPoints = plannedPoints)
        }

    private fun run(id: String, pointId: String, at: Long, status: String = RunStatus.COMPLETED, reason: String? = null,
                    results: PointThroughput? = null) =
        ActiveRun(id, pointId, null, at, at + 1, status, statusReason = reason, results = results)

    private fun internet(at: Long, down: Double?, error: String? = null, http: Int? = null) = PointThroughput(at, listOf(
        ThroughputTestResult("internet", "http", 5, at, downMbps = down, downloadErrorCode = error,
            downloadProxyConfig = ProxyKind.MANUAL, downloadRoute = HttpRoute.PROXY, downloadHttpStatus = http)))

    @Test
    fun theSelectedActionTravelsWithThePendingRequestAndTheScan() {
        val vm = viewModel()
        assertEquals(PointMeasure.COVERAGE, vm.screenState.value.measureAction)
        vm.setMeasureAction(PointMeasure.COVERAGE_AND_PERFORMANCE)
        assertTrue(vm.requestStopAndGoScan(10f, 20f))
        // Changing the selection while permissions or settings are open does not change this request.
        vm.setMeasureAction(PointMeasure.COVERAGE)
        assertEquals(
            PendingScanRequest.StopAndGo(SurveyCoordinate(10f, 20f), measure = PointMeasure.COVERAGE_AND_PERFORMANCE),
            vm.pendingScanRequest()
        )
        // Recreation keeps the pending request (coordinates, identity and action) in the ViewModel.
        assertNull(vm.recoverAfterActivityRecreation())
        assertTrue(vm.transitionScanAccess(ScanAccessStep.CHECKING, ScanAccessStep.APP_SETTINGS))
        assertTrue(vm.transitionScanAccess(ScanAccessStep.APP_SETTINGS, ScanAccessStep.CHECKING))
        vm.grantPendingScanAccess()
        assertEquals(PointMeasure.COVERAGE_AND_PERFORMANCE, vm.scanningMeasure())
        val point = vm.completeStopAndGoScan(listOf(corp))!!
        assertEquals(10f, point.x)
        assertNull(vm.scanningMeasure())
    }

    @Test
    fun aPlannedPointIsMeasuredAgainWithItsIdAndExactPositionForEachAction() {
        val vm = viewModel()
        assertTrue(vm.requestMeasureAtPoint("planned-17", PointMeasure.COVERAGE))
        vm.grantPendingScanAccess()
        assertEquals(PointMeasure.COVERAGE, vm.scanningMeasure())
        val first = vm.completeStopAndGoScan(listOf(corp))!!
        now = 2_000L
        assertTrue(vm.requestMeasureAtPoint("planned-17", PointMeasure.COVERAGE_AND_PERFORMANCE))
        vm.grantPendingScanAccess()
        assertEquals(PointMeasure.COVERAGE_AND_PERFORMANCE, vm.scanningMeasure())
        val second = vm.completeStopAndGoScan(listOf(corp))!!
        assertEquals(listOf("planned-17", "planned-17"), listOf(first.pointId, second.pointId))
        assertEquals(listOf(120f to 80f, 120f to 80f), listOf(first.x to first.y, second.x to second.y))
        assertTrue(first.scanId != second.scanId)
        // Active tests only: the same identity, no scan.
        val runId = vm.startActiveRun("planned-17", null, phases, "device-settings") { _, _ -> internet(now, 90.0) }
        assertEquals("planned-17", vm.screenState.value.activeRuns.single { it.runId == runId }.pointId)
    }

    @Test
    fun theSelectedActionIsKeptWhenAnotherFloorLoads() {
        val vm = viewModel()
        vm.setMeasureAction(PointMeasure.COVERAGE_AND_PERFORMANCE)
        vm.loadProject(MapInfo("other.png", 500, 500), null, emptyList(), emptyList(), emptyList())
        assertEquals(PointMeasure.COVERAGE_AND_PERFORMANCE, vm.screenState.value.measureAction)
    }

    @Test
    fun pointStatesArePendingCoveragePerformanceOrFailure() {
        assertEquals(PointStatus.PENDING, PointStatus.of(false, emptyList()))
        assertEquals(PointStatus.COVERAGE, PointStatus.of(true, emptyList()))
        assertEquals(PointStatus.PERFORMANCE, PointStatus.of(true, listOf(run("r1", "p", 1, results = internet(1, 90.0)))))
        assertEquals(PointStatus.FAILURE, PointStatus.of(true, listOf(
            run("r1", "p", 1, results = internet(1, 90.0)),
            run("r2", "p", 2, results = internet(2, null, ErrorCodes.PROXY_AUTH_REQUIRED, 407)))))
        assertEquals(PointStatus.FAILURE, PointStatus.of(false, listOf(run("r1", "p", 1, RunStatus.SKIPPED, ErrorCodes.NETWORK_CHANGED))))
        // A run the user skipped or cancelled says nothing: the one before decides.
        assertEquals(PointStatus.PERFORMANCE, PointStatus.of(true, listOf(
            run("r1", "p", 1, results = internet(1, 90.0)),
            run("r2", "p", 2, RunStatus.SKIPPED, ErrorCodes.SKIPPED_BY_USER),
            run("r3", "p", 3, RunStatus.CANCELLED))))

        val state = SurveyScreenState(
            mapInfo = MapInfo("m.png", 100, 100),
            scanPoints = listOf(ScanPoint(1L, 1f, 1f, listOf(corp), pointId = "p1", scanId = "s1"),
                ScanPoint(1L, 9f, 9f, emptyList(), importedFromEsx = true, pointId = "imported", scanId = "s2")),
            plannedPoints = listOf(planned),
            activeRuns = listOf(run("r1", "planned-17", 5, results = internet(5, 90.0)))
        )
        assertEquals(mapOf("p1" to PointStatus.COVERAGE, "imported" to PointStatus.PENDING, "planned-17" to PointStatus.PERFORMANCE),
            PointStatus.all(state))
    }

    @Test
    fun theSheetShowsTheLastAttemptPerServiceAndTheLastValidOneWithTheirDates() {
        val state = SurveyScreenState(
            mapInfo = MapInfo("m.png", 100, 100),
            scanPoints = listOf(
                ScanPoint(1L, 1f, 1f, listOf(corp, corp.copy(bssid = "00:00:00:00:00:02", level = -50, frequency = 2_412)), pointId = "p", scanId = "s1")
            ),
            activeRuns = listOf(
                run("r1", "p", 10, results = internet(10, 95.0).copy(networkTests = listOf(
                    NetworkTestResult("app", "http", "app.example.com", "GET", 10, ok = true, totalMs = 120.0, proxyConfig = ProxyKind.NONE, route = HttpRoute.DIRECT)))),
                run("r2", "p", 20, results = internet(20, null, ErrorCodes.RATE_LIMITED, 429).let {
                    it.copy(tests = it.tests.map { t -> t.copy(downloadRetryAt = 99_000L) }, networkTests = listOf(
                        NetworkTestResult("app", "http", "app.example.com", "GET", 20, ok = false, status = 503,
                            errorCode = ErrorCodes.HTTP_STATUS, proxyConfig = ProxyKind.NONE, route = HttpRoute.DIRECT)))
                })
            )
        )
        // Map network = SSID + band: only the 5 GHz radio of Corp counts as its signal.
        val details = PointDetails.of(state, "p", "Corp", WifiBands.B5)!!
        assertEquals(-60, details.latestScan!!.strongestRssi)
        assertEquals(PointStatus.FAILURE, details.status)

        val internet = details.services.single { it.last.key == "target:internet" }
        assertEquals(false, internet.last.ok)
        assertEquals(ErrorCodes.RATE_LIMITED, internet.last.reason)
        assertEquals(429, internet.last.httpStatus)
        assertEquals(HttpRoute.PROXY, internet.last.route)
        assertEquals(ProxyKind.MANUAL, internet.last.proxyConfig)
        assertEquals(99_000L, internet.last.retryAt)
        assertEquals(20L, internet.last.at)
        assertEquals(10L, internet.lastValid!!.at)
        assertTrue(internet.lastValid!!.value!!.startsWith("↓ 95.0 Mbps"))

        val app = details.services.single { it.last.key == "test:app" }
        assertEquals(ErrorCodes.HTTP_STATUS, app.last.reason)
        assertEquals(503, app.last.httpStatus)
        assertEquals(HttpRoute.DIRECT, app.last.route)
        assertEquals("120.0 ms", app.lastValid!!.value)

        // A passing last attempt has no "last valid" line.
        val ok = PointDetails.of(state.copy(activeRuns = state.activeRuns.take(1)), "p", "Corp")!!
        assertTrue(ok.services.all { it.last.ok && it.lastValid == null })
        assertEquals(PointStatus.PERFORMANCE, ok.status)
    }
}
