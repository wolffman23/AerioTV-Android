package com.aeriotv.android.core.network.adaptarr

internal fun interface AdaptiveProbeTransport {
    suspend fun download(baseUrl: String, token: String, sizeBytes: Int): ByteArray
}

/** Runs one bounded same-path probe and retains only a local aggregate measurement. */
internal class AdaptiveProbeRunner(
    private val transport: AdaptiveProbeTransport,
    private val elapsedRealtimeMs: () -> Long,
) {
    suspend fun execute(
        baseUrl: String,
        token: String,
        networkKey: String,
    ): AdaptiveProbeMeasurement {
        val warmup = transport.download(baseUrl, token, WARMUP_BYTES)
        require(warmup.size == WARMUP_BYTES) { "invalid warmup response" }

        val sampleStarted = elapsedRealtimeMs()
        val sample = transport.download(baseUrl, token, SAMPLE_BYTES)
        val sampleFinished = elapsedRealtimeMs()
        require(sample.size == SAMPLE_BYTES) { "invalid sample response" }
        val durationMs = boundedDuration(sampleStarted, sampleFinished)
        val throughputBps = (sample.size.toLong() * BITS_PER_BYTE * MILLIS_PER_SECOND) / durationMs

        return AdaptiveProbeMeasurement(
            networkKey = networkKey,
            measuredThroughputBps = throughputBps,
            sampleCount = 1,
            confidence = AdaptarrTelemetryConfidence.Low,
            measuredAtElapsedRealtimeMs = sampleFinished.coerceAtLeast(0L),
            bytesTransferred = sample.size.toLong(),
            durationMs = durationMs,
            latencyMs = 0,
        )
    }

    private fun boundedDuration(started: Long, finished: Long): Int {
        val elapsed = if (finished >= started) finished - started else 1L
        return elapsed.coerceIn(1L, MAX_DURATION_MS.toLong()).toInt()
    }

    private companion object {
        const val WARMUP_BYTES = 65_536
        const val SAMPLE_BYTES = 1_048_576
        const val MAX_DURATION_MS = 60_000
        const val BITS_PER_BYTE = 8L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
