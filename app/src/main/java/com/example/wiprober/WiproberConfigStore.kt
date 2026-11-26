package com.example.wiprober

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Imported network tests, scripts and roaming settings, kept in app-private storage (excluded
 * from backups: they may hold internal hosts). Credentials are kept in a separate file of the
 * same excluded directory, so definitions never carry them. Throughput and guide sections of an
 * imported file go to their own per-device stores.
 *
 * An import is two steps: [preview] parses nothing new and saves nothing, [apply] saves. A file
 * that fails to parse never reaches [apply], so the previous profile stays as it was; each file
 * is replaced atomically.
 */
class WiproberConfigStore(private val directory: File, private val gson: Gson = Gson()) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIRECTORY))

    @JsonModel
    data class Imported(
        val tests: List<NetworkTest>? = emptyList(),
        val scripts: List<HttpScript>? = emptyList(),
        val roaming: RoamingSettings? = null
    ) {
        val enabledTests: List<NetworkTest> get() = tests.orEmpty().filter { it.enabled }
        val enabledScripts: List<HttpScript> get() = scripts.orEmpty().filter { it.enabled }
        val roamingSettings: RoamingSettings get() = roaming ?: RoamingSettings()
    }

    /** What an import changed, for the confirmation message. */
    data class Summary(
        val throughput: Boolean,
        val guide: Boolean,
        val tests: Int?,
        val scripts: List<String>?,
        val roaming: Boolean,
        val credentials: Int = 0
    )

    private val file = File(directory, FILE_NAME)
    private val credentialsFile = File(directory, CREDENTIALS_FILE_NAME)

    fun load(): Imported = runCatching { gson.fromJson(file.readText(), Imported::class.java) }
        .getOrNull() ?: Imported()

    /** Credential values by name; only the script runner reads them. */
    fun loadCredentials(): Map<String, String> = runCatching {
        gson.fromJson<Map<String, String>>(credentialsFile.readText(), object : TypeToken<Map<String, String>>() {}.type)
    }.getOrNull().orEmpty()

    fun preview(config: WiproberConfig): ImportPreview = ImportPreview.of(config, loadCredentials().keys)

    fun apply(
        config: WiproberConfig,
        throughputStore: ThroughputSettingsStore,
        guideStore: SurveyGuideSettingsStore
    ): Summary {
        val summary = applyImported(config)
        config.throughput?.let(throughputStore::save)
        config.guide?.let(guideStore::save)
        return summary
    }

    /** Saves the tests, scripts, roaming and credentials of [config]; the rest is the caller's. */
    fun applyImported(config: WiproberConfig): Summary {
        val current = load()
        val scripts = config.scripts?.let { imported ->
            if (config.mergeScripts) {
                current.scripts.orEmpty().filterNot { old -> imported.any { it.name == old.name } } + imported
            } else {
                imported
            }
        }
        config.credentials?.takeIf { it.isNotEmpty() }?.let { saveAtomically(credentialsFile, gson.toJson(loadCredentials() + it)) }
        saveAtomically(
            file,
            gson.toJson(
                Imported(
                    tests = config.tests ?: current.tests,
                    scripts = scripts ?: current.scripts,
                    roaming = config.roaming ?: current.roaming
                )
            )
        )
        return Summary(
            throughput = config.throughput != null,
            guide = config.guide != null,
            tests = config.tests?.size,
            scripts = config.scripts?.map { it.displayAlias },
            roaming = config.roaming != null,
            credentials = config.credentials?.size ?: 0
        )
    }

    fun clear() {
        file.delete()
        credentialsFile.delete()
    }

    private fun saveAtomically(target: File, text: String) {
        directory.mkdirs()
        val temp = File(directory, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot save the imported configuration" }
        }
    }

    companion object {
        /** Must match the backup exclusions in res/xml. */
        const val DIRECTORY = "private-config"
        private const val FILE_NAME = "imported.json"
        private const val CREDENTIALS_FILE_NAME = "credentials.json"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
