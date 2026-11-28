package com.mpedreira.nimwifi

/**
 * RC4 · 4: one cached value, recomputed only when one of its inputs changes. Lists are compared
 * by identity: [SurveyScreenState.copy] keeps the same list when a field is untouched, so a
 * progress-only update never looks like new data, and comparing never walks the history.
 */
class Memo<T> {
    private var key: List<Any?>? = null
    private var value: Any? = null
    var computations = 0
        private set

    @Suppress("UNCHECKED_CAST")
    fun get(vararg inputs: Any?, compute: () -> T): T {
        val previous = key
        val same = previous != null && previous.size == inputs.size && inputs.indices.all { i ->
            val a = previous[i]
            val b = inputs[i]
            a === b || (a !is Collection<*> && b !is Collection<*> && a == b)
        }
        if (!same) {
            value = compute()
            key = inputs.toList()
            computations++
        }
        return value as T
    }
}

/** A marker of the map: position, coverage state for the map network, second AP and point status. */
data class MapMarker(val x: Float, val y: Float, val state: String?, val redundant: Boolean = false, val status: String? = null)

/**
 * RC4 · 4: what the map draws, each part derived from its own inputs (Android-free). The
 * selected network, the point markers, the point statuses, the coverage samples and the next
 * suggested point are recomputed only when what they depend on changes; a phase of an active run
 * that only moves the progress dialog reuses all of them.
 */
class MapDerivations(private val coverageCache: CoverageSampleCache = CoverageSampleCache()) {
    private val network = Memo<MapNetwork?>()
    private val statuses = Memo<Map<String, String>>()
    private val markers = Memo<List<MapMarker>>()
    private val suggestion = Memo<SurveyCoordinate?>()

    val networkComputations get() = network.computations
    val markerComputations get() = markers.computations
    val statusComputations get() = statuses.computations
    val suggestionComputations get() = suggestion.computations
    val coverageComputations get() = coverageCache.computations

    /** The primary network (SSID + band): the chosen one, else the most widely detected SSID. */
    fun network(state: SurveyScreenState, settings: SurveyGuideSettings): MapNetwork? =
        network.get(settings.ssidFilter, settings.band, state.scanPoints, state.continuousScanSessions) {
            SsidCoverage.selectedNetwork(settings.ssidFilter, settings.band, state.scanPoints, state.continuousScanSessions)
        }

    fun statuses(state: SurveyScreenState): Map<String, String> =
        statuses.get(state.scanPoints, state.plannedPoints, state.activeRuns) { PointStatus.all(state) }

    /** One marker per physical point, coloured for the map network, with its status badge. */
    fun markers(state: SurveyScreenState, settings: SurveyGuideSettings): List<MapMarker> {
        val network = network(state, settings)
        val statuses = statuses(state)
        val signal = settings.signal
        return markers.get(network, signal.primaryDbm, signal.secondaryDbm, state.scanPoints, state.plannedPoints, state.activeRuns, statuses) {
            if (network == null) {
                state.scanPoints.distinctBy { it.pointId ?: it.scanId ?: "${it.x},${it.y}" }
                    .map { MapMarker(it.x, it.y, null, status = it.pointId?.let(statuses::get)) } +
                    state.plannedPoints.filter { p -> state.scanPoints.none { it.pointId == p.pointId } }
                        .map { MapMarker(it.x, it.y, PointState.NOT_MEASURED, status = statuses[it.pointId]) }
            } else {
                SsidCoverage.points(state.scanPoints, state.plannedPoints, network.ssid, signal.primaryDbm,
                    signal.secondaryDbm, state.activeRuns, band = network.band)
                    .map { MapMarker(it.x, it.y, it.state, it.secondaryOk, statuses[it.pointId]) } +
                    state.scanPoints.filter { it.pointId == null }.map { MapMarker(it.x, it.y, null) }
            }
        }
    }

    /** Coverage samples of the map network only (another SSID never fills its gaps). */
    fun coverage(state: SurveyScreenState, settings: SurveyGuideSettings): List<CoverageSample> =
        network(state, settings)?.let { coverageCache.samples(state.scanPoints, state.continuousScanSessions, it.ssid, it.band) }.orEmpty()

    /** The next suggested point (the one moved by hand wins); null without a calibrated plan. */
    fun suggestion(state: SurveyScreenState, settings: SurveyGuideSettings): SurveyCoordinate? {
        val map = state.mapInfo ?: return null
        val scale = state.metersPerUnit?.takeIf { it > 0.0 } ?: return null
        if (!settings.enabled || state.scanMode != SurveyScanMode.STOP_AND_GO) return null
        state.movedSuggestion?.let { return it }
        return suggestion.get(map.width, map.height, scale, settings, state.scanPoints, state.continuousScanSessions, state.skippedSuggestions) {
            SurveyGuide.suggestNext(map.width, map.height, scale, settings,
                SurveyGuide.measurementOrder(state.scanPoints, state.continuousScanSessions), state.skippedSuggestions)
        }
    }
}

/**
 * RC4 · 4: background work whose result is only kept if nothing newer was asked for meanwhile
 * (a fast floor, SSID or revision change discards the stale coverage bitmap).
 */
class LatestOnly {
    @Volatile private var current = 0L

    /** A new request; earlier ones become stale. */
    @Synchronized fun begin(): Long = ++current

    fun isCurrent(ticket: Long): Boolean = ticket == current
}
