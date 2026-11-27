package com.mpedreira.nimwifi

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * rc.2 · 2: periodic checkpoints of open routes, one per [SaveKey] (project, floor, session).
 *
 * A debounce postponed the write while samples kept coming (a roaming attempt every 200 ms
 * re-armed it for ever). This is a throttle instead: the first change of a session is written at
 * once, and later changes at most every [intervalMillis] after the previous write started. A new
 * sample only replaces *what* the next write takes, never *when* it runs. So, while a route is
 * open, at most [intervalMillis] of the most recent stretch (plus the duration of one write) can
 * be missing from the last draft on disk.
 *
 * The draft is built by `build` on [scope]'s dispatcher (the main thread in the app, where the
 * route is mutated) and written by `write` on [io]. [close] ends a session for good (saved as a
 * route or discarded): its timer is cancelled, an in-flight write is awaited, and no later write
 * runs for it.
 */
class RouteDraftCheckpointer(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val intervalMillis: Long = INTERVAL_MILLIS,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onError: (SaveKey, Throwable) -> Unit = { _, _ -> }
) {
    private class Session {
        val mutex = Mutex()
        var build: (() -> RouteDraft?)? = null
        var write: ((RouteDraft) -> Unit)? = null
        var timer: Job? = null
        var lastWriteAt: Long? = null
        @Volatile var closed = false
        @Volatile var writes = 0
    }

    private val sessions = ConcurrentHashMap<SaveKey, Session>()

    /** Number of drafts written for [key] (diagnostics and tests). */
    fun writes(key: SaveKey): Int = sessions[key]?.writes ?: 0

    /** The route of [key] changed: [build] gives its current draft when the next write runs. */
    fun changed(key: SaveKey, build: () -> RouteDraft?, write: (RouteDraft) -> Unit) {
        val session = sessions.getOrPut(key) { Session() }
        synchronized(session) {
            if (session.closed) return
            session.build = build
            session.write = write
            if (session.timer?.isActive == true) return
            val wait = session.lastWriteAt?.let { (it + intervalMillis - nowMillis()).coerceAtLeast(0L) } ?: 0L
            session.timer = scope.launch {
                if (wait > 0) delay(wait)
                flush(key, session)
            }
        }
    }

    private suspend fun flush(key: SaveKey, session: Session) {
        val (build, write) = synchronized(session) {
            if (session.closed) return
            val taken = session.build to session.write
            session.build = null
            session.lastWriteAt = nowMillis()
            taken
        }
        val draft = build?.invoke() ?: return
        session.mutex.withLock {
            if (session.closed) return
            val error = runCatching { withContext(io) { write?.invoke(draft) } }.exceptionOrNull()
            if (error == null) session.writes++ else if (error !is kotlinx.coroutines.CancellationException) onError(key, error)
        }
        // Changes that arrived during the write are taken by the next interval.
        synchronized(session) {
            session.timer = null
            if (!session.closed && session.build != null) {
                val wait = (session.lastWriteAt!! + intervalMillis - nowMillis()).coerceAtLeast(0L)
                session.timer = scope.launch {
                    if (wait > 0) delay(wait)
                    flush(key, session)
                }
            }
        }
    }

    /**
     * Ends [key] for good: no write runs for it after this returns. An in-flight write is awaited
     * first, then [afterClose] runs (e.g. deleting the draft of a discarded route).
     */
    suspend fun close(key: SaveKey, afterClose: (() -> Unit)? = null) {
        val session = sessions.getOrPut(key) { Session() }
        val timer = synchronized(session) {
            session.closed = true
            session.build = null
            session.timer.also { session.timer = null }
        }
        session.mutex.withLock {
            timer?.cancel()
            afterClose?.let { withContext(io) { it() } }
        }
    }

    /** [close] in this checkpointer's own scope, so it completes even if the caller goes away. */
    fun closeLater(key: SaveKey, afterClose: (() -> Unit)? = null): Job = scope.launch { close(key, afterClose) }

    companion object {
        /** Longest stretch of an open route that can be missing from the last draft on disk. */
        const val INTERVAL_MILLIS = 5_000L
    }
}
