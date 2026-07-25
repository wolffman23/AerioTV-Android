package com.aeriotv.android.core.network.adaptarr

import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes diagnostic writes and guarantees monotonic measurement ordering. */
internal class LatestProbePersistence(
    private val writeThroughputBps: suspend (Long) -> Unit,
) {
    private val mutex = Mutex()
    private var latestMeasuredAtMs = Long.MIN_VALUE

    suspend fun persist(measurement: AdaptiveProbeMeasurement) {
        mutex.withLock {
            if (measurement.measuredAtElapsedRealtimeMs <= latestMeasuredAtMs) return
            try {
                writeThroughputBps(measurement.measuredThroughputBps)
                latestMeasuredAtMs = measurement.measuredAtElapsedRealtimeMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Diagnostic persistence must not turn a valid probe into a failure.
            }
        }
    }
}
