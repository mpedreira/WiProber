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

    /**
     * Restores [backup] under [projectsRoot]: extract to [staging], verify, [validate] (the
     * repository opens the staged project), resolve the ID [conflict], then move into place.
     * Nothing under [projectsRoot] changes unless every step succeeds.
     */
    fun restore(
        backup: File,
        projectsRoot: File,
        staging: File,
        conflict: Conflict,
        newId: () -> String,
        validate: (File) -> Unit,
        idPattern: Regex = DEFAULT_ID_PATTERN
    ): String {
        require(!staging.exists() && staging.mkdirs()) { "Cannot create a restore workspace" }
        var parked: File? = null
        try {
            val manifest = verify(backup, staging, idPattern)
            var projectId = manifest.projectId
            val existing = File(projectsRoot, projectId)
            if (existing.exists()) {
                when (conflict) {
                    Conflict.FAIL -> throw ProjectExistsException(projectId)
                    Conflict.AS_COPY -> {
                        projectId = newId()
                        require(idPattern.matches(projectId) && !File(projectsRoot, projectId).exists()) { "Cannot allocate a project ID" }
                        rewriteProjectId(File(staging, PROJECT_MANIFEST), projectId)
                    }
                    Conflict.REPLACE -> Unit
                }
            }
            validate(staging)
            val destination = File(projectsRoot, projectId)
            if (destination.exists()) {
                // REPLACE: the old project is parked first and deleted only after the move.
                parked = File(projectsRoot, ".deleting-$projectId-${System.nanoTime()}")
                move(destination, parked)
            }
            try {
                move(staging, destination)
            } catch (e: Throwable) {
                parked?.let { move(it, destination) }
                parked = null
                throw e
            }
            return projectId
        } finally {
            staging.deleteRecursively()
            parked?.deleteRecursively()
        }
    }

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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val DEFAULT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,100}")
}
