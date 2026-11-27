package com.mpedreira.nimwifi

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The AP's own advertisements, read best-effort from its information elements. */
class BeaconIdentityTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun tlv(type: Int, text: String): ByteArray {
        val v = text.toByteArray()
        return bytes(type shr 8, type and 0xFF, v.size shr 8, v.size and 0xFF) + v
    }

    /** WPS vendor element (OUI 00:50:F2, type 4) with manufacturer, model name, number and device name. */
    private val wps = bytes(0x00, 0x50, 0xF2, 0x04) + tlv(0x104A, "\u0010") + tlv(0x1021, "ExampleCorp") + tlv(0x1023, "AX-3000") +
        tlv(0x1024, "R2") + tlv(0x1011, "Almacen-Router")
    private val wmm = bytes(0x00, 0x50, 0xF2, 0x02, 0x01)
    private val cisco133 = ByteArray(10) + "PASILLO-3-AP04".toByteArray().copyOf(16)
    private val aruba = bytes(0x00, 0x0B, 0x86, 0x01, 0x03) + "AP-P3-05".toByteArray()

    @Test
    fun wpsCiscoAndArubaAdvertisementsAreRead() {
        val id = BeaconIdentityParser.parse(listOf(0 to "Ops".toByteArray(), 221 to wmm, 221 to wps, 133 to cisco133))!!
        assertEquals(listOf("0050F2"), id.vendorIeOuis)
        assertEquals(listOf("ExampleCorp", "AX-3000", "R2", "Almacen-Router"), listOf(id.wpsManufacturer, id.wpsModelName, id.wpsModelNumber, id.wpsDeviceName))
        assertEquals("PASILLO-3-AP04", id.advertisedName)
        assertEquals("AP-P3-05", BeaconIdentityParser.parse(listOf(221 to aruba))!!.advertisedName)
        assertEquals(listOf("000B86"), BeaconIdentityParser.parse(listOf(221 to aruba))!!.vendorIeOuis)
    }

    @Test
    fun nothingAdvertisedOrUnreadableIsNull() {
        assertNull(BeaconIdentityParser.parse(listOf(0 to "Ops".toByteArray(), 1 to bytes(0x82, 0x84))))
        // A name with control bytes is not trusted.
        val garbage = ByteArray(10) + bytes(0x41, 0x01, 0x02, 0x42) + ByteArray(12)
        assertNull(BeaconIdentityParser.parse(listOf(133 to garbage))?.advertisedName)
        assertNull(BeaconIdentityParser.parseStored(""))
        assertNull(BeaconIdentityParser.parseStored("not base64!"))
    }

    @Test
    fun theStoredBlobOfOlderScansIsReadUpToItsTruncation() {
        fun element(id: Int, value: ByteArray) = bytes(id, value.size) + value
        val blob = element(0, "Ops".toByteArray()) + element(221, wps) + element(133, cisco133).copyOf(10) // cut mid-element (512-byte cap)
        val id = BeaconIdentityParser.parseStored(Base64.getEncoder().encodeToString(blob))!!
        assertEquals("ExampleCorp", id.wpsManufacturer)
        assertNull("the truncated element is ignored", id.advertisedName)
    }
}
