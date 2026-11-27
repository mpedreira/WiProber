package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 19: two floors, two SSIDs; a reader finds the weak zone, the failed destination and the AP change. */
class OperationsReportTest {
    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", emptyList(), "")

    @Test
    fun weakZonesFailedDestinationsAndApChangesAreReadable() {
        val t0 = 1_700_000_000_000L
        val route = ContinuousScanSession("t", t0, t0 + 10_000,
            waypoints = listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(10_000, 100f, 0f)), scanResults = emptyList(),
            roaming = RoamingTracker("gw:443", 200).apply {
                // Two events in the same second: their order and milliseconds are kept.
                add(ProbeAttempt(1_000, 1_005, ProbeResult.CONNECTED, "Ops", "aa:01", -70, true))
                add(ProbeAttempt(1_200, 1_205, ProbeResult.CONNECTED, "Ops", "aa:02", -60, true))
                add(ProbeAttempt(1_400, 1_405, ProbeResult.CONNECTED, "Ops", "aa:03", -58, true))
            }.finish())
        val ground = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1,
            listOf(ScanPoint(1L, 10f, 10f, listOf(net("Ops", "aa:01", -79), net("Guest", "bb:01", -40)), pointId = "p1", scanId = "s1")),
            listOf(route), emptyList(),
            activeRuns = listOf(ActiveRun("r1", "p1", "s1", t0, t0 + 1, RunStatus.COMPLETED, results = PointThroughput(t0,
                listOf(ThroughputTestResult("corporate", ThroughputTestResult.METHOD_IPERF3_TCP, 5, t0, targetId = TargetIds.IPERF,
                    category = TargetCategory.PRIVATE_IPERF, ssid = "Ops", downloadEndpointId = "iperf.example.com:5201", downMbps = 120.0, downloadStatus = RunStatus.COMPLETED)),
                listOf(NetworkTestResult("erp", NetworkTest.TCP, "erp.example.com:1521", "tcp", t0, ok = false, alias = "ERP", ssid = "Ops", errorCode = ErrorCodes.TIMEOUT))))))
        val first = ProjectSnapshot(MapInfo("f.png", 100, 100), 0.1,
            listOf(ScanPoint(2L, 50f, 50f, listOf(net("Guest", "bb:02", -50)), pointId = "p2", scanId = "s2")), emptyList(), emptyList())
        val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(
            FloorExportSnapshot("g", "Baja", File("g.png"), "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, ground),
            FloorExportSnapshot("f", "Primera", File("f.png"), "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444", false, null, first)))
        val html = SurveyReport.html(SurveyReport.build(snapshot, t0, "Ops", weakDbm = -67))

        assertTrue("lang=\"es\"" in html)
        assertTrue("Planta: Baja" in html && "Planta: Primera" in html)
        assertTrue("SSID «Ops» · Baja" in html && "SSID «Guest» · Baja" in html)
        // The weak zone, with value and threshold.
        assertTrue("Peores puntos" in html && "Señal débil medida (umbral -67 dBm)" in html && ">-79<" in html)
        // Destinations by kind, alias, unit, method and samples.
        assertTrue("iPerf3 privado" in html && "Servicios internos" in html)
        assertTrue("ERP" in html && "fallidas 1" in html && "Mbit/s" in html && "iperf3-tcp" in html)
        // AP changes with milliseconds, in order, with observed window and association.
        val first2 = html.indexOf("2023-11-14 22:13:21.200 UTC")
        val second2 = html.indexOf("2023-11-14 22:13:21.400 UTC")
        assertTrue("both events present: $first2 $second2", first2 > 0 && second2 > first2)
        assertTrue("Ventana observada" in html && "Ops · aa:02" in html)
        assertTrue("Caída hacia un servicio" in html && "Degradación de red" in html)
    }
}
