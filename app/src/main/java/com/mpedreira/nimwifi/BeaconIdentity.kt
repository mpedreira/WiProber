package com.mpedreira.nimwifi

/**
 * What a beacon/probe response says about the AP that sent it, extracted when scanning (small and
 * bounded, stored with each scanned network). Nothing here is inferred: these are the AP's own
 * advertisements, read best-effort. Vendor and model inference from them is in [ApIdentity].
 */
@JsonModel
data class BeaconIdentity(
    /** OUIs (AABBCC) of the vendor-specific elements (ID 221), in order, without repeats. */
    val vendorIeOuis: List<String>? = null,
    /** Wi-Fi Protected Setup attributes, when the AP advertises WPS (mostly consumer routers). */
    val wpsManufacturer: String? = null,
    val wpsModelName: String? = null,
    val wpsModelNumber: String? = null,
    val wpsDeviceName: String? = null,
    /** AP name advertised in the beacon (Cisco element 133, Aruba vendor element). */
    val advertisedName: String? = null
) {
    val isEmpty: Boolean get() = vendorIeOuis.isNullOrEmpty() && wpsManufacturer == null && wpsModelName == null &&
        wpsModelNumber == null && wpsDeviceName == null && advertisedName == null
}

/** Android-free reader of information elements (ID + bytes). */
object BeaconIdentityParser {
    private const val VENDOR_SPECIFIC = 221
    private const val CISCO_CCX_NAME = 133
    private const val MAX_OUIS = 8
    private const val MAX_TEXT = 64
    private const val WPS_OUI = "0050F2"
    private const val WPS_TYPE = 0x04
    private const val ARUBA_OUI = "000B86"

    fun parse(elements: List<Pair<Int, ByteArray>>): BeaconIdentity? {
        val ouis = LinkedHashSet<String>()
        var manufacturer: String? = null
        var modelName: String? = null
        var modelNumber: String? = null
        var deviceName: String? = null
        var name: String? = null
        elements.forEach { (id, value) ->
            when (id) {
                VENDOR_SPECIFIC -> if (value.size >= 3) {
                    val oui = hex(value, 0, 3)
                    if (ouis.size < MAX_OUIS) ouis += oui
                    if (oui == WPS_OUI && value.size > 4 && value[3].toInt() == WPS_TYPE) {
                        wps(value, 4).let { attrs ->
                            manufacturer = manufacturer ?: attrs[0x1021]
                            modelName = modelName ?: attrs[0x1023]
                            modelNumber = modelNumber ?: attrs[0x1024]
                            deviceName = deviceName ?: attrs[0x1011]
                        }
                    }
                    // Aruba: type 1, subtype 3 carries the AP name.
                    if (oui == ARUBA_OUI && value.size > 5 && value[3].toInt() == 1 && value[4].toInt() == 3) {
                        name = name ?: text(value, 5, value.size - 5)
                    }
                }
                // Cisco CCX "device name" element: the AP name in 16 bytes after 10 bytes of flags.
                CISCO_CCX_NAME -> if (value.size >= 26) name = name ?: text(value, 10, 16)
            }
        }
        return BeaconIdentity(ouis.toList().ifEmpty { null }, manufacturer, modelName, modelNumber, deviceName, name).takeUnless { it.isEmpty }
    }

    /** The `informationElements` blob stored with each network (ID, length, value… base64). */
    fun parseStored(base64: String?): BeaconIdentity? {
        if (base64.isNullOrBlank()) return null
        val bytes = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
        val elements = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 2 <= bytes.size) {
            val id = bytes[i].toInt() and 0xFF
            val length = bytes[i + 1].toInt() and 0xFF
            if (i + 2 + length > bytes.size) break // truncated (older scans kept 512 bytes)
            elements += id to bytes.copyOfRange(i + 2, i + 2 + length)
            i += 2 + length
        }
        return parse(elements)
    }

    /** WPS attributes: 2-byte type, 2-byte length, value. */
    private fun wps(value: ByteArray, start: Int): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var i = start
        while (i + 4 <= value.size) {
            val type = ((value[i].toInt() and 0xFF) shl 8) or (value[i + 1].toInt() and 0xFF)
            val length = ((value[i + 2].toInt() and 0xFF) shl 8) or (value[i + 3].toInt() and 0xFF)
            if (i + 4 + length > value.size) break
            if (type in setOf(0x1021, 0x1023, 0x1024, 0x1011)) text(value, i + 4, length)?.let { result[type] = it }
            i += 4 + length
        }
        return result
    }

    /** Printable text only (bounded); anything else is not trusted as a name. */
    private fun text(value: ByteArray, start: Int, length: Int): String? {
        if (start < 0 || length <= 0 || start + length > value.size) return null
        val s = String(value, start, length, Charsets.UTF_8).trimEnd('\u0000').trim()
        if (s.isEmpty() || s.any { it.isISOControl() || it == '\uFFFD' }) return null
        return s.take(MAX_TEXT)
    }

    private fun hex(value: ByteArray, start: Int, length: Int) =
        (start until start + length).joinToString("") { "%02X".format(value[it].toInt() and 0xFF) }
}
