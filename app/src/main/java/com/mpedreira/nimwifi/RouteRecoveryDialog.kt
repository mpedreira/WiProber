package com.mpedreira.nimwifi

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Offers an interrupted route: save it as it is, continue it with a new stretch, or discard it. */
object RouteRecoveryDialog {
    fun show(context: Context, draft: RouteDraft, onSave: () -> Unit, onContinue: () -> Unit, onDiscard: () -> Unit) {
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.route_recovery_title)
            .setMessage(
                context.getString(
                    R.string.route_recovery_message,
                    time.format(java.util.Date(draft.startTime)),
                    draft.scanCount,
                    draft.segments.size,
                    time.format(java.util.Date(draft.savedAt))
                )
            )
            .setCancelable(false)
            .setPositiveButton(R.string.route_recovery_save) { _, _ -> onSave() }
            .setNeutralButton(R.string.route_recovery_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.route_recovery_discard) { _, _ ->
                MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.route_recovery_discard_confirm)
                    .setPositiveButton(R.string.route_recovery_discard) { _, _ -> onDiscard() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> show(context, draft, onSave, onContinue, onDiscard) }
                    .show()
            }
            .show()
    }
}
