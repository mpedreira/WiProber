package com.mpedreira.nimwifi

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mejora 15: autosave keyed by project and floor, with revisions. */
class RevisionedAutosaveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val floorA = SaveKey("p", "A")
    private val floorB = SaveKey("p", "B")
    /** What is "on disk" per floor. */
    private val disk = ConcurrentHashMap<SaveKey, String>()
    private val writes = Collections.synchronizedList(mutableListOf<String>())

    private fun store(key: SaveKey, value: String): () -> Unit = { disk[key] = value; writes += "$key=$value" }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out; writes=$writes failures=" }
            Thread.sleep(5)
        }
    }

    @Test
    fun twoFloorsSavingAlmostTogetherBothKeepTheirLatestRevision() {
        val saver = RevisionedAutosave(scope)
        saver.schedule(floorA, 20, store(floorA, "A1"))
        saver.schedule(floorB, 20, store(floorB, "B1")) // must not cancel A's pending save
        saver.schedule(floorA, 20, store(floorA, "A2")) // replaces only A1
        awaitUntil { disk[floorA] == "A2" && disk[floorB] == "B1" }
        Thread.sleep(80)
        assertEquals("A2", disk[floorA])
        assertEquals("B1", disk[floorB])
        assertTrue("A1 never written: $writes", writes.none { it.endsWith("=A1") })
    }

    @Test
    fun anOldWriteThatFinishesLateNeverReplacesANewerRevision() = runBlocking {
        val saver = RevisionedAutosave(scope)
        val slowStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Revision 1 is slow; revision 2 (saveNow, e.g. before an export) waits for it and wins.
        saver.schedule(floorA, 0) { slowStarted.countDown(); release.await(5, TimeUnit.SECONDS); disk[floorA] = "old" }
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
        val newer = async(Dispatchers.IO) { saver.saveNow(floorA, store(floorA, "new")) }
        Thread.sleep(50)
        release.countDown()
        newer.await()
        assertEquals("new", disk[floorA])
        assertEquals(2L, saver.writtenRevision(floorA))

        // A revision older than the one on disk is dropped even if it gets to run.
        saver.schedule(floorB, 30, store(floorB, "B-old"))
        saver.saveNow(floorB, store(floorB, "B-new"))
        Thread.sleep(80)
        assertEquals("B-new", disk[floorB])
    }

    @Test
    fun aPersistentErrorOnOneFloorIsNotHiddenByAnotherFloorSaving() {
        val saver = RevisionedAutosave(scope, retryDelaysMillis = longArrayOf(5, 5))
        var attempts = 0
        saver.schedule(floorA, 0) { attempts++; throw java.io.IOException("disk full") }
        awaitUntil { floorA in saver.failures.value }
        assertEquals(3, attempts) // first try + two retries
        saver.schedule(floorB, 0, store(floorB, "B1"))
        awaitUntil { disk[floorB] == "B1" }
        Thread.sleep(20)
        assertEquals(mapOf(floorA to "disk full"), saver.failures.value)
        // Once floor A saves, its error goes away.
        saver.schedule(floorA, 0, store(floorA, "A1"))
        awaitUntil { saver.failures.value.isEmpty() }
    }

    @Test
    fun anExportSavesItsRevisionAfterEveryPendingSaveOfTheProject() = runBlocking {
        val saver = RevisionedAutosave(scope)
        saver.schedule(floorB, 10_000, store(floorB, "B-pending")) // would run long after the export
        saver.saveNow(floorA, store(floorA, "A-export"))
        saver.cancelPending("p")
        // The export reads what is on disk now: floor A's revision, and floor B's pending save no longer runs.
        assertEquals(mapOf(floorA to "A-export"), disk.toMap())
    }
}
