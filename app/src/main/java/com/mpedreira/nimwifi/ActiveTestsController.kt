package com.mpedreira.nimwifi

import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android side of the active tests and the roaming probe of the survey screen: starts runs on a
 * point, shows the progress dialog (also after recreation) and the summary, and runs the
 * Continuous-mode roaming probe. The runs and the roaming log live in [viewModel].
 */
class ActiveTestsController(
    private val activity: AppCompatActivity,
    private val viewModel: MainViewModel,
    private val statusText: () -> TextView
) {
    private val throughputStore = ThroughputSettingsStore(activity)
    private val configStore = WiproberConfigStore(activity)
    private val probe = ThroughputProbe(activity)
    private var progressDialog: ThroughputProgressDialog? = null

    /**
     * RC4 · 4: the speed-test settings and one coherent copy of the profile with its credentials,
     * read off the main thread once per run and used from the network check to the result.
     */
    private class RunConfig(val settings: ThroughputSettings, val profile: WiproberConfigStore.Snapshot) {
        val phases = ThroughputRunner.plan(settings, profile.imported.enabledTests, profile.imported.enabledScripts)
    }

    private suspend fun loadConfig(): RunConfig = withContext(Dispatchers.IO) {
        RunConfig(throughputStore.load(), configStore.snapshot())
    }

    /** True when the configuration has at least one active test to run (read off the main thread). */
    suspend fun hasTests(): Boolean = loadConfig().phases.isNotEmpty()

    /**
     * Runs the configured active tests on a saved point. The run lives in the ViewModel, so a
     * rotation neither stops nor restarts it; the executor only captures application-level objects.
     * [onNoTests] when there is nothing to run.
     */
    fun start(pointId: String, scanId: String?, scanResults: List<android.net.wifi.ScanResult>, onNoTests: () -> Unit = {}) {
        lastStart = Triple(pointId, scanId, scanResults)
        activity.lifecycleScope.launch {
            val config = loadConfig()
            if (config.phases.isEmpty()) {
                onNoTests()
                return@launch
            }
            // Mejora 13: before sending anything, which Wi-Fi the tests would use and whether it is the declared one.
            val step = withContext(Dispatchers.IO) { NetworkCheckFlow.begin { probe.preflight(config.settings.sanitized().expectedSsid) } }
            when (step) {
                is NetworkCheckFlow.Step.AskDecision -> {
                    val preflight = step.preflight
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.network_check_title)
                        .setMessage(activity.getString(
                            if (preflight.verification == NetworkVerification.UNKNOWN) R.string.network_check_unknown else R.string.network_check_mismatch,
                            preflight.expectedSsid.orEmpty(), preflight.observedSsid ?: "?",
                            preflight.eligible.joinToString(", ") { it ?: "?" }
                        ))
                        .setCancelable(false)
                        .setPositiveButton(R.string.network_check_test_anyway) { _, _ ->
                            val run = NetworkCheckFlow.confirmed(step)
                            run(config, pointId, scanId, scanResults, run.preflight, run.decision)
                        }
                        .setNegativeButton(R.string.network_check_skip, null)
                        .show()
                }
                // Also without Wi-Fi in the check: the run then sends nothing and records why,
                // unless a Wi-Fi appeared meanwhile, which is checked again first (rc.3 · 2).
                is NetworkCheckFlow.Step.Run -> run(config, pointId, scanId, scanResults, step.preflight, step.decision)
            }
        }
    }

    private fun run(config: RunConfig, pointId: String, scanId: String?, scanResults: List<android.net.wifi.ScanResult>,
                    preflight: NetworkPreflight?, decision: String?) {
        val settings = config.settings
        val imported = config.profile.imported
        val credentials = config.profile.credentials
        val phases = config.phases
        val probe = probe
        val budget = ThroughputRunner.runDeadlineMillis(settings, imported.enabledTests, imported.enabledScripts)
        viewModel.startActiveRun(
            pointId, scanId, phases, profile = PROFILE_DEVICE_SETTINGS, budgetMillis = budget,
            preflight = preflight, networkDecision = decision
        ) { control, listener ->
            // Bound to the network of this preflight; a changed network stops before any traffic.
            probe.measure(settings, imported, credentials, scanResults, control, listener, approved = preflight)
        }
    }

    /** Shows the progress dialog while a run is active (also after recreation) and its summary when it ends. */
    fun render(state: SurveyScreenState, previous: SurveyScreenState) {
        val testing = state.interaction as? SurveyInteraction.ActiveTesting
        if (testing == null) {
            progressDialog?.dismiss()
            progressDialog = null
            val finished = (previous.interaction as? SurveyInteraction.ActiveTesting)?.runId
                ?.let { id -> state.activeRuns.firstOrNull { it.runId == id } }
            if (finished != null) statusText().text = ActiveRunSummary.format(activity, finished)
            if (finished?.statusReason == ErrorCodes.NETWORK_CHANGED) askToCheckAgain(finished)
            return
        }
        val budget = state.activeRuns.firstOrNull { it.runId == testing.runId }?.budgetMillis
        val dialog = progressDialog ?: ThroughputProgressDialog(
            activity, onCancel = { viewModel.cancelActiveRun() }, onSkip = { viewModel.skipActiveRun() }
        ).also { it.show(budget); progressDialog = it }
        state.activeRunProgress?.takeIf { it != previous.activeRunProgress || dialog.isFresh }?.let(dialog::update)
    }

    private var lastStart: Triple<String, String?, List<android.net.wifi.ScanResult>>? = null

    /** rc.2 · 3: the approved Wi-Fi changed before the run started: a new check and decision. */
    private fun askToCheckAgain(run: ActiveRun) {
        val start = lastStart?.takeIf { it.first == run.pointId } ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.network_changed_title)
            .setMessage(activity.getString(R.string.network_changed_message, run.observedSsid ?: "?"))
            .setPositiveButton(R.string.network_changed_check_again) { _, _ -> start(start.first, start.second, start.third) }
            .setNegativeButton(R.string.network_check_skip, null)
            .show()
    }

    /** Android sources of a Continuous capture: scanner, foreground service and, if enabled, the roaming probe. */
    fun captureSources(projectId: String?): CaptureSources {
        // In memory after the first read of the profile (RC4 · 4).
        val settings = configStore.snapshot().imported.roamingSettings.sanitized()
        val roaming = settings.takeIf { it.enabled && it.host.isNotEmpty() }
        return CaptureSources(
            rf = AndroidRfScanSource(activity),
            foreground = AndroidCaptureForeground(activity, projectId),
            roaming = roaming?.let { config -> RoamingProbeSource { scope, onAttempt -> probe.launchRoamingProbe(scope, config, onAttempt) } },
            // ICMP watches a host (no port); TCP a host:port.
            roamingTarget = roaming?.let { if (it.isIcmp) it.host else "${it.host}:${it.port}" },
            roamingMethod = if (roaming?.isIcmp == true) RoamingTracker.METHOD_ICMP else RoamingTracker.METHOD,
            roamingIntervalMillis = roaming?.intervalMillis ?: RoamingSettings.DEFAULT_INTERVAL_MILLIS,
            roamingAlias = roaming?.alias,
            roamingTrace = roaming?.trace == true
        )
    }

    fun close() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    /** Reads a point template, previews it and adds the planned points to the current floor. */
    fun importPointTemplate(uri: Uri, floorKey: String, mapFile: java.io.File? = null, floorPlanId: String? = null,
                            isCurrentFloor: () -> Boolean = { true }) {
        val state = viewModel.screenState.value
        val map = state.mapInfo ?: return
        activity.lifecycleScope.launch {
            // RC4 · 4: reading, parsing and the thumbnail (plan decode, downsampling, drawing) off the main thread.
            var thumbnail: android.graphics.Bitmap? = null
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val text = requireNotNull(activity.contentResolver.openInputStream(uri)) { "Cannot open the file" }.use { input ->
                        String(BoundedIo.readBytes(input, 0L, WiproberConfigStore.MAX_FILE_BYTES, "File larger than 2 MB"), Charsets.UTF_8)
                    }
                    // Plan identity (mejora 20): the ESX floor-plan ID and the image hash, when known.
                    val identity = PointTemplateParser.PlanIdentity(floorPlanId, mapFile?.takeIf { it.isFile }?.let(Hashes::sha256))
                    PointTemplateParser.parse(
                        text, map.width, map.height, floorKey, java.util.UUID.randomUUID().toString(),
                        existing = state.plannedPoints.map(PlannedPoint::pointId).toSet(),
                        plan = identity
                    ).also { preview -> thumbnail = PointActionsDialogs.templateBitmap(mapFile, preview) }
                }
            }
            // The floor changed meanwhile: these points belong to the plan that started the import.
            if (!isCurrentFloor() || viewModel.screenState.value.mapInfo != map) {
                thumbnail?.recycle()
                Toast.makeText(activity, R.string.template_floor_changed, Toast.LENGTH_LONG).show()
                return@launch
            }
            parsed.onSuccess { preview ->
                PointActionsDialogs.showTemplatePreview(activity, preview, thumbnail) {
                    if (!isCurrentFloor()) {
                        Toast.makeText(activity, R.string.template_floor_changed, Toast.LENGTH_LONG).show()
                        return@showTemplatePreview
                    }
                    if (!viewModel.addPlannedPoints(preview.points)) {
                        Toast.makeText(activity, R.string.template_nothing_added, Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { error ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.template_import_failed)
                    .setMessage(error.message ?: error.javaClass.simpleName)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }
        }
    }

    private companion object {
        const val PROFILE_DEVICE_SETTINGS = "device-settings"
        const val DECISION_CONFIRMED = "confirmed"
    }
}
