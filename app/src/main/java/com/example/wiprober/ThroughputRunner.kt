package com.example.wiprober

import java.net.InetSocketAddress
import java.net.URI
import java.util.Locale
import javax.net.SocketFactory
import kotlin.math.roundToLong

/** Phases of one target, in the order they run. */
enum class ThroughputPhase { LATENCY, DOWNLOAD, UPLOAD }

/** Outcome of a finished phase: ms for latency, Mbps otherwise; null if it failed or was skipped. */
data class ThroughputPhaseResult(val target: String, val phase: ThroughputPhase, val value: Double?)

/**
 * Reported when a phase starts. [phaseSeconds] is its planned duration (null for latency, which is
 * a few round trips) so the UI can show a countdown; [finished] lists what is already measured.
 */
data class ThroughputProgress(
    val step: Int,
    val totalSteps: Int,
    val target: String,
    val phase: ThroughputPhase,
    val phaseSeconds: Int?,
    val finished: List<ThroughputPhaseResult>
)

/**
 * Runs the configured per-point tests sequentially and keeps going when a direction fails, so a
 * point always gets whatever could be measured plus the reason for what could not. If a server
 * cannot even be connected to, its transfers are skipped instead of waiting for more timeouts.
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
    private class Target(
        val label: String,
        val method: String,
        val durationSeconds: Int,
        val missingConfiguration: String?,
        val latency: () -> Double,
        val download: () -> Double,
        /** Null when the direction is not configured. */
        val upload: (() -> Double)?
    )

    fun measure(settings: ThroughputSettings, progress: (ThroughputProgress) -> Unit = {}): PointThroughput {
        val config = settings.sanitized()
        val targets = buildList {
            if (config.iperfEnabled) add(iperfTarget(config))
            if (config.internetEnabled) add(internetTarget(config))
        }
        val tracker = ProgressTracker(targets.size * ThroughputPhase.entries.size, progress)
        val tests = targets.map { measureTarget(it, tracker) }
        return PointThroughput(measuredAt = clock.now(), tests = tests)
    }

    private fun iperfTarget(config: ThroughputSettings) = Target(
        label = config.iperfLabel,
        method = ThroughputTestResult.METHOD_IPERF3_TCP,
        durationSeconds = config.iperfDurationSeconds,
        missingConfiguration = "iPerf3 server not configured".takeIf { config.iperfHost.isEmpty() },
        latency = { tcpConnectMillis(config.iperfHost, config.iperfPort) },
        download = {
            iperf.run(config.iperfHost, config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.DOWNLOAD)
        },
        upload = {
            iperf.run(config.iperfHost, config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.UPLOAD)
        }
    )

    private fun internetTarget(config: ThroughputSettings) = Target(
        label = ThroughputTestResult.TARGET_INTERNET,
        method = ThroughputTestResult.METHOD_HTTP,
        durationSeconds = config.internetDurationSeconds,
        missingConfiguration = "download URL not configured".takeIf { config.internetDownloadUrl.isEmpty() },
        latency = {
            val uri = URI(config.internetDownloadUrl.replace("{bytes}", "0"))
            val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("http", true)) 80 else 443
            tcpConnectMillis(requireNotNull(uri.host) { "invalid download URL" }, port)
        },
        download = { http.download(config.internetDownloadUrl, config.internetDurationSeconds) },
        upload = if (config.internetUploadUrl.isEmpty()) null else {
            { http.upload(config.internetUploadUrl, config.internetDurationSeconds) }
        }
    )

    private fun measureTarget(target: Target, tracker: ProgressTracker): ThroughputTestResult {
        val before = connection()
        val base = ThroughputTestResult(
            target = target.label,
            method = target.method,
            durationSeconds = target.durationSeconds,
            measuredAt = clock.now(),
            ssid = before?.ssid,
            bssidStart = before?.bssid,
            rssi = before?.rssi,
            linkMbps = before?.linkSpeedMbps
        )
        if (target.missingConfiguration != null || before == null) {
            tracker.skip(target.label)
            return if (target.missingConfiguration != null) {
                base.copy(bssidEnd = before?.bssid, error = target.missingConfiguration)
            } else {
                base.copy(error = NOT_ON_WIFI)
            }
        }

        val errors = mutableListOf<String>()
        val latency = tracker.run(target.label, ThroughputPhase.LATENCY, null) {
            attempt("latency", errors, target.latency)?.let(::round)
        }
        var download: Double? = null
        var upload: Double? = null
        if (latency == null) {
            // Could not even open a connection: the transfers would only add more timeouts.
            errors += "download/upload skipped: server unreachable"
            tracker.skip(target.label, ThroughputPhase.DOWNLOAD, ThroughputPhase.UPLOAD)
        } else {
            download = tracker.run(target.label, ThroughputPhase.DOWNLOAD, target.durationSeconds) {
                attempt("download", errors, target.download)?.let(::toMbps)
            }
            val uploadTest = target.upload
            if (uploadTest == null) {
                errors += "upload: not configured"
                tracker.skip(target.label, ThroughputPhase.UPLOAD)
            } else {
                upload = tracker.run(target.label, ThroughputPhase.UPLOAD, target.durationSeconds) {
                    attempt("upload", errors, uploadTest)?.let(::toMbps)
                }
            }
        }
        return base.copy(
            bssidEnd = connection()?.bssid,
            latencyMs = latency,
            downMbps = download,
            upMbps = upload,
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

    private fun toMbps(bitsPerSecond: Double): Double = round(bitsPerSecond / 1_000_000.0)

    private fun round(value: Double): Double = (value * 10).roundToLong() / 10.0

    /** Numbers the phases across all targets and reports each one as it starts. */
    private class ProgressTracker(val totalSteps: Int, val listener: (ThroughputProgress) -> Unit) {
        private var step = 0
        private val finished = mutableListOf<ThroughputPhaseResult>()

        fun run(target: String, phase: ThroughputPhase, seconds: Int?, block: () -> Double?): Double? {
            step++
            listener(ThroughputProgress(step, totalSteps, target, phase, seconds, finished.toList()))
            return block().also { finished += ThroughputPhaseResult(target, phase, it) }
        }

        fun skip(target: String, vararg phases: ThroughputPhase = ThroughputPhase.entries.toTypedArray()) {
            phases.forEach { phase ->
                step++
                finished += ThroughputPhaseResult(target, phase, null)
            }
        }
    }

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

    private fun mbps(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
}
