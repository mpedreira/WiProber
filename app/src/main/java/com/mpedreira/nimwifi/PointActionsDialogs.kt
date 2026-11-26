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
        onMeasure: () -> Unit,
        onActiveOnly: () -> Unit,
        onNewPoint: () -> Unit
    ) {
        val options = buildList {
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
