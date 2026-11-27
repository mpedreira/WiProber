package com.mpedreira.nimwifi

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports a configuration (JSON, browser HAR or configuration ZIP) from any screen:
 * parse → preview (nothing saved) → explicit activation; a failure keeps the previous profile.
 */
class ConfigurationImport(private val activity: AppCompatActivity, private val onActivated: () -> Unit = {}) {
    private val configStore = WiproberConfigStore(activity)
    private val throughputStore = ThroughputSettingsStore(activity)
    private val guideStore = SurveyGuideSettingsStore(activity)

    fun import(uri: Uri) {
        activity.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(activity.cacheDir, "config-import-${UUID.randomUUID()}")
                    try {
                        requireNotNull(activity.contentResolver.openInputStream(uri)) { "Cannot open the file" }.use {
                            BoundedIo.copyToFile(it, file, WiproberConfigStore.MAX_ZIP_BYTES, "File larger than 8 MB")
                        }
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "config"
                        val config = parseFile(file, name)
                        config to configStore.preview(config)
                    } finally {
                        file.delete()
                    }
                }
            }
            parsed.onSuccess { (config, preview) ->
                ConfigImportDialogs.showPreview(activity, preview) { activate(config) }
            }.onFailure(::showFailure)
        }
    }

    /** A JSON/HAR file or a configuration ZIP already copied to app storage. */
    fun parseFile(file: File, name: String): WiproberConfig = when (ImportedFiles.detect(file)) {
        ImportedFiles.Kind.CONFIG_ZIP -> WiproberConfigParser.parseZip(ImportedFiles.readConfigZip(file))
        ImportedFiles.Kind.CONFIG_JSON -> {
            if (file.length() > WiproberConfigStore.MAX_FILE_BYTES) throw ConfigException("File larger than 2 MB")
            WiproberConfigParser.parse(file.readText(Charsets.UTF_8), name)
        }
        else -> throw ConfigException("Not a nimWifi configuration (JSON, HAR or configuration ZIP)")
    }

    fun previewAndActivate(config: WiproberConfig) {
        ConfigImportDialogs.showPreview(activity, configStore.preview(config)) { activate(config) }
    }

    private fun activate(config: WiproberConfig) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { configStore.apply(config, throughputStore, guideStore) }
            }
            result.onSuccess { summary ->
                onActivated()
                val yes = activity.getString(R.string.config_updated)
                val unchanged = activity.getString(R.string.config_unchanged)
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.config_imported_title)
                    .setMessage(
                        activity.getString(
                            R.string.config_imported_summary,
                            if (summary.throughput) yes else unchanged,
                            if (summary.guide) yes else unchanged,
                            summary.tests?.toString() ?: unchanged,
                            summary.scripts?.joinToString(", ")?.ifEmpty { "0" } ?: unchanged,
                            if (summary.roaming) yes else unchanged,
                            summary.credentials
                        )
                    )
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }.onFailure(::showFailure)
        }
    }

    fun showFailure(error: Throwable) {
        // Parser messages never quote credential values; sanitize anything else.
        val detail = (error as? ConfigException)?.message ?: ErrorClassifier.sanitize(error.message ?: error.javaClass.simpleName)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.config_import_failed)
            .setMessage(activity.getString(R.string.config_import_failed_kept, detail))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }
}
