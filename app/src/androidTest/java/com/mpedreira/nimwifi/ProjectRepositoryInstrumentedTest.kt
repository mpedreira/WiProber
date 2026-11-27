package com.mpedreira.nimwifi

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var projectsDirectory: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        projectsDirectory = File(context.cacheDir, "project-repository-test")
        projectsDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        projectsDirectory.deleteRecursively()
    }

    @Test
    fun createsListsSavesAndReopensProject() {
        var timestamp = 1_000L
        val repository = ProjectRepository(
            context = context,
            now = { timestamp++ },
            newId = { "project-1" },
            projectsRoot = projectsDirectory
        )

        val created = repository.createNewProject(
            mapInput = ByteArrayInputStream(pngBytes()),
            displayName = "Office map.png"
        )

        assertEquals("Office map", created.title)
        assertTrue(created.mapFile.isFile)
        assertEquals(listOf("project-1"), repository.listRecentProjects().map { it.id })

        val opened = repository.openProject("project-1")
        assertEquals(16, opened.activeFloor.mapInfo.width)
        assertEquals(12, opened.activeFloor.mapInfo.height)
        assertEquals(1, opened.floors.size)

        repository.saveProject(
            "project-1",
            ProjectSnapshot(
                mapInfo = opened.activeFloor.mapInfo,
                metersPerUnit = 0.05,
                scanPoints = listOf(
                    ScanPoint(
                        timestamp = 10L,
                        x = 4f,
                        y = 5f,
                        wifiNetworks = listOf(
                            WifiNetworkInfo(
                                ssid = "Fixture",
                                bssid = "00:11:22:33:44:55",
                                level = -50,
                                frequency = 5_180,
                                security = "WPA2",
                                technologies = listOf("AX"),
                                informationElements = ""
                            )
                        )
                    )
                ),
                continuousScanSessions = emptyList(),
                notes = emptyList()
            )
        )

        // Read literal on-disk keys: a Gson-to-Gson round trip alone can hide
        // obfuscation that breaks projects written by the unminified 3.0.0.
        val manifest = com.google.gson.JsonParser.parseString(
            File(projectsDirectory, "project-1/manifest.json").readText()
        ).asJsonObject
        assertEquals("NEW", manifest.get("sourceType").asString)
        val floor = manifest.getAsJsonArray("floors").single().asJsonObject
        val surveyFile = File(File(projectsDirectory, "project-1"), floor.get("surveyRelativePath").asString)
        val json = com.google.gson.JsonParser.parseString(surveyFile.readText()).asJsonObject
        val network = json.getAsJsonArray("scanPoints").single().asJsonObject
            .getAsJsonArray("wifiNetworks").single().asJsonObject
        assertEquals("Fixture", network.get("ssid").asString)
        assertEquals(5180, network.get("frequency").asInt)

        // Simulate a saved survey from the previous, non-R8 release.
        surveyFile.writeText("""
            {"metersPerUnit":0.05,"scanPoints":[{"timestamp":10,"x":4,"y":5,
            "wifiNetworks":[{"ssid":"Fixture","bssid":"00:11:22:33:44:55",
            "level":-50,"frequency":5180,"security":"WPA2","technologies":["AX"],
            "informationElements":""}],"importedFromEsx":false}],
            "continuousScanSessions":[{"id":"route-1","startTime":100,"endTime":200,
            "waypoints":[{"timestamp":0,"x":2,"y":3}],
            "scanResults":[{"timestamp":5,"duration":20,"wifiNetworks":[]}],
            "importedFromEsx":false}],"notes":[]}
        """.trimIndent())

        val reopened = repository.openProject("project-1")
        assertEquals(0.05, reopened.activeFloor.metersPerUnit ?: 0.0, 0.0)
        val route = reopened.activeFloor.continuousScanSessions.single()
        assertEquals(100L, route.startTime)
        assertEquals(2f, route.waypoints.single().x)
        assertEquals(20L, route.scanResults.single().duration)
        assertEquals(
            "00:11:22:33:44:55",
            reopened.activeFloor.scanPoints.single().wifiNetworks.single().bssid
        )
    }

    @Test
    fun addsAndSwitchesFloorsWithoutLosingFirstFloorSurvey() {
        var timestamp = 1_000L
        val repository = ProjectRepository(
            context = context,
            now = { timestamp++ },
            newId = { "project-2" },
            projectsRoot = projectsDirectory
        )
        repository.createNewProject(
            ByteArrayInputStream(pngBytes(16, 12)),
            "ground.png",
            projectName = "Office",
            floorName = "Ground"
        )
        val first = repository.openProject("project-2")
        repository.saveProject(
            "project-2",
            first.activeFloorId,
            ProjectSnapshot(
                first.activeFloor.mapInfo,
                0.1,
                listOf(ScanPoint(1L, 2f, 3f, emptyList())),
                emptyList(),
                emptyList()
            )
        )

        val second = repository.addFloor(
            "project-2",
            ByteArrayInputStream(pngBytes(20, 10)),
            "first.png",
            "First"
        )

        assertEquals("floor-2", second.activeFloorId)
        assertEquals(listOf("Ground", "First"), second.floors.map { it.name })
        assertEquals(20, second.activeFloor.mapInfo.width)
        val reopenedFirst = repository.openProject("project-2", "floor-1")
        assertEquals(1, reopenedFirst.activeFloor.scanPoints.size)
        assertEquals(0.1, reopenedFirst.activeFloor.metersPerUnit ?: 0.0, 0.0)
        assertEquals(2, repository.snapshotProject("project-2").floors.size)
    }

    @Test
    fun migratesLegacySingleFloorManifestInPlace() {
        val projectDirectory = File(projectsDirectory, "legacy-1").apply { mkdirs() }
        val mapDirectory = File(projectDirectory, "map").apply { mkdirs() }
        File(mapDirectory, "floor-plan.png").writeBytes(pngBytes())
        File(projectDirectory, "survey.json").writeText("{}")
        File(projectDirectory, "manifest.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "legacy-1",
              "title": "Legacy",
              "floorName": "Old floor",
              "sourceType": "NEW",
              "mapFileName": "old.png",
              "mapRelativePath": "map/floor-plan.png",
              "mapWidth": 16,
              "mapHeight": 12,
              "createdAt": 1,
              "updatedAt": 2,
              "lastOpenedAt": 3
            }
            """.trimIndent()
        )
        val repository = ProjectRepository(
            context = context,
            newId = { "migration-temp" },
            projectsRoot = projectsDirectory
        )

        val opened = repository.openProject("legacy-1")

        assertEquals("Old floor", opened.activeFloor.name)
        assertEquals(3, File(projectDirectory, "manifest.json").readText()
            .substringAfter("\"schemaVersion\": ")
            .substringBefore(',')
            .trim()
            .toInt())
        assertTrue(File(projectDirectory, "map/floor-plan.png").isFile)
        assertTrue(File(projectDirectory, "survey.json").isFile)
    }

    @Test
    fun migratesSchemaTwoImportedProjectAsLegacyWithoutClaimingSourceArchive() {
        val projectDirectory = File(projectsDirectory, "schema-two").apply { mkdirs() }
        val floorDirectory = File(projectDirectory, "floors/floor-1").apply { mkdirs() }
        val mapDirectory = File(floorDirectory, "map").apply { mkdirs() }
        File(mapDirectory, "floor-plan.png").writeBytes(pngBytes())
        File(floorDirectory, "survey.json").writeText("{}")
        File(projectDirectory, "manifest.json").writeText(
            """
            {
              "schemaVersion": 2,
              "id": "schema-two",
              "title": "Legacy imported",
              "sourceType": "IMPORTED_ESX",
              "activeFloorId": "floor-1",
              "floors": [{
                "id": "floor-1",
                "name": "Old import",
                "mapFileName": "old.png",
                "mapRelativePath": "floors/floor-1/map/floor-plan.png",
                "surveyRelativePath": "floors/floor-1/survey.json",
                "mapWidth": 16,
                "mapHeight": 12
              }],
              "createdAt": 1,
              "updatedAt": 2,
              "lastOpenedAt": 3
            }
            """.trimIndent()
        )
        val repository = ProjectRepository(
            context = context,
            newId = { "migration-id" },
            projectsRoot = projectsDirectory
        )

        val opened = repository.openProject("schema-two")

        assertEquals("Old import", opened.activeFloor.name)
        assertEquals(null, repository.snapshotProject("schema-two").sourceArchive)
        assertTrue(File(projectDirectory, "manifest.json").readText().contains("\"schemaVersion\": 3"))
    }

    @Test
    fun repairsPrefixedSchemaThreeLocalFloorIdsAsStrictUuids() {
        val projectDirectory = File(projectsDirectory, "schema-three-invalid-ids").apply { mkdirs() }
        val floorDirectory = File(projectDirectory, "floors/floor-3").apply { mkdirs() }
        val mapDirectory = File(floorDirectory, "map").apply { mkdirs() }
        File(mapDirectory, "floor-plan.png").writeBytes(pngBytes())
        File(floorDirectory, "survey.json").writeText("{}")
        File(projectDirectory, "manifest.json").writeText(
            """
            {
              "schemaVersion": 3,
              "id": "schema-three-invalid-ids",
              "title": "Repair IDs",
              "sourceType": "IMPORTED_ESX",
              "baseEsxRelativePath": null,
              "activeFloorId": "floor-3",
              "floors": [{
                "id": "floor-3",
                "name": "Floor 5",
                "mapFileName": "floor.png",
                "mapRelativePath": "floors/floor-3/map/floor-plan.png",
                "surveyRelativePath": "floors/floor-3/survey.json",
                "mapWidth": 16,
                "mapHeight": 12,
                "esxFloorPlanId": "wiprober-floor-3-plan-old-id",
                "esxImageId": "wiprober-floor-3-image-old-id",
                "sourceFloorPlanId": null,
                "sourceMetersPerUnit": null
              }],
              "createdAt": 1,
              "updatedAt": 2,
              "lastOpenedAt": 3
            }
            """.trimIndent()
        )
        val repository = ProjectRepository(context, projectsRoot = projectsDirectory)

        val snapshot = repository.snapshotProject("schema-three-invalid-ids")

        val floor = snapshot.floors.single()
        assertTrue(EsxIdFactory.isUuid(floor.esxFloorPlanId))
        assertTrue(EsxIdFactory.isUuid(floor.esxImageId))
        assertTrue(!File(projectDirectory, "manifest.json").readText().contains("wiprober-floor-3"))
    }

    @Test
    fun catalogReportsUnavailableWorkspacesAndDeleteRemovesOnlySelectedProject() {
        val repository = ProjectRepository(
            context = context,
            newId = { "project-delete" },
            projectsRoot = projectsDirectory
        )
        repository.createNewProject(
            ByteArrayInputStream(pngBytes()),
            "delete.png",
            projectName = "Delete me",
            floorName = "Floor"
        )
        File(projectsDirectory, "broken-project").apply { mkdirs() }

        val beforeDelete = repository.listRecentProjectCatalog()

        assertEquals(listOf("project-delete"), beforeDelete.projects.map(ProjectSummary::id))
        assertEquals(1, beforeDelete.unavailableProjectCount)

        repository.deleteProject("project-delete")

        val afterDelete = repository.listRecentProjectCatalog()
        assertTrue(afterDelete.projects.isEmpty())
        assertEquals(1, afterDelete.unavailableProjectCount)
        assertTrue(!File(projectsDirectory, "project-delete").exists())
        assertTrue(File(projectsDirectory, "broken-project").isDirectory)
    }

    @Test
    fun removingLocalNotePrunesItsPrivateMediaCopyButKeepsOtherProjectFiles() {
        val repository = ProjectRepository(
            context = context,
            newId = { "project-media" },
            projectsRoot = projectsDirectory
        )
        repository.createNewProject(
            ByteArrayInputStream(pngBytes()),
            "media.png",
            projectName = "Media",
            floorName = "Floor"
        )
        val project = repository.openProject("project-media")
        val sourcePhoto = File(context.cacheDir, "project-repository-note.png").apply {
            writeBytes(pngBytes(4, 3))
        }
        val untouchedArchive = File(projectsDirectory, "project-media/base.esx").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val note = AppNote(
            id = "note-id",
            text = "Fixture",
            photoUri = Uri.fromFile(sourcePhoto).toString(),
            photoWidth = 4,
            photoHeight = 3,
            photoId = "photo-id",
            x = 2f,
            y = 3f,
            pictureNoteId = "picture-note-id"
        )
        val snapshot = ProjectSnapshot(
            mapInfo = project.activeFloor.mapInfo,
            metersPerUnit = null,
            scanPoints = emptyList(),
            continuousScanSessions = emptyList(),
            notes = listOf(note)
        )

        repository.saveProject("project-media", snapshot)

        val copiedPhoto = File(
            projectsDirectory,
            "project-media/floors/floor-1/media/image-photo-id"
        )
        assertTrue(copiedPhoto.isFile)
        assertEquals(sourcePhoto.readBytes().toList(), copiedPhoto.readBytes().toList())

        repository.saveProject("project-media", snapshot.copy(notes = emptyList()))

        assertTrue(!copiedPhoto.exists())
        assertEquals(listOf<Byte>(1, 2, 3, 4), untouchedArchive.readBytes().toList())
        assertTrue(sourcePhoto.isFile)
        sourcePhoto.delete()
    }

    /** Mejora 5: a clean install restores the copy with the same IDs, coordinates, files and values. */
    @Test
    fun projectCopyRestoresOnACleanInstallAndDuplicatesNeedADecision() {
        val ids = ArrayDeque(listOf("project-copy", "project-copy-2"))
        val phoneA = ProjectRepository(context = context, newId = { ids.removeFirstOrNull() ?: java.util.UUID.randomUUID().toString() },
            projectsRoot = File(projectsDirectory, "a"))
        phoneA.createNewProject(ByteArrayInputStream(pngBytes(16, 12)), "ground.png", projectName = "Almacén", floorName = "Baja")
        val ground = phoneA.openProject("project-copy")
        val photo = File(context.cacheDir, "backup-note.png").apply { writeBytes(pngBytes(4, 3)) }
        val net = WifiNetworkInfo("Ops", "aa:01", -60, 5_180, "WPA2", emptyList(), "")
        val route = ContinuousScanSession("route-1", 1_000, 5_000,
            listOf(RoutePointWrapper(0, 1f, 1f), RoutePointWrapper(4_000, 9f, 1f)), listOf(ScanResultWrapper(1_000, 500, listOf(net))))
        phoneA.saveProject("project-copy", ground.activeFloorId, ProjectSnapshot(
            ground.activeFloor.mapInfo, 0.1,
            listOf(ScanPoint(1L, 2.5f, 3.25f, listOf(net), pointId = "p1", scanId = "s1"), ScanPoint(2L, 2.5f, 3.25f, listOf(net), pointId = "p1", scanId = "s2")),
            listOf(route),
            listOf(AppNote(id = "n1", text = "Muelle", photoUri = Uri.fromFile(photo).toString(), photoWidth = 4, photoHeight = 3,
                photoId = "ph1", x = 5f, y = 6f, pictureNoteId = "pn1")),
            activeRuns = listOf(ActiveRun("r1", "p1", null, 10L, 20L, RunStatus.COMPLETED))
        ))
        phoneA.addFloor("project-copy", ByteArrayInputStream(pngBytes(20, 10)), "first.png", "Primera")
        val copy = File(context.cacheDir, "project-copy.zip")
        copy.outputStream().use { phoneA.backupProject("project-copy", "test", it) }

        val phoneB = ProjectRepository(context = context, newId = { "project-new" }, projectsRoot = File(projectsDirectory, "b"))
        val restored = phoneB.restoreProject(copy)
        assertEquals("project-copy", restored.id)
        assertEquals(2, restored.floorCount)
        val floor = phoneB.openProject("project-copy", ground.activeFloorId).activeFloor
        assertEquals(listOf("s1", "s2"), floor.scanPoints.map { it.scanId })
        assertEquals(3.25f, floor.scanPoints.first().y, 0f)
        assertEquals(0.1, floor.metersPerUnit ?: 0.0, 0.0)
        assertEquals("route-1", floor.continuousScanSessions.single().id)
        assertEquals(null, floor.activeRuns.single().scanId)
        val restoredPhoto = File(Uri.parse(floor.notes.single().photoUri).path!!)
        assertEquals(photo.readBytes().toList(), restoredPhoto.readBytes().toList())

        // Restoring the same copy again: nothing is overwritten without a decision.
        try {
            phoneB.restoreProject(copy)
            throw AssertionError("expected a conflict")
        } catch (_: ProjectBackup.ProjectExistsException) {
        }
        assertEquals("project-new", phoneB.restoreProject(copy, ProjectBackup.Conflict.AS_COPY).id)
        assertEquals(2, phoneB.listRecentProjects().size)
        copy.delete()
        photo.delete()
    }

    /** rc.2 · 1: killed after parking the original, reopening finishes installing the validated copy. */
    @Test
    fun replaceRestoreKilledMidwayRecoversOnReopen() {
        class Killed : Error()
        val root = File(projectsDirectory, "replace")
        val repository = ProjectRepository(context = context, newId = { "project-r" }, projectsRoot = root)
        repository.createNewProject(ByteArrayInputStream(pngBytes()), "ground.png", projectName = "Almacén", floorName = "Baja")
        val floor = repository.openProject("project-r")
        repository.saveProject("project-r", floor.activeFloorId, ProjectSnapshot(floor.activeFloor.mapInfo, 0.1,
            listOf(ScanPoint(1L, 2f, 3f, emptyList(), pointId = "copy", scanId = "s1")), emptyList(), emptyList()))
        val copy = File(context.cacheDir, "replace-copy.zip")
        copy.outputStream().use { repository.backupProject("project-r", "test", it) }
        repository.saveProject("project-r", floor.activeFloorId, ProjectSnapshot(floor.activeFloor.mapInfo, 0.1,
            listOf(ScanPoint(1L, 2f, 3f, emptyList(), pointId = "original", scanId = "s2")), emptyList(), emptyList()))

        val dying = ProjectRepository(context = context, projectsRoot = root,
            restoreCheckpoint = { phase -> if (phase == ProjectBackup.Phase.PARKING) throw Killed() })
        try {
            dying.restoreProject(copy, ProjectBackup.Conflict.REPLACE)
            throw AssertionError("expected the simulated kill")
        } catch (_: Killed) {
        }
        val reopened = ProjectRepository(context = context, projectsRoot = root)
        assertEquals(1, reopened.listRecentProjects().size)
        val restored = reopened.openProject("project-r").activeFloor
        assertTrue(restored.mapFile.isFile)
        assertEquals(listOf("copy"), restored.scanPoints.map { it.pointId })
        copy.delete()
    }

    private fun pngBytes(width: Int = 16, height: Int = 12): ByteArray {
        val output = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }
}
