package com.mpedreira.nimwifi

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MeasurementControlTest {
    /** A connected socket whose read blocks forever (the server never writes). */
    private fun blockedRead(control: MeasurementControl, server: ServerSocket): () -> Unit = {
        val socket = control.track(Socket())
        socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 1_000)
        socket.getInputStream().read()
    }

    @Test
    fun cancellingClosesABlockedSocketAtOnce() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { runCatching { server.accept() } }
            val control = MeasurementControl()
            thread(isDaemon = true) { Thread.sleep(200); control.cancel() }
            val started = System.nanoTime()
            try {
                control.phase(60_000) { blockedRead(control, server)() }
                fail("expected cancellation")
            } catch (expected: MeasurementCancelled) {
                assertEquals(ErrorCodes.CANCELLED, expected.reason)
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }
    }

    @Test
    fun aPhaseDeadlineClosesTheSocketAndFailsOnlyThatPhase() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { runCatching { server.accept(); server.accept() } }
            val control = MeasurementControl()
            try {
                control.phase(300) { blockedRead(control, server)() }
                fail("expected timeout")
            } catch (expected: MeasurementException) {
                assertEquals(ErrorCodes.TIMEOUT, expected.code)
            }
            // The run itself is still usable for the next phase.
            assertTrue(!control.isCancelled)
            assertEquals(7, control.phase(1_000) { 7 })
        }
    }

    @Test
    fun cancellingTheCoroutineCancelsTheControlAndDiscardsTheLateResult() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { runCatching { server.accept() } }
            val control = MeasurementControl()
            val deferred = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                runCancellable(control, "test") { blockedRead(control, server)(); "late" }
            }
            Thread.sleep(200)
            deferred.cancel()
            try {
                deferred.await()
                fail("expected cancellation")
            } catch (expected: CancellationException) {
                assertTrue(control.isCancelled)
            }
        }
    }

    @Test
    fun aRunDeadlineCancelsTheRunWithTimeout() {
        val control = MeasurementControl()
        control.startRunDeadline(100)
        Thread.sleep(400)
        assertEquals(ErrorCodes.TIMEOUT, control.cancelReason)
        try {
            control.checkActive()
            fail("expected cancellation")
        } catch (expected: MeasurementCancelled) {
            assertEquals(ErrorCodes.TIMEOUT, expected.reason)
        }
    }
}
