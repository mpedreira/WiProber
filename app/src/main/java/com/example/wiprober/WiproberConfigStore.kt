package com.example.wiprober

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * Imported network tests, scripts and roaming settings, kept in app-private storage (excluded
 * from backups: they may hold internal hosts and credentials). Throughput and guide sections of
 * an imported file go to their own per-device stores.
 */
class WiproberConfigStore(context: Context, private val gson: Gson = Gson()) {
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
        val roaming: Boolean
    )

    private val file = File(context.applicationContext.filesDir, "$DIRECTORY/$FILE_NAME")

    fun load(): Imported = runCatching { gson.fromJson(file.readText(), Imported::class.java) }
        .getOrNull() ?: Imported()

    fun apply(
        config: WiproberConfig,
        throughputStore: ThroughputSettingsStore,
        guideStore: SurveyGuideSettingsStore
    ): Summary {
        config.throughput?.let(throughputStore::save)
        config.guide?.let(guideStore::save)
        val current = load()
        val scripts = config.scripts?.let { imported ->
            if (config.mergeScripts) {
                current.scripts.orEmpty().filterNot { old -> imported.any { it.name == old.name } } + imported
            } else {
                imported
            }
        }
        save(
            Imported(
                tests = config.tests ?: current.tests,
                scripts = scripts ?: current.scripts,
                roaming = config.roaming ?: current.roaming
            )
        )
        return Summary(
            throughput = config.throughput != null,
            guide = config.guide != null,
            tests = config.tests?.size,
            scripts = config.scripts?.map { it.name },
            roaming = config.roaming != null
        )
    }

    fun clear() {
        file.delete()
    }

    private fun save(imported: Imported) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(gson.toJson(imported))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Cannot save the imported configuration" }
        }
    }

    companion object {
        /** Must match the backup exclusions in res/xml. */
        const val DIRECTORY = "private-config"
        private const val FILE_NAME = "imported.json"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
