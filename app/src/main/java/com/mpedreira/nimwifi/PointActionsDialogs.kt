package com.mpedreira.nimwifi

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dialogs for reusing points, importing a point template and choosing the map SSID. */
object PointActionsDialogs {
    /**
     * Tap on an existing point (local, imported or planned, RC4 · 3): its sheet, a new coverage
     * measurement or coverage + performance at its exact position and identity, active tests only,
     * or a new point at the tap.
     */
    fun showPointActions(
        context: Context,
        label: String,
        canRunActiveTests: Boolean,
        onDetails: () -> Unit,
        onMeasure: (PointMeasure) -> Unit,
        onActiveOnly: () -> Unit,
        onNewPoint: () -> Unit
    ) {
        val options = buildList {
            add(context.getString(R.string.point_details) to onDetails)
            add(context.getString(R.string.measure_coverage_here) to { onMeasure(PointMeasure.COVERAGE) })
            if (canRunActiveTests) {
                add(context.getString(R.string.measure_coverage_performance_here) to { onMeasure(PointMeasure.COVERAGE_AND_PERFORMANCE) })
                add(context.getString(R.string.point_active_only) to onActiveOnly)
            }
            add(context.getString(R.string.point_new_here) to onNewPoint)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.point_actions_title, label))
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun statusLabel(context: Context, status: String): String = context.getString(when (status) {
        PointStatus.PENDING -> R.string.point_status_pending
        PointStatus.COVERAGE -> R.string.point_status_coverage
        PointStatus.PERFORMANCE -> R.string.point_status_performance
        else -> R.string.point_status_failure
    })

    /**
     * RC4 · 3: the point sheet. On top, the latest signal of the map network and the latest
     * attempt of each service (result, reason, proxy and HTTP code); when that attempt failed and
     * an earlier one was valid, both with their dates. The full history folds below.
     */
    fun showPointDetails(context: Context, details: PointDetails, ssid: String?, networkLabel: String? = ssid) {
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
        fun at(millis: Long) = time.format(java.util.Date(millis))
        val summary = buildString {
            append(context.getString(R.string.point_sheet_status, statusLabel(context, details.status)))
            if (details.imported) append("\n").append(context.getString(R.string.point_details_imported))
            if (details.planned) append("\n").append(context.getString(R.string.point_details_planned))
            append("\n\n")
            val scan = details.latestScan
            append(context.getString(R.string.point_sheet_signal, networkLabel ?: context.getString(R.string.guide_legend_no_ssid))).append("\n")
            append(when {
                scan == null -> context.getString(R.string.point_sheet_no_scan)
                ssid == null -> context.getString(R.string.point_details_networks, scan.networks) + " · " + at(scan.at)
                scan.strongestRssi != null -> context.getString(R.string.point_sheet_signal_value, scan.strongestRssi, scan.strongestBssid.orEmpty(),
                    WifiBands.of(scan.frequency)?.let { " · $it GHz" }.orEmpty(), at(scan.at))
                scan.networks > 0 -> context.getString(R.string.point_sheet_signal_absent, scan.networks, at(scan.at))
                else -> context.getString(R.string.point_sheet_signal_empty, at(scan.at))
            })
            details.lastRun?.takeIf { it.status != RunStatus.COMPLETED }?.let { run ->
                append("\n\n").append(context.getString(R.string.point_sheet_last_run, at(run.startedAt), run.status,
                    run.statusReason?.let { " ($it)" }.orEmpty()))
            }
            if (details.services.isNotEmpty()) {
                append("\n\n").append(context.getString(R.string.point_sheet_services))
                details.services.forEach { history ->
                    val a = history.last
                    append("\n• ").append(a.label).append(": ")
                        .append(context.getString(if (a.ok) R.string.point_sheet_ok else R.string.point_sheet_failed))
                    a.value?.let { append(" · ").append(it) }
                    a.reason?.let { append(" · ").append(it) }
                    a.httpStatus?.let { append(" · HTTP ").append(it) }
                    if (a.proxyConfig != null || a.route != null) {
                        append(" · ").append(context.getString(R.string.point_sheet_route,
                            ServerCheckDialog.proxyLabel(context, a.proxyConfig), ServerCheckDialog.routeLabel(context, a.route)))
                    }
                    a.retryAt?.let { append(" · ").append(context.getString(R.string.point_sheet_retry, at(it))) }
                    append(" · ").append(at(a.at))
                    history.lastValid?.let { v ->
                        append("\n   ").append(context.getString(R.string.point_sheet_last_valid, v.value ?: "—", at(v.at)))
                    }
                }
            }
        }
        val history = buildString {
            append(context.getString(R.string.point_details_position, details.x.toInt(), details.y.toInt()))
            append("\n\n").append(context.getString(R.string.point_details_scans, details.scans.size))
            details.scans.forEach { scan ->
                append("\n• ").append(at(scan.at)).append(" · ")
                    .append(context.getString(R.string.point_details_networks, scan.networks))
                if (ssid != null) {
                    append(" · ").append(ssid).append(": ")
                    append(scan.strongestRssi?.let { "$it dBm (${scan.strongestBssid})" } ?: context.getString(R.string.point_details_not_detected))
                }
            }
            append("\n\n").append(context.getString(R.string.point_details_runs, details.runs.size))
            details.runs.forEach { run ->
                append("\n\n• ").append(at(run.startedAt)).append(" · ").append(run.status)
                run.statusReason?.let { append(" (").append(it).append(")") }
                if (run.scanId == null) append(" · ").append(context.getString(R.string.point_details_active_only))
                PointDetails.resultLines(run).forEach { append("\n   ").append(it) }
                run.phases.filter { it.status == RunStatus.FAILED || it.status == RunStatus.SKIPPED }.takeIf { it.isNotEmpty() }?.let { phases ->
                    append("\n   ").append(phases.joinToString(", ") { "${it.label}: ${it.status}${it.errorCode?.let { c -> " ($c)" } ?: ""}" })
                }
            }
        }
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val historyText = android.widget.TextView(context).apply {
            text = history
            visibility = android.view.View.GONE
            setTextIsSelectable(true)
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val historyLabel = context.getString(R.string.point_sheet_history, details.scans.size, details.runs.size)
        val toggle = com.google.android.material.button.MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = historyLabel
            minHeight = (48 * density).toInt()
            setOnClickListener {
                val show = historyText.visibility != android.view.View.VISIBLE
                historyText.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                text = if (show) context.getString(R.string.point_sheet_hide_history) else historyLabel
            }
        }
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(android.widget.TextView(context).apply { text = summary; setTextIsSelectable(true) })
            addView(toggle, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() })
            addView(historyText)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.point_actions_title, details.label))
            .setView(android.widget.ScrollView(context).apply { addView(content) })
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

    /** `Ops · 5 GHz`, `Ops · all bands`. */
    fun networkLabel(context: Context, network: MapNetwork): String = when (network.band) {
        null -> context.getString(R.string.map_network_all_bands, network.ssid)
        else -> context.getString(R.string.map_network_band, network.ssid, context.getString(when (network.band) {
            WifiBands.B24 -> R.string.band_24
            WifiBands.B5 -> R.string.band_5
            else -> R.string.band_6
        }))
    }

    /**
     * The primary network (SSID + band) of the map: "automatic" (the most widely detected SSID,
     * every band), an SSID in every band, or an SSID in one band.
     */
    fun showNetworkPicker(context: Context, networks: List<MapNetwork>, current: MapNetwork?, onChosen: (MapNetwork?) -> Unit) {
        val items = listOf(context.getString(R.string.map_ssid_automatic)) + networks.map { networkLabel(context, it) }
        val checked = current?.let { networks.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.map_network_title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                onChosen(if (which == 0) null else networks[which - 1])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
