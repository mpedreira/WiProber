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

    /**
     * Mejora 20: counts, repeated ids and points outside the plan, and a thumbnail of the plan
     * with the points to add. Without plan identity the user confirms that it is the right plan.
     */
    fun showTemplatePreview(context: Context, preview: PointTemplateParser.Preview, mapFile: java.io.File?, onAccept: () -> Unit) {
        val message = StringBuilder(context.getString(
            R.string.template_preview_message,
            preview.points.size,
            preview.sourceWidth,
            preview.sourceHeight,
            "%.3f".format(java.util.Locale.ROOT, preview.scale),
            preview.outside,
            preview.duplicates,
            preview.points.take(8).joinToString(", ") { it.label }
        ))
        if (preview.repeatedIds.isNotEmpty()) {
            message.append("\n\n").append(context.getString(R.string.template_repeated_ids, preview.repeatedIds.take(10).joinToString(", ")))
        }
        if (preview.outsideLabels.isNotEmpty()) {
            message.append("\n\n").append(context.getString(R.string.template_outside_points, preview.outsideLabels.take(10).joinToString(", ")))
        }
        message.append("\n\n").append(context.getString(
            if (preview.needsConfirmation) R.string.template_plan_unverified else R.string.template_plan_matched
        ))
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.template_preview_title)
            .setMessage(message.toString())
            .setPositiveButton(if (preview.needsConfirmation) R.string.template_add_confirm else R.string.template_add) { _, _ -> onAccept() }
            .setNegativeButton(android.R.string.cancel, null)
        templateThumbnail(context, mapFile, preview)?.let(builder::setView)
        builder.show()
    }

    /** The plan, downscaled, with the points to add drawn on it; null when the plan cannot be read. */
    private fun templateThumbnail(context: Context, mapFile: java.io.File?, preview: PointTemplateParser.Preview): android.view.View? {
        if (mapFile == null || !mapFile.isFile) return null
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(mapFile.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 800) sample *= 2
            val decoded = android.graphics.BitmapFactory.decodeFile(mapFile.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val bitmap = decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { if (it !== decoded) decoded.recycle() }
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(21, 101, 192) }
            val s = bitmap.width.toFloat() / bounds.outWidth
            val radius = maxOf(3f, bitmap.width / 120f)
            preview.points.forEach { canvas.drawCircle(it.x * s, it.y * s, radius, paint) }
            android.widget.ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                val pad = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                contentDescription = context.getString(R.string.template_preview_title)
            }
        }.getOrNull()
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
