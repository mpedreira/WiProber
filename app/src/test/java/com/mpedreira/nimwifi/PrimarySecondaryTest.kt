package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 11: primary coverage, redundancy for roaming and what the guide counts as measured. */
class PrimarySecondaryTest {
    private fun net(ssid: String, bssid: String, level: Int) = WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", emptyList(), "")

    private val oneAp = ScanPoint(1L, 10f, 10f, listOf(net("Ops", "aa:01", -55)), pointId = "one", scanId = "s1")
    private val twoAps = ScanPoint(2L, 20f, 10f, listOf(net("Ops", "aa:01", -60), net("Ops", "aa:02", -70), net("Ops", "aa:02", -75)), pointId = "two", scanId = "s2")
    private val guestOnly = ScanPoint(3L, 30f, 10f, listOf(net("Guest", "bb:01", -35), net("Guest", "bb:02", -40)), pointId = "guest", scanId = "s3")
    private val importedWaypoint = ScanPoint(4L, 40f, 10f, emptyList(), importedFromEsx = true, pointId = "imp", scanId = "s4")

    @Test
    fun primaryAndSecondaryUseDistinctBssidsOfTheSameSsid() {
        val run = ActiveRun("r", "two", "s2", 5L, 6L, RunStatus.COMPLETED, results = PointThroughput(5L, listOf(
            ThroughputTestResult("corporate", "iperf3-tcp", 5, 5L, ssid = "Ops", bssidStart = "aa:02", rssi = -71,
                associationChange = AssociationChange.BSSID_CHANGED))))
        val points = SsidCoverage.points(listOf(oneAp, twoAps, guestOnly, importedWaypoint), emptyList(), "Ops", -67, -72, listOf(run))
            .associateBy { it.pointId }

        val one = points.getValue("one")
        assertEquals(PointState.OK, one.state)
        assertNull(one.secondBssid)
        assertFalse(one.secondaryOk) // good signal, but no second AP: no redundancy evidence

        val two = points.getValue("two")
        assertEquals(PointState.OK, two.state) // -60 ≥ -67
        assertEquals("aa:02", two.secondBssid)
        assertEquals(-70, two.secondRssi) // the strongest reading of that BSSID, never the same BSSID twice
        assertTrue(two.secondaryOk) // -70 ≥ -72
        assertEquals("aa:02", two.associatedBssid)
        assertEquals(-71, two.associatedRssi)
        assertEquals(1, two.apChanges)

        // A strong guest network never gives the operational SSID coverage or redundancy.
        val guest = points.getValue("guest")
        assertEquals(PointState.SSID_NOT_DETECTED, guest.state)
        assertFalse(guest.secondaryOk)
        // Imported geometry without local RF: no evidence.
        assertEquals(PointState.NOT_MEASURED, points.getValue("imp").state)
    }

    @Test
    fun theGuideCountsOnlyLocalRfAsMeasured() {
        val route = ContinuousScanSession("t", 1_000L, 2_000L,
            waypoints = listOf(RoutePointWrapper(0, 0f, 0f), RoutePointWrapper(1_000, 100f, 0f)),
            scanResults = listOf(ScanResultWrapper(500, 100, listOf(net("Ops", "aa:01", -60)))))
        val waypointsOnly = ContinuousScanSession("w", 3_000L, 4_000L,
            waypoints = listOf(RoutePointWrapper(0, 500f, 500f), RoutePointWrapper(1_000, 600f, 500f)), scanResults = emptyList())
        val measured = SurveyGuide.measurementOrder(listOf(oneAp, importedWaypoint), listOf(route, waypointsOnly))
        assertEquals(listOf(SurveyCoordinate(10f, 10f), SurveyCoordinate(50f, 0f)), measured)
        val samples = SurveyGuide.coverageSamples(listOf(oneAp, importedWaypoint), listOf(route), "Ops")
        assertEquals(2, samples.size)
        assertTrue(samples.none { it.x == 40f })
    }
}
