package com.mpedreira.nimwifi

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Mejora 5: a **restorable copy** of a whole local project, distinct from the analysis ZIP
 * (ESX + `wiprober-performance.json`), which only lets the ESX be imported.
 *
 * Format `nimwifi-project-backup`, version [VERSION]: a ZIP whose first entry is [MANIFEST_ENTRY]
 * (format, version, project ID, title, and size + SHA-256 of every file) followed by the project
 * workspace byte for byte under [PROJECT_PREFIX]: `manifest.json`, every floor (plan, scale,
 * scans, routes, notes with their photos, planned points, active runs, skipped cells, open route
 * drafts) and `base.esx` when the project was imported. IDs and coordinates are therefore those of
 * the original. Only the project directory is copied: configuration, credentials and per-device
 * settings live elsewhere and are never included. Temporary files (names starting with `.`) are
 * left out.
 *
 * A restore extracts into staging, checks every file against the manifest (nothing missing,
 * nothing extra, sizes and hashes equal, safe paths), lets the repository open the result, and
 * only then moves it into place. An existing project with the same ID needs an explicit [Conflict].
 */
object ProjectBackup {
    const val FORMAT = "nimwifi-project-backup"
    const val VERSION = 1
    const val MANIFEST_ENTRY = "nimwifi-backup.json"
    const val PROJECT_PREFIX = "project/"
    const val PROJECT_MANIFEST = "manifest.json"
    const val MAX_BACKUP_BYTES = 1024L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
    private const val MAX_FILES = 200_000
    private const val BUFFER = 64 * 1024

    @JsonModel
    data class FileEntry(val path: String = "", val size: Long = 0, val sha256: String = "")

    @JsonModel
    data class Manifest(
        val format: String = "",
        val version: Int = 0,
        val projectId: String = "",
        val title: String = "",
        val createdAt: Long = 0,
        val appVersion: String = "",
        val files: List<FileEntry> = emptyList()
    )

    /** What to do when a project with the backup's ID already exists. */
    enum class Conflict { FAIL, AS_COPY, REPLACE }

    class InvalidBackupException(message: String) : IOException(message)

    class ProjectExistsException(val projectId: String) : IOException("A project with this ID already exists")

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Files of the project workspace, relative with `/`, temporary ones excluded. */
    fun projectFiles(projectDirectory: File): List<String> =
        projectDirectory.walkTopDown()
            .onEnter { it == projectDirectory || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.relativeTo(projectDirectory).invariantSeparatorsPath }
            .sorted()
            .toList()

    fun write(projectDirectory: File, projectId: String, title: String, appVersion: String, now: Long, output: OutputStream): Manifest {
        val paths = projectFiles(projectDirectory)
        require(PROJECT_MANIFEST in paths) { "The project has no manifest" }
        val manifest = Manifest(FORMAT, VERSION, projectId, title, now, appVersion,
            paths.map { path -> File(projectDirectory, path).let { FileEntry(path, it.length(), sha256(it)) } })
        ZipOutputStream(output).use { zip ->
            // The manifest goes first so a stream can be recognised by its first entry.
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            manifest.files.forEach { entry ->
                zip.putNextEntry(ZipEntry(PROJECT_PREFIX + entry.path))
                File(projectDirectory, entry.path).inputStream().use { it.copyTo(zip, BUFFER) }
                zip.closeEntry()
            }
        }
        return manifest
    }

    /** True when [input] starts like a project backup (reads only the first ZIP entry header). */
    fun looksLikeBackup(input: InputStream): Boolean = runCatching {
        ZipInputStream(input).nextEntry?.name == MANIFEST_ENTRY
    }.getOrDefault(false)

    fun isBackup(file: File): Boolean = runCatching {
        ZipFile(file).use { it.getEntry(MANIFEST_ENTRY) != null }
    }.getOrDefault(false)

    /** Checks [backup] completely; with [target], also extracts the project files there. */
    fun verify(backup: File, target: File? = null, idPattern: Regex = DEFAULT_ID_PATTERN): Manifest {
        if (!backup.isFile || backup.length() > MAX_BACKUP_BYTES) throw InvalidBackupException("The project copy is missing or too large")
        try {
            ZipFile(backup).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_ENTRY) ?: throw InvalidBackupException("Not a nimWifi project copy")
                if (manifestEntry.size > MAX_MANIFEST_BYTES) throw InvalidBackupException("The copy manifest is too large")
                val manifest = zip.getInputStream(manifestEntry).use { input ->
                    runCatching { gson.fromJson(input.reader(Charsets.UTF_8), Manifest::class.java) }.getOrNull()
                } ?: throw InvalidBackupException("The copy manifest cannot be read")
                if (manifest.format != FORMAT) throw InvalidBackupException("Not a nimWifi project copy")
                if (manifest.version !in 1..VERSION) throw InvalidBackupException("The copy was made by a newer version (format ${manifest.version})")
                if (!idPattern.matches(manifest.projectId)) throw InvalidBackupException("Invalid project ID in the copy")
                if (manifest.files.size > MAX_FILES) throw InvalidBackupException("Too many files in the copy")
                val listed = manifest.files.associateBy { it.path }
                if (listed.size != manifest.files.size) throw InvalidBackupException("Repeated file in the copy manifest")
                if (PROJECT_MANIFEST !in listed) throw InvalidBackupException("The copy has no project manifest")
                listed.keys.forEach { if (!isSafe(it)) throw InvalidBackupException("Unsafe path in the copy: $it") }

                val seen = HashSet<String>()
                var total = 0L
                zip.entries().asSequence().filter { !it.isDirectory && it.name != MANIFEST_ENTRY }.forEach { entry ->
                    val path = entry.name.removePrefix(PROJECT_PREFIX)
                    val expected = listed[path]
                    if (!entry.name.startsWith(PROJECT_PREFIX) || expected == null) throw InvalidBackupException("Unexpected file in the copy: ${entry.name}")
                    if (!seen.add(path)) throw InvalidBackupException("Repeated file in the copy: $path")
                    val destination = target?.let { File(it, path) }
                    destination?.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.getInputStream(entry).use { input ->
                        val out = destination?.outputStream()
                        try {
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                total += count
                                if (size > expected.size || total > MAX_BACKUP_BYTES * 4) throw InvalidBackupException("File larger than declared: $path")
                                digest.update(buffer, 0, count)
                                out?.write(buffer, 0, count)
                            }
                        } finally {
                            out?.close()
                        }
                    }
                    if (size != expected.size || hex(digest.digest()) != expected.sha256) throw InvalidBackupException("File damaged in the copy: $path")
                }
                val missing = listed.keys - seen
                if (missing.isNotEmpty()) throw InvalidBackupException("Incomplete copy: ${missing.size} file(s) missing, e.g. ${missing.first()}")
                // The project inside must be the one the copy claims to be.
                val projectManifest = zip.getInputStream(zip.getEntry(PROJECT_PREFIX + PROJECT_MANIFEST)).use { input ->
                    runCatching { JsonParser.parseReader(input.reader(Charsets.UTF_8)).asJsonObject }.getOrNull()
                } ?: throw InvalidBackupException("The project manifest cannot be read")
                if (projectManifest.get("id")?.asString != manifest.projectId) throw InvalidBackupException("The project identity does not match the copy")
                return manifest
            }
        } catch (e: ZipException) {
            throw InvalidBackupException("The project copy is damaged: ${e.message}")
        }
    }

    /** Persisted phases of a restore (`journal.json`), so a killed process can be recovered. */
    object Phase {
        /** The copy is extracted and validated in `staged/`; the original has not been touched. */
        const val PREPARED = "prepared"
        /** About to move (or moved) the original into `original/`; the copy goes into place next. */
        const val PARKING = "parking"
        /** The copy is in place; the original is kept until the copy has been opened. */
        const val INSTALLED = "installed"
    }

    @JsonModel
    data class Journal(val projectId: String = "", val phase: String = "", val replacing: Boolean = false)

    const val RESTORE_PREFIX = ".restore-"
    private const val JOURNAL = "journal.json"
    private const val STAGED = "staged"
    private const val ORIGINAL = "original"
    private const val REJECTED = "rejected"

    /** Test seam: called after each persisted phase; throwing an [Error] there simulates process death. */
    fun interface Checkpoint {
        fun reached(phase: String)
    }

    /**
     * Restores [backup] under [projectsRoot] through a restore workspace [work] (a directory named
     * with [RESTORE_PREFIX] inside [projectsRoot]): extract to `staged/`, verify, [validate] (the
     * repository opens it), resolve the ID [conflict], then install it.
     *
     * Replacing never deletes the original before the copy is in place **and** has been opened:
     * the original is moved into `original/` of the same workspace and removed only at the end.
     * Every step is recorded in `journal.json`; [recover] finishes or rolls back an interrupted
     * restore. An ordinary failure rolls back here and leaves the original as it was.
     */
    fun restore(
        backup: File,
        projectsRoot: File,
        work: File,
        conflict: Conflict,
        newId: () -> String,
        validate: (File) -> Unit,
        idPattern: Regex = DEFAULT_ID_PATTERN,
        checkpoint: Checkpoint = Checkpoint { }
    ): String {
        require(work.name.startsWith(RESTORE_PREFIX) && work.parentFile == projectsRoot) { "Invalid restore workspace" }
        require(!work.exists() && work.mkdirs()) { "Cannot create a restore workspace" }
        val staged = File(work, STAGED)
        try {
            val manifest = verify(backup, staged, idPattern)
            var projectId = manifest.projectId
            val existing = File(projectsRoot, projectId)
            var replacing = false
            if (existing.exists()) {
                when (conflict) {
                    Conflict.FAIL -> throw ProjectExistsException(projectId)
                    Conflict.AS_COPY -> {
                        projectId = newId()
                        require(idPattern.matches(projectId) && !File(projectsRoot, projectId).exists()) { "Cannot allocate a project ID" }
                        rewriteProjectId(File(staged, PROJECT_MANIFEST), projectId)
                    }
                    Conflict.REPLACE -> replacing = true
                }
            }
            validate(staged)
            writeJournal(work, Journal(projectId, Phase.PREPARED, replacing))
            checkpoint.reached(Phase.PREPARED)
            val destination = File(projectsRoot, projectId)
            if (replacing) {
                writeJournal(work, Journal(projectId, Phase.PARKING, true))
                move(destination, File(work, ORIGINAL))
                checkpoint.reached(Phase.PARKING)
            }
            move(staged, destination)
            writeJournal(work, Journal(projectId, Phase.INSTALLED, replacing))
            checkpoint.reached(Phase.INSTALLED)
            // The original goes only once the installed copy opens.
            check(finish(work, projectsRoot, validate)) { "The restored copy cannot be opened; the original project was kept" }
            return projectId
        } catch (e: Exception) {
            rollBack(work, projectsRoot)
            throw e
        }
    }

    /**
     * Finishes or rolls back every interrupted restore under [projectsRoot]. Idempotent: running
     * it again (or after it was itself interrupted) gives the same result.
     *
     * - no journal: nothing was installed and the original was never moved; the workspace goes;
     * - the copy is still in `staged/` (prepared, or the original was not moved yet): roll back;
     * - the original is in `original/` and nothing is in place: finish installing the copy;
     * - the copy is in place: open it; if it opens, remove the original, otherwise put it back.
     */
    fun recover(projectsRoot: File, validate: (File) -> Unit) {
        projectsRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(RESTORE_PREFIX) }
            .sortedBy { it.name }
            .forEach { work ->
                val journal = readJournal(work)
                if (journal == null || !DEFAULT_ID_PATTERN.matches(journal.projectId)) {
                    // No journal: the original was never moved (the journal precedes every move).
                    if (!File(work, ORIGINAL).exists()) work.deleteRecursively()
                    return@forEach
                }
                val destination = File(projectsRoot, journal.projectId)
                val staged = File(work, STAGED)
                val original = File(work, ORIGINAL)
                if (!destination.exists()) {
                    when {
                        journal.phase != Phase.PREPARED && staged.isDirectory -> move(staged, destination)
                        original.isDirectory -> move(original, destination)
                    }
                }
                when {
                    !destination.exists() -> work.deleteRecursively()
                    staged.exists() -> rollBack(work, projectsRoot)
                    else -> finish(work, projectsRoot, validate)
                }
            }
    }

    /** The copy is in place: keep it if it opens and drop the original, else restore the original. */
    private fun finish(work: File, projectsRoot: File, validate: (File) -> Unit): Boolean {
        val journal = readJournal(work)
        if (journal == null) {
            work.deleteRecursively()
            return false
        }
        val destination = File(projectsRoot, journal.projectId)
        val original = File(work, ORIGINAL)
        val opens = runCatching { validate(destination) }.isSuccess
        if (!opens && original.isDirectory) {
            move(destination, File(work, REJECTED))
            move(original, destination)
        }
        work.deleteRecursively()
        if (!opens && !original.isDirectory) {
            // A new project (no original) whose copy does not open is not kept either.
            if (!journal.replacing) destination.deleteRecursively()
        }
        return opens
    }

    /** Puts the original back in place (when it was moved) and removes the restore workspace. */
    private fun rollBack(work: File, projectsRoot: File) {
        val journal = readJournal(work)
        val original = File(work, ORIGINAL)
        if (journal != null && original.isDirectory) {
            val destination = File(projectsRoot, journal.projectId)
            if (destination.exists()) move(destination, File(work, REJECTED))
            move(original, destination)
        }
        work.deleteRecursively()
    }

    private fun writeJournal(work: File, journal: Journal) {
        val temporary = File(work, "$JOURNAL.tmp")
        temporary.writeText(gson.toJson(journal))
        try {
            Files.move(temporary.toPath(), File(work, JOURNAL).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), File(work, JOURNAL).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readJournal(work: File): Journal? = runCatching {
        gson.fromJson(File(work, JOURNAL).readText(), Journal::class.java)
    }.getOrNull()

    private fun rewriteProjectId(manifestFile: File, projectId: String) {
        val json = JsonParser.parseString(manifestFile.readText()).asJsonObject
        json.addProperty("id", projectId)
        manifestFile.writeText(gson.toJson(json))
    }

    private fun move(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun isSafe(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") }

    private fun sha256(file: File): String = Hashes.sha256(file)

    private fun hex(bytes: ByteArray) = Hashes.hex(bytes)

    private val DEFAULT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
}
