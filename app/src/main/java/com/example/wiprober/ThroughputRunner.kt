package com.example.wiprober

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong

/** Phases of one target, in the order they run. */
enum class ThroughputPhase { LATENCY, DOWNLOAD, UPLOAD, NETWORK_TEST, SCRIPT_STEP }

/**
 * Outcome of a finished phase: Mbps for transfers, ms otherwise; null if it failed or was
 * skipped. [detail] names the network test or script step.
 */
data class ThroughputPhaseResult(
    val target: String,
    val phase: ThroughputPhase,
    val value: Double?,
    val detail: String? = null
)

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
    val finished: List<ThroughputPhaseResult>,
    val detail: String? = null
)

/** Channel width and BSS Load of an AP, taken from the scan made at the point. */
data class BeaconInfo(val channelWidthMhz: Int?, val bssLoad: BssLoad?)

/**
 * Runs the configured per-point tests sequentially on one [MeasurementNetwork] and keeps going
 * when a direction fails, so a point always gets whatever could be measured plus the reason for
 * what could not. Targets resolve on that network; the TCP pre-check picks the address
 * ([AddressPolicy]) that every later connection of the target uses. Without a network nothing is
 * sent; if the network disappears, the remaining phases end with [ErrorCodes.WIFI_LOST].
 * Blocking: call it off the main thread.
 */
class ThroughputRunner(
    private val iperf: Iperf3Client,
    private val http: HttpSpeedTest,
    private val network: MeasurementNetwork?,
    /** Beacons of the point's scan by lower-case BSSID (channel width, BSS Load of the connected AP). */
    private val beacons: Map<String, BeaconInfo> = emptyMap(),
    private val device: DeviceInfo? = null,
    private val networkTests: NetworkTestRunner? = null,
    private val scriptRunner: ScriptRunner? = null,
    private val clock: MillisClock = SystemMillisClock
) {
    /** TCP handshake times in ms over the successful samples, and the address that answered. */
    private class LatencyStats(
        val min: Double, val mean: Double, val jitter: Double, val samples: Int, val address: InetAddress
    )

    private class Target(
        val id: String,
        val alias: String,
        val category: String,
        val method: String,
        val durationSeconds: Int,
        val missingConfiguration: String?,
        val host: String,
        val port: Int,
        val downloadEndpoint: String?,
        val uploadEndpoint: String?,
        val download: (InetAddress) -> Double,
        /** Null when the direction is not configured. */
        val upload: ((InetAddress) -> Double)?
    )

    fun measure(
        settings: ThroughputSettings,
        tests: List<NetworkTest> = emptyList(),
        scripts: List<HttpScript> = emptyList(),
        progress: (ThroughputProgress) -> Unit = {}
    ): PointThroughput {
        val config = settings.sanitized()
        val targets = buildList {
            if (config.iperfEnabled) add(iperfTarget(config))
            if (config.internetEnabled) add(internetTarget(config))
        }
        val enabledTests = tests.filter { it.enabled }
        val enabledScripts = scripts.filter { it.enabled }
        val tracker = ProgressTracker(
            targets.size * TARGET_PHASES + enabledTests.size + enabledScripts.sumOf { it.steps.size },
            progress
        )
        val results = targets.map { measureTarget(it, tracker) }
        val networkResults = enabledTests.map { test -> runNetworkTest(test, tracker) }
        val scriptRuns = enabledScripts.map { script -> runScript(script, tracker) }
        return PointThroughput(
            measuredAt = clock.now(),
            tests = results,
            networkTests = networkResults,
            scripts = scriptRuns
        )
    }

    private fun runNetworkTest(test: NetworkTest, tracker: ProgressTracker): NetworkTestResult {
        var result: NetworkTestResult? = null
        tracker.run(test.displayAlias, ThroughputPhase.NETWORK_TEST, null, detail = test.type) {
            val runner = networkTests
            result = runner?.run(test) ?: NetworkTestResult(
                name = test.name, type = test.type, target = null, method = null,
                measuredAt = clock.now(), alias = test.displayAlias, category = TargetCategory.INTERNAL,
                error = "network tests unavailable", errorCode = ErrorCodes.NOT_CONFIGURED
            )
            result?.takeIf { it.ok }?.let { it.dnsMs ?: it.rttAvgMs ?: it.totalMs }
        }
        return result!!
    }

    private fun runScript(script: HttpScript, tracker: ProgressTracker): ScriptRun {
        val before = network?.association()
        val base = ScriptRun(
            name = script.name, measuredAt = clock.now(),
            ssid = before?.ssid, bssidStart = before?.bssid, rssi = before?.rssi
        )
        val runner = scriptRunner
        val unavailable = when {
            network == null -> NOT_ON_WIFI
            !network.isAvailable() -> ErrorCodes.WIFI_LOST
            runner == null -> "scripts unavailable"
            else -> null
        }
        if (unavailable != null || runner == null) {
            tracker.skipSteps(script.name, script.steps.size)
            return base.copy(error = unavailable)
        }
        val results = runner.run(
            script,
            onStep = { _, step -> tracker.startStep(script.name, step.name) },
            onResult = { result -> tracker.finishStep(script.name, result) }
        )
        return base.copy(bssidEnd = network?.association()?.bssid, steps = results)
    }

    private fun iperfTarget(config: ThroughputSettings) = Target(
        id = TargetIds.IPERF,
        alias = config.iperfLabel,
        category = TargetCategory.PRIVATE_IPERF,
        method = ThroughputTestResult.METHOD_IPERF3_TCP,
        durationSeconds = config.iperfDurationSeconds,
        missingConfiguration = "iPerf3 server not configured".takeIf { config.iperfHost.isEmpty() },
        host = config.iperfHost,
        port = config.iperfPort,
        downloadEndpoint = "${config.iperfHost}:${config.iperfPort}",
        uploadEndpoint = "${config.iperfHost}:${config.iperfPort}",
        download = { address -> iperf.run(address, config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.DOWNLOAD) },
        upload = { address -> iperf.run(address, config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.UPLOAD) }
    )

    private fun internetTarget(config: ThroughputSettings): Target {
        val download = runCatching { URI(config.internetDownloadUrl.replace("{bytes}", "0")) }.getOrNull()
        val upload = config.internetUploadUrl.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() }
        return Target(
            id = TargetIds.INTERNET,
            alias = ThroughputTestResult.TARGET_INTERNET,
            category = TargetCategory.INTERNET,
            method = ThroughputTestResult.METHOD_HTTP,
            durationSeconds = config.internetDurationSeconds,
            missingConfiguration = when {
                config.internetDownloadUrl.isEmpty() -> "download URL not configured"
                download?.host == null -> "invalid download URL"
                else -> null
            },
            host = download?.host.orEmpty(),
            port = download?.let(::defaultPort) ?: 443,
            downloadEndpoint = download?.host,
            uploadEndpoint = upload?.host,
            download = { http.download(config.internetDownloadUrl, config.internetDurationSeconds) },
            upload = if (upload == null) null else {
                { http.upload(config.internetUploadUrl, config.internetDurationSeconds) }
            }
        )
    }

    private fun defaultPort(uri: URI): Int = if (uri.port > 0) uri.port else if (uri.scheme.equals("http", true)) 80 else 443

    private fun connectionInfo(association: Association?): WifiConnectionInfo? {
        if (association == null || !association.known) return null
        val beacon = association.bssid?.let(beacons::get)
        return association.toConnectionInfo(beacon?.channelWidthMhz, beacon?.bssLoad)
    }

    private fun measureTarget(target: Target, tracker: ProgressTracker): ThroughputTestResult {
        val startAssociation = network?.association()
        val before = connectionInfo(startAssociation)
        val base = ThroughputTestResult(
            target = target.alias,
            method = target.method,
            durationSeconds = target.durationSeconds,
            measuredAt = clock.now(),
            targetId = target.id,
            category = target.category,
            networkId = network?.id,
            networkSelection = network?.selection,
            associationSource = startAssociation?.source,
            downloadEndpoint = target.downloadEndpoint,
            uploadEndpoint = target.uploadEndpoint,
            ssid = before?.ssid,
            bssidStart = before?.bssid,
            rssi = before?.rssi,
            linkMbps = before?.linkSpeedMbps,
            rxLinkMbps = before?.rxLinkSpeedMbps,
            frequencyMhz = before?.frequencyMhz,
            channel = before?.channel,
            channelWidthMhz = before?.channelWidthMhz,
            wifiStandard = before?.wifiStandard,
            bssStationCount = before?.bssLoad?.stationCount,
            bssChannelUtilizationPct = before?.bssLoad?.channelUtilizationPct,
            deviceModel = device?.model,
            androidVersion = device?.androidVersion
        )
        if (network == null) {
            tracker.skip(target.alias)
            return base.copy(error = NOT_ON_WIFI, associationChange = AssociationChange.UNKNOWN)
        }
        if (target.missingConfiguration != null) {
            tracker.skip(target.alias)
            return base.copy(error = target.missingConfiguration, associationChange = AssociationChange.UNKNOWN)
        }

        val errors = mutableListOf<String>()
        var stats: LatencyStats? = null
        val latency = tracker.run(target.alias, ThroughputPhase.LATENCY, null) {
            stats = attempt("latency", errors) { tcpLatency(target.host, target.port) }
            stats?.min?.let(::round)
        }
        var download: Double? = null
        var upload: Double? = null
        val address = stats?.address
        if (address == null) {
            // Could not even open a connection: the transfers would only add more timeouts.
            errors += "download/upload skipped: server unreachable"
            tracker.skip(target.alias, ThroughputPhase.DOWNLOAD, ThroughputPhase.UPLOAD)
        } else {
            download = tracker.run(target.alias, ThroughputPhase.DOWNLOAD, target.durationSeconds) {
                attempt("download", errors) { target.download(address) }?.let(::toMbps)
            }
            val uploadTest = target.upload
            if (uploadTest == null) {
                errors += "upload: not configured"
                tracker.skip(target.alias, ThroughputPhase.UPLOAD)
            } else {
                upload = tracker.run(target.alias, ThroughputPhase.UPLOAD, target.durationSeconds) {
                    attempt("upload", errors) { uploadTest(address) }?.let(::toMbps)
                }
            }
        }
        val endAssociation = network.association()
        return base.copy(
            ssidEnd = endAssociation.ssid,
            bssidEnd = endAssociation.bssid,
            associationChange = AssociationChange.between(startAssociation, endAssociation),
            resolvedAddress = address?.hostAddress,
            latencyMs = latency,
            latencyAvgMs = stats?.mean?.let(::round),
            jitterMs = stats?.jitter?.let(::round),
            latencySamples = stats?.samples,
            downMbps = download,
            upMbps = upload,
            error = errors.joinToString("; ").ifEmpty { null }
        )
    }

    /**
     * TCP handshakes (one network round trip each) on the measured network. The host resolves
     * with that network's resolver; candidates follow [AddressPolicy] and the first one with at
     * least one successful handshake is the address of the target for the rest of the run.
     */
    private fun tcpLatency(host: String, port: Int): LatencyStats {
        val net = network ?: throw MeasurementException(ErrorCodes.NOT_ON_WIFI)
        val candidates = AddressPolicy.candidates(net.resolve(host))
        if (candidates.isEmpty()) throw MeasurementException(ErrorCodes.RESOLUTION_FAILED, "no address for the host")
        var lastError: Exception? = null
        fun sample(address: InetAddress): Double? = try {
            net.socketFactory.createSocket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                (System.nanoTime() - start) / 1_000_000.0
            }
        } catch (e: IOException) {
            lastError = e
            if (!net.isAvailable()) throw MeasurementException(ErrorCodes.WIFI_LOST, cause = e)
            null
        }
        for (address in candidates) {
            // A candidate whose first handshake fails is abandoned at once for the next one.
            val first = sample(address) ?: continue
            val samples = listOf(first) + (2..LATENCY_SAMPLES).mapNotNull { sample(address) }
            return latencyStats(samples, address)
        }
        throw lastError ?: MeasurementException(ErrorCodes.CONNECT_FAILED)
    }

    private fun latencyStats(samples: List<Double>, address: InetAddress) = LatencyStats(
        min = samples.min(),
        mean = samples.average(),
        // Mean absolute difference between consecutive samples (as ping tools report jitter).
        jitter = samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().takeIf { !it.isNaN() } ?: 0.0,
        samples = samples.size,
        address = address
    )

    /** Runs a phase; a failure becomes "label: code (detail)", and a vanished network is reported as such. */
    private fun <T> attempt(label: String, errors: MutableList<String>, block: () -> T): T? {
        if (network?.isAvailable() == false) {
            errors += "$label: ${ErrorCodes.WIFI_LOST}"
            return null
        }
        return try {
            block()
        } catch (e: Exception) {
            val code = if (network?.isAvailable() == false) ErrorCodes.WIFI_LOST else ErrorClassifier.code(e)
            errors += "$label: $code (${ErrorClassifier.detail(e)})"
            null
        }
    }

    private fun toMbps(bitsPerSecond: Double): Double = round(bitsPerSecond / 1_000_000.0)

    private fun round(value: Double): Double = (value * 10).roundToLong() / 10.0

    /** Numbers the phases across all targets and reports each one as it starts. */
    private class ProgressTracker(val totalSteps: Int, val listener: (ThroughputProgress) -> Unit) {
        private var step = 0
        private val finished = mutableListOf<ThroughputPhaseResult>()

        fun run(
            target: String,
            phase: ThroughputPhase,
            seconds: Int?,
            detail: String? = null,
            block: () -> Double?
        ): Double? {
            step++
            listener(ThroughputProgress(step, totalSteps, target, phase, seconds, finished.toList(), detail))
            return block().also { finished += ThroughputPhaseResult(target, phase, it, detail) }
        }

        fun startStep(script: String, stepName: String) {
            step++
            listener(ThroughputProgress(step, totalSteps, script, ThroughputPhase.SCRIPT_STEP, null, finished.toList(), stepName))
        }

        fun finishStep(script: String, result: ScriptStepResult) {
            finished += ThroughputPhaseResult(script, ThroughputPhase.SCRIPT_STEP, result.totalMs?.takeIf { result.ok }, result.name)
        }

        fun skipSteps(script: String, count: Int) {
            repeat(count) {
                step++
                finished += ThroughputPhaseResult(script, ThroughputPhase.SCRIPT_STEP, null)
            }
        }

        fun skip(target: String, vararg phases: ThroughputPhase = TARGET_PHASE_LIST) {
            phases.forEach { phase ->
                step++
                finished += ThroughputPhaseResult(target, phase, null)
            }
        }
    }

    companion object {
        const val NOT_ON_WIFI = "not connected to a Wi-Fi network"
        private val TARGET_PHASE_LIST = arrayOf(ThroughputPhase.LATENCY, ThroughputPhase.DOWNLOAD, ThroughputPhase.UPLOAD)
        private val TARGET_PHASES = TARGET_PHASE_LIST.size
        private const val LATENCY_SAMPLES = 10
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
