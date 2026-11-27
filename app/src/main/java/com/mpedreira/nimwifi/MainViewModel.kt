package com.mpedreira.nimwifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

/** Owns immutable survey data and the single active interaction on the survey screen. */
class MainViewModel @JvmOverloads constructor(
    private val clock: MillisClock = SystemMillisClock,
    private val idSource: IdSource = UuidIdSource,
    /** Scope of active runs; tests pass their own, the app uses [viewModelScope] (survives rotation). */
    private val runScope: CoroutineScope? = null,
    /** Time base of Continuous tracks (waypoints, scans, roaming probe); defaults to monotonic time. */
    monotonicClock: MillisClock? = null
) : ViewModel() {
    private val monotonic: MillisClock = monotonicClock ?: if (clock === SystemMillisClock) MonotonicMillisClock else clock
    private var activeSessionStartMono: Long = 0L
    private var activeRunJob: Job? = null
    private var skipRequested: Job? = null

    private val _screenState = MutableStateFlow(SurveyScreenState())
    val screenState: StateFlow<SurveyScreenState> = _screenState.asStateFlow()

    private val recentScanTimestamps = ArrayDeque<Long>()
    private var activeSessionId: String? = null
    private var activeSessionStartTime: Long = 0L
    private val activeWaypoints = mutableListOf<RoutePointWrapper>()
    private val activeScanResults = mutableListOf<ScanResultWrapper>()
    private var roamingTracker: RoamingTracker? = null
    private var roamingTarget: String? = null
    private var roamingInterval: Int? = null
    /** UTC start of the route (of its first stretch when it was resumed). */
    private var activeRouteStartUtc: Long = 0L
    /** Stretches recorded before an interruption, when the route was resumed. */
    private var previousSegments: List<RouteSegment> = emptyList()
    private val activeScanWindows = mutableListOf<ScanWindow>()
    private val _routeDraftRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Mejora 2: the Continuous capture of this screen (scanner, roaming probe, foreground service). */
    private val capture: ContinuousCaptureController by lazy {
        ContinuousCaptureController(runScope ?: viewModelScope, captureSink)
    }

    /** What the capture is really doing (RF, roaming, service), also after a recreation. */
    val captureStatus: kotlinx.coroutines.flow.StateFlow<CaptureStatus> get() = capture.status

    /**
     * Captures the open track with [sources]. Idempotent: when the screen is recreated and asks
     * again, the running capture is kept and the new sources are closed.
     */
    fun startCapture(sources: CaptureSources) {
        val sessionId = activeSessionId
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking || sessionId == null) {
            sources.rf.close()
            return
        }
        capture.start(sessionId, sources)
    }

    val isCapturing: Boolean get() = capture.isRunning

    private val captureSink = object : ContinuousCaptureController.Sink {
        private fun current(sessionId: String) =
            sessionId == activeSessionId && _screenState.value.interaction is SurveyInteraction.ContinuousTracking

        override fun beginScan(sessionId: String): Long? = if (current(sessionId)) beginContinuousScanAttempt() else null

        override fun scanSucceeded(sessionId: String, startedAt: Long, networks: List<WifiNetworkInfo>): Boolean =
            current(sessionId) && completeContinuousScanAttempt(startedAt, networks)

        override fun scanFailed(sessionId: String, startedAt: Long, outcome: String, reason: String) {
            if (!current(sessionId)) return
            activeScanWindows += ScanWindow(startedAt - activeSessionStartMono, monotonic.now() - activeSessionStartMono, outcome, reason)
            routeChanged()
        }

        override fun roamingSession(target: String, intervalMillis: Int): String? = startRoamingLog(target, intervalMillis)

        override fun roamingAttempt(sessionId: String, startMono: Long, endMono: Long, result: String, association: Association?) =
            recordRoamingAttempt(sessionId, startMono, endMono, result, association)

        override fun stopBecause(sessionId: String, reason: String) {
            if (!current(sessionId)) return
            val last = activeWaypoints.lastOrNull() ?: return
            stopContinuousTrack(last.x, last.y)
        }
    }

    override fun onCleared() {
        // The survey screen is gone for good: end the capture and release its resources.
        capture.stop()
        super.onCleared()
    }

    /** Changes whenever the open route gains data: the screen saves its draft when it changes. */
    val routeDraftRevision: kotlinx.coroutines.flow.StateFlow<Long> = _routeDraftRevision

    private fun routeChanged() {
        _routeDraftRevision.value = _routeDraftRevision.value + 1
    }

    /** Durable checkpoint of the open route, or null without one. */
    fun activeRouteDraft(floorId: String): RouteDraft? {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return null
        val sessionId = activeSessionId ?: return null
        return RouteDraft(
            sessionId = sessionId,
            floorId = floorId,
            startTime = activeRouteStartUtc,
            segments = previousSegments + currentSegment(),
            roamingTarget = roamingTarget,
            roamingIntervalMillis = roamingInterval,
            savedAt = clock.now()
        )
    }

    private fun currentSegment(): RouteSegment = RouteSegment(
        startUtc = activeSessionStartTime,
        waypoints = activeWaypoints.toList(),
        scanResults = activeScanResults.toList(),
        probeAttempts = roamingTracker?.attempts().orEmpty(),
        scanWindows = activeScanWindows.toList(),
        lastObservedMs = (monotonic.now() - activeSessionStartMono).coerceAtLeast(0L)
    )

    /**
     * Saves an interrupted route as it is. Idempotent: if the route is already in the survey
     * (a retry after a failed save), nothing is added twice.
     */
    fun finalizeInterruptedRoute(sessionId: String): ContinuousScanSession? {
        val state = _screenState.value
        val draft = state.interruptedRoutes.firstOrNull { it.sessionId == sessionId }
        val existing = state.continuousScanSessions.firstOrNull { it.id == sessionId }
        if (existing != null || draft == null) {
            _screenState.value = state.copy(interruptedRoutes = state.interruptedRoutes.filterNot { it.sessionId == sessionId })
            return existing
        }
        val session = RouteDrafts.finalize(draft, recovered = true)
        _screenState.value = state.copy(
            continuousScanSessions = state.continuousScanSessions + session,
            interruptedRoutes = state.interruptedRoutes - draft,
            actionHistory = state.actionHistory + LastAction.SCAN_SESSION
        )
        return session
    }

    /** Forgets an interrupted route (the screen deletes its draft). */
    fun discardInterruptedRoute(sessionId: String) {
        _screenState.update { it.copy(interruptedRoutes = it.interruptedRoutes.filterNot { r -> r.sessionId == sessionId }) }
    }

    /** Continues an interrupted route from its last waypoint, with a new stretch on its own clock. */
    fun requestResumeRoute(sessionId: String): Boolean {
        val state = _screenState.value
        val draft = state.interruptedRoutes.firstOrNull { it.sessionId == sessionId } ?: return false
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle) return false
        val last = draft.lastWaypoint ?: return false
        _screenState.value = state.copy(
            scanMode = SurveyScanMode.CONTINUOUS,
            interaction = SurveyInteraction.AwaitingScanAccess(
                PendingScanRequest.ContinuousStart(SurveyCoordinate(last.x, last.y), resumeSessionId = sessionId)
            )
        )
        return true
    }

    /**
     * Starts the roaming log of the active Continuous track (once; kept across recreation) and
     * returns the session id the probe must send its attempts with, or null without a track.
     */
    fun startRoamingLog(target: String, intervalMillis: Int): String? {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return null
        if (roamingTracker == null) {
            roamingTracker = RoamingTracker(target, intervalMillis)
            roamingTarget = target
            roamingInterval = intervalMillis
        }
        return activeSessionId
    }

    /**
     * One probe attempt; [startMono]/[endMono] are [MonotonicMillisClock] times. Attempts of
     * another (older) session, from before this session started, or without a track are ignored.
     */
    fun recordRoamingAttempt(sessionId: String, startMono: Long, endMono: Long, result: String, association: Association?) {
        val tracker = roamingTracker ?: return
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking || sessionId != activeSessionId) return
        if (startMono < activeSessionStartMono) return
        tracker.add(
            ProbeAttempt(
                startMs = startMono - activeSessionStartMono,
                endMs = endMono - activeSessionStartMono,
                result = result,
                ssid = association?.ssid,
                bssid = association?.bssid,
                rssi = association?.rssi,
                associationKnown = association?.known == true
            )
        )
        routeChanged()
    }

    fun loadProject(
        mapInfo: MapInfo,
        scale: Double?,
        scanPoints: List<ScanPoint>,
        continuousSessions: List<ContinuousScanSession>,
        notes: List<AppNote>,
        skippedSuggestions: List<SkippedSuggestion> = emptyList(),
        activeRuns: List<ActiveRun> = emptyList(),
        plannedPoints: List<PlannedPoint> = emptyList(),
        routeDrafts: List<RouteDraft> = emptyList()
    ) {
        activeRunJob?.cancel()
        activeRunJob = null
        val selectedScanMode = _screenState.value.scanMode
        clearActiveSession()
        recentScanTimestamps.clear()
        _screenState.value = SurveyScreenState(
            mapInfo = mapInfo,
            metersPerUnit = scale,
            scanPoints = scanPoints.map { it.copy(wifiNetworks = it.wifiNetworks.toList()) },
            continuousScanSessions = continuousSessions.map { it.immutableCopy() },
            notes = notes.toList(),
            skippedSuggestions = skippedSuggestions.map { SurveyCoordinate(it.x, it.y) },
            // Nothing runs right after a load: anything unfinished was interrupted.
            activeRuns = SurveyIds.reconcile(activeRuns, clock.now()),
            plannedPoints = plannedPoints.toList(),
            // A draft whose route is already saved was finished (its cleanup failed): not interrupted.
            interruptedRoutes = routeDrafts.filter { draft -> continuousSessions.none { it.id == draft.sessionId } },
            scanMode = selectedScanMode,
            actionHistory = buildList {
                repeat(scanPoints.count { !it.importedFromEsx }) { add(LastAction.SCAN) }
                repeat(continuousSessions.count { !it.importedFromEsx }) { add(LastAction.SCAN_SESSION) }
                repeat(notes.count { !it.importedFromEsx }) { add(LastAction.NOTE) }
                repeat(activeRuns.size) { add(LastAction.ACTIVE_RUN) }
            }
        )
    }

    fun setScanMode(mode: SurveyScanMode): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.interaction is SurveyInteraction.ContinuousTracking ||
            state.interaction is SurveyInteraction.StopAndGoScanning ||
            state.interaction is SurveyInteraction.AwaitingScanAccess ||
            state.interaction is SurveyInteraction.ProjectBusy
        ) return false
        _screenState.value = state.copy(
            scanMode = mode,
            interaction = when {
                mode == SurveyScanMode.CONTINUOUS &&
                    state.interaction is SurveyInteraction.AwaitingContinuousStart -> state.interaction
                else -> SurveyInteraction.Idle
            }
        )
        return true
    }

    fun beginNotePlacement(): Boolean = transitionFromIdle(SurveyInteraction.NotePlacement)

    fun finishNotePlacement() {
        if (_screenState.value.interaction is SurveyInteraction.NotePlacement) setInteraction(SurveyInteraction.Idle)
    }

    fun enterCalibrationMode(): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(
            scanMode = SurveyScanMode.STOP_AND_GO,
            interaction = SurveyInteraction.Calibration(emptyList())
        )
        return true
    }

    fun addCalibrationPoint(point: SurveyCoordinate): List<SurveyCoordinate> {
        val calibration = _screenState.value.interaction as? SurveyInteraction.Calibration
            ?: return emptyList()
        val points = (calibration.points + point).take(2)
        setInteraction(SurveyInteraction.Calibration(points))
        return points
    }

    fun applyCalibration(distanceMeters: Double): Boolean {
        val calibration = _screenState.value.interaction as? SurveyInteraction.Calibration ?: return false
        if (!distanceMeters.isFinite() || distanceMeters <= 0 || calibration.points.size != 2) return false
        val (first, second) = calibration.points
        val dx = (first.x - second.x).toDouble()
        val dy = (first.y - second.y).toDouble()
        val distancePixels = sqrt(dx * dx + dy * dy)
        if (!distancePixels.isFinite() || distancePixels <= 0.0) return false
        val scale = distanceMeters / distancePixels
        if (!FloorScalePolicy.isConfirmed(scale)) return false
        _screenState.update { it.copy(metersPerUnit = scale, interaction = SurveyInteraction.Idle) }
        return true
    }

    fun clearCalibration() {
        if (_screenState.value.interaction is SurveyInteraction.Calibration) setInteraction(SurveyInteraction.Idle)
    }

    fun armContinuousStart(): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.scanMode != SurveyScanMode.CONTINUOUS ||
            state.interaction !is SurveyInteraction.Idle
        ) return false
        setInteraction(SurveyInteraction.AwaitingContinuousStart)
        return true
    }

    fun cancelContinuousStart() {
        if (_screenState.value.interaction is SurveyInteraction.AwaitingContinuousStart) {
            setInteraction(SurveyInteraction.Idle)
        }
    }

    fun requestStopAndGoScan(x: Float, y: Float): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.scanMode != SurveyScanMode.STOP_AND_GO ||
            state.interaction !is SurveyInteraction.Idle
        ) return false
        setInteraction(
            SurveyInteraction.AwaitingScanAccess(PendingScanRequest.StopAndGo(SurveyCoordinate(x, y)))
        )
        return true
    }

    /**
     * "Measure at this point": a new passive scan (then the active tests) at the exact position of
     * an existing local, imported or planned point, reusing its pointId. Earlier scans and runs
     * of the point are kept.
     */
    fun requestMeasureAtPoint(pointId: String): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.scanMode != SurveyScanMode.STOP_AND_GO || state.interaction !is SurveyInteraction.Idle) return false
        val position = state.pointPosition(pointId) ?: return false
        setInteraction(SurveyInteraction.AwaitingScanAccess(PendingScanRequest.StopAndGo(position, pointId)))
        return true
    }

    /** Adds the points of one template import (Undo removes them as a batch). */
    fun addPlannedPoints(points: List<PlannedPoint>): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle || points.isEmpty()) return false
        val known = state.plannedPoints.map(PlannedPoint::pointId).toSet()
        val added = points.filter { it.pointId !in known }
        if (added.isEmpty()) return false
        _screenState.value = state.copy(
            plannedPoints = state.plannedPoints + added,
            actionHistory = state.actionHistory + LastAction.PLANNED_POINTS
        )
        return true
    }

    fun requestContinuousStart(x: Float, y: Float): Boolean {
        val state = _screenState.value
        if (state.scanMode != SurveyScanMode.CONTINUOUS ||
            state.interaction !is SurveyInteraction.AwaitingContinuousStart
        ) return false
        setInteraction(
            SurveyInteraction.AwaitingScanAccess(PendingScanRequest.ContinuousStart(SurveyCoordinate(x, y)))
        )
        return true
    }

    fun pendingScanRequest(): PendingScanRequest? =
        (_screenState.value.interaction as? SurveyInteraction.AwaitingScanAccess)?.request

    fun pendingScanAccess(): SurveyInteraction.AwaitingScanAccess? =
        _screenState.value.interaction as? SurveyInteraction.AwaitingScanAccess

    fun transitionScanAccess(expected: ScanAccessStep, next: ScanAccessStep): Boolean {
        val pending = pendingScanAccess() ?: return false
        if (pending.step != expected) return false
        setInteraction(pending.copy(step = next))
        return true
    }

    fun grantPendingScanAccess(): StartedScanAction? {
        if (pendingScanAccess()?.step != ScanAccessStep.CHECKING) return null
        val request = pendingScanRequest() ?: return null
        return when (request) {
            is PendingScanRequest.StopAndGo -> {
                setInteraction(SurveyInteraction.StopAndGoScanning(request.coordinate, request.pointId))
                StartedScanAction.StopAndGo(request.coordinate)
            }
            is PendingScanRequest.ContinuousStart -> {
                startContinuousTrack(request.coordinate, request.resumeSessionId)
                StartedScanAction.Continuous
            }
        }
    }

    fun cancelPendingScanRequest() {
        if (_screenState.value.interaction is SurveyInteraction.AwaitingScanAccess) {
            setInteraction(SurveyInteraction.Idle)
        }
    }

    fun failStopAndGoScan() {
        if (_screenState.value.interaction is SurveyInteraction.StopAndGoScanning) {
            setInteraction(SurveyInteraction.Idle)
        }
    }

    /**
     * Saves the passive scan as a point right away (before any active test), with its own
     * [ScanPoint.scanId]; [pointId] reuses an existing physical point, else a new one is created.
     */
    fun completeStopAndGoScan(wifiNetworks: List<WifiNetworkInfo>, pointId: String? = null): ScanPoint? {
        val scan = _screenState.value.interaction as? SurveyInteraction.StopAndGoScanning ?: return null
        val timestamp = clock.now()
        val point = ScanPoint(
            timestamp = timestamp,
            x = scan.coordinate.x,
            y = scan.coordinate.y,
            wifiNetworks = wifiNetworks.toList(),
            pointId = pointId ?: scan.pointId ?: idSource.newId(),
            scanId = idSource.newId()
        )
        recentScanTimestamps.addLast(timestamp)
        _screenState.update {
            it.copy(
                scanPoints = it.scanPoints + point,
                actionHistory = it.actionHistory + LastAction.SCAN,
                movedSuggestion = null,
                interaction = SurveyInteraction.Idle
            )
        }
        return point
    }

    fun stopAndGoThrottleDelayMillis(systemThrottlingEnabled: Boolean): Long {
        if (!systemThrottlingEnabled) return 0L
        val now = clock.now()
        while (recentScanTimestamps.firstOrNull()?.let { now - it >= THROTTLE_WINDOW_MILLIS } == true) {
            recentScanTimestamps.removeFirst()
        }
        if (recentScanTimestamps.size < MAX_SCANS_PER_WINDOW) return 0L
        return (recentScanTimestamps.first() + THROTTLE_WINDOW_MILLIS - now).coerceAtLeast(1L)
    }

    fun addContinuousWaypoint(x: Float, y: Float): Boolean {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return false
        activeWaypoints += RoutePointWrapper(monotonic.now() - activeSessionStartMono, x, y)
        publishTrackingState()
        routeChanged()
        return true
    }

    fun beginContinuousScanAttempt(): Long? =
        if (_screenState.value.interaction is SurveyInteraction.ContinuousTracking) monotonic.now() else null

    fun completeContinuousScanAttempt(
        startedAt: Long,
        wifiNetworks: List<WifiNetworkInfo>
    ): Boolean {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return false
        val now = monotonic.now()
        activeScanResults += ScanResultWrapper(
            timestamp = now - activeSessionStartMono,
            duration = (now - startedAt).coerceAtLeast(0L),
            wifiNetworks = wifiNetworks.toList()
        )
        activeScanWindows += ScanWindow(startedAt - activeSessionStartMono, now - activeSessionStartMono, ScanWindow.SUCCESS)
        publishTrackingState()
        routeChanged()
        return true
    }

    fun stopContinuousTrack(x: Float, y: Float): ContinuousScanSession? {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return null
        capture.stop()
        val elapsed = monotonic.now() - activeSessionStartMono
        activeWaypoints += RoutePointWrapper(elapsed, x, y)
        val scanWindows = activeScanResults.map { (it.timestamp - it.duration)..it.timestamp }
        val session = if (previousSegments.isNotEmpty()) {
            // A resumed route: its stretches are joined on the route's relative time base.
            RouteDrafts.finalize(
                RouteDraft(activeSessionId ?: idSource.newId(), "", activeRouteStartUtc, previousSegments + currentSegment(),
                    roamingTarget, roamingInterval),
                recovered = true
            )
        } else {
            ContinuousScanSession(
                id = activeSessionId ?: idSource.newId(),
                startTime = activeSessionStartTime,
                endTime = activeSessionStartTime + elapsed,
                waypoints = activeWaypoints.toList(),
                scanResults = activeScanResults.toList(),
                roaming = roamingTracker?.finish(scanWindows),
                timeBase = RoamingTracker.TIME_BASE,
                segments = 1,
                scanWindows = activeScanWindows.toList(),
                rfGaps = RfGaps.compute(activeScanWindows, elapsed)
            )
        }
        _screenState.update {
            it.copy(
                continuousScanSessions = it.continuousScanSessions + session,
                actionHistory = it.actionHistory + LastAction.SCAN_SESSION,
                interaction = SurveyInteraction.Idle
            )
        }
        clearActiveSession()
        return session
    }

    fun activeContinuousElapsedMillis(now: Long = monotonic.now()): Long =
        if (_screenState.value.interaction is SurveyInteraction.ContinuousTracking) {
            (now - activeSessionStartMono).coerceAtLeast(0L)
        } else {
            0L
        }

    fun addNote(
        text: String,
        photoUri: String?,
        photoWidth: Int?,
        photoHeight: Int?,
        x: Float,
        y: Float,
        photoFormat: String?
    ): AppNote? {
        val state = _screenState.value
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle) return null
        val note = AppNote(
            id = idSource.newId(),
            text = text,
            photoUri = photoUri,
            photoWidth = photoWidth,
            photoHeight = photoHeight,
            photoId = photoUri?.let { idSource.newId() },
            x = x,
            y = y,
            pictureNoteId = idSource.newId(),
            photoFormat = photoFormat
        )
        _screenState.value = state.copy(
            notes = state.notes + note,
            actionHistory = state.actionHistory + LastAction.NOTE
        )
        return note
    }

    fun undoLastAction(): LastAction? {
        val state = _screenState.value
        if (state.interaction !is SurveyInteraction.Idle) return null
        val action = state.actionHistory.lastOrNull()
        if (action == null) {
            _screenState.value = state.copy(lastUndoneAction = null)
            return null
        }
        _screenState.value = when (action) {
            LastAction.SCAN -> state.copy(
                scanPoints = state.scanPoints.dropLastLocal(),
                // A removed scan takes the runs that followed it along.
                activeRuns = state.scanPoints.lastOrNull { !it.importedFromEsx }?.scanId?.let { removed ->
                    state.activeRuns.filterNot { it.scanId == removed }
                } ?: state.activeRuns,
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
            LastAction.NOTE -> state.copy(
                notes = state.notes.dropLastLocal(),
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
            LastAction.SCAN_SESSION -> state.copy(
                continuousScanSessions = state.continuousScanSessions.dropLastLocal(),
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
            LastAction.SUGGESTION_SKIP -> state.copy(
                skippedSuggestions = state.skippedSuggestions.dropLast(1),
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
            LastAction.ACTIVE_RUN -> state.copy(
                activeRuns = state.activeRuns.dropLast(1),
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
            LastAction.PLANNED_POINTS -> state.copy(
                plannedPoints = state.plannedPoints.lastOrNull()?.batch.let { batch ->
                    if (batch == null) state.plannedPoints.dropLast(1) else state.plannedPoints.filterNot { it.batch == batch }
                },
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
        }
        return action
    }

    fun consumeLastUndoneAction() {
        if (_screenState.value.lastUndoneAction != null) {
            _screenState.update { it.copy(lastUndoneAction = null) }
        }
    }

    fun beginProjectOperation(operation: ProjectOperation): Boolean {
        val state = _screenState.value
        val mayReplacePicker = state.interaction is SurveyInteraction.ProjectBusy &&
            state.interaction.operation in setOf(
                ProjectOperation.FLOOR_MAP_SELECTION,
                ProjectOperation.EXPORT_DESTINATION
            )
        val exportPreflightComplete = state.interaction ==
            SurveyInteraction.ProjectBusy(ProjectOperation.EXPORT_PREFLIGHT) &&
            operation == ProjectOperation.EXPORT_DESTINATION
        if (!mayReplacePicker && !exportPreflightComplete && state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(interaction = SurveyInteraction.ProjectBusy(operation))
        return true
    }

    fun finishProjectOperation(operation: ProjectOperation? = null) {
        val busy = _screenState.value.interaction as? SurveyInteraction.ProjectBusy ?: return
        if (operation == null || busy.operation == operation) setInteraction(SurveyInteraction.Idle)
    }

    /** Returns an Activity-owned project operation that must be safely restarted. */
    fun recoverAfterActivityRecreation(): ProjectOperation? {
        return when (val interaction = _screenState.value.interaction) {
            is SurveyInteraction.StopAndGoScanning -> {
                setInteraction(SurveyInteraction.Idle)
                null
            }
            is SurveyInteraction.ProjectBusy -> {
                if (interaction.operation == ProjectOperation.FLOOR_MAP_SELECTION ||
                    interaction.operation == ProjectOperation.EXPORT_DESTINATION
                ) {
                    null // Activity ResultRegistry will deliver the outstanding result.
                } else {
                    setInteraction(SurveyInteraction.Idle)
                    interaction.operation
                }
            }
            else -> null
        }
    }

    fun projectSnapshot(): ProjectSnapshot? {
        val state = _screenState.value
        val mapInfo = state.mapInfo ?: return null
        return ProjectSnapshot(
            mapInfo = mapInfo,
            metersPerUnit = state.metersPerUnit,
            scanPoints = state.scanPoints.toList(),
            continuousScanSessions = state.continuousScanSessions.map { it.immutableCopy() },
            notes = state.notes.toList(),
            skippedSuggestions = state.skippedSuggestions.map { SkippedSuggestion(it.x, it.y) },
            activeRuns = state.activeRuns.toList(),
            plannedPoints = state.plannedPoints.toList()
        )
    }

    /**
     * Starts the active tests of an existing point. The run is recorded (all phases pending)
     * before anything is measured; [executor] measures off the main thread, reporting finished
     * phases and partial results, which are saved as they arrive. Returns the run id, or null
     * when the screen is busy or the point does not exist.
     */
    fun startActiveRun(
        pointId: String,
        scanId: String?,
        phases: List<PhaseRecord>,
        profile: String?,
        budgetMillis: Long? = null,
        executor: suspend (MeasurementControl, RunListener) -> PointThroughput
    ): String? {
        val state = _screenState.value
        if (state.interaction !is SurveyInteraction.Idle || phases.isEmpty()) return null
        val position = state.pointPosition(pointId) ?: return null
        val runId = idSource.newId()
        val run = ActiveRun(
            runId = runId, pointId = pointId, scanId = scanId, startedAt = clock.now(),
            status = RunStatus.RUNNING, profile = profile, phases = phases,
            x = position.x, y = position.y, budgetMillis = budgetMillis
        )
        _screenState.value = state.copy(
            activeRuns = state.activeRuns + run,
            actionHistory = state.actionHistory + LastAction.ACTIVE_RUN,
            interaction = SurveyInteraction.ActiveTesting(runId, pointId),
            activeRunProgress = null
        )
        val control = MeasurementControl()
        activeRunJob = (runScope ?: viewModelScope).launch {
            try {
                executor(control, runListener(runId))
                finishRun(runId, RunStatus.COMPLETED, null)
            } catch (e: MeasurementCancelled) {
                // Raised inside the run: its deadline expired (or it was cancelled from within).
                finishRun(runId, if (e.reason == ErrorCodes.TIMEOUT) RunStatus.FAILED else RunStatus.CANCELLED, e.reason)
            } catch (e: CancellationException) {
                if (skipRequested != null && skipRequested === activeRunJob) {
                    finishRun(runId, RunStatus.SKIPPED, ErrorCodes.SKIPPED_BY_USER)
                } else {
                    finishRun(runId, RunStatus.CANCELLED, ErrorCodes.CANCELLED)
                }
                skipRequested = null
                throw e
            } catch (e: Exception) {
                finishRun(runId, RunStatus.FAILED, ErrorClassifier.code(e))
            }
        }
        return runId
    }

    /** "Cancel tests": stops the active run; the point and the finished phases are kept. */
    fun cancelActiveRun() {
        activeRunJob?.cancel()
    }

    /** "Skip point": stops the run like a cancel but records it as skipped by the user. */
    fun skipActiveRun() {
        val job = activeRunJob ?: return
        skipRequested = job
        job.cancel()
    }

    /** Callbacks of [runId] only: late callbacks of another run, point or floor are ignored. */
    private fun runListener(runId: String) = object : RunListener {
        override fun onProgress(progress: ThroughputProgress) = updateRun(runId) { state, _ ->
            state.copy(activeRunProgress = progress)
        }

        override fun onPhase(key: String, status: String, errorCode: String?) = updateRun(runId) { state, run ->
            val now = clock.now()
            state.replaceRun(run.copy(phases = run.phases.map { phase ->
                if (phase.key == key) phase.copy(status = status, errorCode = errorCode, endedAt = now) else phase
            }))
        }

        override fun onPartial(results: PointThroughput) = updateRun(runId) { state, run ->
            state.replaceRun(run.copy(results = results))
        }

        // Status and value of a phase in one state update (saved together).
        override fun onPhaseResult(key: String, status: String, errorCode: String?, results: PointThroughput) =
            updateRun(runId) { state, run ->
                val now = clock.now()
                state.replaceRun(run.copy(
                    results = results,
                    phases = run.phases.map { phase ->
                        if (phase.key == key) phase.copy(status = status, errorCode = errorCode, endedAt = now) else phase
                    }
                ))
            }
    }

    private fun updateRun(runId: String, change: (SurveyScreenState, ActiveRun) -> SurveyScreenState) {
        _screenState.update { state ->
            val run = state.activeRuns.firstOrNull { it.runId == runId }
            val point = run?.let { r -> state.pointPosition(r.pointId) }
            if (run == null || point == null || run.isFinished) state else change(state, run)
        }
    }

    private fun finishRun(runId: String, status: String, reason: String?) {
        _screenState.update { state ->
            val run = state.activeRuns.firstOrNull { it.runId == runId } ?: return@update state
            val now = clock.now()
            val ended = if (status == RunStatus.COMPLETED) {
                run.copy(
                    status = RunStatus.COMPLETED, endedAt = now,
                    phases = run.phases.map { if (it.status in RunStatus.UNFINISHED) it.copy(status = RunStatus.SKIPPED, endedAt = now) else it }
                )
            } else {
                run.end(status, reason, now)
            }
            val interaction = state.interaction
            state.replaceRun(ended).copy(
                activeRunProgress = null,
                interaction = if (interaction is SurveyInteraction.ActiveTesting && interaction.runId == runId) {
                    SurveyInteraction.Idle
                } else {
                    interaction
                }
            )
        }
        activeRunJob = null
    }

    private fun SurveyScreenState.replaceRun(run: ActiveRun) =
        copy(activeRuns = activeRuns.map { if (it.runId == run.runId) run else it })

    /** Marks the cell containing [coordinate] as "do not measure"; undoable. */
    fun skipSuggestion(coordinate: SurveyCoordinate): Boolean {
        val state = _screenState.value
        if (state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(
            skippedSuggestions = state.skippedSuggestions + coordinate,
            movedSuggestion = null,
            actionHistory = state.actionHistory + LastAction.SUGGESTION_SKIP
        )
        return true
    }

    /** The user dragged the next point elsewhere; it is kept until measured or skipped. */
    fun moveSuggestion(coordinate: SurveyCoordinate): Boolean {
        val state = _screenState.value
        if (state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(movedSuggestion = coordinate)
        return true
    }

    private fun startContinuousTrack(coordinate: SurveyCoordinate, resumeSessionId: String? = null) {
        val resumed = resumeSessionId?.let { id -> _screenState.value.interruptedRoutes.firstOrNull { it.sessionId == id } }
        activeSessionId = resumed?.sessionId ?: idSource.newId()
        activeSessionStartTime = clock.now()
        activeSessionStartMono = monotonic.now()
        activeRouteStartUtc = resumed?.startTime ?: activeSessionStartTime
        previousSegments = resumed?.segments.orEmpty()
        if (resumed?.roamingTarget != null) {
            roamingTarget = resumed.roamingTarget
            roamingInterval = resumed.roamingIntervalMillis
            roamingTracker = RoamingTracker(resumed.roamingTarget, resumed.roamingIntervalMillis ?: RoamingSettings.DEFAULT_INTERVAL_MILLIS)
        }
        activeWaypoints.clear()
        activeScanResults.clear()
        activeScanWindows.clear()
        activeWaypoints += RoutePointWrapper(0L, coordinate.x, coordinate.y)
        if (resumed != null) {
            _screenState.update { it.copy(interruptedRoutes = it.interruptedRoutes - resumed) }
        }
        publishTrackingState()
        routeChanged()
    }

    private fun publishTrackingState() {
        _screenState.update {
            it.copy(
                interaction = SurveyInteraction.ContinuousTracking(
                    startedAt = activeSessionStartTime,
                    waypoints = activeWaypoints.map { point -> SurveyCoordinate(point.x, point.y) },
                    scanCount = activeScanResults.size
                )
            )
        }
    }

    private fun clearActiveSession() {
        roamingTracker = null
        roamingTarget = null
        roamingInterval = null
        previousSegments = emptyList()
        activeRouteStartUtc = 0L
        activeScanWindows.clear()
        activeSessionId = null
        activeSessionStartTime = 0L
        activeSessionStartMono = 0L
        activeWaypoints.clear()
        activeScanResults.clear()
    }

    private fun transitionFromIdle(interaction: SurveyInteraction): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(interaction = interaction)
        return true
    }

    private fun setInteraction(interaction: SurveyInteraction) {
        _screenState.update { it.copy(interaction = interaction) }
    }

    private fun ContinuousScanSession.immutableCopy(): ContinuousScanSession = copy(
        waypoints = waypoints.toList(),
        scanResults = scanResults.map { it.copy(wifiNetworks = it.wifiNetworks.toList()) }
    )

    private fun <T> List<T>.dropLastLocal(): List<T> {
        val index = indexOfLast { item ->
            when (item) {
                is ScanPoint -> !item.importedFromEsx
                is ContinuousScanSession -> !item.importedFromEsx
                is AppNote -> !item.importedFromEsx
                else -> true
            }
        }
        return if (index < 0) this else filterIndexed { itemIndex, _ -> itemIndex != index }
    }

    companion object {
        private const val THROTTLE_WINDOW_MILLIS = 2 * 60 * 1_000L
        private const val MAX_SCANS_PER_WINDOW = 4
    }
}
