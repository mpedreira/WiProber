package com.mpedreira.nimwifi

import android.net.wifi.ScanResult
import android.os.Build

/** Android scan results as survey networks (shared by Stop-and-Go and Continuous capture). */
object ScanResultMapper {
    fun map(results: List<ScanResult>): List<WifiNetworkInfo> = results.map { scanResult ->
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            scanResult.wifiSsid?.toString()?.removeSurrounding("\"") ?: "<unknown ssid>"
        } else {
            @Suppress("DEPRECATION")
            scanResult.SSID
        }
        WifiNetworkInfo(
            ssid = ssid,
            bssid = scanResult.BSSID,
            level = scanResult.level,
            frequency = scanResult.frequency,
            security = security(scanResult.capabilities),
            technologies = WifiTechnologyMapper.map(scanResult.wifiStandard, scanResult.frequency),
            informationElements = informationElements(scanResult)
        )
    }

    private fun security(capabilities: String): String = when {
        capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        capabilities.contains("ESS") -> "Open"
        else -> "Unknown"
    }

    private fun informationElements(scanResult: ScanResult): String {
        val elements = scanResult.informationElements ?: return ""
        val buffer = java.nio.ByteBuffer.allocate(512)
        for (ie in elements) {
            val value = ByteArray(ie.bytes.remaining()).also { ie.bytes.get(it) }
            if (buffer.remaining() < 2 + value.size) break
            buffer.put(ie.id.toByte())
            buffer.put(value.size.toByte())
            buffer.put(value)
        }
        val bytes = ByteArray(buffer.position()).also { buffer.rewind(); buffer.get(it) }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
}

/** Continuous capture sources backed by Android: a scanner not bound to any Activity. */
internal class AndroidRfScanSource(context: android.content.Context) : RfScanSource {
    private val scanner = WifiScanner(context.applicationContext, lifecycle = null)

    override fun start() = scanner.start()
    override fun isWifiEnabled(): Boolean = scanner.isWifiEnabled()
    override fun close() = scanner.close()

    override suspend fun scan(): RfScan = when (val outcome = scanner.scan()) {
        is WifiScanOutcome.Success -> RfScan.Success(map(outcome.results))
        is WifiScanOutcome.Cancelled -> RfScan.Cancelled(outcome.reason.name.lowercase())
        is WifiScanOutcome.Failure -> RfScan.Failure(
            outcome.reason.name.lowercase(),
            fatal = outcome.reason in setOf(
                WifiScanFailureReason.WIFI_DISABLED, WifiScanFailureReason.PERMISSION_DENIED,
                WifiScanFailureReason.SCAN_UNAVAILABLE, WifiScanFailureReason.SCANNER_CLOSED
            )
        )
    }

    private fun map(results: List<ScanResult>) = ScanResultMapper.map(results)
}

internal class AndroidCaptureForeground(private val context: android.content.Context, private val projectId: String?) : CaptureForeground {
    override fun start(): String? = SurveyForegroundService.acquire(context.applicationContext, SurveyForegroundService.REASON_TRACK, projectId)
    override fun stop() = SurveyForegroundService.release(context.applicationContext, SurveyForegroundService.REASON_TRACK)
}
