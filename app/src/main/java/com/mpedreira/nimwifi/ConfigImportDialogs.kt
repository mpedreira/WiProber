package com.mpedreira.nimwifi

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Preview and confirmation of an imported configuration: destinations, methods and what was
 * stripped are shown before anything is activated; profiles with writes need a second, explicit
 * confirmation. Only names, hosts and methods are shown, never credential values.
 */
object ConfigImportDialogs {
    fun showPreview(context: Context, preview: ImportPreview, onActivate: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(if (preview.kind == ImportPreview.KIND_HAR) R.string.config_preview_title_har else R.string.config_preview_title)
            .setMessage(format(context, preview))
            .setPositiveButton(R.string.config_activate) { _, _ ->
                if (preview.requiresWriteConfirmation) confirmWrites(context, preview, onActivate) else onActivate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmWrites(context: Context, preview: ImportPreview, onActivate: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.config_writes_title)
            .setMessage(context.getString(R.string.config_writes_message, preview.writeSteps.joinToString("\n• ", prefix = "• ")))
            .setPositiveButton(R.string.config_writes_confirm) { _, _ -> onActivate() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun format(context: Context, preview: ImportPreview): String = buildList {
        val none = context.getString(R.string.config_none)
        add(
            context.getString(
                R.string.config_preview_destinations,
                preview.destinations.joinToString("\n") { d ->
                    val port = d.port?.let { ":$it" }.orEmpty()
                    val cleartext = if (d.cleartext) " " + context.getString(R.string.config_preview_cleartext) else ""
                    "• ${d.scheme}://${d.host}$port — ${d.methods.joinToString("/")}$cleartext"
                }.ifEmpty { none }
            )
        )
        preview.tests?.let { add(context.getString(R.string.config_preview_tests, it)) }
        preview.scripts?.let { add(context.getString(R.string.config_preview_scripts, it.joinToString(", ").ifEmpty { none })) }
        if (preview.writeSteps.isNotEmpty()) add(context.getString(R.string.config_preview_writes, preview.writeSteps.size))
        if (preview.stripped.isNotEmpty()) {
            add(context.getString(R.string.config_preview_stripped, preview.stripped.joinToString("\n• ", prefix = "• ")))
        }
        if (preview.credentialsProvided.isNotEmpty()) {
            add(context.getString(R.string.config_preview_credentials, preview.credentialsProvided.joinToString(", ")))
        }
        if (preview.credentialsMissing.isNotEmpty()) {
            add(context.getString(R.string.config_preview_missing, preview.credentialsMissing.joinToString(", ")))
        }
    }.joinToString("\n\n")
}
