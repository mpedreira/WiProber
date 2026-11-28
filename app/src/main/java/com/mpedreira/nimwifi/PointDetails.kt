package com.mpedreira.nimwifi

import java.util.Locale

/**
 * Everything measured at one physical point, newest first: its passive scans and its active
 * runs. Android-free; [format] turns it into the text of the point sheet.
 */
data class PointDetails(
    val pointId: String,
    val label: String,
    val x: Float,
    val y: Float,
    val imported: Boolean,
    val planned: Boolean,
    val scans: List<Scan>,
    val runs: List<ActiveRun>,
    /** RC4 · 3: [PointStatus] of the point. */
    val status: String = PointStatus.of(scans.isNotEmpty(), runs),
    /** RC4 · 3: the last attempt of each service (target, network test, script), newest first. */
    val services: List<ServiceHistory> = ServiceHistory.of(runs)
) {
    data class Scan(val scanId: String?, val at: Long, val networks: Int, val strongestBssid: String?, val strongestRssi: Int?,
                    val frequency: Int? = null)

    /** Latest local scan: the top of the sheet (SSID detected or not in a valid scan). */
    val latestScan: Scan? get() = scans.firstOrNull()

    /** The latest finished run, when its outcome is not per service (skipped, wrong Wi-Fi, interrupted). */
    val lastRun: ActiveRun? get() = runs.firstOrNull { it.isFinished }

    companion object {
        /** [band]: only that band of [ssid] counts as its signal (map network = SSID + band). */
        fun of(state: SurveyScreenState, pointId: String, ssid: String?, band: String? = null): PointDetails? {
            val position = state.pointPosition(pointId) ?: return null
            val scans = state.scanPoints.filter { it.pointId == pointId }
            val label = SsidCoverage.labels(state.scanPoints, state.plannedPoints)[pointId] ?: pointId
            return PointDetails(
                pointId = pointId,
                label = label,
                x = position.x,
                y = position.y,
                imported = scans.isNotEmpty() && scans.all { it.importedFromEsx },
                planned = state.plannedPoints.any { it.pointId == pointId },
                scans = scans.filterNot { it.importedFromEsx }.sortedByDescending { it.timestamp }.map { scan ->
                    val best = ssid?.let { name ->
                        scan.wifiNetworks.filter { it.ssid == name && (band == null || WifiBands.of(it.frequency) == band) }.maxByOrNull { it.level }
                    }
                    Scan(scan.scanId, scan.timestamp, scan.wifiNetworks.size, best?.bssid, best?.level, best?.frequency)
                },
                runs = state.activeRuns.filter { it.pointId == pointId }.sortedByDescending { it.startedAt }
            )
        }

        /** Result lines of one run: one per target, network test and script. */
        fun resultLines(run: ActiveRun): List<String> {
            val results = run.results ?: return emptyList()
            fun n(value: Double?, unit: String) = value?.let { String.format(Locale.ROOT, "%.1f %s", it, unit) } ?: "—"
            return buildList {
                results.tests.forEach { t ->
                    val change = if (t.associationChange != null && t.associationChange != AssociationChange.SAME &&
                        t.associationChange != AssociationChange.UNKNOWN) " · ${t.associationChange}" else ""
                    add("${t.target}: ↓ ${n(t.downMbps, "Mbps")} ↑ ${n(t.upMbps, "Mbps")} · ${n(t.latencyMs, "ms")}" +
                        (t.rssi?.let { " · $it dBm" } ?: "") + (t.bssidStart?.let { " · $it" } ?: "") + change +
                        (listOfNotNull(t.downloadErrorCode, t.uploadErrorCode, t.latencyErrorCode).distinct()
                            .takeIf { it.isNotEmpty() }?.joinToString(prefix = " ⚠ ") ?: ""))
                }
                results.networkTests.orEmpty().forEach { t ->
                    val value = t.dnsMs ?: t.rttAvgMs ?: t.connectMs ?: t.totalMs
                    add("${t.alias ?: t.name} (${t.type}${t.method?.let { "/$it" } ?: ""}): " +
                        (if (t.ok) n(value, "ms") + (t.lossPct?.takeIf { it > 0 }?.let { " · ${it}%" } ?: "") else "⚠ ${t.errorCode ?: "error"}") +
                        (t.redirectLocation?.let { " → $it" } ?: ""))
                }
                results.scripts.orEmpty().forEach { s ->
                    val ok = s.steps.count { it.ok }
                    add("${s.alias ?: s.name}: $ok/${s.stepsTotal ?: s.steps.size}" +
                        (s.steps.mapNotNull { it.totalMs }.takeIf { it.isNotEmpty() }?.let { " · ${n(it.sum(), "ms")}" } ?: "") +
                        (s.errorCode ?: s.steps.firstOrNull { !it.ok }?.errorCode)?.let { " ⚠ $it" }.orEmpty())
                }
            }
        }
    }
}

/**
 * RC4 · 3: the state of a point on the map. [PENDING]: nothing measured here yet (planned or
 * imported point). [COVERAGE]: a local scan, no active tests. [PERFORMANCE]: its latest run
 * passed every test it ran. [FAILURE]: its latest run failed, or a test in it did.
 */
object PointStatus {
    const val PENDING = "pending"
    const val COVERAGE = "coverage"
    const val PERFORMANCE = "performance"
    const val FAILURE = "failure"

    /** Runs the user stopped or skipped say nothing about the point: the one before decides. */
    private fun decisive(run: ActiveRun) = run.isFinished && run.status != RunStatus.CANCELLED &&
        !(run.status == RunStatus.SKIPPED && run.statusReason == ErrorCodes.SKIPPED_BY_USER)

    fun of(hasLocalScan: Boolean, runs: List<ActiveRun>): String {
        val latest = runs.sortedByDescending { it.startedAt }.firstOrNull(::decisive)
            ?: return if (hasLocalScan) COVERAGE else PENDING
        val failed = latest.status != RunStatus.COMPLETED || ServiceHistory.attempts(latest).any { !it.ok } ||
            latest.phases.any { it.status == RunStatus.FAILED }
        return if (failed) FAILURE else PERFORMANCE
    }

    /** Status of every point of the floor (local, imported and planned). */
    fun all(state: SurveyScreenState): Map<String, String> {
        val runs = state.activeRuns.groupBy { it.pointId }
        val local = state.scanPoints.filterNot { it.importedFromEsx }.mapNotNull { it.pointId }.toSet()
        val ids = LinkedHashSet<String>().apply {
            state.scanPoints.mapNotNullTo(this) { it.pointId }
            state.plannedPoints.mapTo(this) { it.pointId }
            addAll(runs.keys)
        }
        return ids.associateWith { of(it in local, runs[it].orEmpty()) }
    }
}

/** One attempt of one service at a point: its result, the reason when it failed and the HTTP route. */
data class ServiceAttempt(
    /** `target:<name>`, `test:<name>` or `script:<name>`: the same service across runs. */
    val key: String,
    val label: String,
    val at: Long,
    val ok: Boolean,
    /** Measured value, already formatted (`↓ 90.0 ↑ 40.0 Mbps · 3.0 ms`, `12.0 ms`, `3/3 · 410.0 ms`). */
    val value: String?,
    /** Error code of the failure ([ErrorCodes]); null when it passed. */
    val reason: String?,
    val proxyConfig: String? = null,
    val route: String? = null,
    val httpStatus: Int? = null,
    /** HTTP 429: when the server may be tried again (epoch ms). */
    val retryAt: Long? = null
)

/** The last attempt of a service and, when it failed, its last valid result before it. */
data class ServiceHistory(val last: ServiceAttempt, val lastValid: ServiceAttempt?) {
    companion object {
        private fun n(value: Double?, unit: String) = value?.let { String.format(Locale.ROOT, "%.1f %s", it, unit) } ?: "—"

        /** The attempt ended without an error code and without a value (sheet only, never exported). */
        const val NO_RESULT = "no_result"

        /** The attempts of one run: one per target, network test and script. */
        fun attempts(run: ActiveRun): List<ServiceAttempt> {
            val results = run.results ?: return emptyList()
            return buildList {
                results.tests.forEach { t ->
                    val errors = listOfNotNull(t.downloadErrorCode, t.uploadErrorCode, t.latencyErrorCode)
                    val measured = t.downMbps != null || t.upMbps != null || t.latencyMs != null
                    val failedDirection = if (t.downloadErrorCode != null) "download" else if (t.uploadErrorCode != null) "upload" else null
                    add(ServiceAttempt(
                        key = "target:${t.target}", label = t.target, at = t.measuredAt,
                        ok = errors.isEmpty() && measured,
                        value = if (measured) "↓ ${n(t.downMbps, "Mbps")} ↑ ${n(t.upMbps, "Mbps")} · ${n(t.latencyMs, "ms")}" else null,
                        reason = errors.firstOrNull() ?: if (!measured) NO_RESULT else null,
                        proxyConfig = if (failedDirection == "upload") t.uploadProxyConfig else t.downloadProxyConfig ?: t.uploadProxyConfig,
                        route = if (failedDirection == "upload") t.uploadRoute else t.downloadRoute ?: t.uploadRoute,
                        httpStatus = if (failedDirection == "upload") t.uploadHttpStatus else t.downloadHttpStatus ?: t.uploadHttpStatus,
                        retryAt = t.downloadRetryAt ?: t.uploadRetryAt
                    ))
                }
                results.networkTests.orEmpty().forEach { t ->
                    val value = t.dnsMs ?: t.rttAvgMs ?: t.connectMs ?: t.totalMs
                    add(ServiceAttempt(
                        key = "test:${t.name}", label = t.alias ?: t.name, at = t.measuredAt, ok = t.ok,
                        value = value?.let { n(it, "ms") + (t.lossPct?.takeIf { l -> l > 0 }?.let { l -> " · $l%" } ?: "") },
                        reason = if (t.ok) null else t.errorCode ?: NO_RESULT,
                        proxyConfig = t.proxyConfig, route = t.route, httpStatus = t.status
                    ))
                }
                results.scripts.orEmpty().forEach { s ->
                    val failed = s.steps.firstOrNull { !it.ok }
                    val ok = s.errorCode == null && failed == null && s.steps.isNotEmpty()
                    val step = failed ?: s.steps.lastOrNull()
                    add(ServiceAttempt(
                        key = "script:${s.name}", label = s.alias ?: s.name, at = s.measuredAt, ok = ok,
                        value = "${s.steps.count { it.ok }}/${s.stepsTotal ?: s.steps.size}" +
                            (s.steps.mapNotNull { it.totalMs }.takeIf { it.isNotEmpty() }?.let { " · ${n(it.sum(), "ms")}" } ?: ""),
                        reason = if (ok) null else s.errorCode ?: failed?.errorCode ?: NO_RESULT,
                        proxyConfig = step?.proxyConfig, route = step?.route, httpStatus = step?.status
                    ))
                }
            }
        }

        /** Per service: its last attempt and, if that failed, the last valid one (newest service first). */
        fun of(runs: List<ActiveRun>): List<ServiceHistory> {
            val attempts = runs.sortedByDescending { it.startedAt }.flatMap(::attempts)
            return attempts.groupBy { it.key }.values.map { list ->
                val last = list.first()
                ServiceHistory(last, if (last.ok) null else list.drop(1).firstOrNull { it.ok })
            }.sortedByDescending { it.last.at }
        }
    }
}
