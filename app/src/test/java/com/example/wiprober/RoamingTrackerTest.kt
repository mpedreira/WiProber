package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attempts carry explicit (injected) monotonic times relative to the session start. */
class RoamingTrackerTest {
    private val a = "aa:aa:aa:aa:aa:01"
    private val b = "aa:aa:aa:aa:aa:02"
    private val c = "aa:aa:aa:aa:aa:03"

    private fun attempt(
        at: Long,
        result: String = ProbeResult.CONNECTED,
        bssid: String? = a,
        ssid: String? = "Operativa",
        rssi: Int? = -60,
        rtt: Long = 5
    ) = ProbeAttempt(at, at + rtt, result, ssid, bssid, rssi, associationKnown = bssid != null)

    private fun track(interval: Int = 200, scans: List<LongRange> = emptyList(), vararg attempts: ProbeAttempt): RoamingLog =
        RoamingTracker("gw:443", interval).apply { attempts.forEach(::add) }.finish(scans)

    @Test
    fun cleanRoamHasNoObservedOutageAndBoundedResolution() {
        val log = track(200, emptyList(),
            attempt(0, rssi = -60), attempt(200, rssi = -72), attempt(400, bssid = b, rssi = -58))

        val roam = log.events.single()
        assertEquals(RoamingEvent.BSSID_CHANGE, roam.type)
        assertEquals(400L, roam.atMillis)
        assertEquals(a, roam.fromBssid)
        assertEquals(b, roam.toBssid)
        assertEquals(-72, roam.fromRssi)
        assertEquals(-58, roam.toRssi)
        assertEquals(false, roam.outageObserved)
        assertEquals(0, roam.failedAttempts)
        assertEquals(0L, roam.outageLowerBoundMs)
        // Between the end of the last reachable attempt (205) and the start of the next (400).
        assertEquals(195L, roam.outageUpperBoundMs)
        assertEquals(false, roam.open)
        assertEquals(false, roam.samplingGap)
        assertEquals(2, log.schema)
        assertEquals(RoamingTracker.TIME_BASE, log.timeBase)
        assertEquals(200L, log.observedMedianIntervalMs)
        assertEquals(5.0, log.avgRttMs)
    }

    @Test
    fun bssidChangeAfterASchedulerDelayIsNotAnOutage() {
        val log = track(200, emptyList(), attempt(0), attempt(200), attempt(5_200, bssid = b))

        val roam = log.events.single()
        assertEquals(RoamingEvent.BSSID_CHANGE, roam.type)
        assertEquals(false, roam.outageObserved)
        assertEquals(true, roam.samplingGap)
        // Nothing observed in the gap: no lower bound, only the resolution of the estimate.
        assertNull(roam.outageLowerBoundMs)
        assertEquals(4_995L, roam.outageUpperBoundMs)
        assertEquals(1, log.samplingGaps)
        assertEquals(5_000L, log.observedMaxIntervalMs)
        assertEquals(0, log.lost)
    }

    @Test
    fun documentedFailureWithRecoveryIsAReachabilityLoss() {
        val log = track(200, emptyList(),
            attempt(0),
            attempt(200, ProbeResult.TIMEOUT, rtt = 150),
            attempt(400, ProbeResult.TIMEOUT, rtt = 150),
            attempt(600, ProbeResult.UNREACHABLE, rtt = 1),
            attempt(800))

        val loss = log.events.single()
        assertEquals(RoamingEvent.REACHABILITY_LOSS, loss.type)
        assertEquals(200L, loss.atMillis)
        assertEquals(3, loss.failedAttempts)
        assertEquals(true, loss.outageObserved)
        assertEquals(401L, loss.outageLowerBoundMs) // 200 .. 601
        assertEquals(795L, loss.outageUpperBoundMs) // 5 .. 800
        assertEquals(5L, loss.lastReachableAtMillis)
        assertEquals(800L, loss.firstReachableAtMillis)
        assertEquals(false, loss.open)
        assertEquals(3, log.lost)
        assertEquals(2, log.timeouts)
        assertEquals(1, log.unreachable)
    }

    @Test
    fun isolatedFailuresRefusalsAndResolutionFailuresAreNotOutages() {
        val log = track(200, emptyList(),
            attempt(0),
            attempt(200, ProbeResult.TIMEOUT),
            attempt(400, ProbeResult.REFUSED),
            attempt(600, ProbeResult.RESOLUTION_FAILED, bssid = a),
            attempt(800, ProbeResult.RESOLUTION_FAILED, bssid = a),
            attempt(1_000, ProbeResult.RESOLUTION_FAILED, bssid = a),
            attempt(1_200, ProbeResult.INVALID_TARGET),
            attempt(1_400))

        assertTrue(log.events.isEmpty())
        assertEquals(2, log.connected)
        // Refused: the target answered, but the port is not accessible.
        assertEquals(1, log.refused)
        assertEquals(3, log.resolutionFailures)
        assertEquals(1, log.invalidTarget)
        assertEquals(5.0, log.avgRttMs) // only connected attempts
    }

    @Test
    fun ssidChangeIsDistinguishedFromABssidChange() {
        val log = track(200, emptyList(),
            attempt(0, ssid = "Operativa"),
            attempt(200, ssid = "Invitados", bssid = b))

        val change = log.events.single()
        assertEquals(RoamingEvent.SSID_CHANGE, change.type)
        assertEquals("Operativa", change.fromSsid)
        assertEquals("Invitados", change.toSsid)
    }

    @Test
    fun successiveRoamsWithFailuresInBetween() {
        val log = track(200, emptyList(),
            attempt(0),
            attempt(200, ProbeResult.TIMEOUT),
            attempt(400, ProbeResult.TIMEOUT, bssid = b),
            attempt(600, ProbeResult.TIMEOUT, bssid = b),
            attempt(800, bssid = b),
            attempt(1_000, bssid = c),
            attempt(1_200, bssid = c))

        assertEquals(listOf(RoamingEvent.BSSID_CHANGE, RoamingEvent.BSSID_CHANGE), log.events.map { it.type })
        val first = log.events[0]
        assertEquals(400L, first.atMillis)
        assertEquals(3, first.failedAttempts)
        assertEquals(true, first.outageObserved)
        assertEquals(405L, first.outageLowerBoundMs) // 200 .. 605
        val second = log.events[1]
        assertEquals(b, second.fromBssid)
        assertEquals(c, second.toBssid)
        assertEquals(false, second.outageObserved)
        // Failures explained by the first roam are not reported again as a reachability loss.
        assertFalse(log.events.any { it.type == RoamingEvent.REACHABILITY_LOSS })
    }

    @Test
    fun unrecoveredCutIsAnOpenEventWithoutUpperBound() {
        val log = track(200, emptyList(),
            attempt(0),
            attempt(200, ProbeResult.NO_WIFI, bssid = null, ssid = null),
            attempt(400, ProbeResult.NO_WIFI, bssid = null, ssid = null))

        val lost = log.events.single()
        assertEquals(RoamingEvent.ASSOCIATION_LOST, lost.type)
        assertEquals(200L, lost.atMillis)
        assertEquals(true, lost.open)
        assertNull(lost.outageUpperBoundMs)
        assertNull(lost.toBssid)
        assertEquals(2, lost.failedAttempts)
        assertEquals(2, log.noWifi)
    }

    @Test
    fun unrecoveredReachabilityLossStaysOpen() {
        val log = track(200, emptyList(),
            attempt(0),
            attempt(200, ProbeResult.TIMEOUT),
            attempt(400, ProbeResult.TIMEOUT),
            attempt(600, ProbeResult.TIMEOUT))

        val loss = log.events.single()
        assertEquals(RoamingEvent.REACHABILITY_LOSS, loss.type)
        assertEquals(true, loss.open)
        assertNull(loss.firstReachableAtMillis)
        assertNull(loss.outageUpperBoundMs)
    }

    @Test
    fun associationRecoveredOnAnotherApAfterLoss() {
        val log = track(200, emptyList(),
            attempt(0),
            attempt(200, ProbeResult.NO_WIFI, bssid = null, ssid = null),
            attempt(400, bssid = b))

        val lost = log.events.single()
        assertEquals(RoamingEvent.ASSOCIATION_LOST, lost.type)
        assertEquals(false, lost.open)
        assertEquals(a, lost.fromBssid)
        assertEquals(b, lost.toBssid)
    }

    @Test
    fun aWholeScanInsideOneProbeIntervalIsReportedAsOverlap() {
        val inside = track(1_000, listOf(300L..700L), attempt(0), attempt(1_000, bssid = b))
        assertEquals(true, inside.events.single().scanOverlap)

        val outside = track(1_000, listOf(1_500L..1_900L), attempt(0), attempt(1_000, bssid = b), attempt(2_000, bssid = b))
        assertEquals(false, outside.events.single().scanOverlap)
    }

    @Test
    fun outOfOrderAndInvalidAttemptsAreIgnored() {
        val tracker = RoamingTracker("gw:443", 200)
        tracker.add(attempt(400))
        tracker.add(attempt(200, bssid = b))
        tracker.add(ProbeAttempt(-5, 0, ProbeResult.CONNECTED))
        tracker.add(ProbeAttempt(600, 500, ProbeResult.CONNECTED))
        val log = tracker.finish()
        assertEquals(1, log.probes)
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun placeholdersAreEvaluatedIndependently() {
        val dns = java.net.InetAddress.getByName("192.0.2.53")
        val gatewayOnly = FakeMeasurementNetwork(link = NetworkContext(gateway = "192.0.2.1"))
        assertEquals("192.0.2.1", expandRoamingTarget("\${gateway}", gatewayOnly))
        // ${dns} is not needed, so a missing DNS server does not invalidate the target.
        assertNull(expandRoamingTarget("\${dns}", gatewayOnly))

        val dnsOnly = FakeMeasurementNetwork(link = NetworkContext(dnsServers = listOf(dns)))
        assertEquals("192.0.2.53", expandRoamingTarget("\${dns}", dnsOnly))
        assertNull(expandRoamingTarget("\${gateway}", dnsOnly))

        assertEquals("gw.example.com", expandRoamingTarget("gw.example.com", FakeMeasurementNetwork()))
        assertNull(expandRoamingTarget("\${unknown}", gatewayOnly))
    }
}
