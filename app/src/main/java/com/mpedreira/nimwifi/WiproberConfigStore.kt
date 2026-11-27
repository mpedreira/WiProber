package com.mpedreira.nimwifi

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Imported network tests, scripts and roaming settings, and the credentials they reference, kept
 * in app-private storage (excluded from backups). Throughput and guide sections of an imported
 * file go to their own per-device stores.
 *
 * Mejora 16: the profile is transactional. Every change writes a **complete revision**
 * (`revisions/<id>/imported.json` + `credentials.json`), validates the resulting set as a whole
 * (scripts, steps, headers, credentials, sizes) and only then switches the `active` pointer with
 * one atomic rename: a failure at any point leaves the previous revision in use, never a mix of
 * new and old. A missing profile and a corrupt one are told apart ([status]).
 *
 * Layout written by earlier versions (`imported.json`, `credentials.json` at the top level) is
 * still read while no revision exists. All I/O is blocking: call it off the main thread.
 */
class WiproberConfigStore(
    private val directory: File,
    private val gson: Gson = Gson(),
    /** Test seam: runs after the new revision is written and before it is activated. */
    private val beforeActivate: () -> Unit = {}
) {
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
        val credentials: Int = 0,
        val revision: String? = null
    )

    /** State of the stored profile. */
    data class Status(val state: String, val revision: String?, val error: String?) {
        companion object {
            const val ABSENT = "absent"
            const val OK = "ok"
            const val CORRUPT = "corrupt"
        }
    }

    private val pointer = File(directory, ACTIVE_FILE)
    private val revisions = File(directory, REVISIONS_DIRECTORY)
    private val legacyFile = File(directory, FILE_NAME)
    private val legacyCredentials = File(directory, CREDENTIALS_FILE_NAME)

    private class Profile(val imported: Imported, val credentials: Map<String, String>)

    private fun activeRevision(): String? = pointer.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Reads the active profile; throws when it exists but cannot be read (corrupt). */
    private fun read(): Profile? {
        val revision = activeRevision()
        val (importedFile, credentialsFile) = if (revision != null) {
            File(revisions, revision).let { File(it, FILE_NAME) to File(it, CREDENTIALS_FILE_NAME) }
        } else {
            legacyFile to legacyCredentials
        }
        if (revision == null && !importedFile.exists() && !credentialsFile.exists()) return null
        if (revision != null) check(importedFile.isFile) { "The active configuration revision is missing" }
        val imported = if (importedFile.isFile) {
            gson.fromJson(importedFile.readText(), Imported::class.java) ?: error("The configuration is empty")
        } else {
            Imported()
        }
        val credentials: Map<String, String> = if (credentialsFile.isFile) {
            gson.fromJson<Map<String, String>>(credentialsFile.readText(), object : TypeToken<Map<String, String>>() {}.type).orEmpty()
        } else {
            emptyMap()
        }
        return Profile(imported, credentials)
    }

    fun status(): Status = try {
        if (read() == null) Status(Status.ABSENT, null, null) else Status(Status.OK, activeRevision(), null)
    } catch (e: Exception) {
        Status(Status.CORRUPT, activeRevision(), e.message ?: e.javaClass.simpleName)
    }

    /** The active profile; empty when absent or unreadable (see [status] to tell them apart). */
    fun load(): Imported = runCatching { read()?.imported }.getOrNull() ?: Imported()

    /** Credential values by name; only the script runner reads them. */
    fun loadCredentials(): Map<String, String> = runCatching { read()?.credentials }.getOrNull().orEmpty()

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

    /** Saves the tests, scripts, roaming and credentials of [config] as one new revision. */
    fun applyImported(config: WiproberConfig): Summary {
        val current = currentForChange()
        val scripts = config.scripts?.let { imported ->
            if (config.mergeScripts) {
                current.imported.scripts.orEmpty().filterNot { old -> imported.any { it.name == old.name } } + imported
            } else {
                imported
            }
        }
        val next = Profile(
            Imported(
                tests = config.tests ?: current.imported.tests,
                scripts = scripts ?: current.imported.scripts,
                roaming = config.roaming ?: current.imported.roaming
            ),
            current.credentials + config.credentials.orEmpty()
        )
        val revision = commit(next)
        return Summary(
            throughput = config.throughput != null,
            guide = config.guide != null,
            tests = config.tests?.size,
            scripts = config.scripts?.map { it.displayAlias },
            roaming = config.roaming != null,
            credentials = config.credentials?.size ?: 0,
            revision = revision
        )
    }

    /** Edits the stored tests, scripts and roaming (the configuration screen), as a new revision. */
    fun update(transform: (Imported) -> Imported) {
        val current = currentForChange()
        commit(Profile(transform(current.imported), current.credentials))
    }

    fun credentialNames(): Set<String> = loadCredentials().keys

    fun setCredential(name: String, value: String) {
        require(Sensitive.CREDENTIAL_NAME.matches(name)) { "Invalid credential name" }
        require(value.length <= Sensitive.MAX_CREDENTIAL_LENGTH && value.none { it == '\r' || it == '\n' }) { "Invalid credential value" }
        val current = currentForChange()
        commit(Profile(current.imported, current.credentials + (name to value)))
    }

    fun removeCredential(name: String) {
        val current = currentForChange()
        commit(Profile(current.imported, current.credentials - name))
    }

    fun clear() {
        pointer.delete()
        revisions.deleteRecursively()
        legacyFile.delete()
        legacyCredentials.delete()
    }

    /** A corrupt profile is never silently replaced by a change: the user removes it first. */
    private fun currentForChange(): Profile = try {
        read() ?: Profile(Imported(), emptyMap())
    } catch (e: Exception) {
        throw ConfigException("The stored configuration cannot be read (${e.message}); remove it before importing")
    }

    /** Validates the whole set, writes it as a new revision and activates it atomically. */
    private fun commit(profile: Profile): String {
        validate(profile)
        val revision = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val target = File(revisions, revision)
        check(target.mkdirs()) { "Cannot create a configuration revision" }
        try {
            write(File(target, FILE_NAME), gson.toJson(profile.imported))
            write(File(target, CREDENTIALS_FILE_NAME), gson.toJson(profile.credentials))
            // Read back: what is activated is exactly what was written.
            check(gson.fromJson(File(target, FILE_NAME).readText(), Imported::class.java) == profile.imported) { "Revision check failed" }
            beforeActivate()
            val temp = File(directory, "$ACTIVE_FILE.tmp")
            temp.writeText(revision)
            if (!temp.renameTo(pointer)) {
                pointer.delete()
                check(temp.renameTo(pointer)) { "Cannot activate the configuration" }
            }
        } catch (e: Throwable) {
            target.deleteRecursively()
            throw e
        }
        prune(keep = setOf(revision))
        return revision
    }

    /** Keeps the active revision and the one before it (for diagnosis), removes older ones. */
    private fun prune(keep: Set<String>) {
        val all = revisions.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        all.dropLast(2).filter { it.name !in keep }.forEach { it.deleteRecursively() }
    }

    private fun write(file: File, text: String) {
        require(text.length <= MAX_FILE_BYTES) { "The configuration is too large" }
        file.writeText(text)
    }

    /** The resulting profile as a whole, after any merge: the same limits as an imported file. */
    private fun validate(profile: Profile) {
        val imported = profile.imported
        val scripts = imported.scripts.orEmpty()
        val tests = imported.tests.orEmpty()
        if (scripts.size > WiproberConfigParser.MAX_SCRIPTS) throw ConfigException("At most ${WiproberConfigParser.MAX_SCRIPTS} scripts in the profile")
        if (scripts.map { it.name }.toSet().size != scripts.size) throw ConfigException("Script names must be unique in the profile")
        scripts.forEach { script ->
            if (script.steps.isEmpty()) throw ConfigException("Script \"${script.name}\" has no steps")
            if (script.steps.size > WiproberConfigParser.MAX_STEPS) throw ConfigException("Script \"${script.name}\": at most ${WiproberConfigParser.MAX_STEPS} steps")
            if (script.steps.any { it.headers.size > WiproberConfigParser.MAX_HEADERS }) {
                throw ConfigException("Script \"${script.name}\": at most ${WiproberConfigParser.MAX_HEADERS} headers per step")
            }
            if (script.steps.any { it.isWrite && !it.allowWrite }) throw ConfigException("Script \"${script.name}\": a write step needs allowWrite")
        }
        if (tests.size > WiproberConfigParser.MAX_TESTS) throw ConfigException("At most ${WiproberConfigParser.MAX_TESTS} tests in the profile")
        if (tests.map { it.name }.toSet().size != tests.size) throw ConfigException("Test names must be unique in the profile")
        if (profile.credentials.size > Sensitive.MAX_CREDENTIALS) throw ConfigException("At most ${Sensitive.MAX_CREDENTIALS} credentials")
        profile.credentials.forEach { (name, value) ->
            if (!Sensitive.CREDENTIAL_NAME.matches(name)) throw ConfigException("Invalid credential name \"$name\"")
            if (value.length > Sensitive.MAX_CREDENTIAL_LENGTH) throw ConfigException("Credential \"$name\" is too long")
        }
        if (gson.toJson(imported).length > MAX_FILE_BYTES) throw ConfigException("The profile is larger than 2 MB")
    }

    companion object {
        /** Must match the backup exclusions in res/xml. */
        const val DIRECTORY = "private-config"
        private const val FILE_NAME = "imported.json"
        private const val CREDENTIALS_FILE_NAME = "credentials.json"
        private const val ACTIVE_FILE = "active"
        private const val REVISIONS_DIRECTORY = "revisions"
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        /** A configuration ZIP (config + HAR recordings). */
        const val MAX_ZIP_BYTES = 8L * 1024 * 1024
    }
}
