package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 13: which Wi-Fi an active test uses, and whether it is the declared one. */
class NetworkSelectionTest {
    private fun ap(ssid: String?) = Association(true, Association.SOURCE_NETWORK_CALLBACK, ssid, "aa:01", -60, 400, 400, 5_180, null)

    @Test
    fun aLocalWifiWithoutInternetIsUsedWhileMobileDataIsTheDefault() {
        // Only one Wi-Fi network, not default (mobile data is) and not validated (no Internet).
        val wifi = NetworkCandidate(id = 101, isDefault = false, validated = false, ssid = "Almacen")
        val (chosen, selection) = NetworkSelectionPolicy.choose(listOf(wifi), expectedSsid = "Almacen")!!
        assertEquals(101L, chosen.id)
        assertEquals(NetworkSelectionPolicy.ONLY, selection)
        assertEquals(NetworkVerification.MATCHED, NetworkVerification.of("Almacen", true, ap("Almacen")))
    }

    @Test
    fun withTwoEligibleWifiNetworksTheExpectedSsidWins() {
        val guest = NetworkCandidate(100, isDefault = true, validated = true, ssid = "Invitados")
        val ops = NetworkCandidate(200, isDefault = false, validated = false, ssid = "Operativa")
        assertEquals(200L to NetworkSelectionPolicy.EXPECTED_SSID, NetworkSelectionPolicy.choose(listOf(guest, ops), "Operativa")!!.let { it.first.id to it.second })
        // Without an expectation the deterministic rule keeps the default network.
        assertEquals(100L to NetworkSelectionPolicy.DEFAULT, NetworkSelectionPolicy.choose(listOf(guest, ops), "")!!.let { it.first.id to it.second })
        // Expected SSID on none of them: the rule applies and the check reports the mismatch.
        val chosen = NetworkSelectionPolicy.choose(listOf(guest, ops), "Otra")!!
        assertEquals(NetworkSelectionPolicy.DEFAULT, chosen.second)
        assertEquals(NetworkVerification.MISMATCH, NetworkVerification.of("Otra", true, ap("Invitados")))
        assertTrue(NetworkVerification.needsDecision(NetworkVerification.MISMATCH))
    }

    @Test
    fun anUnknownAssociationIsAnExplicitStateThatNeedsADecision() {
        assertEquals(NetworkVerification.UNKNOWN, NetworkVerification.of("Operativa", true, Association.UNKNOWN))
        assertEquals(NetworkVerification.UNKNOWN, NetworkVerification.of("Operativa", true, ap(null)))
        assertTrue(NetworkVerification.needsDecision(NetworkVerification.UNKNOWN))
        assertEquals(NetworkVerification.NOT_DECLARED, NetworkVerification.of("", true, Association.UNKNOWN))
        assertFalse(NetworkVerification.needsDecision(NetworkVerification.NOT_DECLARED))
        assertEquals(NetworkVerification.NO_WIFI, NetworkVerification.of("Operativa", false, null))
        assertNull(NetworkSelectionPolicy.choose(emptyList(), "Operativa"))
    }

    @Test
    fun theExpectedSsidIsPartOfTheProfile() {
        val config = WiproberConfigParser.parse("""{"format": "wiprober-config", "version": 1, "throughput": {"expectedSsid": " Operativa "}}""", "c.json")
        assertEquals("Operativa", config.throughput!!.expectedSsid)
        val json = ConfigExport.toJson(WiproberConfigStore.Imported(), config.throughput!!, SurveyGuideSettings(), emptySet())
        assertEquals("Operativa", WiproberConfigParser.parse(json, "c.json").throughput!!.expectedSsid)
    }
}
