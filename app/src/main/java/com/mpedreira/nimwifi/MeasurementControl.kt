package com.mpedreira.nimwifi

import java.io.Closeable
import java.net.HttpURLConnection
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Thrown inside a run once it was cancelled; it is a [CancellationException] so it propagates. */
class MeasurementCancelled(val reason: String) : CancellationException(reason)

/**
 * Cancellation and monotonic deadlines of one active run. Blocking I/O cannot be interrupted by
 * checking a clock around it, so every socket, HTTP connection and process of the run is
 * [track]ed: cancelling the run or reaching a deadline closes them, which makes the blocked call
 * fail at once. A phase deadline only aborts that phase ([ErrorCodes.TIMEOUT]); the point
 * deadline, or a user cancel, aborts the whole run.
 */
class MeasurementControl(private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile var cancelReason: String? = null
        private set
    val isCancelled: Boolean get() = cancelReason != null

    private val resources = Collections.synchronizedSet(LinkedHashSet<Any>())
    @Volatile private var phaseTimedOut = false
    private var phaseTimer: ScheduledFuture<*>? = null
    private var runTimer: ScheduledFuture<*>? = null

    /** Registers a socket/connection/process so cancel and deadlines can close it. */
    fun <T : Any> track(resource: T): T {
        if (isCancelled) {
            closeQuietly(resource)
            throw MeasurementCancelled(cancelReason!!)
        }
        resources += resource
        return resource
    }

    fun untrack(resource: Any) {
        resources -= resource
    }

    /** Use a tracked [Closeable] and stop tracking it afterwards. */
    inline fun <T : Closeable, R> using(resource: T, block: (T) -> R): R =
        track(resource).use { try { block(it) } finally { untrack(it) } }

    fun cancel(reason: String = ErrorCodes.CANCELLED) {
        if (cancelReason == null) cancelReason = reason
        closeAll()
        phaseTimer?.cancel(false)
        runTimer?.cancel(false)
    }

    /** Throws [MeasurementCancelled] if the run was cancelled. */
    fun checkActive() {
        cancelReason?.let { throw MeasurementCancelled(it) }
    }

    /** Whole-run deadline: when it expires the run is cancelled with [ErrorCodes.TIMEOUT]. */
    fun startRunDeadline(millis: Long) {
        runTimer?.cancel(false)
        runTimer = scheduler.schedule({ cancel(ErrorCodes.TIMEOUT) }, millis, TimeUnit.MILLISECONDS)
    }

    fun clearRunDeadline() {
        runTimer?.cancel(false)
    }

    /**
     * Runs one phase with its own monotonic deadline. On expiry the phase's resources are closed
     * and the phase fails with [ErrorCodes.TIMEOUT]; the run continues with the next phase.
     */
    fun <T> phase(deadlineMillis: Long, block: () -> T): T {
        checkActive()
        phaseTimedOut = false
        val start = nanoTime()
        phaseTimer = scheduler.schedule({ phaseTimedOut = true; closeAll() }, deadlineMillis, TimeUnit.MILLISECONDS)
        try {
            return block()
        } catch (e: Exception) {
            checkActive()
            if (phaseTimedOut || (nanoTime() - start) / 1_000_000 >= deadlineMillis) {
                throw MeasurementException(ErrorCodes.TIMEOUT, "phase deadline of $deadlineMillis ms reached", e)
            }
            throw e
        } finally {
            phaseTimer?.cancel(false)
            phaseTimer = null
            closeAll()
        }
    }

    private fun closeAll() {
        val snapshot = synchronized(resources) { resources.toList().also { resources.clear() } }
        snapshot.forEach(::closeQuietly)
    }

    private fun closeQuietly(resource: Any) {
        runCatching {
            when (resource) {
                is Closeable -> resource.close()
                is HttpURLConnection -> resource.disconnect()
                is Process -> resource.destroyForcibly()
                is Thread -> resource.interrupt()
                is AutoCloseable -> resource.close()
            }
        }
    }

    companion object {
        /** No cancellation, no deadlines: for callers that do not need them. */
        val NONE: MeasurementControl get() = MeasurementControl()

        private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "measurement-deadlines").apply { isDaemon = true }
        }
    }
}

/**
 * Runs blocking measurement code on its own thread and suspends until it ends. Cancelling the
 * coroutine cancels [control] at once (closing every tracked socket/process) and resumes with a
 * [CancellationException]; late results of the worker thread are discarded.
 */
suspend fun <T> runCancellable(control: MeasurementControl, name: String, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val worker = Thread({
            val outcome = runCatching(block)
            if (continuation.isActive) {
                outcome.fold(continuation::resume, continuation::resumeWithException)
            }
        }, name).apply { isDaemon = true }
        continuation.invokeOnCancellation {
            control.cancel(ErrorCodes.CANCELLED)
            worker.interrupt()
        }
        worker.start()
    }
