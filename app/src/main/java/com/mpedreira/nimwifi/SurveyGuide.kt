package com.mpedreira.nimwifi

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Device-side settings of the survey guide: next-point grid and coverage drawing. */
data class SurveyGuideSettings(
    val enabled: Boolean = true,
    val spacingMeters: Double = DEFAULT_SPACING_METERS,
    val coverageRadiusMeters: Double = DEFAULT_RADIUS_METERS,
    /** Only networks with this SSID count for the coverage colour; empty = every network. */
    val ssidFilter: String = "",
    /**
     * With [ssidFilter], only its APs in this band ([WifiBands]: `2.4`, `5`, `6`) colour the map:
     * the primary network is SSID + band. Empty = every band (as before).
     */
    val bandFilter: String = "",
    /** Signal profile ([SignalProfile]) that decides when a point's signal is weak. */
    val profile: String = SignalProfile.DEFAULT.id
) {
    val signal: SignalProfile get() = SignalProfile.of(profile)

    /** The primary network's band, or null for every band. */
    val band: String? get() = bandFilter.ifEmpty { null }

    fun sanitized(): SurveyGuideSettings = copy(
        spacingMeters = spacingMeters.takeIf { it.isFinite() }?.coerceIn(1.0, 100.0) ?: DEFAULT_SPACING_METERS,
        coverageRadiusMeters = coverageRadiusMeters.takeIf { it.isFinite() }?.coerceIn(0.5, 50.0)
            ?: DEFAULT_RADIUS_METERS,
        ssidFilter = ssidFilter.trim(),
        bandFilter = bandFilter.trim().takeIf { it in WifiBands.ORDER && ssidFilter.isNotBlank() }.orEmpty(),
        profile = SignalProfile.of(profile).id
    )

    companion object {
        const val DEFAULT_SPACING_METERS = 10.0
        const val DEFAULT_RADIUS_METERS = 5.0
    }
}

/**
 * Signal requirements per use, the same profiles as the wifianalisys report: a point is weak when
 * the strongest AP of the SSID is below [primaryDbm]; [secondaryDbm] is the second AP needed for
 * roaming. Starting values; validate them with the real devices.
 */
enum class SignalProfile(val id: String, val primaryDbm: Int, val secondaryDbm: Int) {
    PDA("pda", -67, -72),
    VOICE("voz", -67, -67),
    DATA("datos", -70, -75);

    companion object {
        val DEFAULT = PDA
        fun of(id: String?): SignalProfile = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: DEFAULT
    }
}

/** Survey-guide grid in original floor-plan pixels: square cells of [cellPx] from the top-left corner. */
data class GuideGrid(val cellPx: Float, val mapWidth: Int, val mapHeight: Int)

/** Where a measurement was taken (floor-plan pixels) and the strongest signal seen there. */
data class CoverageSample(val x: Float, val y: Float, val bestRssi: Int?)

/**
 * Mejora 18: the RF layer's samples, recomputed only when its inputs change. State lists are
 * immutable and replaced on change, so an identity check skips progress-only updates cheaply.
 */
class CoverageSampleCache(
    private val compute: (List<ScanPoint>, List<ContinuousScanSession>, String, String?) -> List<CoverageSample> =
        { points, sessions, ssid, band -> SurveyGuide.coverageSamples(points, sessions, ssid, band) }
) {
    private var points: List<ScanPoint>? = null
    private var sessions: List<ContinuousScanSession>? = null
    private var ssid: String? = null
    private var band: String? = null
    private var cached: List<CoverageSample> = emptyList()

    var computations = 0
        private set

    fun samples(scanPoints: List<ScanPoint>, continuous: List<ContinuousScanSession>, ssidFilter: String, bandFilter: String? = null): List<CoverageSample> {
        val same = ssid == ssidFilter && band == bandFilter &&
            (points === scanPoints || points == scanPoints) &&
            (sessions === continuous || sessions == continuous)
        if (!same) {
            cached = compute(scanPoints, continuous, ssidFilter, bandFilter)
            computations++
            points = scanPoints
            sessions = continuous
            ssid = ssidFilter
            band = bandFilter
        }
        return cached
    }
}

/**
 * Survey guide (NetSpot-like): which parts of the floor are already measured and where to measure
 * next. Everything is in original floor-plan pixels and needs a calibrated scale, because the
 * grid spacing and the coverage radius are in metres.
 */
object SurveyGuide {
    /** Hard limit so a tiny scale cannot make the grid explode. */
    private const val MAX_GRID_CELLS = 250_000

    /** Penalty for turning away from the current walking direction (0 = straight on, 1 = U-turn). */
    private const val TURN_PENALTY = 0.35

    /** Weakest and strongest signal of the colour gradient, in dBm. */
    const val WEAK_RSSI = -85
    const val STRONG_RSSI = -55

    /**
     * Local RF samples: valid Stop-and-Go scans plus every scan of the local Continuous routes,
     * placed along the route by time. Imported geometry (no local RF), planned points and route
     * waypoints are not samples.
     */
    fun coverageSamples(
        scanPoints: List<ScanPoint>,
        sessions: List<ContinuousScanSession>,
        ssidFilter: String = "",
        /** Only this band ([WifiBands]); null = every band. */
        band: String? = null
    ): List<CoverageSample> = buildList {
        scanPoints.filter(SsidCoverage::isValidScan).forEach { add(CoverageSample(it.x, it.y, bestRssi(it.wifiNetworks, ssidFilter, band))) }
        sessions.filterNot { it.importedFromEsx }.forEach { session ->
            val route = session.waypoints.sortedBy(RoutePointWrapper::timestamp)
            session.scanResults.forEach { scan ->
                positionAt(route, scan.timestamp)?.let { (x, y) ->
                    add(CoverageSample(x, y, bestRssi(scan.wifiNetworks, ssidFilter, band)))
                }
            }
        }
    }

    /**
     * Mejora 11: positions with local RF, in the order they were measured (the last two give the
     * walking direction): valid Stop-and-Go scans and Continuous scans placed on their route.
     * Waypoints alone, planned points and imported geometry without local RF are not measured.
     */
    fun measurementOrder(scanPoints: List<ScanPoint>, sessions: List<ContinuousScanSession>): List<SurveyCoordinate> =
        (scanPoints.filter(SsidCoverage::isValidScan).map { it.timestamp to SurveyCoordinate(it.x, it.y) } +
            sessions.filterNot { it.importedFromEsx }.flatMap { session ->
                val route = session.waypoints.sortedBy(RoutePointWrapper::timestamp)
                session.scanResults.mapNotNull { scan ->
                    positionAt(route, scan.timestamp)?.let { (x, y) -> (session.startTime + scan.timestamp) to SurveyCoordinate(x, y) }
                }
            })
            .sortedBy { it.first }
            .map { it.second }

    /**
     * Next suggested point: the centre of the nearest grid cell that is neither covered nor
     * skipped, preferring to keep walking in the same direction. Null without a scale, without a
     * first measurement, or when every cell is done.
     */
    fun suggestNext(
        mapWidth: Int,
        mapHeight: Int,
        metersPerUnit: Double?,
        settings: SurveyGuideSettings,
        measured: List<SurveyCoordinate>,
        skipped: List<SurveyCoordinate>
    ): SurveyCoordinate? {
        val config = settings.sanitized()
        if (!config.enabled || metersPerUnit == null || measured.isEmpty()) return null
        val cell = cellSizePx(metersPerUnit, config) ?: return null
        val radius = (config.coverageRadiusMeters / metersPerUnit).toFloat()
        val columns = ceil(mapWidth / cell).toInt()
        val rows = ceil(mapHeight / cell).toInt()
        if (columns <= 0 || rows <= 0 || columns.toLong() * rows > MAX_GRID_CELLS) return null

        val blocked = BooleanArray(columns * rows)
        fun index(column: Int, row: Int) = row * columns + column
        fun cellOf(point: SurveyCoordinate): Int? {
            val column = (point.x / cell).toInt()
            val row = (point.y / cell).toInt()
            return if (column in 0 until columns && row in 0 until rows) index(column, row) else null
        }
        // A cell is done when a measurement fell inside it or its centre is within the coverage radius.
        measured.forEach { point ->
            cellOf(point)?.let { blocked[it] = true }
            val minColumn = ((point.x - radius) / cell).toInt().coerceAtLeast(0)
            val maxColumn = ((point.x + radius) / cell).toInt().coerceAtMost(columns - 1)
            val minRow = ((point.y - radius) / cell).toInt().coerceAtLeast(0)
            val maxRow = ((point.y + radius) / cell).toInt().coerceAtMost(rows - 1)
            for (row in minRow..maxRow) for (column in minColumn..maxColumn) {
                if (hypot((column + 0.5f) * cell - point.x, (row + 0.5f) * cell - point.y) <= radius) {
                    blocked[index(column, row)] = true
                }
            }
        }
        skipped.forEach { point -> cellOf(point)?.let { blocked[it] = true } }

        val last = measured.last()
        val previous = measured.getOrNull(measured.size - 2)
        val headingX = previous?.let { last.x - it.x } ?: 0f
        val headingY = previous?.let { last.y - it.y } ?: 0f
        val headingLength = hypot(headingX, headingY)

        var best: SurveyCoordinate? = null
        var bestScore = Double.MAX_VALUE
        for (row in 0 until rows) for (column in 0 until columns) {
            if (blocked[index(column, row)]) continue
            val x = ((column + 0.5f) * cell).coerceAtMost(mapWidth.toFloat())
            val y = ((row + 0.5f) * cell).coerceAtMost(mapHeight.toFloat())
            val dx = x - last.x
            val dy = y - last.y
            val distance = hypot(dx, dy).toDouble()
            val turn = if (headingLength > 0f && distance > 0.0) {
                (1.0 - (dx * headingX + dy * headingY) / (distance * headingLength)) / 2.0
            } else {
                0.0
            }
            val score = distance * (1.0 + TURN_PENALTY * turn)
            if (score < bestScore) {
                bestScore = score
                best = SurveyCoordinate(x, y)
            }
        }
        return best
    }

    /**
     * Side of one guide cell in floor-plan pixels (spacing in metres through the scale): the same
     * grid [suggestNext] works on. Null without a finite positive scale.
     */
    fun cellSizePx(metersPerUnit: Double?, settings: SurveyGuideSettings): Float? {
        if (metersPerUnit == null || !metersPerUnit.isFinite() || !(metersPerUnit > 0.0)) return null
        return (settings.sanitized().spacingMeters / metersPerUnit).toFloat().takeIf { it.isFinite() && it > 0f }
    }

    /**
     * Grid to draw over a [mapWidth] x [mapHeight] plan, or null when there is no scale or the
     * grid would exceed the same cell limit as [suggestNext].
     */
    fun grid(mapWidth: Int, mapHeight: Int, metersPerUnit: Double?, settings: SurveyGuideSettings): GuideGrid? {
        val cell = cellSizePx(metersPerUnit, settings) ?: return null
        val columns = ceil(mapWidth / cell).toInt()
        val rows = ceil(mapHeight / cell).toInt()
        if (columns <= 0 || rows <= 0 || columns.toLong() * rows > MAX_GRID_CELLS) return null
        return GuideGrid(cell, mapWidth, mapHeight)
    }

    /** Opaque ARGB colour from red (≤ [WEAK_RSSI]) through yellow to green (≥ [STRONG_RSSI]). */
    fun rssiColor(rssi: Int?): Int {
        val t = ((rssi ?: WEAK_RSSI) - WEAK_RSSI).toFloat() / (STRONG_RSSI - WEAK_RSSI)
        return hsvToArgb(120f * t.coerceIn(0f, 1f), 0.85f, 0.9f)
    }

    private fun bestRssi(networks: List<WifiNetworkInfo>, ssidFilter: String, band: String? = null): Int? =
        networks.asSequence()
            .filter { ssidFilter.isEmpty() || it.ssid == ssidFilter }
            .filter { band == null || WifiBands.of(it.frequency) == band }
            .maxOfOrNull(WifiNetworkInfo::level)

    /** Linear position on the route at [timestamp] (both relative to the session start). */
    internal fun positionAt(route: List<RoutePointWrapper>, timestamp: Long): Pair<Float, Float>? {
        if (route.isEmpty()) return null
        if (timestamp <= route.first().timestamp) return route.first().x to route.first().y
        route.zipWithNext().forEach { (a, b) ->
            if (timestamp <= b.timestamp) {
                val span = (b.timestamp - a.timestamp).toFloat()
                val f = if (span > 0f) (timestamp - a.timestamp) / span else 1f
                return (a.x + (b.x - a.x) * f) to (a.y + (b.y - a.y) * f)
            }
        }
        return route.last().x to route.last().y
    }

    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1 - abs((hue / 60f) % 2 - 1))
        val m = value - c
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            else -> Triple(x, c, 0f) // 60..120: yellow to green
        }
        fun channel(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
