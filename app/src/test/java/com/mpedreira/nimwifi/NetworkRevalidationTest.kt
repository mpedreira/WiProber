package com.mpedreira.nimwifi

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** rc.2 · 3: the run measures on the very Wi-Fi the technician approved, or sends nothing. */
class NetworkRevalidationTest {
    private val operativaAp = Association(true, Association.SOURCE_NETWORK_CALLBACK, "Operativa", "aa:00:00:00:00:01", -58, 400, 400, 5_180, null)

    private fun preflight(id: String, ssid: String) = NetworkPreflight(id, NetworkSelectionPolicy.EXPECTED_SSID, ssid, "aa:00:00:00:00:01",
        "Operativa", NetworkVerification.MATCHED, listOf(ssid))

    private fun network(id: String, ssid: String, bssid: String = "aa:00:00:00:00:01") =
        FakeMeasurementNetwork(id = id, associationProvider = { operativaAp.copy(ssid = ssid, bssid = bssid) })

    @Test
    fun onlyInvitadosLeftBeforeStartSendsNothingUntilANewDecision() {
        val approved = preflight("100", "Operativa")
        val invitados = network("200", "Invitados")
        val selected = AtomicInteger()
        try {
            NetworkRevalidation.open(approved, byId = { id -> invitados.takeIf { it.id == id } }, wifiPresent = { selected.incrementAndGet(); true }) { }
            fail("expected the start to stop")
        } catch (e: NetworkChangedException) {
            assertEquals(NetworkRevalidation.GONE, e.state)
            assertEquals(ErrorCodes.NETWORK_CHANGED, e.code)
        }
        assertEquals("no fresh choice of another Wi-Fi", 0, selected.get())
        assertTrue("nothing resolved or sent on Invitados", invitados.resolved.isEmpty())

        // Through the ViewModel: the run ends skipped with the reason, no phase ever started.
        val vm = MainViewModel({ 1_000L }, { "id-${it()}" }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).apply {
            loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
            requestStopAndGoScan(10f, 20f); grantPendingScanAccess(); completeStopAndGoScan(emptyList())
        }
        val point = vm.screenState.value.scanPoints.single()
        val traffic = AtomicInteger()
        val runId = vm.startActiveRun(point.pointId!!, point.scanId, listOf(PhaseRecord("target:iperf3-private:download", "download")), null,
            preflight = approved) { _, _ ->
            NetworkRevalidation.open(approved, byId = { null }, wifiPresent = { true }) { }
            traffic.incrementAndGet()
            PointThroughput(1L, emptyList())
        }!!
        val run = vm.screenState.value.activeRuns.single { it.runId == runId }
        assertEquals(RunStatus.SKIPPED, run.status)
        assertEquals(ErrorCodes.NETWORK_CHANGED, run.statusReason)
        assertEquals(0, traffic.get())
    }

    @Test
    fun theSameHandleNowOnAnotherSsidOrAnotherHandleAlsoStops() {
        val approved = preflight("100", "Operativa")
        assertEquals(NetworkRevalidation.SSID_CHANGED, NetworkRevalidation.check(approved, network("100", "Invitados"), network("100", "Invitados").association()))
        assertEquals(NetworkRevalidation.REPLACED, NetworkRevalidation.check(approved, network("300", "Operativa"), operativaAp))
        val closed = AtomicInteger()
        try {
            NetworkRevalidation.open(approved, byId = { network("100", "Invitados") }, wifiPresent = { false }) { closed.incrementAndGet() }
            fail()
        } catch (e: NetworkChangedException) {
            assertEquals(NetworkRevalidation.SSID_CHANGED, e.state)
        }
        assertEquals("the looked-up network is closed", 1, closed.get())
    }

    @Test
    fun anApChangeInsideTheSameNetworkIsMeasuredAndRecordedAsSuch() {
        val approved = preflight("100", "Operativa")
        // Revalidation and the start of the test see aa:01; the phone roams to aa:02 during the test.
        val aps = ArrayDeque(listOf(operativaAp, operativaAp, operativaAp.copy(bssid = "aa:00:00:00:00:02")))
        val bound = FakeMeasurementNetwork(id = "100", associationProvider = { if (aps.size > 1) aps.removeFirst() else aps.first() })
        val opened = NetworkRevalidation.open(approved, byId = { bound }, wifiPresent = { false }) { }
        assertTrue(opened === bound)
        val closedPort = ServerSocket(0).use { it.localPort }
        val runner = ThroughputRunner(iperf = Iperf3Client(), http = HttpSpeedTest(), network = bound, beacons = emptyMap(),
            device = DeviceInfo("m", "13"), networkTests = null, scriptRunner = null, clock = { 1_000L })
        val test = runner.measure(ThroughputSettings(iperfEnabled = true, iperfHost = "127.0.0.1", iperfPort = closedPort, iperfDurationSeconds = 1)).tests.single()
        assertEquals("Operativa", test.ssid)
        assertEquals(AssociationChange.BSSID_CHANGED, test.associationChange)
    }

    @Test
    fun aLocalWifiWithoutInternetWhileMobileDataIsDefaultIsKept() {
        // Mobile data is the default network and is not a candidate; the Wi-Fi has no Internet.
        val wifi = NetworkCandidate(id = 100, isDefault = false, validated = false, ssid = "Operativa")
        assertEquals(100L, NetworkSelectionPolicy.choose(listOf(wifi), "Operativa")!!.first.id)
        val local = network("100", "Operativa")
        assertTrue(NetworkRevalidation.open(preflight("100", "Operativa"), byId = { local }, wifiPresent = { false }) { } === local)
        // rc.3 · 2: without a checked network that Wi-Fi is not used silently: it is checked first.
        try { NetworkRevalidation.open(null, byId = { null }, wifiPresent = { true }) { }; fail() } catch (e: NetworkChangedException) {
            assertEquals(NetworkRevalidation.UNCHECKED, e.state)
        }
        // No Wi-Fi at all: nothing is opened (and never mobile data).
        assertNull(NetworkRevalidation.open(null, byId = { null }, wifiPresent = { false }) { })
    }

    @Test
    fun theExportedRunCarriesTheNetworkItWasBoundTo() {
        val vm = MainViewModel({ 1_000L }, { "id-${it()}" }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).apply {
            loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
            requestStopAndGoScan(10f, 20f); grantPendingScanAccess(); completeStopAndGoScan(emptyList())
        }
        val point = vm.screenState.value.scanPoints.single()
        val preflight = preflight("100", "Operativa").copy(verification = NetworkVerification.UNKNOWN, observedSsid = null)
        var seen: ActiveRun? = null
        val runId = vm.startActiveRun(point.pointId!!, point.scanId, listOf(PhaseRecord("k", "k")), null, preflight = preflight,
            networkDecision = "confirmed") { _, listener ->
            listener.onNetwork("100", "Operativa", NetworkVerification.MATCHED)
            seen = vm.screenState.value.activeRuns.single()
            PointThroughput(1L, emptyList())
        }!!
        val run = vm.screenState.value.activeRuns.single { it.runId == runId }
        assertEquals("100", run.networkId)
        assertEquals("Operativa", run.observedSsid)
        assertEquals(NetworkVerification.MATCHED, run.networkVerification)
        assertEquals("the decision taken is kept", "confirmed", run.networkDecision)
        assertFalse(seen == null)
    }

    private var n = 0
    private fun it() = n++
}
