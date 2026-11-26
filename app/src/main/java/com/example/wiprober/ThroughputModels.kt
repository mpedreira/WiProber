package com.example.wiprober

/** Throughput tests run at one Stop-and-Go point, one result per configured target. */
@JsonModel
data class PointThroughput(
    val measuredAt: Long,
    val tests: List<ThroughputTestResult>
)

/**
 * One target measured at a point. Null values were not measured or not reported: [error] says
 * why a test failed; never 0. Connection fields describe the associated AP when the test
 * started; [bssidEnd] is read again when it finished, so a different value means the device
 * roamed during the test. [linkMbps] is the transmit link rate, [rxLinkMbps] the receive one.
 * BSS Load values come from the AP's own beacon (only APs that advertise it).
 * Latency is the TCP handshake time: [latencyMs] the minimum, [latencyAvgMs] the mean and
 * [jitterMs] the mean difference between consecutive samples.
 */
@JsonModel
data class ThroughputTestResult(
    val target: String,
    val method: String,
    val durationSeconds: Int,
    val measuredAt: Long,
    val ssid: String? = null,
    val bssidStart: String? = null,
    val bssidEnd: String? = null,
    val rssi: Int? = null,
    val linkMbps: Int? = null,
    val rxLinkMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    val channelWidthMhz: Int? = null,
    val wifiStandard: String? = null,
    val bssStationCount: Int? = null,
    val bssChannelUtilizationPct: Int? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val latencyMs: Double? = null,
    val latencyAvgMs: Double? = null,
    val jitterMs: Double? = null,
    val latencySamples: Int? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val error: String? = null
) {
    companion object {
        const val METHOD_IPERF3_TCP = "iperf3-tcp"
        const val METHOD_HTTP = "http"
        const val TARGET_INTERNET = "internet"
    }
}

/** The AP the device is associated with, as Android reports it (plus its beacon from the last scan). */
data class WifiConnectionInfo(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val linkSpeedMbps: Int?,
    val rxLinkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val channelWidthMhz: Int? = null,
    val wifiStandard: String? = null,
    val bssLoad: BssLoad? = null
) {
    val channel: Int? get() = frequencyMhz?.let(WifiChannels::channel)
}

/** Phone that ran the tests: RSSI and throughput differ between models. */
data class DeviceInfo(val model: String, val androidVersion: String)

/**
 * User configuration of the per-point tests; it lives on the device, never in the code.
 * Durations are seconds per direction.
 */
data class ThroughputSettings(
    val iperfEnabled: Boolean = false,
    val iperfLabel: String = DEFAULT_IPERF_LABEL,
    val iperfHost: String = "",
    val iperfPort: Int = DEFAULT_IPERF_PORT,
    val iperfDurationSeconds: Int = DEFAULT_DURATION_SECONDS,
    val internetEnabled: Boolean = false,
    val internetDownloadUrl: String = DEFAULT_DOWNLOAD_URL,
    val internetUploadUrl: String = DEFAULT_UPLOAD_URL,
    val internetDurationSeconds: Int = DEFAULT_DURATION_SECONDS
) {
    val anyEnabled: Boolean get() = iperfEnabled || internetEnabled

    /** Returns a copy with values trimmed and clamped to what the tests support. */
    fun sanitized(): ThroughputSettings = copy(
        iperfLabel = iperfLabel.trim().ifEmpty { DEFAULT_IPERF_LABEL },
        iperfHost = iperfHost.trim(),
        iperfPort = iperfPort.coerceIn(1, 65_535),
        iperfDurationSeconds = iperfDurationSeconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS),
        internetDownloadUrl = internetDownloadUrl.trim(),
        internetUploadUrl = internetUploadUrl.trim(),
        internetDurationSeconds = internetDurationSeconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
    )

    companion object {
        /** Exported as `target`; set it per device (e.g. the company name) instead of in the code. */
        const val DEFAULT_IPERF_LABEL = "corporate"
        const val DEFAULT_IPERF_PORT = 5201
        const val DEFAULT_DURATION_SECONDS = 5
        const val MIN_DURATION_SECONDS = 1
        const val MAX_DURATION_SECONDS = 30
        /** Cloudflare speed test endpoints; {bytes} is replaced with the requested download size. */
        const val DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes={bytes}"
        const val DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    }
}
