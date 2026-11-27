package com.mpedreira.nimwifi

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a save writes: one floor of one project (or one of its route drafts, see [draft]). */
data class SaveKey(val projectId: String, val floorId: String, val draft: String? = null) {
    override fun toString(): String = listOfNotNull(projectId, floorId, draft).joinToString("/")
}

/**
 * Saves keyed by [SaveKey], each with a revision taken when the save is requested:
 *
 * - writes for the same key run one at a time, and a revision older than the last one written
 *   is dropped, so an old write that finishes late never replaces a newer one;
 * - scheduling a save for one key never cancels another key's pending save;
 * - failures and retries belong to their key: a successful save of one floor does not hide
 *   the error of another.
 *
 * Saves run in [scope], not in an Activity, so they also complete with the screen in the
 * background. Android-free: [ProjectAutosave] is the process-wide instance.
 */
class RevisionedAutosave(
    private val scope: CoroutineScope,
    private val retryDelaysMillis: LongArray = longArrayOf(2_000L, 5_000L, 10_000L),
    private val onError: (SaveKey, Throwable) -> Unit = { _, _ -> }
) {
    private class Slot {
        val mutex = Mutex()
        @Volatile var lastRequested = 0L
        @Volatile var lastWritten = 0L
        var pending: Job? = null
    }

    private val slots = ConcurrentHashMap<SaveKey, Slot>()
    private val _failures = MutableStateFlow<Map<SaveKey, String>>(emptyMap())

    /** Keys whose last save failed even after the retries, with the reason. */
    val failures: StateFlow<Map<SaveKey, String>> = _failures.asStateFlow()

    private fun slot(key: SaveKey) = slots.getOrPut(key) { Slot() }

    @Synchronized
    private fun nextRevision(slot: Slot): Long = ++slot.lastRequested

    /** Revision of the last successful write of [key] (0 = none). */
    fun writtenRevision(key: SaveKey): Long = slots[key]?.lastWritten ?: 0L

    /** Replaces the pending save of [key] (only that key) with [save], written after [delayMillis]. */
    fun schedule(key: SaveKey, delayMillis: Long, save: () -> Unit): Job {
        val slot = slot(key)
        val revision = nextRevision(slot)
        synchronized(slot) {
            slot.pending?.cancel()
            return scope.launch {
                if (delayMillis > 0) delay(delayMillis)
                var attempt = 0
                while (true) {
                    val error = runCatching { write(key, slot, revision, save) }.exceptionOrNull() ?: return@launch
                    if (error is CancellationException) throw error
                    if (attempt >= retryDelaysMillis.size) {
                        recordFailure(key, error)
                        return@launch
                    }
                    delay(retryDelaysMillis[attempt++])
                }
            }.also { slot.pending = it }
        }
    }

    /**
     * Writes [save] now as the newest revision of [key] (before an export or a floor switch):
     * the pending save of that key is cancelled and awaited first. Throws if the write fails.
     */
    suspend fun saveNow(key: SaveKey, save: () -> Unit) {
        val slot = slot(key)
        val revision = nextRevision(slot)
        val pending = synchronized(slot) { slot.pending.also { slot.pending = null } }
        pending?.cancel()
        pending?.join()
        try {
            write(key, slot, revision, save)
        } catch (e: Exception) {
            if (e !is CancellationException) recordFailure(key, e)
            throw e
        }
    }

    /** Cancels and awaits the pending saves of [projectId] (all floors). */
    suspend fun cancelPending(projectId: String) {
        slots.filterKeys { it.projectId == projectId }.values.forEach { slot ->
            val job = synchronized(slot) { slot.pending }
            job?.cancel()
            job?.join()
        }
    }

    private suspend fun write(key: SaveKey, slot: Slot, revision: Long, save: () -> Unit) {
        slot.mutex.withLock {
            if (revision <= slot.lastWritten) return // a newer revision is already on disk
            save()
            slot.lastWritten = revision
        }
        _failures.update { it - key }
    }

    private fun recordFailure(key: SaveKey, error: Throwable) {
        onError(key, error)
        _failures.update { it + (key to (error.message ?: error.javaClass.simpleName)) }
    }
}

/** The process-wide autosave used by the survey screen. */
object ProjectAutosave {
    val saver = RevisionedAutosave(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { key, error ->
        android.util.Log.e("ProjectStorage", "Cannot save $key", error)
    }

    fun schedule(key: SaveKey, delayMillis: Long, save: () -> Unit): Job = saver.schedule(key, delayMillis, save)

    suspend fun saveNow(key: SaveKey, save: () -> Unit) = saver.saveNow(key, save)

    val failures: StateFlow<Map<SaveKey, String>> get() = saver.failures
}
