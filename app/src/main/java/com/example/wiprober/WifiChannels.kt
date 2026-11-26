package com.example.wiprober

import kotlin.math.roundToInt

/** IEEE channel numbers from centre frequencies (2.4, 5 and 6 GHz bands). */
object WifiChannels {
    fun channel(frequencyMhz: Int): Int? = when (frequencyMhz) {
        2484 -> 14
        in 2412..2472 -> (frequencyMhz - 2407) / 5
        in 5160..5885 -> (frequencyMhz - 5000) / 5
        5935 -> 2
        in 5955..7115 -> (frequencyMhz - 5950) / 5
        else -> null
    }

    /** `ScanResult.CHANNEL_WIDTH_*` constant to MHz (80+80 counts as 160). */
    fun widthMhz(scanResultChannelWidth: Int): Int? = when (scanResultChannelWidth) {
        0 -> 20
        1 -> 40
        2 -> 80
        3, 4 -> 160
        5 -> 320
        else -> null
    }

    /** `ScanResult.WIFI_STANDARD_*` / `WifiInfo.getWifiStandard()` constant to its name. */
    fun standardName(wifiStandard: Int): String? = when (wifiStandard) {
        1 -> "802.11a/b/g"
        4 -> "802.11n (Wi-Fi 4)"
        5 -> "802.11ac (Wi-Fi 5)"
        6 -> "802.11ax (Wi-Fi 6/6E)"
        7 -> "802.11ad"
        8 -> "802.11be (Wi-Fi 7)"
        else -> null
    }
}

/**
 * BSS Load element (IEEE 802.11, element ID 11) an AP may advertise in its beacon: associated
 * stations and the share of time it sensed the channel busy.
 */
data class BssLoad(val stationCount: Int, val channelUtilizationPct: Int) {
    companion object {
        const val ELEMENT_ID = 11

        /** Parses the element body (without ID/length); null if it is too short. */
        fun parse(body: ByteArray): BssLoad? {
            if (body.size < 3) return null
            val stations = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8)
            val utilization = body[2].toInt() and 0xFF // 0..255 = 0..100 % busy
            return BssLoad(stations, (utilization * 100 / 255.0).roundToInt())
        }
    }
}
