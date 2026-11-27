package com.mpedreira.nimwifi

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Mejora 5: a complete, verified, restorable project copy, distinct from the analysis ZIP. */
class ProjectBackupTest {
    private val root = Files.createTempDirectory("backup").toFile()
    private val projectId = "11111111-2222-4333-8444-555555555555"

    /** Two floors, a note photo, a reused point, a route, an active run without scan, base.esx. */
    private fun project(base: File = File(root, "phone-a/projects")): File = File(base, projectId).apply {
        mkdirs()
        File(this, "manifest.json").writeText("""{"schemaVersion":3,"id":"$projectId","title":"Almacén","activeFloorId":"floor-1","baseEsxRelativePath":"base.esx"}""")
        File(this, "base.esx").writeBytes(ByteArray(4096) { (it % 251).toByte() })
        File(this, "floors/floor-1/map").mkdirs(); File(this, "floors/floor-1/map/floor-plan.png").writeBytes(byteArrayOf(1, 2, 3))
        File(this, "floors/floor-1/media").mkdirs(); File(this, "floors/floor-1/media/image-n1").writeBytes(ByteArray(2048) { 9 })
        File(this, "floors/floor-1/survey.json").writeText(
            """{"scanPoints":[{"pointId":"p1","x":10.5,"y":20.25,"scanId":"s1"},{"pointId":"p1","x":10.5,"y":20.25,"scanId":"s2"}],""" +
                """"continuousScanSessions":[{"id":"route-1"}],"activeRuns":[{"runId":"r1","pointId":"p1","scanId":null}]}""")
        File(this, "floors/floor-1/drafts").mkdirs(); File(this, "floors/floor-1/drafts/route-2.json").writeText("{}")
        File(this, "floors/floor-2/map").mkdirs(); File(this, "floors/floor-2/map/floor-plan.jpg").writeBytes(byteArrayOf(4, 5))
        File(this, "floors/floor-2/survey.json").writeText("""{"scanPoints":[]}""")
        File(this, ".manifest.json.tmp").writeText("temporary")
    }

    private fun backup(source: File = project()): File = File(root, "copy-${System.nanoTime()}.zip").also { out ->
        out.outputStream().use { ProjectBackup.write(source, projectId, "Almacén", "4.0.0", 1L, it) }
    }

    private fun restore(copy: File, projects: File, conflict: ProjectBackup.Conflict = ProjectBackup.Conflict.FAIL, validate: (File) -> Unit = {}) =
        ProjectBackup.restore(copy, projects, File(projects, ".staging-restore-${System.nanoTime()}"), conflict, { "22222222-0000-4000-8000-000000000000" }, validate)

    private fun tree(dir: File) = ProjectBackup.projectFiles(dir).associateWith { File(dir, it).readBytes().toList() }

    @Test
    fun aCleanInstallRestoresEveryFileIdAndValue() {
        val source = project()
        val copy = backup(source)
        assertEquals(ProjectBackup.MANIFEST_ENTRY, ZipFile(copy).use { it.entries().nextElement().name })
        assertFalse("temporary files are not copied", ZipFile(copy).use { zip -> zip.entries().toList().any { ".tmp" in it.name } })
        assertTrue(ProjectBackup.looksLikeBackup(copy.inputStream()))
        assertEquals(ImportedFiles.Kind.PROJECT_BACKUP, ImportedFiles.detect(copy))

        val clean = File(root, "phone-b/projects").apply { mkdirs() }
        assertEquals(projectId, restore(copy, clean))
        val restored = File(clean, projectId)
        assertEquals(tree(source), tree(restored))
        assertTrue(File(restored, "floors/floor-1/survey.json").readText().contains(""""x":10.5,"y":20.25"""))
        assertArrayEquals(File(source, "base.esx").readBytes(), File(restored, "base.esx").readBytes())
        assertEquals("no staging left", listOf(projectId), clean.list()!!.toList())
    }

    @Test
    fun anIncompleteOrDamagedCopyIsRejectedAndNothingIsWritten() {
        val copy = backup()
        // Drop one file: the manifest still lists it.
        val incomplete = File(root, "incomplete.zip")
        ZipFile(copy).use { zip ->
            ZipOutputStream(incomplete.outputStream()).use { out ->
                zip.entries().toList().filterNot { it.name.endsWith("image-n1") }.forEach { e ->
                    out.putNextEntry(ZipEntry(e.name)); zip.getInputStream(e).use { it.copyTo(out) }; out.closeEntry()
                }
            }
        }
        val damaged = File(root, "damaged.zip")
        ZipFile(copy).use { zip ->
            ZipOutputStream(damaged.outputStream()).use { out ->
                zip.entries().toList().forEach { e ->
                    out.putNextEntry(ZipEntry(e.name))
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    if (e.name.endsWith("survey.json") && "floor-1" in e.name) bytes[5] = 'X'.code.toByte()
                    out.write(bytes); out.closeEntry()
                }
            }
        }
        val truncated = File(root, "truncated.zip").also { it.writeBytes(copy.readBytes().copyOf((copy.length() / 2).toInt())) }
        val projects = File(root, "phone-c/projects").apply { mkdirs() }
        listOf(incomplete, damaged, truncated).forEach { bad ->
            try { restore(bad, projects); fail(bad.name) } catch (_: ProjectBackup.InvalidBackupException) {}
        }
        assertEquals(emptyList<String>(), projects.list()!!.toList())
        // A copy the repository cannot open is not committed either.
        try { restore(copy, projects, validate = { error("unreadable survey") }); fail() } catch (_: IllegalStateException) {}
        assertEquals(emptyList<String>(), projects.list()!!.toList())
    }

    @Test
    fun aDuplicateRestoreNeedsAnExplicitDecision() {
        val projects = File(root, "phone-d/projects").apply { mkdirs() }
        val original = project(projects)
        File(original, "floors/floor-1/survey.json").writeText("""{"local":"changes"}""")
        val copy = backup(project(File(root, "other/projects")))

        try { restore(copy, projects); fail() } catch (e: ProjectBackup.ProjectExistsException) { assertEquals(projectId, e.projectId) }
        assertEquals("""{"local":"changes"}""", File(original, "floors/floor-1/survey.json").readText())

        val newId = restore(copy, projects, ProjectBackup.Conflict.AS_COPY)
        assertNotEquals(projectId, newId)
        assertTrue(File(projects, "$newId/manifest.json").readText().contains(newId))
        assertEquals("""{"local":"changes"}""", File(original, "floors/floor-1/survey.json").readText())

        assertEquals(projectId, restore(copy, projects, ProjectBackup.Conflict.REPLACE))
        assertTrue(File(original, "floors/floor-1/survey.json").readText().contains("scanPoints"))
        assertEquals(setOf(projectId, newId), projects.list()!!.toSet())
    }

    @Test
    fun anAnalysisZipIsNotAProjectCopy() {
        val esx = File(root, "p.esx").also { f -> ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("project.json")); it.write("{}".toByteArray()); it.closeEntry() } }
        val bundle = File(root, "analysis.zip").also { f -> f.outputStream().use { PerformanceExport.writeBundle(it, "Site.esx", esx, "{}".toByteArray(), null) } }
        assertEquals(ImportedFiles.Kind.EXPORT_BUNDLE, ImportedFiles.detect(bundle))
        assertFalse(ProjectBackup.isBackup(bundle))
        assertFalse(ProjectBackup.looksLikeBackup(bundle.inputStream()))
        try { ProjectBackup.verify(bundle); fail() } catch (_: ProjectBackup.InvalidBackupException) {}
    }

    @Test
    fun unsafePathsAndNewerFormatsAreRejected() {
        fun zip(manifest: String, vararg entries: String) = File(root, "z${System.nanoTime()}.zip").also { f ->
            ZipOutputStream(f.outputStream()).use { out ->
                out.putNextEntry(ZipEntry(ProjectBackup.MANIFEST_ENTRY)); out.write(manifest.toByteArray()); out.closeEntry()
                entries.forEach { out.putNextEntry(ZipEntry(it)); out.closeEntry() }
            }
        }
        val newer = zip("""{"format":"${ProjectBackup.FORMAT}","version":99,"projectId":"$projectId","files":[]}""")
        try { ProjectBackup.verify(newer); fail() } catch (e: ProjectBackup.InvalidBackupException) { assertTrue("newer" in e.message!!) }
        val traversal = zip("""{"format":"${ProjectBackup.FORMAT}","version":1,"projectId":"$projectId","files":[""" +
            """{"path":"manifest.json","size":0,"sha256":""},{"path":"../evil","size":0,"sha256":""}]}""")
        try { ProjectBackup.verify(traversal); fail() } catch (e: ProjectBackup.InvalidBackupException) { assertTrue("Unsafe" in e.message!!) }
        val extra = zip("""{"format":"${ProjectBackup.FORMAT}","version":1,"projectId":"$projectId","files":[]}""", "other/file")
        try { ProjectBackup.verify(extra); fail() } catch (_: ProjectBackup.InvalidBackupException) {}
    }
}
