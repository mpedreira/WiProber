package com.mpedreira.nimwifi

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/** Per-device storage of [ThroughputSettings]: server names stay on the phone, not in the code. */
class ThroughputSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ThroughputSettings {
        val defaults = ThroughputSettings()
        return ThroughputSettings(
            iperfEnabled = prefs.getBoolean(IPERF_ENABLED, defaults.iperfEnabled),
            iperfLabel = prefs.getString(IPERF_LABEL, null) ?: defaults.iperfLabel,
            iperfHost = prefs.getString(IPERF_HOST, null) ?: defaults.iperfHost,
            iperfPort = prefs.getInt(IPERF_PORT, defaults.iperfPort),
            iperfDurationSeconds = prefs.getInt(IPERF_DURATION, defaults.iperfDurationSeconds),
            internetEnabled = prefs.getBoolean(INTERNET_ENABLED, defaults.internetEnabled),
            internetDownloadUrl = prefs.getString(INTERNET_DOWNLOAD, null) ?: defaults.internetDownloadUrl,
            internetUploadUrl = prefs.getString(INTERNET_UPLOAD, null) ?: defaults.internetUploadUrl,
            internetDurationSeconds = prefs.getInt(INTERNET_DURATION, defaults.internetDurationSeconds),
            expectedSsid = prefs.getString(EXPECTED_SSID, null) ?: defaults.expectedSsid
        ).sanitized()
    }

    fun save(settings: ThroughputSettings) {
        val clean = settings.sanitized()
        prefs.edit {
            putBoolean(IPERF_ENABLED, clean.iperfEnabled)
            putString(IPERF_LABEL, clean.iperfLabel)
            putString(IPERF_HOST, clean.iperfHost)
            putInt(IPERF_PORT, clean.iperfPort)
            putInt(IPERF_DURATION, clean.iperfDurationSeconds)
            putBoolean(INTERNET_ENABLED, clean.internetEnabled)
            putString(INTERNET_DOWNLOAD, clean.internetDownloadUrl)
            putString(INTERNET_UPLOAD, clean.internetUploadUrl)
            putInt(INTERNET_DURATION, clean.internetDurationSeconds)
            putString(EXPECTED_SSID, clean.expectedSsid)
        }
    }

    private companion object {
        const val PREFS = "throughput_settings"
        const val IPERF_ENABLED = "iperf_enabled"
        const val IPERF_LABEL = "iperf_label"
        const val IPERF_HOST = "iperf_host"
        const val IPERF_PORT = "iperf_port"
        const val IPERF_DURATION = "iperf_duration"
        const val INTERNET_ENABLED = "internet_enabled"
        const val INTERNET_DOWNLOAD = "internet_download_url"
        const val INTERNET_UPLOAD = "internet_upload_url"
        const val INTERNET_DURATION = "internet_duration"
        const val EXPECTED_SSID = "expected_ssid"
    }
}

/** Dialog that edits [ThroughputSettingsStore]; kept out of SurveyActivity. */
object ThroughputSettingsDialog {
    /** ASCII digits: the fields are parsed back with toIntOrNull(). */
    private fun number(value: Int): String = String.format(Locale.ROOT, "%d", value)

    fun show(activity: AppCompatActivity, store: ThroughputSettingsStore) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_throughput_settings, null)
        val iperfEnabled = view.findViewById<CheckBox>(R.id.iperfEnabled)
        val iperfLabel = view.findViewById<TextInputEditText>(R.id.iperfLabel)
        val iperfHost = view.findViewById<TextInputEditText>(R.id.iperfHost)
        val iperfPort = view.findViewById<TextInputEditText>(R.id.iperfPort)
        val iperfDuration = view.findViewById<TextInputEditText>(R.id.iperfDuration)
        val internetEnabled = view.findViewById<CheckBox>(R.id.internetEnabled)
        val downloadUrl = view.findViewById<TextInputEditText>(R.id.internetDownloadUrl)
        val uploadUrl = view.findViewById<TextInputEditText>(R.id.internetUploadUrl)
        val internetDuration = view.findViewById<TextInputEditText>(R.id.internetDuration)
        val expectedSsid = view.findViewById<TextInputEditText>(R.id.expectedSsid)

        fun fill(settings: ThroughputSettings) {
            iperfEnabled.isChecked = settings.iperfEnabled
            iperfLabel.setText(settings.iperfLabel)
            iperfHost.setText(settings.iperfHost)
            iperfPort.setText(number(settings.iperfPort))
            iperfDuration.setText(number(settings.iperfDurationSeconds))
            internetEnabled.isChecked = settings.internetEnabled
            downloadUrl.setText(settings.internetDownloadUrl)
            uploadUrl.setText(settings.internetUploadUrl)
            internetDuration.setText(number(settings.internetDurationSeconds))
            expectedSsid.setText(settings.expectedSsid)
        }

        val current = store.load()

        fill(current)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.throughput_settings_title)
            .setView(view)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                store.save(
                    ThroughputSettings(
                        iperfEnabled = iperfEnabled.isChecked,
                        iperfLabel = iperfLabel.text?.toString().orEmpty(),
                        iperfHost = iperfHost.text?.toString().orEmpty(),
                        iperfPort = iperfPort.text?.toString()?.toIntOrNull() ?: current.iperfPort,
                        iperfDurationSeconds = iperfDuration.text?.toString()?.toIntOrNull()
                            ?: current.iperfDurationSeconds,
                        internetEnabled = internetEnabled.isChecked,
                        internetDownloadUrl = downloadUrl.text?.toString().orEmpty(),
                        internetUploadUrl = uploadUrl.text?.toString().orEmpty(),
                        internetDurationSeconds = internetDuration.text?.toString()?.toIntOrNull()
                            ?: current.internetDurationSeconds,
                        expectedSsid = expectedSsid.text?.toString().orEmpty()
                    )
                )
                Toast.makeText(activity, R.string.throughput_settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.throughput_restore_defaults, null)
            .show()
        // Keep the dialog open: restoring defaults only refills the fields.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            fill(
                ThroughputSettings(
                    iperfEnabled = iperfEnabled.isChecked,
                    iperfLabel = iperfLabel.text?.toString().orEmpty(),
                    iperfHost = iperfHost.text?.toString().orEmpty(), // never wipe the user's server
                    internetEnabled = internetEnabled.isChecked
                ).sanitized()
            )
        }
    }
}
