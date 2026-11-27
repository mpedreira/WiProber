package com.mpedreira.nimwifi

import java.io.File
import java.util.zip.ZipFile

/**
 * What a file opened with the app is: an Ekahau ESX, a nimWifi export ZIP (ESX + results), a
 * configuration ZIP, or a configuration JSON / HAR. Decided from the content, never the name.
 */
object ImportedFiles {
    /**
     * [PROJECT_BACKUP] is a restorable copy of a whole project (mejora 5); [EXPORT_BUNDLE] is an
     * analysis ZIP, from which only the ESX can be imported.
     */
    enum class Kind { ESX, EXPORT_BUNDLE, PROJECT_BACKUP, CONFIG_ZIP, CONFIG_JSON, UNKNOWN }

    private const val MAX_CONFIG_ENTRIES = 50
    private const val MAX_CONFIG_ENTRY_BYTES = 2L * 1024 * 1024
    private const val MAX_CONFIG_TOTAL_BYTES = 8L * 1024 * 1024

    fun detect(file: File): Kind {
        val head = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val names = runCatching { ZipFile(file).use { zip -> zip.entries().toList().map { it.name } } }.getOrNull()
                ?: return Kind.UNKNOWN
            return when {
                ProjectBackup.MANIFEST_ENTRY in names -> Kind.PROJECT_BACKUP
                "project.json" in names -> Kind.ESX
                names.any { it.equals(WiproberConfigParser.CONFIG_FILE_NAME, true) } -> Kind.CONFIG_ZIP
                PerformanceExport.FILE_NAME in names && names.count { it.endsWith(".esx", true) && '/' !in it } == 1 -> Kind.EXPORT_BUNDLE
                else -> Kind.UNKNOWN
            }
        }
        val first = file.inputStream().use { input ->
            generateSequence { input.read().takeIf { it >= 0 } }.take(64).firstOrNull { !Character.isWhitespace(it) && it != 0xEF && it != 0xBB && it != 0xBF }
        }
        return if (first == '{'.code) Kind.CONFIG_JSON else Kind.UNKNOWN
    }

    /** Copies the ESX of a nimWifi export ZIP to [destination]; the results JSON is not imported. */
    fun extractBundleEsx(bundle: File, destination: File, maxBytes: Long) {
        ZipFile(bundle).use { zip ->
            val entry = zip.entries().toList().single { it.name.endsWith(".esx", true) && '/' !in it.name && !it.isDirectory }
            zip.getInputStream(entry).use { BoundedIo.copyToFile(it, destination, maxBytes, "The ESX project is too large") }
        }
    }

    /** Root text files of a configuration ZIP, bounded; directories and nested paths are ignored. */
    fun readConfigZip(file: File): Map<String, String> = ZipFile(file).use { zip ->
        val entries = zip.entries().toList().filter { !it.isDirectory && '/' !in it.name && '\\' !in it.name }
        if (entries.size > MAX_CONFIG_ENTRIES) throw ConfigException("The ZIP has too many files")
        var total = 0L
        entries.filter { it.name.endsWith(".json", true) || it.name.endsWith(".har", true) }.associate { entry ->
            val bytes = zip.getInputStream(entry).use { input ->
                BoundedIo.readBytes(input, 0L, MAX_CONFIG_ENTRY_BYTES, "${entry.name} is larger than 2 MB")
            }
            total += bytes.size
            if (total > MAX_CONFIG_TOTAL_BYTES) throw ConfigException("The ZIP content is too large")
            entry.name to String(bytes, Charsets.UTF_8)
        }
    }
}
