package com.mpedreira.nimwifi

import android.annotation.SuppressLint
import android.content.Context
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/** Per-device storage of [SurveyGuideSettings]. */
class SurveyGuideSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): SurveyGuideSettings {
        val defaults = SurveyGuideSettings()
        return SurveyGuideSettings(
            enabled = prefs.getBoolean(ENABLED, defaults.enabled),
            spacingMeters = prefs.getFloat(SPACING, defaults.spacingMeters.toFloat()).toDouble(),
            coverageRadiusMeters = prefs.getFloat(RADIUS, defaults.coverageRadiusMeters.toFloat()).toDouble(),
            ssidFilter = prefs.getString(SSID, null) ?: defaults.ssidFilter,
            bandFilter = prefs.getString(BAND, null) ?: defaults.bandFilter,
            profile = prefs.getString(PROFILE, null) ?: defaults.profile
        ).sanitized()
    }

    fun save(settings: SurveyGuideSettings) {
        val clean = settings.sanitized()
        prefs.edit {
            putBoolean(ENABLED, clean.enabled)
            putFloat(SPACING, clean.spacingMeters.toFloat())
            putFloat(RADIUS, clean.coverageRadiusMeters.toFloat())
            putString(SSID, clean.ssidFilter)
            putString(BAND, clean.bandFilter)
            putString(PROFILE, clean.profile)
        }
    }

    private companion object {
        const val PREFS = "survey_guide_settings"
        const val ENABLED = "enabled"
        const val SPACING = "spacing_m"
        const val RADIUS = "radius_m"
        const val SSID = "ssid_filter"
        const val BAND = "band_filter"
        const val PROFILE = "signal_profile"
    }
}

/** Dialog that edits [SurveyGuideSettingsStore]; [onSaved] lets the screen redraw the guide. */
object SurveyGuideSettingsDialog {
    @SuppressLint("InflateParams") // dialog content has no parent until the dialog attaches it
    fun show(activity: AppCompatActivity, store: SurveyGuideSettingsStore, onSaved: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_survey_guide_settings, null)
        val enabled = view.findViewById<CheckBox>(R.id.guideEnabled)
        val spacing = view.findViewById<TextInputEditText>(R.id.guideSpacing)
        val radius = view.findViewById<TextInputEditText>(R.id.guideRadius)
        val ssid = view.findViewById<TextInputEditText>(R.id.guideSsid)
        val profiles = view.findViewById<android.widget.RadioGroup>(R.id.guideProfile)
        val profileButtons = mapOf(
            SignalProfile.PDA to R.id.guideProfilePda, SignalProfile.VOICE to R.id.guideProfileVoice, SignalProfile.DATA to R.id.guideProfileData
        )

        fun fill(settings: SurveyGuideSettings) {
            enabled.isChecked = settings.enabled
            spacing.setText(meters(settings.spacingMeters))
            radius.setText(meters(settings.coverageRadiusMeters))
            ssid.setText(settings.ssidFilter)
            profiles.check(profileButtons.getValue(settings.signal))
        }

        val current = store.load()
        fill(current)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.guide_settings_title)
            .setView(view)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                store.save(
                    SurveyGuideSettings(
                        enabled = enabled.isChecked,
                        spacingMeters = parse(spacing) ?: current.spacingMeters,
                        coverageRadiusMeters = parse(radius) ?: current.coverageRadiusMeters,
                        ssidFilter = ssid.text?.toString().orEmpty(),
                        profile = profileButtons.entries.firstOrNull { it.value == profiles.checkedRadioButtonId }?.key?.id
                            ?: current.profile
                    )
                )
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.throughput_restore_defaults, null)
            .show()
        // Keep the dialog open: restoring defaults only refills the fields.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            fill(SurveyGuideSettings(ssidFilter = ssid.text?.toString().orEmpty()))
        }
    }

    /** Accepts "7.5" and "7,5". */
    private fun parse(field: TextInputEditText): Double? =
        field.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun meters(value: Double): String =
        if (value % 1.0 == 0.0) String.format(Locale.ROOT, "%d", value.toLong()) else String.format(Locale.ROOT, "%.1f", value)
}
