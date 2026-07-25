package com.aeriotv.android.core.network.adaptarr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveProbeRunnerTest {

    @Test
    fun `runner performs exact warmup then measured sample and keeps result local`() = runTest {
        val events = mutableListOf<String>()
        val times = ArrayDeque(listOf(2_000L, 2_500L))
        val runner = AdaptiveProbeRunner(
            transport = AdaptiveProbeTransport { baseUrl, token, sizeBytes ->
                events += "download:$sizeBytes"
                assertEquals(BASE, baseUrl)
                assertEquals(TOKEN, token)
                ByteArray(sizeBytes)
            },
            elapsedRealtimeMs = { times.removeFirst() },
        )

        val result = runner.execute(BASE, TOKEN, KEY)

        assertEquals(listOf("download:65536", "download:1048576"), events)
        assertEquals(KEY, result.networkKey)
        assertEquals(16_777_216L, result.measuredThroughputBps)
        assertEquals(1, result.sampleCount)
        assertEquals(AdaptarrTelemetryConfidence.Low, result.confidence)
        assertEquals(2_500L, result.measuredAtElapsedRealtimeMs)
    }

    @Test
    fun `zero millisecond sample interval is clamped to one without overflow`() = runTest {
        val runner = AdaptiveProbeRunner(
            transport = AdaptiveProbeTransport { _, _, sizeBytes -> ByteArray(sizeBytes) },
            elapsedRealtimeMs = { 42L },
        )

        val result = runner.execute(BASE, TOKEN, KEY)

        assertEquals(8_388_608_000L, result.measuredThroughputBps)
        assertEquals(42L, result.measuredAtElapsedRealtimeMs)
    }

    private companion object {
        const val BASE = "https://adaptarr.example"
        const val TOKEN = "tttttttttttttttttttttttttttttttt"
        const val KEY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
