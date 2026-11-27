package com.mpedreira.nimwifi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inferred AP vendor and model: marked as inferred, with its source; BSSIDs are never grouped. */
class ApIdentityTest {
    private val registry: OuiRegistry by lazy {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/res/raw/oui_registry.gz") }
            .firstOrNull { it.isFile } ?: File("src/main/res/raw/oui_registry.gz")
        file.inputStream().use(OuiRegistry::read)
    }

    @Test
    fun theBundledRegistryNamesVendorsAndSkipsLocallyAdministeredAddresses() {
        assertTrue(registry.size > 30_000)
        assertTrue(registry.vendorOf("00:40:96:12:34:56")!!.startsWith("Cisco"))
        assertEquals("Ruckus Wireless", registry.vendorOf("00-13-92-00-00-01"))
        // 0x02 bit: a virtual BSSID of a multi-SSID AP; its "OUI" is nobody's.
        assertTrue(OuiRegistry.isLocallyAdministered("02:40:96:12:34:56"))
        assertNull(registry.vendorOf("02:40:96:12:34:56"))
        assertNull(registry.vendorOf("not a bssid"))
    }

    @Test
    fun sourcesArePrioritisedAndChipsetElementsIgnored() {
        val wps = BeaconIdentity(vendorIeOuis = listOf("0050F2"), wpsManufacturer = "ExampleCorp", wpsModelName = "AX-3000", wpsModelNumber = "R2")
        val byWps = ApIdentity.infer("00:40:96:00:00:01", listOf(wps), registry)
        assertEquals(listOf("ExampleCorp", ApIdentity.SOURCE_WPS, "AX-3000 R2"), listOf(byWps.vendor, byWps.vendorSource, byWps.model))
        val byOui = ApIdentity.infer("00:40:96:00:00:01", emptyList(), registry)
        assertEquals(ApIdentity.SOURCE_OUI, byOui.vendorSource)
        assertNull("no model without WPS", byOui.model)
        // Locally administered BSSID: the vendor element (Aruba) still says who made it.
        val byIe = ApIdentity.infer("02:00:00:00:00:01", listOf(BeaconIdentity(vendorIeOuis = listOf("0050F2", "000B86"), advertisedName = "AP-P3-05")), registry)
        assertEquals(listOf(ApIdentity.SOURCE_VENDOR_IE, "AP-P3-05", true), listOf(byIe.vendorSource, byIe.advertisedName, byIe.locallyAdministered))
        assertTrue(byIe.vendor!!.contains("Hewlett Packard"))
        // Only WMM/WPS and chipset elements: nothing about the brand.
        val unknown = ApIdentity.infer("06:00:00:00:00:01", listOf(BeaconIdentity(vendorIeOuis = listOf("0050F2", "001018", "00037F"))), registry)
        assertNull(unknown.vendor)
        assertEquals("desconocido", ApIdentity.sourceLabel(unknown.vendorSource))
    }

    private fun net(ssid: String, bssid: String, level: Int, beacon: BeaconIdentity? = null, stored: String = "") =
        WifiNetworkInfo(ssid, bssid, level, 5_180, "WPA2", emptyList(), stored, beacon)

    @Test
    fun theInventoryListsEveryRadioOnItsOwnAndReadsOlderScans() {
        val sameAp = BeaconIdentity(vendorIeOuis = listOf("004096"), advertisedName = "PASILLO-3-AP04")
        // Older scan: no beacon summary, only the stored (truncated) elements with a WPS block.
        val wps = byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04, 0x10, 0x21, 0x00, 0x04) + "Acme".toByteArray()
        val stored = java.util.Base64.getEncoder().encodeToString(byteArrayOf(221.toByte(), wps.size.toByte()) + wps)
        val survey = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1, listOf(
            ScanPoint(1L, 1f, 1f, listOf(net("Ops", "00:40:96:00:00:01", -50, sameAp), net("Guest", "00:40:96:00:00:02", -55, sameAp),
                net("Old", "aa:00:00:00:00:09", -80, stored = stored)), pointId = "a")), emptyList(), emptyList())
        val inventory = ApInventory.of(survey, ApDirectory(listOf(ApName("00:40:96:00:00:01", "AP-P3-04"))), registry)
        assertEquals("two radios of one AP stay two entries", listOf("00:40:96:00:00:01", "00:40:96:00:00:02", "aa:00:00:00:00:09"), inventory.map { it.bssid })
        assertEquals("AP-P3-04", inventory[0].userName)
        assertNull("the user's name is never inferred for another BSSID", inventory[1].userName)
        assertEquals(listOf("Ops"), inventory[0].ssids)
        assertEquals("Acme", inventory[2].identity.vendor)
        assertEquals(ApIdentity.SOURCE_WPS, inventory[2].identity.vendorSource)
    }

    @Test
    fun theReportAndTheJsonShowTheInferredIdentity() {
        val beacon = BeaconIdentity(wpsManufacturer = "ExampleCorp", wpsModelName = "AX-3000")
        val survey = ProjectSnapshot(MapInfo("g.png", 100, 100), 0.1,
            listOf(ScanPoint(1L, 1f, 1f, listOf(net("Ops", "00:40:96:00:00:01", -75, beacon)), pointId = "a", scanId = "s1")), emptyList(), emptyList())
        val snapshot = ProjectExportSnapshot("p", "Almacén", null, listOf(FloorExportSnapshot("g", "Baja", File("g.png"),
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", false, null, survey)))
        val model = SurveyReport.build(snapshot, 0L, "Ops", weakDbm = -67, ouiRegistry = registry)
        val html = SurveyReport.html(model)
        assertTrue("Inventario de APs de la planta (1 radios)" in html)
        assertTrue("ExampleCorp (por WPS)" in html && "AX-3000 (por WPS)" in html)
        assertTrue("label", "00:40:96:00:00:01 (5 GHz c36 · ExampleCorp (inferido))" in html)
        val row = PerformanceExport.build(null, "Site.esx", 0L, snapshot, model).getAsJsonArray("apInventory").single().asJsonObject
        assertEquals(listOf("ExampleCorp", "wps", "AX-3000", "5", "36"),
            listOf("inferredVendor", "inferredVendorSource", "inferredModel", "band", "channel").map { row[it].asString })
    }
}
