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

/** Receives a run's progress; called on the measuring thread. */
interface RunListener {
    /** A phase is starting. */
    fun onProgress(progress: ThroughputProgress) {}

    /** A planned phase ([PhaseRecord.key]) finished with [status] ([RunStatus]) and an optional error code. */
    fun onPhase(key: String, status: String, errorCode: String?) {}

    /** Results finished so far, to be saved progressively. */
    fun onPartial(results: PointThroughput) {}
}

/**
 * Runs the configured per-point tests sequentially on one [MeasurementNetwork] and keeps going
 * when a phase fails, so a point always gets whatever could be measured plus the reason for what
 * could not. Targets resolve on that network; the TCP pre-check picks the address
 * ([AddressPolicy]) that every later connection of the target uses. Without a network nothing is
 * sent; if the network disappears, the remaining phases end with [ErrorCodes.WIFI_LOST].
 * Every phase runs under a monotonic deadline of [control]; cancelling [control] stops the run
 * with [MeasurementCancelled] after the finished phases were reported.
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
    private val clock: MillisClock = SystemMillisClock,
    private val control: MeasurementControl = MeasurementControl()
) {
    /** TCP handshake times in ms over the successful samples, and the address that answered. */
    private class LatencyStats(
        val min: Double, val mean: Double, val jitter: Double, val samples: Int, val failures: Int, val address: InetAddress
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
        /** The upload goes to another host than the pre-checked one: it is evaluated on its own. */
        val uploadIndependent: Boolean,
        val download: (InetAddress) -> TransferResult,
        /** Null when the direction is not configured. */
        val upload: ((InetAddress?) -> TransferResult)?
    )

    /** Outcome of one phase: value or error, with the facts needed for the export. */
    private class PhaseOutcome<T>(val value: T?, val status: String, val code: String?)

    fun measure(
        settings: ThroughputSettings,
        tests: List<NetworkTest> = emptyList(),
        scripts: List<HttpScript> = emptyList(),
        listener: RunListener = object : RunListener {}
    ): PointThroughput {
        val config = settings.sanitized()
        val targets = targets(config)
        val enabledTests = tests.filter { it.enabled }
        val enabledScripts = scripts.filter { it.enabled }
        val tracker = ProgressTracker(
            targets.size * TARGET_PHASES + enabledTests.size + enabledScripts.sumOf { it.steps.size },
            listener
        )
        var partial = PointThroughput(measuredAt = clock.now(), tests = emptyList(), networkTests = emptyList(), scripts = emptyList())
        targets.forEach { target ->
            partial = partial.copy(tests = partial.tests + measureTarget(target, tracker))
            listener.onPartial(partial)
        }
        enabledTests.forEach { test ->
            partial = partial.copy(networkTests = partial.networkTests.orEmpty() + runNetworkTest(test, tracker))
            listener.onPartial(partial)
        }
        enabledScripts.forEach { script ->
            partial = partial.copy(scripts = partial.scripts.orEmpty() + runScript(script, tracker))
            listener.onPartial(partial)
        }
        return partial
    }

    private fun targets(config: ThroughputSettings) = buildList {
        if (config.iperfEnabled) add(iperfTarget(config))
        if (config.internetEnabled) add(internetTarget(config))
    }

    private fun runNetworkTest(test: NetworkTest, tracker: ProgressTracker): NetworkTestResult {
        var result: NetworkTestResult? = null
        tracker.run(test.displayAlias, ThroughputPhase.NETWORK_TEST, null, detail = test.type) {
            val runner = networkTests
            result = try {
                control.phase(networkTestDeadline(test)) {
                    runner?.run(test) ?: NetworkTestResult(
                        name = test.name, type = test.type, target = null, method = null,
                        measuredAt = clock.now(), alias = test.displayAlias, category = TargetCategory.INTERNAL,
                        error = "network tests unavailable", errorCode = ErrorCodes.NOT_CONFIGURED
                    )
                }
            } catch (e: MeasurementCancelled) {
                throw e
            } catch (e: Exception) {
                val code = ErrorClassifier.code(e)
                NetworkTestResult(
                    name = test.name, type = test.type, target = null, method = null, measuredAt = clock.now(),
                    alias = test.displayAlias, category = TargetCategory.INTERNAL,
                    error = "$code: ${ErrorClassifier.detail(e)}", errorCode = code
                )
            }
            result?.takeIf { it.ok }?.let { it.dnsMs ?: it.rttAvgMs ?: it.totalMs }
        }
        val finished = result!!
        tracker.phaseDone(testKey(test), if (finished.ok) RunStatus.COMPLETED else RunStatus.FAILED, finished.errorCode)
        return finished
    }

    private fun runScript(script: HttpScript, tracker: ProgressTracker): ScriptRun {
        val before = network?.association()
        val base = ScriptRun(
            name = script.name, measuredAt = clock.now(), alias = script.displayAlias, stepsTotal = script.steps.size,
            ssid = before?.ssid, bssidStart = before?.bssid, rssi = before?.rssi
        )
        val runner = scriptRunner
        val unavailable = when {
            network == null -> ErrorCodes.NOT_ON_WIFI
            !network.isAvailable() -> ErrorCodes.WIFI_LOST
            runner == null -> ErrorCodes.NOT_CONFIGURED
            else -> null
        }
        if (unavailable != null || runner == null) {
            script.steps.indices.forEach { index -> tracker.phaseDone(scriptKey(script, index), RunStatus.SKIPPED, unavailable) }
            tracker.skipSteps(script.displayAlias, script.steps.size)
            return base.copy(error = if (unavailable == ErrorCodes.NOT_ON_WIFI) NOT_ON_WIFI else unavailable, errorCode = unavailable)
        }
        var index = 0
        val results = runner.run(
            script,
            onStep = { i, step ->
                control.checkActive()
                index = i
                tracker.startStep(script.displayAlias, step.name)
            },
            onResult = { result ->
                tracker.finishStep(script.displayAlias, result)
                tracker.phaseDone(scriptKey(script, index), if (result.ok) RunStatus.COMPLETED else RunStatus.FAILED, result.errorCode)
            }
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
        uploadIndependent = false,
        download = { address -> iperf.run(address, config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.DOWNLOAD) },
        upload = { address ->
            iperf.run(requireNotNull(address), config.iperfPort, config.iperfDurationSeconds, Iperf3Client.Direction.UPLOAD)
        }
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
            uploadIndependent = upload?.host != null && upload.host != download?.host,
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
            androidVersion = device?.androidVersion,
            latencyMethod = LATENCY_METHOD
        )
        val keys = TARGET_PHASE_NAMES.map { targetKey(target.id, it) }
        val notRunCode = when {
            network == null -> ErrorCodes.NOT_ON_WIFI
            target.missingConfiguration != null -> ErrorCodes.NOT_CONFIGURED
            else -> null
        }
        if (notRunCode != null) {
            tracker.skip(target.alias)
            keys.forEach { tracker.phaseDone(it, RunStatus.SKIPPED, notRunCode) }
            return base.copy(
                error = if (network == null) NOT_ON_WIFI else target.missingConfiguration,
                associationChange = AssociationChange.UNKNOWN,
                latencyStatus = RunStatus.SKIPPED, latencyErrorCode = notRunCode,
                downloadStatus = RunStatus.SKIPPED, downloadErrorCode = notRunCode,
                uploadStatus = RunStatus.SKIPPED, uploadErrorCode = notRunCode
            )
        }

        val errors = mutableListOf<String>()
        var latency: PhaseOutcome<LatencyStats>? = null
        tracker.run(target.alias, ThroughputPhase.LATENCY, null) {
            latency = phase("latency", errors, LATENCY_DEADLINE_MILLIS) { tcpLatency(target.host, target.port) }
            latency?.value?.min?.let(::round)
        }
        val stats = latency!!.value
        val latencyCode = latency!!.code ?: if (stats != null && stats.failures > 0) ErrorCodes.PARTIAL_LOSS else null
        tracker.phaseDone(keys[0], latency!!.status, latencyCode)

        val address = stats?.address
        val transferDeadline = (target.durationSeconds + TRANSFER_GRACE_SECONDS) * 1_000L
        val download: PhaseOutcome<TransferResult>
        if (address == null) {
            // The pre-checked endpoint is unreachable: its transfers would only add more timeouts.
            errors += "download skipped: server unreachable"
            download = PhaseOutcome(null, RunStatus.SKIPPED, ErrorCodes.SKIPPED_UNREACHABLE)
            tracker.skip(target.alias, ThroughputPhase.DOWNLOAD)
        } else {
            var outcome: PhaseOutcome<TransferResult>? = null
            tracker.run(target.alias, ThroughputPhase.DOWNLOAD, target.durationSeconds) {
                outcome = phase("download", errors, transferDeadline) { target.download(address) }
                outcome?.value?.let { toMbps(it.bitsPerSecond) }
            }
            download = outcome!!
        }
        tracker.phaseDone(keys[1], download.status, download.code)

        val uploadTest = target.upload
        val upload: PhaseOutcome<TransferResult> = when {
            uploadTest == null -> {
                errors += "upload: not configured"
                tracker.skip(target.alias, ThroughputPhase.UPLOAD)
                PhaseOutcome(null, RunStatus.SKIPPED, ErrorCodes.NOT_CONFIGURED)
            }
            address == null && !target.uploadIndependent -> {
                errors += "upload skipped: server unreachable"
                tracker.skip(target.alias, ThroughputPhase.UPLOAD)
                PhaseOutcome(null, RunStatus.SKIPPED, ErrorCodes.SKIPPED_UNREACHABLE)
            }
            else -> {
                var outcome: PhaseOutcome<TransferResult>? = null
                tracker.run(target.alias, ThroughputPhase.UPLOAD, target.durationSeconds) {
                    outcome = phase("upload", errors, transferDeadline) { uploadTest(address) }
                    outcome?.value?.let { toMbps(it.bitsPerSecond) }
                }
                outcome!!
            }
        }
        tracker.phaseDone(keys[2], upload.status, upload.code)

        val endAssociation = network!!.association()
        return base.copy(
            ssidEnd = endAssociation.ssid,
            bssidEnd = endAssociation.bssid,
            associationChange = AssociationChange.between(startAssociation, endAssociation),
            resolvedAddress = address?.hostAddress,
            latencyMs = stats?.min?.let(::round),
            latencyAvgMs = stats?.mean?.let(::round),
            jitterMs = stats?.jitter?.let(::round),
            latencySamples = stats?.samples,
            latencyFailures = stats?.failures,
            latencyStatus = latency!!.status,
            latencyErrorCode = latencyCode,
            downMbps = download.value?.let { toMbps(it.bitsPerSecond) },
            downBytes = download.value?.bytes,
            downSeconds = download.value?.seconds?.let(::round),
            downloadStatus = download.status,
            downloadErrorCode = download.code,
            upMbps = upload.value?.let { toMbps(it.bitsPerSecond) },
            upBytes = upload.value?.bytes,
            upSeconds = upload.value?.seconds?.let(::round),
            uploadStatus = upload.status,
            uploadErrorCode = upload.code,
            error = errors.joinToString("; ").ifEmpty { null }
        )
    }

    /**
     * TCP handshakes (one network round trip each) on the measured network. The host resolves
     * with that network's resolver; candidates follow [AddressPolicy]. A candidate whose first
     * handshake fails is abandoned for the next one; once one answers, all its samples are taken
     * and failed ones are counted, not discarded with the valid ones.
     */
    private fun tcpLatency(host: String, port: Int): LatencyStats {
        val net = network ?: throw MeasurementException(ErrorCodes.NOT_ON_WIFI)
        val candidates = AddressPolicy.candidates(net.resolve(host))
        if (candidates.isEmpty()) throw MeasurementException(ErrorCodes.RESOLUTION_FAILED, "no address for the host")
        var lastError: Exception? = null
        fun sample(address: InetAddress): Double? = try {
            control.using(net.socketFactory.createSocket()) { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                (System.nanoTime() - start) / 1_000_000.0
            }
        } catch (e: MeasurementCancelled) {
            throw e
        } catch (e: IOException) {
            control.checkActive()
            lastError = e
            if (!net.isAvailable()) throw MeasurementException(ErrorCodes.WIFI_LOST, cause = e)
            null
        }
        for (address in candidates) {
            val first = sample(address) ?: continue
            val rest = (2..LATENCY_SAMPLES).map { sample(address) }
            val ok = listOf(first) + rest.filterNotNull()
            return latencyStats(ok, rest.count { it == null }, address)
        }
        throw lastError ?: MeasurementException(ErrorCodes.CONNECT_FAILED)
    }

    private fun latencyStats(samples: List<Double>, failures: Int, address: InetAddress) = LatencyStats(
        min = samples.min(),
        mean = samples.average(),
        // Mean absolute difference between consecutive samples (as ping tools report jitter).
        jitter = samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().takeIf { !it.isNaN() } ?: 0.0,
        samples = samples.size,
        failures = failures,
        address = address
    )

    /**
     * Runs one phase under its deadline. A failure becomes "label: code (detail)" and a vanished
     * network is reported as such; cancellation propagates.
     */
    private fun <T> phase(label: String, errors: MutableList<String>, deadlineMillis: Long, block: () -> T): PhaseOutcome<T> {
        control.checkActive()
        if (network?.isAvailable() == false) {
            errors += "$label: ${ErrorCodes.WIFI_LOST}"
            return PhaseOutcome(null, RunStatus.FAILED, ErrorCodes.WIFI_LOST)
        }
        return try {
            PhaseOutcome(control.phase(deadlineMillis, block), RunStatus.COMPLETED, null)
        } catch (e: MeasurementCancelled) {
            throw e
        } catch (e: Exception) {
            val code = if (network?.isAvailable() == false) ErrorCodes.WIFI_LOST else ErrorClassifier.code(e)
            errors += "$label: $code (${ErrorClassifier.detail(e)})"
            PhaseOutcome(null, RunStatus.FAILED, code)
        }
    }

    private fun toMbps(bitsPerSecond: Double): Double = round(bitsPerSecond / 1_000_000.0)

    private fun round(value: Double): Double = (value * 10).roundToLong() / 10.0

    /** Numbers the phases across all targets and reports each one as it starts. */
    private class ProgressTracker(val totalSteps: Int, val listener: RunListener) {
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
            listener.onProgress(ThroughputProgress(step, totalSteps, target, phase, seconds, finished.toList(), detail))
            return block().also { finished += ThroughputPhaseResult(target, phase, it, detail) }
        }

        fun phaseDone(key: String, status: String, code: String?) = listener.onPhase(key, status, code)

        fun startStep(script: String, stepName: String) {
            step++
            listener.onProgress(ThroughputProgress(step, totalSteps, script, ThroughputPhase.SCRIPT_STEP, null, finished.toList(), stepName))
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
        const val LATENCY_METHOD = "tcp-connect"
        private val TARGET_PHASE_LIST = arrayOf(ThroughputPhase.LATENCY, ThroughputPhase.DOWNLOAD, ThroughputPhase.UPLOAD)
        private val TARGET_PHASE_NAMES = listOf("latency", "download", "upload")
        private val TARGET_PHASES = TARGET_PHASE_LIST.size
        private const val LATENCY_SAMPLES = 10
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val LATENCY_DEADLINE_MILLIS = 15_000L
        private const val TRANSFER_GRACE_SECONDS = 10
        private const val SCRIPT_STEP_DEADLINE_MILLIS = 20_000L
        private const val RUN_MARGIN_MILLIS = 15_000L

        fun targetKey(targetId: String, phase: String) = "target:$targetId:$phase"
        fun testKey(test: NetworkTest) = "test:${test.name}"
        fun scriptKey(script: HttpScript, index: Int) = "script:${script.name}:${index + 1}"

        private fun networkTestDeadline(test: NetworkTest): Long = when (test.type) {
            NetworkTest.DNS -> 5_000L
            NetworkTest.PING -> (test.count ?: NetworkTestRunner.DEFAULT_PING_COUNT) * 2_500L + 6_000L
            NetworkTest.TCP -> (test.count ?: NetworkTestRunner.DEFAULT_TCP_COUNT) * 2_500L + 3_000L
            else -> 25_000L
        }

        /** Every phase the run will go through, all pending, in order. */
        fun plan(settings: ThroughputSettings, tests: List<NetworkTest>, scripts: List<HttpScript>): List<PhaseRecord> {
            val config = settings.sanitized()
            return buildList {
                if (config.iperfEnabled) TARGET_PHASE_NAMES.forEach { add(PhaseRecord(targetKey(TargetIds.IPERF, it), "${config.iperfLabel} · $it")) }
                if (config.internetEnabled) TARGET_PHASE_NAMES.forEach { add(PhaseRecord(targetKey(TargetIds.INTERNET, it), "${ThroughputTestResult.TARGET_INTERNET} · $it")) }
                tests.filter { it.enabled }.forEach { add(PhaseRecord(testKey(it), "${it.displayAlias} · ${it.type}")) }
                scripts.filter { it.enabled }.forEach { script ->
                    script.steps.forEachIndexed { i, step -> add(PhaseRecord(scriptKey(script, i), "${script.name} · ${step.name}")) }
                }
            }
        }

        /** Monotonic deadline of a whole point: every phase deadline plus a margin. */
        fun runDeadlineMillis(settings: ThroughputSettings, tests: List<NetworkTest>, scripts: List<HttpScript>): Long {
            val config = settings.sanitized()
            fun targetMillis(seconds: Int) = LATENCY_DEADLINE_MILLIS + 2 * (seconds + TRANSFER_GRACE_SECONDS) * 1_000L
            return RUN_MARGIN_MILLIS +
                (if (config.iperfEnabled) targetMillis(config.iperfDurationSeconds) else 0L) +
                (if (config.internetEnabled) targetMillis(config.internetDurationSeconds) else 0L) +
                tests.filter { it.enabled }.sumOf(::networkTestDeadline) +
                scripts.filter { it.enabled }.sumOf { it.steps.size * SCRIPT_STEP_DEADLINE_MILLIS }
        }
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
