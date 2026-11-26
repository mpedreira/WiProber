package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamingTrackerTest {
    private val a = "aa:aa:aa:aa:aa:01"
    private val b = "aa:aa:aa:aa:aa:02"

    @Test
    fun seamlessRoamHasZeroOutage() {
        val tracker = RoamingTracker("gw:443", 200)
        tracker.add(0, a, -60, ok = true, rttMs = 2.0, scanning = false)
        tracker.add(200, a, -72, ok = true, rttMs = 4.0, scanning = false)
        tracker.add(400, b, -58, ok = true, rttMs = 3.0, scanning = false)

        val log = tracker.finish()
        val roam = log.events.single()
        assertEquals(RoamingEvent.ROAM, roam.type)
        assertEquals(400, roam.atMillis)
        assertEquals(a, roam.fromBssid)
        assertEquals(b, roam.toBssid)
        assertEquals(-72, roam.fromRssi)
        assertEquals(-58, roam.toRssi)
        assertEquals(0L, roam.outageMillis)
        assertEquals(0, roam.lostProbes)
        assertEquals(3, log.probes)
        assertEquals(3.0, log.avgRttMs)
    }

    @Test
    fun roamWithLostProbesMeasuresTheGap() {
        val tracker = RoamingTracker("gw:443", 200)
        tracker.add(0, a, -70, ok = true, rttMs = 2.0, scanning = false)
        tracker.add(200, a, -80, ok = false, rttMs = null, scanning = false)
        tracker.add(400, b, -60, ok = false, rttMs = null, scanning = false)
        tracker.add(600, b, -60, ok = false, rttMs = null, scanning = false)
        tracker.add(800, b, -59, ok = true, rttMs = 3.0, scanning = false)

        val roam = tracker.finish().events.single()
        assertEquals(400, roam.atMillis)
        assertEquals(600L, roam.outageMillis) // last ok 0 → first ok 800, minus one 200 ms interval
        assertEquals(3, roam.lostProbes)
    }

    @Test
    fun failuresWithoutRoamBecomeAnOutageFlaggedWhenAScanRan() {
        val tracker = RoamingTracker("gw:443", 200)
        tracker.add(0, a, -70, ok = true, rttMs = 2.0, scanning = false)
        tracker.add(200, a, -71, ok = false, rttMs = null, scanning = true)
        tracker.add(400, a, -71, ok = false, rttMs = null, scanning = true)
        tracker.add(600, a, -71, ok = false, rttMs = null, scanning = false)
        tracker.add(800, a, -70, ok = true, rttMs = 2.0, scanning = false)
        tracker.add(1000, a, -70, ok = false, rttMs = null, scanning = false) // a single loss is not an outage
        tracker.add(1200, a, -70, ok = true, rttMs = 2.0, scanning = false)

        val log = tracker.finish()
        val outage = log.events.single()
        assertEquals(RoamingEvent.OUTAGE, outage.type)
        assertEquals(200, outage.atMillis)
        assertEquals(600L, outage.outageMillis)
        assertEquals(3, outage.lostProbes)
        assertTrue(outage.duringScan)
        assertEquals(4, log.lost)
    }

    @Test
    fun unrecoveredEventsAtTheEndHaveNoDuration() {
        val tracker = RoamingTracker("gw:443", 200)
        tracker.add(0, a, -70, ok = true, rttMs = 2.0, scanning = false)
        tracker.add(200, b, -75, ok = false, rttMs = null, scanning = false)

        val roam = tracker.finish().events.single()
        assertEquals(RoamingEvent.ROAM, roam.type)
        assertNull(roam.outageMillis)
    }

    @Test
    fun theViewModelAttachesTheLogToTheTrack() {
        var now = 1_000L
        var ids = 0
        val vm = MainViewModel({ now }, { "id-${ids++}" })
        vm.loadProject(MapInfo("m.png", 100, 100), 0.1, emptyList(), emptyList(), emptyList())
        vm.setScanMode(SurveyScanMode.CONTINUOUS)
        assertTrue(!vm.startRoamingLog("gw:443", 200)) // not tracking yet
        vm.armContinuousStart()
        vm.requestContinuousStart(0f, 0f)
        vm.grantPendingScanAccess()
        assertTrue(vm.startRoamingLog("gw:443", 200))

        vm.recordRoamingSample(1_100, a, -60, true, 2.0, false)
        vm.recordRoamingSample(1_300, b, -55, true, 2.0, false)
        now = 2_000L
        val session = vm.stopContinuousTrack(10f, 0f)!!

        val roam = session.roaming!!.events.single()
        assertEquals(300, roam.atMillis) // relative to the track start
        assertEquals("gw:443", session.roaming!!.target)
    }
}
