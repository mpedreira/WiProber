package com.mpedreira.nimwifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide project autosave. Saves run in their own scope, not the Activity's, so a save
 * started while the survey screen is in the background (a long run or track with the screen off)
 * still completes. A failed save is retried; if it keeps failing, [failure] tells the screen so
 * the user is warned instead of losing data silently.
 */
object ProjectAutosave {
    /** Retries after a failed save, with these delays. */
    private val RETRY_DELAYS_MILLIS = longArrayOf(2_000L, 5_000L, 10_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private val _failure = MutableStateFlow<String?>(null)

    /** Reason of the last save that failed even after the retries; null once a save succeeds. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /** Replaces any pending save with this snapshot, saved after [delayMillis]. */
    @Synchronized
    fun schedule(delayMillis: Long, save: () -> Unit): Job {
        pending?.cancel()
        return scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            var attempt = 0
            while (true) {
                val result = runCatching(save)
                val error = result.exceptionOrNull()
                if (error == null) {
                    _failure.value = null
                    return@launch
                }
                if (error is CancellationException) throw error
                if (attempt >= RETRY_DELAYS_MILLIS.size) {
                    _failure.value = error.message ?: error.javaClass.simpleName
                    android.util.Log.e("ProjectStorage", "Cannot save project", error)
                    return@launch
                }
                delay(RETRY_DELAYS_MILLIS[attempt++])
            }
        }.also { pending = it }
    }
}
