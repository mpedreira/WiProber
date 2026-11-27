package com.mpedreira.nimwifi

import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rc.3 · 2: a Wi-Fi the technician did not confirm is never used. Expected «Operativa», the
 * check finds no Wi-Fi and «Invitados» appears just before the run.
 */
class UncheckedNetworkTest {
    /** A Wi-Fi network that records every socket, DNS lookup and HTTP connection. */
    private class Recorded(override val id: String, val ssid: String) : MeasurementNetwork {
        val sockets = AtomicInteger(); val dns = AtomicInteger(); val http = AtomicInteger()
        override val selection = "test"
        override val socketFactory: SocketFactory = object : SocketFactory() {
            override fun createSocket(): Socket { sockets.incrementAndGet(); return Socket() }
            override fun createSocket(host: String?, port: Int) = createSocket()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = createSocket()
            override fun createSocket(host: InetAddress?, port: Int) = createSocket()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = createSocket()
        }
        override fun resolve(host: String): List<InetAddress> { dns.incrementAndGet(); return listOf(InetAddress.getLoopbackAddress()) }
        override fun openHttp(url: URL): HttpURLConnection { http.incrementAndGet(); return url.openConnection() as HttpURLConnection }
        override fun bind(socket: DatagramSocket) { sockets.incrementAndGet() }
        override fun link() = NetworkContext()
        override fun association() = Association(true, Association.SOURCE_NETWORK_CALLBACK, ssid, "bb:00:00:00:00:01", -55, 400, 400, 5_180, null)
        override fun isAvailable() = true
        val traffic get() = sockets.get() + dns.get() + http.get()
    }

    /** The phone's Wi-Fi networks; [opens] counts networks opened for a measurement. */
    private class Phone {
        val present = mutableListOf<Recorded>()
        val opens = AtomicInteger()
        fun byId(id: String): Recorded? { opens.incrementAndGet(); return present.firstOrNull { it.id == id } }
        fun hasWifi() = present.isNotEmpty()
        /** What ThroughputProbe.preflight reports: reads the association only, sends nothing. */
        fun preflight(expected: String): NetworkPreflight {
            val chosen = NetworkSelectionPolicy.choose(present.map { NetworkCandidate(it.id.toLong(), false, false, it.ssid) }, expected)
            val network = chosen?.let { c -> present.first { it.id == c.first.id.toString() } }
            val association = network?.association()
            return NetworkPreflight(network?.id, chosen?.second, association?.ssid, association?.bssid, expected,
                NetworkVerification.of(expected, network != null, association), present.map { it.ssid })
        }
    }

    private fun viewModel() = MainViewModel({ 1_000L }, { "id-${n++}" }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).apply {
        loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
        requestStopAndGoScan(10f, 20f); grantPendingScanAccess(); completeStopAndGoScan(emptyList())
    }
    private var n = 0

    /** The probe's executor: open only the checked network, then (only then) traffic. */
    private fun execute(phone: Phone, preflight: NetworkPreflight?): PointThroughput {
        val network = NetworkRevalidation.open(preflight, phone::byId, phone::hasWifi) { }
        network?.let { it.resolve("erp.example.com"); it.socketFactory.createSocket().close() }
        return PointThroughput(1L, emptyList())
    }

    @Test
    fun noWifiAtTheCheckThenInvitadosAppearsSendsNothingUntilItIsChecked() {
        val phone = Phone()
        val step = NetworkCheckFlow.begin { phone.preflight("Operativa") } as NetworkCheckFlow.Step.Run
        assertEquals(NetworkVerification.NO_WIFI, step.preflight!!.verification)

        val invitados = Recorded("200", "Invitados")
        phone.present += invitados // just before the run

        val vm = viewModel()
        val point = vm.screenState.value.scanPoints.single()
        val runId = vm.startActiveRun(point.pointId!!, point.scanId, listOf(PhaseRecord("k", "k")), null, preflight = step.preflight) { _, _ ->
            execute(phone, step.preflight)
        }!!
        val run = vm.screenState.value.activeRuns.single { it.runId == runId }
        assertEquals(RunStatus.SKIPPED, run.status)
        assertEquals(ErrorCodes.NETWORK_CHANGED, run.statusReason)
        assertEquals("no network opened for the measurement", 0, phone.opens.get())
        assertEquals("no socket, DNS or HTTP", 0, invitados.traffic)

        // The new network goes through the check: «Invitados» is not the expected SSID.
        val again = NetworkCheckFlow.begin { phone.preflight("Operativa") }
        assertTrue(again is NetworkCheckFlow.Step.AskDecision)
        assertEquals(0, invitados.traffic)

        // Only after the technician's decision, on that very network.
        val confirmed = NetworkCheckFlow.confirmed(again as NetworkCheckFlow.Step.AskDecision)
        assertEquals("200", confirmed.preflight!!.networkId)
        execute(phone, confirmed.preflight)
        assertEquals(1, invitados.dns.get())
        assertEquals(1, invitados.sockets.get())
        assertEquals(0, invitados.http.get())
    }

    @Test
    fun stillNoWifiRecordsTheReasonAndKeepsThePoint() {
        val phone = Phone()
        val step = NetworkCheckFlow.begin { phone.preflight("Operativa") } as NetworkCheckFlow.Step.Run
        assertEquals(null, NetworkRevalidation.open(step.preflight, phone::byId, phone::hasWifi) { })
        val runner = ThroughputRunner(iperf = Iperf3Client(), http = HttpSpeedTest(), network = null, beacons = emptyMap(),
            device = DeviceInfo("m", "13"), networkTests = null, scriptRunner = null, clock = { 1_000L })
        val test = runner.measure(ThroughputSettings(iperfEnabled = true, iperfHost = "iperf.example.com", iperfPort = 5201)).tests.single()
        assertTrue(test.error!!, test.error!!.contains(ThroughputRunner.NOT_ON_WIFI))
        assertEquals(0, phone.opens.get())
        assertEquals(1, viewModel().screenState.value.scanPoints.size)
    }

    @Test
    fun aCheckThatCouldNotRunIsTreatedAsUnchecked() {
        val phone = Phone().apply { present += Recorded("300", "Operativa") }
        val step = NetworkCheckFlow.begin { error("preflight failed") } as NetworkCheckFlow.Step.Run
        try {
            NetworkRevalidation.open(step.preflight, phone::byId, phone::hasWifi) { }
            throw AssertionError("expected a new check")
        } catch (e: NetworkChangedException) {
            assertEquals(NetworkRevalidation.UNCHECKED, e.state)
        }
        assertEquals(0, phone.opens.get())
        assertEquals(0, phone.present.single().traffic)
    }
}
