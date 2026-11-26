package com.example.wiprober

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale

/**
 * Modal shown while a point's throughput tests run: which step of how many, the current target
 * and phase with a countdown, and the values already measured. Not cancellable: the point is
 * saved when the tests finish.
 */
class ThroughputProgressDialog(private val activity: AppCompatActivity) {
    @SuppressLint("InflateParams") // dialog content has no parent until the dialog attaches it
    private val view = activity.layoutInflater.inflate(R.layout.dialog_throughput_progress, null)
    private val stepText = view.findViewById<TextView>(R.id.throughputStep)
    private val phaseText = view.findViewById<TextView>(R.id.throughputPhase)
    private val bar = view.findViewById<LinearProgressIndicator>(R.id.throughputPhaseProgress)
    private val finishedText = view.findViewById<TextView>(R.id.throughputFinished)
    private var dialog: AlertDialog? = null
    private var current: ThroughputProgress? = null
    private var phaseStartedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            renderCountdown()
            view.postDelayed(this, TICK_MILLIS)
        }
    }

    fun show() {
        stepText.setText(R.string.throughput_progress_starting)
        phaseText.text = ""
        finishedText.visibility = View.GONE
        bar.isIndeterminate = true
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.throughput_progress_title)
            .setView(view)
            .setCancelable(false)
            .show()
        view.post(ticker)
    }

    fun update(progress: ThroughputProgress) {
        current = progress
        phaseStartedAt = SystemClock.elapsedRealtime()
        stepText.text = activity.getString(
            R.string.throughput_progress_step, progress.step, progress.totalSteps, progress.target,
            phaseName(progress.phase, progress.detail)
        )
        if (progress.phaseSeconds == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.max = progress.phaseSeconds * 1_000
        }
        finishedText.visibility = if (progress.finished.isEmpty()) View.GONE else View.VISIBLE
        finishedText.text = progress.finished.joinToString("\n", transform = ::finishedLine)
        renderCountdown()
    }

    fun dismiss() {
        view.removeCallbacks(ticker)
        dialog?.dismiss()
        dialog = null
    }

    private fun renderCountdown() {
        val progress = current ?: return
        val seconds = progress.phaseSeconds
        if (seconds == null) {
            phaseText.setText(
                if (progress.phase == ThroughputPhase.LATENCY) R.string.throughput_progress_latency_hint
                else R.string.throughput_progress_running
            )
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - phaseStartedAt).coerceAtMost(seconds * 1_000L)
        bar.setProgressCompat(elapsed.toInt(), true)
        val remaining = ((seconds * 1_000L - elapsed + 999) / 1_000).toInt()
        phaseText.text = if (remaining > 0) {
            activity.getString(R.string.throughput_progress_remaining, remaining)
        } else {
            activity.getString(R.string.throughput_progress_finishing)
        }
    }

    private fun finishedLine(result: ThroughputPhaseResult): String {
        val transfer = result.phase == ThroughputPhase.DOWNLOAD || result.phase == ThroughputPhase.UPLOAD
        val value = result.value?.let { String.format(Locale.US, "%.1f %s", it, if (transfer) "Mbps" else "ms") }
            ?: activity.getString(R.string.throughput_progress_failed)
        return "${if (result.value != null) "✓" else "✗"} ${result.target} · ${phaseName(result.phase, result.detail)}: $value"
    }

    private fun phaseName(phase: ThroughputPhase, detail: String?): String = when (phase) {
        ThroughputPhase.LATENCY -> activity.getString(R.string.throughput_phase_latency)
        ThroughputPhase.DOWNLOAD -> activity.getString(R.string.throughput_phase_download)
        ThroughputPhase.UPLOAD -> activity.getString(R.string.throughput_phase_upload)
        ThroughputPhase.NETWORK_TEST, ThroughputPhase.SCRIPT_STEP -> detail.orEmpty()
    }

    private companion object {
        const val TICK_MILLIS = 200L
    }
}
