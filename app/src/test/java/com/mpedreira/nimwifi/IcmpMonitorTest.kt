package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Test

/** Continuous service monitor by ICMP (request during the field work), next to TCP. */
class IcmpMonitorTest {
    @Test
    fun icmpIsAConfigurableMethodWithASaferMinimumInterval() {
        val config = WiproberConfigParser.parse(
            """{"format": "wiprober-config", "version": 1, "roaming": {"enabled": true, "host": "10.0.0.5", "method": "ICMP", "intervalMs": 100, "alias": "ERP"}}""",
            "c.json"
        ).roaming!!
        assertEquals(RoamingSettings.METHOD_ICMP, config.method)
        assertEquals(RoamingSettings.MIN_ICMP_INTERVAL_MILLIS, config.intervalMillis)
        assertEquals("ERP", config.alias)
        assertEquals(RoamingSettings.METHOD_TCP, RoamingSettings(method = "whatever").sanitized().method)
        assertEquals(100, RoamingSettings(intervalMillis = 100).sanitized().intervalMillis)
        val again = WiproberConfigParser.parse(ConfigExport.toJson(WiproberConfigStore.Imported(roaming = config), ThroughputSettings(), SurveyGuideSettings(), emptySet()), "c.json")
        assertEquals(config, again.roaming)
    }

    @Test
    fun echoRepliesAreReachableAndCountInTheProbeDenominator() {
        val log = RoamingTracker("10.0.0.5", 1_000, "ERP", method = RoamingTracker.METHOD_ICMP).apply {
            add(ProbeAttempt(0, 3, ProbeResult.REPLIED, "Ops", "aa:01", -60, true, rttMs = 1.2))
            add(ProbeAttempt(1_000, 2_000, ProbeResult.TIMEOUT, "Ops", "aa:01", -70, true))
            add(ProbeAttempt(2_000, 2_004, ProbeResult.REPLIED, "Ops", "aa:02", -58, true, rttMs = 1.8))
        }.finish()
        assertEquals(RoamingTracker.METHOD_ICMP, log.method)
        assertEquals(3, log.tcpAttempts)
        assertEquals(33.3, log.tcpLossPct!!, 0.0)
        // rc.2 · 4: the RTT is the echo's `time=`; the attempts' durations are reported apart.
        assertEquals(1.5, log.avgRttMs)
        assertEquals(3.5, log.avgAttemptMs)
        assertEquals(RoamingEvent.BSSID_CHANGE, log.events.single().type)
    }
}
