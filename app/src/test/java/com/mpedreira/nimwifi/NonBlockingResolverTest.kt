package com.mpedreira.nimwifi

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A slow or failing DNS never holds the roaming probe's cadence. */
class NonBlockingResolverTest {
    private val address = InetAddress.getByName("192.0.2.1")

    @Test
    fun aSlowLookupReturnsAtOnceAndIsUsedWhenItArrives() {
        val release = CountDownLatch(1)
        NonBlockingResolver(waitMillis = 50).use { resolver ->
            val started = System.nanoTime()
            assertNull(resolver.lookup("k", 0) { release.await(5, TimeUnit.SECONDS); address })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            release.countDown()
            Thread.sleep(50)
            assertEquals(address, resolver.lookup("k", 100) { throw AssertionError("not looked up again") })
        }
    }

    @Test
    fun aFailedLookupIsRetriedOnlyAfterTheRetryInterval() {
        val calls = AtomicInteger()
        NonBlockingResolver(waitMillis = 500, retryMillis = 5_000).use { resolver ->
            assertNull(resolver.lookup("k", 0) { calls.incrementAndGet(); null })
            assertNull(resolver.lookup("k", 1_000) { calls.incrementAndGet(); null })
            assertEquals(1, calls.get())
            assertEquals(address, resolver.lookup("k", 6_000) { calls.incrementAndGet(); address })
            assertEquals(2, calls.get())
        }
    }
}
