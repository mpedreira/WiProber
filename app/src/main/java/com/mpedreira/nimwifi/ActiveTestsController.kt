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

    /** True when the configuration has at least one active test to run. */
    fun hasTests(): Boolean {
        val imported = configStore.load()
        return ThroughputRunner.plan(throughputStore.load(), imported.enabledTests, imported.enabledScripts).isNotEmpty()
    }

    /**
     * Runs the configured active tests on a saved point. The run lives in the ViewModel, so a
     * rotation neither stops nor restarts it; the executor only captures application-level objects.
     */
    fun start(pointId: String, scanId: String?, scanResults: List<android.net.wifi.ScanResult>) {
        lastStart = Triple(pointId, scanId, scanResults)
        val settings = throughputStore.load()
        val imported = configStore.load()
        if (ThroughputRunner.plan(settings, imported.enabledTests, imported.enabledScripts).isEmpty()) return
        // Mejora 13: before sending anything, which Wi-Fi the tests would use and whether it is the declared one.
        activity.lifecycleScope.launch {
            val preflight = withContext(Dispatchers.IO) { runCatching { probe.preflight(settings.sanitized().expectedSsid) }.getOrNull() }
            if (preflight != null && NetworkVerification.needsDecision(preflight.verification)) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.network_check_title)
                    .setMessage(activity.getString(
                        if (preflight.verification == NetworkVerification.UNKNOWN) R.string.network_check_unknown else R.string.network_check_mismatch,
                        preflight.expectedSsid.orEmpty(), preflight.observedSsid ?: "?",
                        preflight.eligible.joinToString(", ") { it ?: "?" }
                    ))
                    .setCancelable(false)
                    .setPositiveButton(R.string.network_check_test_anyway) { _, _ -> run(pointId, scanId, scanResults, preflight, DECISION_CONFIRMED) }
                    .setNegativeButton(R.string.network_check_skip, null)
                    .show()
            } else {
                run(pointId, scanId, scanResults, preflight, null)
            }
        }
    }

    private fun run(pointId: String, scanId: String?, scanResults: List<android.net.wifi.ScanResult>, preflight: NetworkPreflight?, decision: String?) {
        val settings = throughputStore.load()
        val imported = configStore.load()
        val credentials = configStore.loadCredentials()
        val phases = ThroughputRunner.plan(settings, imported.enabledTests, imported.enabledScripts)
        if (phases.isEmpty()) return
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
        val settings = configStore.load().roamingSettings.sanitized()
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
    fun importPointTemplate(uri: Uri, floorKey: String, mapFile: java.io.File? = null, floorPlanId: String? = null) {
        val state = viewModel.screenState.value
        val map = state.mapInfo ?: return
        activity.lifecycleScope.launch {
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
                    )
                }
            }
            parsed.onSuccess { preview ->
                PointActionsDialogs.showTemplatePreview(activity, preview, mapFile) {
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
