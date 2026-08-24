package com.aeriotv.android.core.network.adaptarr

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestProbePersistenceTest {

    @Test
    fun `concurrent writes finish with newest measurement regardless of scheduling`() = runTest {
        val writes = mutableListOf<Long>()
        val oldWriteStarted = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val persistence = LatestProbePersistence { throughput ->
            if (throughput == 1L) {
                oldWriteStarted.complete(Unit)
                releaseOldWrite.await()
            }
            writes += throughput
        }

        val older = async { persistence.persist(measurement(1L, measuredAt = 100L)) }
        oldWriteStarted.await()
        val newer = async { persistence.persist(measurement(2L, measuredAt = 200L)) }
        runCurrent()
        releaseOldWrite.complete(Unit)
        older.await()
        newer.await()
        persistence.persist(measurement(3L, measuredAt = 150L))

        assertEquals(listOf(1L, 2L), writes)
    }

    @Test
    fun `diagnostic write propagates caller cancellation`() = runTest {
        val persistence = LatestProbePersistence { throw CancellationException("cancelled") }

        try {
            persistence.persist(measurement(1L, 100L))
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is never converted to a successful probe.
        }
    }

    @Test
    fun `failed diagnostic write is ignored and remains retryable`() = runTest {
        var attempts = 0
        val persistence = LatestProbePersistence {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("disk")
        }

        persistence.persist(measurement(1L, 100L))
        persistence.persist(measurement(1L, 100L))

        assertEquals(2, attempts)
    }

    private fun measurement(throughput: Long, measuredAt: Long) = AdaptiveProbeMeasurement(
        networkKey = "a".repeat(64),
        measuredThroughputBps = throughput,
        sampleCount = 1,
        confidence = AdaptarrTelemetryConfidence.Low,
        measuredAtElapsedRealtimeMs = measuredAt,
    )
}
