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
        val settings = throughputStore.load()
        val imported = configStore.load()
        val credentials = configStore.loadCredentials()
        val phases = ThroughputRunner.plan(settings, imported.enabledTests, imported.enabledScripts)
        if (phases.isEmpty()) return
        val probe = probe
        viewModel.startActiveRun(pointId, scanId, phases, profile = PROFILE_DEVICE_SETTINGS) { control, listener ->
            probe.measure(settings, imported, credentials, scanResults, control, listener)
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
            return
        }
        val dialog = progressDialog ?: ThroughputProgressDialog(
            activity, onCancel = { viewModel.cancelActiveRun() }, onSkip = { viewModel.skipActiveRun() }
        ).also { it.show(); progressDialog = it }
        state.activeRunProgress?.takeIf { it != previous.activeRunProgress || dialog.isFresh }?.let(dialog::update)
    }

    /** Android sources of a Continuous capture: scanner, foreground service and, if enabled, the roaming probe. */
    fun captureSources(projectId: String?): CaptureSources {
        val settings = configStore.load().roamingSettings.sanitized()
        val roaming = settings.takeIf { it.enabled && it.host.isNotEmpty() }
        return CaptureSources(
            rf = AndroidRfScanSource(activity),
            foreground = AndroidCaptureForeground(activity, projectId),
            roaming = roaming?.let { config -> RoamingProbeSource { scope, onAttempt -> probe.launchRoamingProbe(scope, config, onAttempt) } },
            roamingTarget = roaming?.let { "${it.host}:${it.port}" },
            roamingIntervalMillis = roaming?.intervalMillis ?: RoamingSettings.DEFAULT_INTERVAL_MILLIS
        )
    }

    fun close() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    /** Reads a point template, previews it and adds the planned points to the current floor. */
    fun importPointTemplate(uri: Uri, floorKey: String) {
        val state = viewModel.screenState.value
        val map = state.mapInfo ?: return
        activity.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val text = requireNotNull(activity.contentResolver.openInputStream(uri)) { "Cannot open the file" }.use { input ->
                        String(BoundedIo.readBytes(input, 0L, WiproberConfigStore.MAX_FILE_BYTES, "File larger than 2 MB"), Charsets.UTF_8)
                    }
                    PointTemplateParser.parse(
                        text, map.width, map.height, floorKey, java.util.UUID.randomUUID().toString(),
                        existing = state.plannedPoints.map(PlannedPoint::pointId).toSet()
                    )
                }
            }
            parsed.onSuccess { preview ->
                PointActionsDialogs.showTemplatePreview(activity, preview) {
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
    }
}
