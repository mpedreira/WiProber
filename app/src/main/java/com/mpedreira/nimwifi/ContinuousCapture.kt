package com.mpedreira.nimwifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One RF scan of a Continuous capture. */
sealed interface RfScan {
    data class Success(val networks: List<WifiNetworkInfo>) : RfScan
    /** [fatal]: the capture cannot go on (Wi-Fi off, permission revoked, scanning unavailable). */
    data class Failure(val reason: String, val fatal: Boolean) : RfScan
    data class Cancelled(val reason: String) : RfScan
}

/** Passive Wi-Fi scanning of a capture, not tied to an Activity lifecycle. */
interface RfScanSource {
    fun start()
    fun isWifiEnabled(): Boolean
    suspend fun scan(): RfScan
    fun close()
}

/** The roaming probe of a capture. */
fun interface RoamingProbeSource {
    fun launch(scope: CoroutineScope, onAttempt: (startMono: Long, endMono: Long, result: String, association: Association?, rttMs: Double?) -> Unit): Job
}

/** Keeps the process in the foreground while capturing (wake and Wi-Fi locks). */
interface CaptureForeground {
    /** Null when it started, else why it could not (permission, background start, …). */
    fun start(): String?
    fun stop()
}

/** Everything a capture needs from Android, created by the screen, owned by the capture. */
class CaptureSources(
    val rf: RfScanSource,
    val foreground: CaptureForeground,
    val roaming: RoamingProbeSource? = null,
    val roamingTarget: String? = null,
    val roamingIntervalMillis: Int = RoamingSettings.DEFAULT_INTERVAL_MILLIS,
    val roamingAlias: String? = null,
    val roamingTrace: Boolean = false,
    val roamingMethod: String = RoamingTracker.METHOD
)

/** What the capture is really doing, for the screen (also after a recreation). */
data class CaptureStatus(
    val sessionId: String? = null,
    val running: Boolean = false,
    val rfScans: Int = 0,
    val rfFailures: Int = 0,
    val lastRfOutcome: String? = null,
    val lastRfAtMono: Long? = null,
    val roamingEnabled: Boolean = false,
    val roamingAttempts: Int = 0,
    val lastRoamingResult: String? = null,
    /** Null = running in the foreground; otherwise why the foreground service did not start. */
    val serviceProblem: String? = null,
    /** Why the capture stopped by itself (fatal scan failure), until the next start. */
    val stoppedBecause: String? = null
)

/**
 * Mejora 2: owner of a Continuous capture. One instance per survey ViewModel, so a rotation
 * re-attaches the screen without a second scanner: the same session drives the RF scanner, the
 * roaming probe and the foreground service; stopping it cancels the work and releases the
 * receiver and the locks. Data goes to [sink] tagged with the session id, so a late callback of an
 * earlier session is rejected.
 */
class ContinuousCaptureController(
    private val scope: CoroutineScope,
    private val sink: Sink,
    private val retryDelayMillis: Long = 1_000L
) {
    /** Where the capture's data goes (the ViewModel). Each call says which session it belongs to. */
    interface Sink {
        fun beginScan(sessionId: String): Long?
        fun scanSucceeded(sessionId: String, startedAt: Long, networks: List<WifiNetworkInfo>): Boolean
        fun scanFailed(sessionId: String, startedAt: Long, outcome: String, reason: String)
        fun roamingSession(target: String, intervalMillis: Int, alias: String? = null, trace: Boolean = false, method: String = RoamingTracker.METHOD): String?
        fun roamingAttempt(sessionId: String, startMono: Long, endMono: Long, result: String, association: Association?, rttMs: Double? = null)
        fun stopBecause(sessionId: String, reason: String)
    }

    private var sessionId: String? = null
    private var sources: CaptureSources? = null
    private var scanJob: Job? = null
    private var roamingJob: Job? = null
    private val _status = MutableStateFlow(CaptureStatus())
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    val isRunning: Boolean get() = scanJob?.isActive == true

    /**
     * Starts capturing [session] with [newSources]. If that session is already being captured
     * (the screen was recreated) the new sources are closed and nothing is duplicated.
     */
    fun start(session: String, newSources: CaptureSources) {
        if (sessionId == session && isRunning) {
            newSources.rf.close()
            return
        }
        stop()
        sessionId = session
        sources = newSources
        val serviceProblem = newSources.foreground.start()
        _status.value = CaptureStatus(sessionId = session, running = true, serviceProblem = serviceProblem)
        newSources.rf.start()
        scanJob = scope.launch { scanLoop(session, newSources.rf) }
        val target = newSources.roamingTarget
        if (newSources.roaming != null && target != null) {
            val roamingSession = sink.roamingSession(target, newSources.roamingIntervalMillis, newSources.roamingAlias, newSources.roamingTrace, newSources.roamingMethod)
            if (roamingSession == session) {
                _status.update { it.copy(roamingEnabled = true) }
                roamingJob = newSources.roaming.launch(scope) { start, end, result, association, rttMs ->
                    if (sessionId != session) return@launch // a late attempt of an earlier session
                    sink.roamingAttempt(session, start, end, result, association, rttMs)
                    _status.update { it.copy(roamingAttempts = it.roamingAttempts + 1, lastRoamingResult = result) }
                }
            }
        }
    }

    /** Ends the capture: cancels scanning and probing, releases the receiver and the locks. */
    fun stop() {
        scanJob?.cancel()
        roamingJob?.cancel()
        scanJob = null
        roamingJob = null
        sources?.let { current ->
            current.rf.close()
            current.foreground.stop()
        }
        sources = null
        sessionId = null
        _status.update { it.copy(running = false) }
    }

    private suspend fun scanLoop(session: String, rf: RfScanSource) {
        while (scope.isActive && sessionId == session) {
            if (!rf.isWifiEnabled()) {
                fail(session, ErrorCodes.NOT_ON_WIFI)
                return
            }
            val startedAt = sink.beginScan(session) ?: return
            when (val outcome = rf.scan()) {
                is RfScan.Success -> {
                    if (sessionId != session || !sink.scanSucceeded(session, startedAt, outcome.networks)) return
                    _status.update { it.copy(rfScans = it.rfScans + 1, lastRfOutcome = ScanWindow.SUCCESS, lastRfAtMono = startedAt) }
                }
                is RfScan.Cancelled -> {
                    if (sessionId != session) return
                    sink.scanFailed(session, startedAt, ScanWindow.CANCELLED, outcome.reason)
                    _status.update { it.copy(lastRfOutcome = ScanWindow.CANCELLED) }
                    delay(retryDelayMillis)
                }
                is RfScan.Failure -> {
                    if (sessionId != session) return
                    sink.scanFailed(session, startedAt, ScanWindow.FAILED, outcome.reason)
                    _status.update { it.copy(rfFailures = it.rfFailures + 1, lastRfOutcome = "${ScanWindow.FAILED}:${outcome.reason}") }
                    if (outcome.fatal) {
                        fail(session, outcome.reason)
                        return
                    }
                    delay(retryDelayMillis)
                }
            }
        }
    }

    private fun fail(session: String, reason: String) {
        if (sessionId != session) return
        _status.update { it.copy(stoppedBecause = reason) }
        val stopping = sessionId
        stop()
        if (stopping != null) sink.stopBecause(stopping, reason)
    }
}
