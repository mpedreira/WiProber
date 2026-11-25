package com.example.wiprober

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
            R.string.throughput_progress_step, progress.step, progress.totalSteps, progress.target, phaseName(progress.phase)
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
            phaseText.setText(R.string.throughput_progress_latency_hint)
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
        val value = result.value?.let {
            String.format(Locale.US, "%.1f %s", it, if (result.phase == ThroughputPhase.LATENCY) "ms" else "Mbps")
        } ?: activity.getString(R.string.throughput_progress_failed)
        return "✓ ${result.target} · ${phaseName(result.phase)}: $value"
    }

    private fun phaseName(phase: ThroughputPhase): String = activity.getString(
        when (phase) {
            ThroughputPhase.LATENCY -> R.string.throughput_phase_latency
            ThroughputPhase.DOWNLOAD -> R.string.throughput_phase_download
            ThroughputPhase.UPLOAD -> R.string.throughput_phase_upload
        }
    )

    private companion object {
        const val TICK_MILLIS = 200L
    }
}
