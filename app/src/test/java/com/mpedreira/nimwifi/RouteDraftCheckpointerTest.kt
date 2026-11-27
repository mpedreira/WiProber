package com.mpedreira.nimwifi

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rc.2 · 2: samples without pauses never postpone the route draft. Time is compressed: samples
 * every 20 ms against a 200 ms interval stand for 200 ms against the app's 5 s.
 */
class RouteDraftCheckpointerTest {
    private val dir = Files.createTempDirectory("drafts").toFile()
    private val store = RouteDraftStore(dir)
    private val interval = 200L

    private fun draft(session: String, points: Int) = RouteDraft(session, "floor-1", 1_000L,
        listOf(RouteSegment(1_000L, waypoints = (0 until points).map { RoutePointWrapper(it * 20L, it.toFloat(), 0f) },
            lastObservedMs = points * 20L)))

    private fun awaitWrites(checkpoints: RouteDraftCheckpointer, key: SaveKey, writes: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (checkpoints.writes(key) < writes && System.nanoTime() < deadline) Thread.sleep(5)
    }

    @Test
    fun continuousSamplesStillProduceRegularWritesAndARecoverableDraft() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checkpoints = RouteDraftCheckpointer(scope, Dispatchers.IO, interval)
        val key = SaveKey("p", "floor-1", "route-1")
        val samples = AtomicInteger()
        val started = System.nanoTime()
        repeat(60) { // 1.2 s of samples, never a pause longer than 20 ms
            samples.incrementAndGet()
            checkpoints.changed(key, build = { draft("route-1", samples.get()) }) { store.save(it) }
            if (it == 0) {
                // The first draft is written at once, well before one interval.
                val deadline = System.nanoTime() + (interval - 50) * 1_000_000
                while (checkpoints.writes(key) == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals("first draft when the route starts", 1, checkpoints.writes(key))
            }
            Thread.sleep(20)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val writes = checkpoints.writes(key)
        assertTrue("several writes while samples keep coming: $writes in $elapsed ms", writes >= elapsed / interval - 1 && writes >= 4)
        // Process death: nothing is closed or flushed; the last draft on disk is what reopens.
        scope.cancel()
        val reopened = RouteDraftStore(dir).list().single()
        val kept = reopened.segments.single().waypoints.size
        val lostMillis = (samples.get() - kept) * 20L
        assertTrue("at most one interval (plus one write) of the recent stretch is lost: $lostMillis ms", lostMillis <= interval * 2)
        val route = RouteDrafts.finalize(reopened, recovered = true)
        assertEquals(kept, route.waypoints.size)
    }

    @Test
    fun aNormalStopWithAPendingSaveWritesNothingAfterwards() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checkpoints = RouteDraftCheckpointer(scope, Dispatchers.IO, interval)
        val key = SaveKey("p", "floor-1", "route-2")
        checkpoints.changed(key, build = { draft("route-2", 1) }) { store.save(it) }
        awaitWrites(checkpoints, key, 1)
        assertEquals(1, checkpoints.writes(key))
        // A change is pending (its interval has not elapsed) when the route is saved definitively.
        checkpoints.changed(key, build = { draft("route-2", 2) }) { store.save(it) }
        checkpoints.close(key)
        store.delete("route-2") // what the floor save does once the route is in the survey
        checkpoints.changed(key, build = { draft("route-2", 3) }) { store.save(it) } // a straggler sample
        Thread.sleep(interval * 2)
        assertEquals(1, checkpoints.writes(key))
        assertTrue("no draft comes back", store.list().none { it.sessionId == "route-2" })
        scope.cancel()
    }

    @Test
    fun discardingWaitsForAnInFlightWriteAndThenDeletesTheDraft() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checkpoints = RouteDraftCheckpointer(scope, Dispatchers.IO, interval)
        val key = SaveKey("p", "floor-1", "route-3")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        checkpoints.changed(key, build = { draft("route-3", 5) }) { d -> writing.countDown(); release.await(5, TimeUnit.SECONDS); store.save(d) }
        assertTrue(writing.await(2, TimeUnit.SECONDS))
        val closing = checkpoints.closeLater(key) { store.delete("route-3") }
        Thread.sleep(50)
        assertFalse("close waits for the write in progress", closing.isCompleted)
        release.countDown()
        closing.join()
        assertTrue("the late write did not survive the discard", store.list().isEmpty())
        scope.cancel()
    }

    @Test
    fun sessionsOfDifferentFloorsAreIndependent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checkpoints = RouteDraftCheckpointer(scope, Dispatchers.IO, interval)
        val a = SaveKey("p", "floor-1", "route-a")
        val b = SaveKey("p", "floor-2", "route-b")
        checkpoints.changed(a, build = { draft("route-a", 1) }) { store.save(it) }
        checkpoints.changed(b, build = { draft("route-b", 1) }) { store.save(it) }
        awaitWrites(checkpoints, a, 1)
        awaitWrites(checkpoints, b, 1)
        checkpoints.close(a)
        Thread.sleep(100)
        checkpoints.changed(b, build = { draft("route-b", 2) }) { store.save(it) }
        awaitWrites(checkpoints, b, 2)
        assertEquals(2, checkpoints.writes(b))
        assertEquals("closing A did not touch B", 1, checkpoints.writes(a))
        assertEquals(setOf("route-a", "route-b"), store.list().map { it.sessionId }.toSet())
        scope.cancel()
    }
}
