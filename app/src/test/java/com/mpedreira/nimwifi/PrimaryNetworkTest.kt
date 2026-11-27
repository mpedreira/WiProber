package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The map is coloured by one primary network: SSID + band. */
class PrimaryNetworkTest {
    private fun net(ssid: String, bssid: String, level: Int, mhz: Int) = WifiNetworkInfo(ssid, bssid, level, mhz, "WPA2", emptyList(), "")

    // a: Ops strong on 2.4, weak on 5 · b: Ops only on 2.4 · Guest strong on 5 everywhere.
    private val points = listOf(
        ScanPoint(1L, 10f, 10f, listOf(net("Ops", "aa:00:00:00:00:24", -50, 2437), net("Ops", "aa:00:00:00:00:50", -75, 5180),
            net("Guest", "bb:00:00:00:00:50", -40, 5180)), pointId = "a"),
        ScanPoint(2L, 50f, 10f, listOf(net("Ops", "aa:00:00:00:00:24", -55, 2437), net("Guest", "bb:00:00:00:00:50", -45, 5180)), pointId = "b"))

    @Test
    fun theNetworksOfAFloorAreEachSsidInEveryBandThenPerBand() {
        assertEquals(listOf(MapNetwork("Guest"), MapNetwork("Guest", "5"), MapNetwork("Ops"), MapNetwork("Ops", "2.4"), MapNetwork("Ops", "5")),
            SsidCoverage.networks(points))
        assertEquals(MapNetwork("Guest"), SsidCoverage.selectedNetwork("", "5", points, emptyList())) // automatic: every band
        assertEquals(MapNetwork("Ops", "5"), SsidCoverage.selectedNetwork("Ops", "5", points, emptyList()))
    }

    @Test
    fun pointStatesFollowThePrimaryNetwork() {
        fun states(band: String?) = SsidCoverage.points(points, emptyList(), "Ops", weakDbm = -67, band = band).map { it.state }
        assertEquals(listOf(PointState.OK, PointState.OK), states(null))
        assertEquals(listOf(PointState.OK, PointState.OK), states("2.4"))
        // On 5 GHz: a is weak, b does not have Ops at all. Guest's strong 5 GHz never fills the gap.
        assertEquals(listOf(PointState.WEAK, PointState.SSID_NOT_DETECTED), states("5"))
        val a5 = SsidCoverage.points(points, emptyList(), "Ops", weakDbm = -67, band = "5").first()
        assertEquals(listOf("aa:00:00:00:00:50", -75), listOf(a5.strongestBssid, a5.strongestRssi))
    }

    @Test
    fun theCoverageColourUsesOnlyThatNetwork() {
        assertEquals(listOf(-50, -55), SurveyGuide.coverageSamples(points, emptyList(), "Ops").map { it.bestRssi })
        assertEquals(listOf(-75, null), SurveyGuide.coverageSamples(points, emptyList(), "Ops", "5").map { it.bestRssi })
        val cache = CoverageSampleCache()
        cache.samples(points, emptyList(), "Ops")
        cache.samples(points, emptyList(), "Ops")
        cache.samples(points, emptyList(), "Ops", "5")
        assertEquals("changing the band recomputes", 2, cache.computations)
    }

    @Test
    fun theBandIsSavedWithItsSsidOnly() {
        assertEquals("5", SurveyGuideSettings(ssidFilter = "Ops", bandFilter = "5").sanitized().bandFilter)
        assertEquals("", SurveyGuideSettings(ssidFilter = "", bandFilter = "5").sanitized().bandFilter)
        assertEquals("", SurveyGuideSettings(ssidFilter = "Ops", bandFilter = "3").sanitized().bandFilter)
        assertNull(SurveyGuideSettings(ssidFilter = "Ops").band)
        val config = WiproberConfigParser.parse("""{"format":"wiprober-config","version":1,"guide":{"ssid":"Ops","band":"5 GHz"}}""", "c.json").guide!!
        assertEquals("5", config.bandFilter)
        val exported = ConfigExport.toJson(WiproberConfigStore.Imported(), ThroughputSettings(), config, emptySet())
        assertEquals(config, WiproberConfigParser.parse(exported, "c.json").guide)
    }
}
