package com.aeriotv.android.core.network.adaptarr

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveProbeCoordinatorTest {

    private val wifi = AdaptiveNetworkIdentity(AdaptiveTransport.Wifi, metered = false, vpn = false)
    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `unchanged relevant network identity does not invalidate`() {
        val tracker = AdaptiveNetworkChangeTracker(wifi)

        assertFalse(tracker.update(wifi.copy()))
    }

    @Test
    fun `relevant network identity change invalidates and advances baseline`() {
        val tracker = AdaptiveNetworkChangeTracker(wifi)
        val meteredWifi = wifi.copy(metered = true)

        assertTrue(tracker.update(meteredWifi))
        assertFalse(tracker.update(meteredWifi.copy()))
        assertTrue(tracker.update(meteredWifi.copy(vpn = true)))
        assertTrue(tracker.update(meteredWifi.copy(transport = AdaptiveTransport.Cellular)))
    }

    @Test
    fun `network key uses fixed HMAC vector and changes with secret or identity`() {
        val first = AdaptiveNetworkKey.derive(wifi, BASE, secret)

        assertEquals("609cb20f54c5fac09e6dc2a06e735875fafd82c193123cc8be0f42654a96f742", first)
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals(first, AdaptiveNetworkKey.derive(wifi, BASE, ByteArray(32) { 7 }))
        assertNotEquals(first, AdaptiveNetworkKey.derive(wifi.copy(vpn = true), BASE, secret))
    }

    @Test
    fun `cache is valid before ttl and expires exactly at fifteen minutes`() = runTest {
        var now = 1_000L
        var calls = 0
        val cache = cache(now = { now }) { key -> measurement(key, (++calls) * 1_000_000L, now) }

        assertEquals(AdaptiveProbeSource.Fresh, cache.get(wifi, BASE).success().source)
        now += 899_999
        assertEquals(AdaptiveProbeSource.Cached, cache.get(wifi, BASE).success().source)
        now += 1
        assertEquals(AdaptiveProbeSource.Fresh, cache.get(wifi, BASE).success().source)
        assertEquals(2, calls)
    }

    @Test
    fun `transport metered vpn and endpoint partition cache without token input`() = runTest {
        var calls = 0
        val cache = cache { key -> measurement(key, (++calls).toLong(), 10L) }

        cache.get(wifi, BASE)
        cache.get(wifi.copy(transport = AdaptiveTransport.Ethernet), BASE)
        cache.get(wifi.copy(metered = true), BASE)
        cache.get(wifi.copy(vpn = true), BASE)
        cache.get(wifi, "$BASE/other")
        cache.get(wifi, BASE)

        assertEquals(5, calls)
    }

    @Test
    fun `concurrent callers share one probe and cancelling one waiter does not cancel it`() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<AdaptiveProbeMeasurement>()
        val cache = cache {
            calls.incrementAndGet()
            gate.await()
        }

        val cancelledWaiter = async { cache.get(wifi, BASE) }
        val survivingWaiter = async { cache.get(wifi, BASE) }
        runCurrent()
        assertEquals(1, calls.get())
        cancelledWaiter.cancel(CancellationException("screen left"))
        val key = AdaptiveNetworkKey.derive(wifi, BASE, secret)
        gate.complete(measurement(key, 5_000_000L, 100L))

        assertEquals(5_000_000L, survivingWaiter.await().success().measurement.measuredThroughputBps)
        assertEquals(1, calls.get())
    }

    @Test
    fun `synchronous invalidation cancels old work and generation race fails stale`() = runTest {
        var operationCancelled = false
        val gate = CompletableDeferred<AdaptiveProbeMeasurement>()
        val cache = cache { gate.await() }
        val oldGeneration = cache.generationToken()
        val first = async {
            cache.get(wifi, BASE, oldGeneration) {
                try {
                    gate.await()
                } finally {
                    operationCancelled = true
                }
            }
        }
        runCurrent()

        cache.invalidateNow()
        runCurrent()
        assertEquals(AdaptiveProbeResult.Stale, first.await())
        assertTrue(operationCancelled || gate.isCancelled)

        var called = false
        val staleSnapshot = cache.get(wifi, BASE, oldGeneration) {
            called = true
            measurement(it, 1L, 1L)
        }
        assertEquals(AdaptiveProbeResult.Stale, staleSnapshot)
        assertTrue(!called)
    }

    @Test
    fun `timeout and execution failure are removed rather than cached`() = runTest {
        var calls = 0
        val timeoutCache = cache(
            now = { testScheduler.currentTime },
            timeoutMs = 1_500L,
        ) {
            calls += 1
            CompletableDeferred<AdaptiveProbeMeasurement>().await()
        }
        repeat(2) {
            val result = async { timeoutCache.get(wifi, BASE) }
            advanceTimeBy(1_501L)
            runCurrent()
            assertEquals(AdaptiveProbeResult.Timeout, result.await())
        }
        assertEquals(2, calls)
        assertEquals(0, timeoutCache.inFlightCountForTest())

        val failed = cache { throw IllegalStateException("offline") }
        assertEquals(AdaptiveProbeResult.Unavailable, failed.get(wifi, BASE))
        assertEquals(0, failed.inFlightCountForTest())
    }

    @Test
    fun `cache and concurrent in flight work are capacity bounded`() = runTest {
        var calls = 0
        val bounded = AdaptiveProbeCache(
            scope = backgroundScope,
            elapsedRealtimeMs = { 10L },
            processSecret = secret,
            maxCacheEntries = 2,
            maxInFlight = 2,
            execute = { key -> measurement(key, (++calls).toLong(), 10L) },
        )
        bounded.get(wifi, "$BASE/1")
        bounded.get(wifi, "$BASE/2")
        bounded.get(wifi, "$BASE/3")
        assertEquals(2, bounded.entryCountForTest())

        val gates = mapOf(
            "$BASE/a" to CompletableDeferred<AdaptiveProbeMeasurement>(),
            "$BASE/b" to CompletableDeferred(),
        )
        val limited = AdaptiveProbeCache(
            scope = backgroundScope,
            elapsedRealtimeMs = { 10L },
            processSecret = secret,
            maxInFlight = 2,
        )
        val a = async { limited.get(wifi, "$BASE/a") { gates.getValue("$BASE/a").await() } }
        val b = async { limited.get(wifi, "$BASE/b") { gates.getValue("$BASE/b").await() } }
        runCurrent()
        assertEquals(2, limited.inFlightCountForTest())
        assertEquals(
            AdaptiveProbeResult.Unavailable,
            limited.get(wifi, "$BASE/c") { error("must not execute") },
        )
        gates.getValue("$BASE/a").complete(
            measurement(AdaptiveNetworkKey.derive(wifi, "$BASE/a", secret), 1L, 10L),
        )
        gates.getValue("$BASE/b").complete(
            measurement(AdaptiveNetworkKey.derive(wifi, "$BASE/b", secret), 1L, 10L),
        )
        a.await()
        b.await()
    }

    private fun TestScope.cache(
        now: () -> Long = { 10L },
        timeoutMs: Long = 5_000L,
        execute: suspend (String) -> AdaptiveProbeMeasurement,
    ) = AdaptiveProbeCache(
        scope = backgroundScope,
        elapsedRealtimeMs = now,
        processSecret = secret,
        timeoutMs = timeoutMs,
        execute = execute,
    )

    private fun measurement(key: String, throughput: Long, measuredAt: Long) =
        AdaptiveProbeMeasurement(
            networkKey = key,
            measuredThroughputBps = throughput,
            sampleCount = 1,
            confidence = AdaptarrTelemetryConfidence.Low,
            measuredAtElapsedRealtimeMs = measuredAt,
        )

    private fun AdaptiveProbeResult.success() = this as AdaptiveProbeResult.Success

    private companion object {
        const val BASE = "https://adaptarr.example/proxy"
    }
}
