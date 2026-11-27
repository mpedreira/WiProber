package com.mpedreira.nimwifi

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * rc.2 · 1: «Restaurar y reemplazar» killed at any point leaves one valid project after reopening,
 * and recovering twice gives the same result.
 */
class ProjectRestoreRecoveryTest {
    private val root = Files.createTempDirectory("recovery").toFile()
    private val id = "11111111-2222-4333-8444-555555555555"

    /** Simulates the process being killed: not an Exception, so nothing rolls back in-process. */
    private class Killed : Error()

    private fun project(dir: File, scans: String) = File(dir, id).apply {
        mkdirs()
        File(this, "manifest.json").writeText("""{"schemaVersion":3,"id":"$id","title":"Almacén","activeFloorId":"floor-1"}""")
        File(this, "floors/floor-1/map").mkdirs(); File(this, "floors/floor-1/map/floor-plan.png").writeBytes(byteArrayOf(1, 2, 3))
        File(this, "floors/floor-1/survey.json").writeText("""{"scanPoints":[$scans]}""")
    }

    /** What the repository checks when it opens a project: manifest, plan and survey. */
    private val opens: (File) -> Unit = { dir ->
        check(File(dir, "manifest.json").readText().contains(id))
        check(File(dir, "floors/floor-1/map/floor-plan.png").isFile)
        check(File(dir, "floors/floor-1/survey.json").readText().contains("scanPoints"))
    }

    private fun copyOf(scans: String): File {
        val source = project(File(root, "source-${System.nanoTime()}"), scans)
        return File(root, "copy-${System.nanoTime()}.zip").also { out -> out.outputStream().use { ProjectBackup.write(source, id, "Almacén", "t", 1, it) } }
    }

    private fun replace(projects: File, copy: File, killAt: String? = null, validate: (File) -> Unit = opens) =
        ProjectBackup.restore(copy, projects, File(projects, "${ProjectBackup.RESTORE_PREFIX}${System.nanoTime()}"),
            ProjectBackup.Conflict.REPLACE, { "unused" }, validate, checkpoint = { phase -> if (phase == killAt) throw Killed() })

    private fun survey(projects: File) = File(projects, "$id/floors/floor-1/survey.json").readText()

    private fun reopen(projects: File) {
        // Twice: recovery is idempotent.
        ProjectBackup.recover(projects, opens)
        ProjectBackup.recover(projects, opens)
        opens(File(projects, id))
        assertEquals("only the project remains", listOf(id), projects.list()!!.toList())
    }

    private fun scenario(killAt: String): String {
        val projects = File(root, "phone-$killAt").apply { mkdirs() }
        project(projects, """{"pointId":"original"}""")
        try { replace(projects, copyOf("""{"pointId":"copy"}"""), killAt); fail("expected the simulated kill") } catch (_: Killed) {}
        reopen(projects)
        return survey(projects)
    }

    @Test
    fun killedBeforeTheOriginalMovesKeepsTheOriginal() {
        assertTrue("original" in scenario(ProjectBackup.Phase.PREPARED))
    }

    @Test
    fun killedAfterParkingTheOriginalFinishesInstallingTheValidatedCopy() {
        assertTrue("copy" in scenario(ProjectBackup.Phase.PARKING))
    }

    @Test
    fun killedAfterInstallingButBeforeCleanupKeepsTheCopy() {
        assertTrue("copy" in scenario(ProjectBackup.Phase.INSTALLED))
    }

    @Test
    fun aDamagedCopyLeavesTheOriginalIntact() {
        val projects = File(root, "phone-damaged").apply { mkdirs() }
        project(projects, """{"pointId":"original"}""")
        val copy = copyOf("""{"pointId":"copy"}""")
        val damaged = File(root, "damaged.zip")
        ZipFile(copy).use { zip ->
            ZipOutputStream(damaged.outputStream()).use { out ->
                zip.entries().toList().forEach { e ->
                    out.putNextEntry(ZipEntry(e.name))
                    val bytes = zip.getInputStream(e).readBytes()
                    if (e.name.endsWith("survey.json")) bytes[3] = 'X'.code.toByte()
                    out.write(bytes); out.closeEntry()
                }
            }
        }
        try { replace(projects, damaged); fail() } catch (_: ProjectBackup.InvalidBackupException) {}
        reopen(projects)
        assertTrue("original" in survey(projects))
    }

    @Test
    fun anInstalledCopyThatDoesNotOpenIsSwappedBackForTheOriginal() {
        val projects = File(root, "phone-unreadable").apply { mkdirs() }
        project(projects, """{"pointId":"original"}""")
        var calls = 0
        // Opens while staged, fails once in place (e.g. the move broke a file).
        val flaky: (File) -> Unit = { dir -> calls++; opens(dir); check(dir.parentFile != projects) { "does not open in place" } }
        try { replace(projects, copyOf("""{"pointId":"copy"}"""), validate = flaky); fail() } catch (_: IllegalStateException) {}
        reopen(projects)
        assertTrue("original" in survey(projects))
        assertTrue(calls >= 2)
    }

    @Test
    fun restoreAsCopyAndExplicitDeletionAreUnchanged() {
        val projects = File(root, "phone-copy").apply { mkdirs() }
        project(projects, """{"pointId":"original"}""")
        val newId = "99999999-2222-4333-8444-555555555555"
        val restored = ProjectBackup.restore(copyOf("""{"pointId":"copy"}"""), projects, File(projects, "${ProjectBackup.RESTORE_PREFIX}1"),
            ProjectBackup.Conflict.AS_COPY, { newId }, { })
        assertEquals(newId, restored)
        assertEquals(setOf(id, newId), projects.list()!!.toSet())
        // A `.deleting-*` workspace is the explicit-delete path; recovery never touches it.
        val deleting = File(projects, ".deleting-$id-1").apply { mkdirs() }
        ProjectBackup.recover(projects, opens)
        assertTrue(deleting.exists())
        assertFalse(projects.list()!!.any { it.startsWith(ProjectBackup.RESTORE_PREFIX) })
    }
}
