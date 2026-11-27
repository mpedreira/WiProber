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
    val runs: List<ActiveRun>
) {
    data class Scan(val scanId: String?, val at: Long, val networks: Int, val strongestBssid: String?, val strongestRssi: Int?)

    companion object {
        fun of(state: SurveyScreenState, pointId: String, ssid: String?): PointDetails? {
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
                    val best = ssid?.let { name -> scan.wifiNetworks.filter { it.ssid == name }.maxByOrNull { it.level } }
                    Scan(scan.scanId, scan.timestamp, scan.wifiNetworks.size, best?.bssid, best?.level)
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
