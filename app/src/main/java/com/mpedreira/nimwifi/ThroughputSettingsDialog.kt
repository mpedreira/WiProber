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
            expectedSsid = prefs.getString(EXPECTED_SSID, null) ?: defaults.expectedSsid,
            // Absent in settings saved before RC4: resolved from the URLs (exact match, else custom).
            internetServerId = prefs.getString(INTERNET_SERVER, null).orEmpty(),
            customDownloadUrl = prefs.getString(CUSTOM_DOWNLOAD, null).orEmpty(),
            customUploadUrl = prefs.getString(CUSTOM_UPLOAD, null).orEmpty()
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
            putString(INTERNET_SERVER, clean.internetServerId)
            putString(CUSTOM_DOWNLOAD, clean.customDownloadUrl)
            putString(CUSTOM_UPLOAD, clean.customUploadUrl)
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
        const val INTERNET_SERVER = "internet_server_id"
        const val CUSTOM_DOWNLOAD = "internet_custom_download_url"
        const val CUSTOM_UPLOAD = "internet_custom_upload_url"
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
        val serverField = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.internetServer)
        val customUrls = view.findViewById<android.view.View>(R.id.internetCustomUrls)
        val serverNote = view.findViewById<android.widget.TextView>(R.id.internetServerNote)
        // RC4 · 1: one drop-down; choosing only changes the destination, no traffic is sent.
        val serverIds = InternetServers.all.map { it.id } + InternetServers.CUSTOM
        val serverLabels = InternetServers.all.map { it.label } + activity.getString(R.string.throughput_server_custom)
        serverField.setSimpleItems(serverLabels.toTypedArray())
        var selectedServer = InternetServers.CLOUDFLARE

        fun showServer(id: String) {
            selectedServer = id
            serverField.setText(serverLabels[serverIds.indexOf(id).coerceAtLeast(0)], false)
            customUrls.visibility = if (id == InternetServers.CUSTOM) android.view.View.VISIBLE else android.view.View.GONE
            serverNote.setText(when {
                id == InternetServers.CUSTOM -> R.string.throughput_server_note_custom
                id == InternetServers.CLOUDFLARE -> R.string.throughput_server_note_cloudflare
                else -> R.string.throughput_server_note_librespeed
            })
        }
        serverField.setOnItemClickListener { _, _, position, _ -> showServer(serverIds[position]) }

        fun fill(settings: ThroughputSettings) {
            iperfEnabled.isChecked = settings.iperfEnabled
            iperfLabel.setText(settings.iperfLabel)
            iperfHost.setText(settings.iperfHost)
            iperfPort.setText(number(settings.iperfPort))
            iperfDuration.setText(number(settings.iperfDurationSeconds))
            internetEnabled.isChecked = settings.internetEnabled
            // The custom fields keep the custom URLs, whatever destination is selected now.
            downloadUrl.setText(settings.customDownloadUrl)
            uploadUrl.setText(settings.customUploadUrl)
            showServer(settings.internetServerId.ifEmpty { InternetServers.CLOUDFLARE })
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
                        internetServerId = selectedServer,
                        customDownloadUrl = downloadUrl.text?.toString().orEmpty(),
                        customUploadUrl = uploadUrl.text?.toString().orEmpty(),
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
                    internetEnabled = internetEnabled.isChecked,
                    internetServerId = InternetServers.CLOUDFLARE,
                    // Nor the custom URLs: they come back when "Personalizado" is chosen again.
                    customDownloadUrl = downloadUrl.text?.toString().orEmpty(),
                    customUploadUrl = uploadUrl.text?.toString().orEmpty()
                ).sanitized()
            )
        }
    }
}
