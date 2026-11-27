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

/** A phase whose block returned after its deadline: the value is discarded. */
class LateResult(deadlineMillis: Long) : MeasurementException(ErrorCodes.TIMEOUT, "phase deadline of $deadlineMillis ms reached; late result discarded")

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
    @Volatile private var runDeadlineAt: Long? = null

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
        runDeadlineAt = nanoTime() + millis * 1_000_000L
        runTimer = scheduler.schedule({ cancel(ErrorCodes.TIMEOUT) }, millis, TimeUnit.MILLISECONDS)
    }

    fun clearRunDeadline() {
        runTimer?.cancel(false)
        runDeadlineAt = null
    }

    /** Monotonic milliseconds left of the point's budget; null without one. */
    fun remainingRunMillis(): Long? = runDeadlineAt?.let { ((it - nanoTime()) / 1_000_000L).coerceAtLeast(0L) }

    /**
     * Mejora 7: a phase that cannot fit in what is left of the point's budget is not started
     * (it would only be killed by the point deadline): [ErrorCodes.BUDGET_EXHAUSTED].
     */
    fun checkBudget(phaseMillis: Long) {
        val left = remainingRunMillis() ?: return
        if (left < minOf(phaseMillis, MIN_PHASE_MILLIS)) {
            throw MeasurementException(ErrorCodes.BUDGET_EXHAUSTED, "$left ms left of the point budget, phase needs up to $phaseMillis ms")
        }
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
            val value = block()
            // A value that arrives after the deadline is discarded: the phase timed out even if
            // the block never threw (mejora 7).
            if (phaseTimedOut || (nanoTime() - start) / 1_000_000 >= deadlineMillis) {
                checkActive()
                throw LateResult(deadlineMillis)
            }
            return value
        } catch (e: LateResult) {
            throw e
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

        /** Shortest useful phase: below this a phase is never started on what is left of the budget. */
        const val MIN_PHASE_MILLIS = 3_000L

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


/**
 * Mejora 7: blocking calls that cannot be interrupted (name lookups) run on a small pool with a
 * wait limit and a cap on the calls still pending: a slow DNS never holds a phase longer than
 * [timeoutMillis] and never piles up threads.
 */
class BoundedCalls(private val timeoutMillis: Long, maxPending: Int = 2, private val name: String = "bounded") {
    private val pending = java.util.concurrent.Semaphore(maxPending)
    private val executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "$name-call").apply { isDaemon = true } }

    fun <T> call(what: String, block: () -> T): T {
        if (!pending.tryAcquire()) throw MeasurementException(ErrorCodes.TIMEOUT, "$what: too many calls still pending")
        val future = try {
            executor.submit<T> { try { block() } finally { pending.release() } }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pending.release()
            throw MeasurementException(ErrorCodes.IO_ERROR, "$what: rejected", e)
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            // A blocked lookup cannot be interrupted: it keeps its slot until it really ends, so
            // the pending cap holds. Its late answer is discarded.
            future.cancel(false)
            throw MeasurementException(ErrorCodes.TIMEOUT, "$what did not answer in $timeoutMillis ms")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }
}

/** [delegate] with name lookups bounded by [calls] (the binding to the Wi-Fi network is kept). */
class BoundedResolveNetwork(private val delegate: MeasurementNetwork, private val calls: BoundedCalls) : MeasurementNetwork by delegate {
    override fun resolve(host: String): List<java.net.InetAddress> = calls.call("DNS lookup of the target") { delegate.resolve(host) }
}
