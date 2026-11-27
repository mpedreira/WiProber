package com.mpedreira.nimwifi

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Mejora 18: an export is built completely in app storage (staging), checked, and only then
 * copied to the destination the user chose, in fixed-size chunks.
 *
 * A document provider (Drive, a USB stick, another app) does not offer an atomic replace: once the
 * copy has started, a failure can leave a partial file at the destination. That case is reported
 * as [DestinationIncompleteException] so the user is told the destination may be incomplete and
 * must be exported again; a failure before the destination is opened leaves it untouched.
 */
object StagedExport {
    const val STAGING_PREFIX = "esx-export-"
    private const val STALE_MILLIS = 60L * 60 * 1000
    private const val BUFFER_BYTES = 64 * 1024

    class InvalidArtifactException(message: String) : IOException(message)

    /** The copy to the destination failed after it started: the destination may hold a partial file. */
    class DestinationIncompleteException(val copiedBytes: Long, val totalBytes: Long, cause: Throwable) :
        IOException("The export destination may be incomplete ($copiedBytes of $totalBytes bytes written): export again", cause)

    /**
     * Checks that [artifact] is a readable ZIP with every [requiredEntries] name, reading each
     * entry through (so a truncated or corrupt entry fails here, not at the destination).
     */
    fun verifyZip(artifact: File, requiredEntries: Set<String>, nestedEsx: String? = null) {
        if (!artifact.isFile || artifact.length() == 0L) throw InvalidArtifactException("The export was not created")
        try {
            ZipFile(artifact).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                val missing = requiredEntries - names
                if (missing.isNotEmpty()) throw InvalidArtifactException("The export is missing ${missing.sorted().joinToString()}")
                val buffer = ByteArray(BUFFER_BYTES)
                zip.entries().asSequence().forEach { entry ->
                    zip.getInputStream(entry).use { input -> while (input.read(buffer) >= 0) Unit }
                }
            }
        } catch (e: ZipException) {
            throw InvalidArtifactException("The export is not a valid archive: ${e.message}")
        }
        if (nestedEsx != null) {
            // The ESX inside a bundle must itself be a valid project archive.
            val nested = File(artifact.parentFile, "${artifact.name}.nested")
            try {
                ZipFile(artifact).use { zip ->
                    zip.getInputStream(zip.getEntry(nestedEsx)).use { input -> nested.outputStream().use { input.copyTo(it, BUFFER_BYTES) } }
                }
                verifyZip(nested, setOf(ESX_PROJECT_ENTRY))
            } finally {
                nested.delete()
            }
        }
    }

    /**
     * Copies the verified [artifact] to the stream [open] returns. A failure to open leaves the
     * destination untouched (plain [IOException]); a failure during the copy or close is a
     * [DestinationIncompleteException].
     */
    fun deliver(artifact: File, open: () -> OutputStream?): Long {
        val total = artifact.length()
        val output = open() ?: throw IOException("Cannot open the selected export destination")
        var copied = 0L
        try {
            output.use { out ->
                artifact.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        copied += count
                    }
                    out.flush()
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw DestinationIncompleteException(copied, total, e)
        }
        if (copied != total) throw DestinationIncompleteException(copied, total, IOException("Short copy"))
        return copied
    }

    /** Staging left behind by a process that died mid-export. */
    fun removeStale(cacheDir: File, now: Long = System.currentTimeMillis()) {
        cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith(STAGING_PREFIX) && now - it.lastModified() > STALE_MILLIS }
            .forEach { it.deleteRecursively() }
    }

    const val ESX_PROJECT_ENTRY = "project.json"
}
