package com.mpedreira.nimwifi

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.util.UUID

class PreparedEsxImport internal constructor(
    private val workspace: File,
    internal val sourceArchive: File,
    val floors: List<ImportedEsxProject>
) : Closeable {
    override fun close() {
        workspace.deleteRecursively()
    }
}

/** The opened file is a nimWifi configuration, not a project: import it as configuration. */
class ConfigurationFileException : Exception("The file is a nimWifi configuration")

class EsxImportService(
    private val context: Context,
    private val projectRepository: ProjectRepository
) {
    fun prepare(uri: Uri): PreparedEsxImport {
        val importRoot = File(context.cacheDir, IMPORT_CACHE_DIRECTORY).apply { mkdirs() }
        val workspace = File(importRoot, UUID.randomUUID().toString())
        require(workspace.mkdirs()) { "Cannot create an ESX import workspace" }
        try {
            val sourceFile = File(workspace, SOURCE_FILE)
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Cannot read the selected ESX file"
            }
            input.use {
                BoundedIo.copyToFile(
                    it,
                    sourceFile,
                    DEFAULT_ESX_LIMITS.maxSourceBytes,
                    "The ESX project is too large"
                )
            }
            // An ESX, or a nimWifi export ZIP whose ESX is imported (its results JSON is not);
            // a configuration file is handed back to the caller.
            val esx = when (ImportedFiles.detect(sourceFile)) {
                ImportedFiles.Kind.EXPORT_BUNDLE -> File(workspace, BUNDLE_ESX_FILE).also {
                    ImportedFiles.extractBundleEsx(sourceFile, it, DEFAULT_ESX_LIMITS.maxSourceBytes)
                    sourceFile.delete()
                }
                ImportedFiles.Kind.CONFIG_ZIP, ImportedFiles.Kind.CONFIG_JSON -> throw ConfigurationFileException()
                else -> sourceFile
            }
            val extractedDirectory = File(workspace, EXTRACTED_DIRECTORY)
            val floors = EsxProjectImporter().import(esx, extractedDirectory)
            require(floors.isNotEmpty()) { "The ESX project does not contain usable floor plans" }
            return PreparedEsxImport(workspace, esx, floors)
        } catch (error: Throwable) {
            workspace.deleteRecursively()
            throw error
        }
    }

    fun commit(prepared: PreparedEsxImport): ProjectSummary =
        projectRepository.importProject(prepared.floors, prepared.sourceArchive)

    companion object {
        private const val IMPORT_CACHE_DIRECTORY = "esx-import-jobs"
        private const val EXTRACTED_DIRECTORY = "extracted"
        private const val SOURCE_FILE = "source.esx"
        private const val BUNDLE_ESX_FILE = "bundle.esx"
    }
}
