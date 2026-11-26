package com.mpedreira.nimwifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiChannelsTest {
    @Test
    fun mapsFrequenciesOfAllBandsToChannels() {
        assertEquals(1, WifiChannels.channel(2412))
        assertEquals(13, WifiChannels.channel(2472))
        assertEquals(14, WifiChannels.channel(2484))
        assertEquals(36, WifiChannels.channel(5180))
        assertEquals(165, WifiChannels.channel(5825))
        assertEquals(1, WifiChannels.channel(5955))
        assertEquals(2, WifiChannels.channel(5935))
        assertEquals(233, WifiChannels.channel(7115))
        assertNull(WifiChannels.channel(1000))
    }

    @Test
    fun mapsAndroidWidthAndStandardConstants() {
        assertEquals(listOf(20, 40, 80, 160, 160, 320), (0..5).map(WifiChannels::widthMhz))
        assertNull(WifiChannels.widthMhz(-1))
        assertEquals("802.11ax (Wi-Fi 6/6E)", WifiChannels.standardName(6))
        assertNull(WifiChannels.standardName(0))
    }

    @Test
    fun parsesBssLoadElementBody() {
        // 0x0102 = 258 stations (little endian), utilisation 128/255 = 50 %, admission capacity ignored
        val load = BssLoad.parse(byteArrayOf(0x02, 0x01, 0x80.toByte(), 0x00, 0x00))

        assertEquals(BssLoad(stationCount = 258, channelUtilizationPct = 50), load)
        assertEquals(100, BssLoad.parse(byteArrayOf(0, 0, 0xFF.toByte()))!!.channelUtilizationPct)
        assertNull(BssLoad.parse(byteArrayOf(1, 0)))
    }
}
