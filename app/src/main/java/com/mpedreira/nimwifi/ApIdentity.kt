package com.mpedreira.nimwifi

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The IEEE MA-L registry (OUI → organization), bundled as `res/raw/oui_registry.gz` (one
 * `AABBCC<TAB>Organization` line each). Android-free: the app opens the resource and passes it.
 */
class OuiRegistry(private val organizations: Map<String, String>) {
    val size: Int get() = organizations.size

    fun organization(oui: String): String? = organizations[oui.uppercase()]

    /**
     * The organization the BSSID's OUI was assigned to; null when unknown or when the address is
     * locally administered (virtual BSSIDs of a multi-SSID AP often are: the OUI is not a vendor's).
     */
    fun vendorOf(bssid: String?): String? {
        val normalized = Bssids.normalize(bssid) ?: return null
        if (isLocallyAdministered(normalized)) return null
        return organization(normalized.replace(":", "").take(6))
    }

    companion object {
        val EMPTY = OuiRegistry(emptyMap())

        fun isLocallyAdministered(bssid: String): Boolean =
            Bssids.normalize(bssid)?.take(2)?.toIntOrNull(16)?.let { it and 0x02 != 0 } == true

        /** Reads the gzipped registry; lines starting with `#` are comments. */
        fun read(gzipped: InputStream): OuiRegistry = GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).useLines { lines ->
            OuiRegistry(lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab != 6) null else line.substring(0, 6).uppercase() to line.substring(7).trim()
            }.toMap())
        }
    }
}

/**
 * What can be inferred about the physical AP behind one BSSID. Everything here is an inference,
 * with its source; it never groups BSSIDs and never replaces the user's AP table ([ApDirectory]).
 */
data class ApIdentityInfo(
    val vendor: String?,
    /** `wps` (the AP's WPS manufacturer), `oui` (the BSSID's IEEE assignment) or `vendor-ie` (a vendor element's OUI). */
    val vendorSource: String?,
    /** WPS model name and number (consumer gear mostly); null when the AP does not advertise it. */
    val model: String?,
    /** Name the AP advertises in its beacon (Cisco, Aruba), if any. */
    val advertisedName: String?,
    val locallyAdministered: Boolean
) {
    /** Short vendor for labels: the organization name up to its first comma. */
    val shortVendor: String? get() = vendor?.substringBefore(',')?.trim()?.ifEmpty { null }
}

object ApIdentity {
    const val SOURCE_WPS = "wps"
    const val SOURCE_OUI = "oui"
    const val SOURCE_VENDOR_IE = "vendor-ie"

    /**
     * Vendor elements that say nothing about the AP's brand: Wi-Fi standards bodies and chipsets
     * (WMM/WPS, Wi-Fi Alliance, Broadcom, Atheros/Qualcomm, MediaTek, Realtek).
     */
    private val NOT_A_BRAND = setOf("0050F2", "506F9A", "001018", "00904C", "00037F", "8CFDF0", "000CE7", "00E04C")

    fun infer(bssid: String, beacons: List<BeaconIdentity>, registry: OuiRegistry): ApIdentityInfo {
        val wpsVendor = beacons.firstNotNullOfOrNull { it.wpsManufacturer }
        val ouiVendor = registry.vendorOf(bssid)
        val ieVendor = beacons.asSequence().flatMap { it.vendorIeOuis.orEmpty().asSequence() }
            .filter { it !in NOT_A_BRAND }.firstNotNullOfOrNull { registry.organization(it) }
        val (vendor, source) = when {
            wpsVendor != null -> wpsVendor to SOURCE_WPS
            ouiVendor != null -> ouiVendor to SOURCE_OUI
            ieVendor != null -> ieVendor to SOURCE_VENDOR_IE
            else -> null to null
        }
        val model = beacons.firstNotNullOfOrNull { b ->
            listOfNotNull(b.wpsModelName, b.wpsModelNumber).joinToString(" ").ifEmpty { null }
        }
        return ApIdentityInfo(vendor, source, model, beacons.firstNotNullOfOrNull { it.advertisedName },
            OuiRegistry.isLocallyAdministered(bssid))
    }

    fun sourceLabel(source: String?): String = when (source) {
        SOURCE_WPS -> "por WPS"
        SOURCE_OUI -> "por OUI"
        SOURCE_VENDOR_IE -> "por IE de fabricante"
        else -> "desconocido"
    }
}

/** One radio heard on a floor: what was seen and what is inferred about its AP. */
data class ApInventoryEntry(
    val bssid: String,
    val ssids: List<String>,
    val frequency: Int?,
    val bestRssi: Int?,
    val userName: String?,
    val identity: ApIdentityInfo
)

object ApInventory {
    /** Every BSSID of the floor's local scans (Stop-and-Go and Continuous), strongest first. */
    fun of(survey: ProjectSnapshot, directory: ApDirectory, registry: OuiRegistry): List<ApInventoryEntry> {
        class Seen(var frequency: Int? = null, var best: Int? = null, val ssids: LinkedHashSet<String> = linkedSetOf(),
                   val beacons: MutableList<BeaconIdentity> = mutableListOf(), var stored: String? = null)
        val seen = linkedMapOf<String, Seen>()
        fun add(n: WifiNetworkInfo) {
            val key = Bssids.normalize(n.bssid) ?: return
            val s = seen.getOrPut(key) { Seen() }
            if (WifiBands.of(n.frequency) != null) s.frequency = n.frequency
            s.best = maxOf(s.best ?: n.level, n.level)
            if (n.ssid.isNotEmpty()) s.ssids += n.ssid
            n.beacon?.let { if (s.beacons.size < 4 && it !in s.beacons) s.beacons += it }
            if (s.stored == null && n.informationElements.isNotEmpty()) s.stored = n.informationElements
        }
        survey.scanPoints.filterNot { it.importedFromEsx }.forEach { p -> p.wifiNetworks.forEach(::add) }
        survey.continuousScanSessions.filterNot { it.importedFromEsx }.forEach { session -> session.scanResults.forEach { r -> r.wifiNetworks.forEach(::add) } }
        return seen.map { (bssid, s) ->
            // Scans saved before rc.5 have no beacon summary: read what their stored elements kept.
            val beacons = s.beacons.ifEmpty { listOfNotNull(BeaconIdentityParser.parseStored(s.stored)) }
            ApInventoryEntry(bssid, s.ssids.toList(), s.frequency, s.best, directory.name(bssid), ApIdentity.infer(bssid, beacons, registry))
        }.sortedWith(compareByDescending<ApInventoryEntry> { it.bestRssi ?: Int.MIN_VALUE }.thenBy { it.bssid })
    }
}
