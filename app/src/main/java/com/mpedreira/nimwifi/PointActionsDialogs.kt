package com.mpedreira.nimwifi

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dialogs for reusing points, importing a point template and choosing the map SSID. */
object PointActionsDialogs {
    /** Tap on an existing point: measure again there, active tests only, or a new point at the tap. */
    fun showPointActions(
        context: Context,
        label: String,
        canRunActiveTests: Boolean,
        onDetails: () -> Unit,
        onMeasure: () -> Unit,
        onActiveOnly: () -> Unit,
        onNewPoint: () -> Unit
    ) {
        val options = buildList {
            add(context.getString(R.string.point_details) to onDetails)
            add(context.getString(R.string.point_measure_here) to onMeasure)
            if (canRunActiveTests) add(context.getString(R.string.point_active_only) to onActiveOnly)
            add(context.getString(R.string.point_new_here) to onNewPoint)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.point_actions_title, label))
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The point sheet: its scans and every run with status, values and errors, newest first. */
    fun showPointDetails(context: Context, details: PointDetails, ssid: String?) {
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
        val text = buildString {
            append(context.getString(R.string.point_details_position, details.x.toInt(), details.y.toInt()))
            if (details.imported) append("\n").append(context.getString(R.string.point_details_imported))
            if (details.planned) append("\n").append(context.getString(R.string.point_details_planned))
            append("\n\n").append(context.getString(R.string.point_details_scans, details.scans.size))
            details.scans.forEach { scan ->
                append("\n• ").append(time.format(java.util.Date(scan.at))).append(" · ")
                    .append(context.getString(R.string.point_details_networks, scan.networks))
                if (ssid != null) {
                    append(" · ").append(ssid).append(": ")
                    append(scan.strongestRssi?.let { "$it dBm (${scan.strongestBssid})" } ?: context.getString(R.string.point_details_not_detected))
                }
            }
            append("\n\n").append(context.getString(R.string.point_details_runs, details.runs.size))
            details.runs.forEach { run ->
                append("\n\n• ").append(time.format(java.util.Date(run.startedAt))).append(" · ").append(run.status)
                run.statusReason?.let { append(" (").append(it).append(")") }
                if (run.scanId == null) append(" · ").append(context.getString(R.string.point_details_active_only))
                PointDetails.resultLines(run).forEach { append("\n   ").append(it) }
                run.phases.filter { it.status == RunStatus.FAILED || it.status == RunStatus.SKIPPED }.takeIf { it.isNotEmpty() }?.let { phases ->
                    append("\n   ").append(phases.joinToString(", ") { "${it.label}: ${it.status}${it.errorCode?.let { c -> " ($c)" } ?: ""}" })
                }
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.point_actions_title, details.label))
            .setMessage(text)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    fun showTemplatePreview(context: Context, preview: PointTemplateParser.Preview, onAccept: () -> Unit) {
        val message = context.getString(
            R.string.template_preview_message,
            preview.points.size,
            preview.sourceWidth,
            preview.sourceHeight,
            "%.3f".format(java.util.Locale.ROOT, preview.scale),
            preview.outside,
            preview.duplicates,
            preview.points.take(8).joinToString(", ") { it.label }
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.template_preview_title)
            .setMessage(message)
            .setPositiveButton(R.string.template_add) { _, _ -> onAccept() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Single choice among the SSIDs scanned on the floor; "automatic" = the most widely detected. */
    fun showSsidPicker(context: Context, ssids: List<String>, current: String, onChosen: (String) -> Unit) {
        val automatic = context.getString(R.string.map_ssid_automatic)
        val items = listOf(automatic) + ssids
        val checked = ssids.indexOf(current).let { if (it < 0) 0 else it + 1 }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.map_ssid_title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                onChosen(if (which == 0) "" else ssids[which - 1])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
