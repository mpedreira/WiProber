package com.example.wiprober

import java.net.InetSocketAddress
import java.net.URI
import javax.net.SocketFactory
import kotlin.math.roundToLong

/**
 * Runs the configured per-point tests sequentially and keeps going when a direction fails, so a
 * point always gets whatever could be measured plus the reason for what could not.
 * Blocking: call it off the main thread. Android binds every connection to the Wi-Fi network
 * through [socketFactory] and the [HttpSpeedTest] it passes in.
 */
class ThroughputRunner(
    private val iperf: Iperf3Client,
    private val http: HttpSpeedTest,
    private val socketFactory: SocketFactory,
    private val connection: () -> WifiConnectionInfo?,
    private val clock: MillisClock = SystemMillisClock
) {
    fun measure(settings: ThroughputSettings): PointThroughput {
        val config = settings.sanitized()
        val tests = buildList {
            if (config.iperfEnabled) add(measureIperf(config))
            if (config.internetEnabled) add(measureInternet(config))
        }
        return PointThroughput(measuredAt = clock.now(), tests = tests)
    }

    private fun measureIperf(config: ThroughputSettings): ThroughputTestResult = measureTarget(
        target = config.iperfLabel,
        method = ThroughputTestResult.METHOD_IPERF3_TCP,
        durationSeconds = config.iperfDurationSeconds,
        missingConfiguration = "iPerf3 server not configured".takeIf { config.iperfHost.isEmpty() }
    ) { errors ->
        Triple(
            attempt("latency", errors) { tcpConnectMillis(config.iperfHost, config.iperfPort) },
            attempt("download", errors) {
                iperf.run(config.iperfHost, config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.DOWNLOAD)
            },
            attempt("upload", errors) {
                iperf.run(config.iperfHost, config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.UPLOAD)
            }
        )
    }

    private fun measureInternet(config: ThroughputSettings): ThroughputTestResult = measureTarget(
        target = ThroughputTestResult.TARGET_INTERNET,
        method = ThroughputTestResult.METHOD_HTTP,
        durationSeconds = config.internetDurationSeconds,
        missingConfiguration = "download URL not configured".takeIf { config.internetDownloadUrl.isEmpty() }
    ) { errors ->
        Triple(
            attempt("latency", errors) {
                val uri = URI(config.internetDownloadUrl.replace("{bytes}", "0"))
                val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("http", true)) 80 else 443
                tcpConnectMillis(requireNotNull(uri.host) { "invalid download URL" }, port)
            },
            attempt("download", errors) {
                http.download(config.internetDownloadUrl, config.internetDurationSeconds)
            },
            if (config.internetUploadUrl.isEmpty()) {
                errors += "upload: not configured"
                null
            } else {
                attempt("upload", errors) { http.upload(config.internetUploadUrl, config.internetDurationSeconds) }
            }
        )
    }

    /** Runs [body] (latency ms, down bit/s, up bit/s) between two reads of the associated AP. */
    private fun measureTarget(
        target: String,
        method: String,
        durationSeconds: Int,
        missingConfiguration: String?,
        body: (MutableList<String>) -> Triple<Double?, Double?, Double?>
    ): ThroughputTestResult {
        val startedAt = clock.now()
        val before = connection()
        val base = ThroughputTestResult(
            target = target,
            method = method,
            durationSeconds = durationSeconds,
            measuredAt = startedAt,
            ssid = before?.ssid,
            bssidStart = before?.bssid,
            rssi = before?.rssi,
            linkMbps = before?.linkSpeedMbps
        )
        if (missingConfiguration != null) return base.copy(bssidEnd = before?.bssid, error = missingConfiguration)
        if (before == null) return base.copy(error = NOT_ON_WIFI)

        val errors = mutableListOf<String>()
        val (latency, download, upload) = body(errors)
        return base.copy(
            bssidEnd = connection()?.bssid,
            latencyMs = latency?.let { round(it) },
            downMbps = download?.let { round(it / 1_000_000.0) },
            upMbps = upload?.let { round(it / 1_000_000.0) },
            error = errors.joinToString("; ").ifEmpty { null }
        )
    }

    /** One network round trip: the lowest TCP handshake time of a few attempts, in ms. */
    private fun tcpConnectMillis(host: String, port: Int): Double {
        val address = InetSocketAddress(host, port) // resolve once, outside the timing
        if (address.isUnresolved) throw java.net.UnknownHostException(host)
        return (1..LATENCY_SAMPLES).minOf {
            socketFactory.createSocket().use { socket ->
                val start = System.nanoTime()
                socket.connect(address, CONNECT_TIMEOUT_MILLIS)
                (System.nanoTime() - start) / 1_000_000.0
            }
        }
    }

    private fun <T> attempt(label: String, errors: MutableList<String>, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            errors += "$label: ${e.message ?: e.javaClass.simpleName}"
            null
        }

    private fun round(value: Double): Double = (value * 10).roundToLong() / 10.0

    companion object {
        const val NOT_ON_WIFI = "not connected to a Wi-Fi network"
        private const val LATENCY_SAMPLES = 3
        private const val CONNECT_TIMEOUT_MILLIS = 3_000
    }
}

/** One-line on-screen summary of a point's results, e.g. "internet ↓95.3 ↑— Mbps ⚠". */
object ThroughputSummary {
    fun format(result: PointThroughput): String = result.tests.joinToString(" · ") { test ->
        val warning = if (test.error != null) " ⚠" else ""
        "${test.target} ↓${mbps(test.downMbps)} ↑${mbps(test.upMbps)} Mbps$warning"
    }

    private fun mbps(value: Double?): String = value?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"
}
