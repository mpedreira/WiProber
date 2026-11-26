package com.mpedreira.nimwifi

import kotlin.math.hypot

/** State of a physical point for one SSID (map markers and report). */
object PointState {
    /** No valid local scan at this point: planned, imported from an ESX (opaque binaries) or only empty scans. */
    const val NOT_MEASURED = "not_measured"
    /** A valid local scan (other networks seen) did not detect the SSID. */
    const val SSID_NOT_DETECTED = "ssid_not_detected"
    /** Strongest AP of the SSID below the weak threshold. */
    const val WEAK = "weak"
    const val OK = "ok"
}

/**
 * One physical point (all its scans share the pointId) evaluated for one SSID. [strongestBssid]
 * and [strongestRssi] come from the passive scan (strongest AP of that SSID), which is not
 * necessarily the AP the phone was associated with during active tests.
 */
data class PointCoverage(
    val pointId: String,
    val label: String,
    val x: Float,
    val y: Float,
    val state: String,
    val strongestBssid: String? = null,
    val strongestRssi: Int? = null,
    /** Valid local scans at the point; the state uses the latest one. */
    val scans: Int = 0,
    val imported: Boolean = false,
    val planned: Boolean = false
)

/**
 * Per-SSID point classification. Each SSID is evaluated on its own: a strong guest network
 * never makes a point look covered for the operational one.
 */
object SsidCoverage {
    const val DEFAULT_WEAK_DBM = -70

    /** SSIDs detected in local scans of the floor, most widely detected first. */
    fun ssids(scanPoints: List<ScanPoint>, sessions: List<ContinuousScanSession> = emptyList()): List<String> {
        val counts = mutableMapOf<String, Int>()
        scanPoints.filter(::isValidScan).forEach { point ->
            point.wifiNetworks.map { it.ssid }.filter(String::isNotEmpty).toSet().forEach { counts.merge(it, 1, Int::plus) }
        }
        sessions.filterNot { it.importedFromEsx }.flatMap { it.scanResults }.forEach { scan ->
            scan.wifiNetworks.map { it.ssid }.filter(String::isNotEmpty).toSet().forEach { counts.merge(it, 1, Int::plus) }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
    }

    /** The SSID to show: the chosen one, else the most widely detected; null when nothing was scanned. */
    fun selected(chosen: String, scanPoints: List<ScanPoint>, sessions: List<ContinuousScanSession>): String? =
        chosen.trim().ifEmpty { null } ?: ssids(scanPoints, sessions).firstOrNull()

    /** A local scan that saw at least one network; an empty result is not evidence the SSID is absent. */
    fun isValidScan(point: ScanPoint): Boolean = !point.importedFromEsx && point.wifiNetworks.isNotEmpty()

    fun points(
        scanPoints: List<ScanPoint>,
        planned: List<PlannedPoint>,
        ssid: String,
        weakDbm: Int = DEFAULT_WEAK_DBM
    ): List<PointCoverage> {
        val labels = labels(scanPoints, planned)
        val byPoint = scanPoints.filter { it.pointId != null }.groupBy { it.pointId!! }
        val measured = byPoint.map { (pointId, scans) ->
            val valid = scans.filter(::isValidScan).sortedBy(ScanPoint::timestamp)
            val first = scans.first()
            val latest = valid.lastOrNull()
            val strongest = latest?.wifiNetworks?.filter { it.ssid == ssid }?.maxByOrNull(WifiNetworkInfo::level)
            PointCoverage(
                pointId = pointId,
                label = labels.getValue(pointId),
                x = first.x,
                y = first.y,
                state = when {
                    latest == null -> PointState.NOT_MEASURED
                    strongest == null -> PointState.SSID_NOT_DETECTED
                    strongest.level < weakDbm -> PointState.WEAK
                    else -> PointState.OK
                },
                strongestBssid = strongest?.bssid,
                strongestRssi = strongest?.level,
                scans = valid.size,
                imported = scans.all { it.importedFromEsx },
                planned = planned.any { it.pointId == pointId }
            )
        }
        val pending = planned.filter { it.pointId !in byPoint }.map {
            PointCoverage(it.pointId, labels.getValue(it.pointId), it.x, it.y, PointState.NOT_MEASURED, planned = true)
        }
        return measured + pending
    }

    /** Planned label when there is one, else `P1`, `P2`… in the order points were first measured. */
    fun labels(scanPoints: List<ScanPoint>, planned: List<PlannedPoint>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        planned.forEach { result[it.pointId] = it.label }
        var next = 1
        scanPoints.sortedBy(ScanPoint::timestamp).mapNotNull(ScanPoint::pointId).distinct().forEach { id ->
            if (id !in result) result[id] = "P${next++}"
        }
        return result
    }

    /** The point within [radius] (original-image pixels) of a tap, nearest first. */
    fun pointAt(scanPoints: List<ScanPoint>, planned: List<PlannedPoint>, x: Float, y: Float, radius: Float): String? {
        val candidates = scanPoints.mapNotNull { p -> p.pointId?.let { Triple(it, p.x, p.y) } } +
            planned.map { Triple(it.pointId, it.x, it.y) }
        return candidates
            .map { (id, px, py) -> id to hypot((px - x).toDouble(), (py - y).toDouble()) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first
    }
}
