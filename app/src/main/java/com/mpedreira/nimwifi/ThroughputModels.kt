package com.mpedreira.nimwifi

/** Throughput tests run at one Stop-and-Go point, one result per configured target. */
@JsonModel
data class PointThroughput(
    val measuredAt: Long,
    val tests: List<ThroughputTestResult>,
    /** Imported network tests (DNS, ping, TCP, HTTP); nullable for points saved before them. */
    val networkTests: List<NetworkTestResult>? = emptyList(),
    /** Imported HTTP scripts replayed at the point. */
    val scripts: List<ScriptRun>? = emptyList()
) {
    val isEmpty: Boolean get() = tests.isEmpty() && networkTests.isNullOrEmpty() && scripts.isNullOrEmpty()
}

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
    /** Exported alias of the target (configurable label). */
    val target: String,
    val method: String,
    val durationSeconds: Int,
    val measuredAt: Long,
    /** Stable target identifier ([TargetIds]) and its category ([TargetCategory]). */
    val targetId: String? = null,
    val category: String? = null,
    /** Android network handle of the measured Wi-Fi and how it was chosen. */
    val networkId: String? = null,
    val networkSelection: String? = null,
    /** Where SSID/BSSID came from ([Association] sources); `unknown` = could not be attributed. */
    val associationSource: String? = null,
    val ssid: String? = null,
    val ssidEnd: String? = null,
    val bssidStart: String? = null,
    val bssidEnd: String? = null,
    /** [AssociationChange] between start and end of the test. */
    val associationChange: String? = null,
    /** Address the target resolved to on the measured network (used for every connection of the run). */
    val resolvedAddress: String? = null,
    val downloadEndpoint: String? = null,
    val uploadEndpoint: String? = null,
    /** Mejora 14: endpoint identity `scheme://host:port` of each direction. */
    val downloadEndpointId: String? = null,
    val uploadEndpointId: String? = null,
    /** IP the pre-check of each direction connected to (the transport's own IP is not observable). */
    val downloadPrecheckAddress: String? = null,
    val uploadPrecheckAddress: String? = null,
    val uploadPrecheckErrorCode: String? = null,
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
    /** Failed handshakes among the latency samples (the valid ones are kept). */
    val latencyFailures: Int? = null,
    /** How latency was measured: `tcp-connect` (TCP handshake), never ICMP. */
    val latencyMethod: String? = null,
    /** Per-phase [RunStatus] and error code ([ErrorCodes]). */
    val latencyStatus: String? = null,
    val latencyErrorCode: String? = null,
    val downloadStatus: String? = null,
    val downloadErrorCode: String? = null,
    val uploadStatus: String? = null,
    val uploadErrorCode: String? = null,
    /** Bytes and real seconds of each transfer. */
    val downBytes: Long? = null,
    val downSeconds: Double? = null,
    val upBytes: Long? = null,
    val upSeconds: Double? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    /** RC4 · 1: the Internet destination selected (catalogue id or `custom`) and its label. */
    val internetServerId: String? = null,
    val internetServerLabel: String? = null,
    /** Where the provider served from, when it says (e.g. Cloudflare data centre `MAD`). */
    val serverLocation: String? = null,
    /** Rate of the bytes moved before an HTTP 429: diagnosis only, never a valid value. */
    val downPartialMbps: Double? = null,
    val upPartialMbps: Double? = null,
    /** When the rate-limited server may be tried again (epoch ms). */
    val downloadRetryAt: Long? = null,
    val uploadRetryAt: Long? = null,
    /**
     * RC4 · 2: per direction, the proxy Android has configured for the URL ([ProxyKind]), the
     * route the measuring connection took ([HttpRoute]) and the last HTTP status it got.
     */
    val downloadProxyConfig: String? = null,
    val downloadRoute: String? = null,
    val downloadHttpStatus: Int? = null,
    val uploadProxyConfig: String? = null,
    val uploadRoute: String? = null,
    val uploadHttpStatus: Int? = null,
    /** How [latencyMs] was taken: a direct TCP handshake to the origin, even when HTTP goes via a proxy. */
    val latencyRoute: String? = null,
    val error: String? = null
) {
    companion object {
        const val METHOD_IPERF3_TCP = "iperf3-tcp"
        const val METHOD_HTTP = "http"
        const val TARGET_INTERNET = "internet"
    }
}

/** One finished transfer: rate, bytes actually counted and real duration. */
data class TransferResult(
    val bitsPerSecond: Double,
    val bytes: Long,
    val seconds: Double,
    /** RC4 · 1: where the provider served from, when it says (Cloudflare's data centre in CF-RAY). */
    val serverLocation: String? = null
)

/**
 * RC4 · 1: the server answered HTTP 429. New requests to it stopped; [bytes] transferred by
 * other connections before that are kept for diagnosis only (never a valid rate).
 * [retryAfterMillis] is the server's `Retry-After`, when it sent one.
 */
class RateLimitedException(
    val host: String,
    val retryAfterMillis: Long?,
    val bytes: Long = 0,
    val seconds: Double = 0.0
) : MeasurementException(ErrorCodes.RATE_LIMITED, "HTTP 429 from $host: the server is limiting requests" +
    (retryAfterMillis?.let { " (Retry-After ${it / 1000} s)" } ?: "")) {
    val partialBitsPerSecond: Double? get() = if (bytes > 0 && seconds > 0) bytes * 8.0 / seconds else null
}

/** Stable identifiers of the built-in throughput targets. */
object TargetIds {
    const val IPERF = "iperf3-private"
    const val INTERNET = "internet-http"
}

/** Kinds of destination, kept apart in the app and the export. */
object TargetCategory {
    const val PRIVATE_IPERF = "private-iperf"
    const val INTERNET = "internet"
    const val INTERNAL = "internal-service"
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
    val internetDurationSeconds: Int = DEFAULT_DURATION_SECONDS,
    /**
     * Mejora 13: SSID the active tests must run on. Before sending anything the app compares it
     * with the network it would use; a mismatch or an unknown association needs a decision.
     * Not the map SSID (the passive filter of the coverage map).
     */
    val expectedSsid: String = "",
    /**
     * RC4 · 1: the Internet destination chosen in the drop-down ([InternetServers]); empty in
     * settings saved before, resolved from the URLs ([InternetServers.match]).
     */
    val internetServerId: String = "",
    /** URLs typed for *Personalizado*, kept while another destination is selected. */
    val customDownloadUrl: String = "",
    val customUploadUrl: String = ""
) {
    val anyEnabled: Boolean get() = iperfEnabled || internetEnabled

    /** The catalogue entry in use; null for a custom destination. */
    val internetServer: InternetServer? get() = InternetServers.byId(internetServerId)

    val internetStreams: Int get() = internetServer?.streams ?: InternetServers.CUSTOM_STREAMS
    val internetMaxUploadChunkBytes: Int get() = internetServer?.maxUploadChunkBytes ?: InternetServers.CUSTOM_MAX_UPLOAD_CHUNK_BYTES

    /**
     * Resolves the selection: a known destination sets the URLs measured; *Personalizado* uses
     * the custom URLs. Settings without an id keep their URLs: they select the catalogue entry
     * they match exactly, else *Personalizado* with those URLs (nothing is lost).
     */
    private fun resolvedInternet(): ThroughputSettings {
        val download = internetDownloadUrl.trim()
        val upload = internetUploadUrl.trim()
        val id = internetServerId.trim().takeIf { it == InternetServers.CUSTOM || InternetServers.byId(it) != null }
            ?: InternetServers.match(download, upload)
        val server = InternetServers.byId(id)
        return if (server != null) {
            copy(internetServerId = id, internetDownloadUrl = server.downloadUrl, internetUploadUrl = server.uploadUrl,
                customDownloadUrl = customDownloadUrl.trim(), customUploadUrl = customUploadUrl.trim())
        } else {
            // Custom: the typed URLs, else the ones the settings had (a migrated or imported profile).
            val customDown = customDownloadUrl.trim().ifEmpty { download }
            val customUp = customUploadUrl.trim().ifEmpty { upload }
            copy(internetServerId = InternetServers.CUSTOM, internetDownloadUrl = customDown, internetUploadUrl = customUp,
                customDownloadUrl = customDown, customUploadUrl = customUp)
        }
    }

    /** Returns a copy with values trimmed and clamped to what the tests support. */
    fun sanitized(): ThroughputSettings = resolvedInternet().copy(
        iperfLabel = iperfLabel.trim().ifEmpty { DEFAULT_IPERF_LABEL },
        iperfHost = iperfHost.trim(),
        iperfPort = iperfPort.coerceIn(1, 65_535),
        iperfDurationSeconds = iperfDurationSeconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS),
        internetDurationSeconds = internetDurationSeconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS),
        expectedSsid = expectedSsid.trim().take(32)
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
