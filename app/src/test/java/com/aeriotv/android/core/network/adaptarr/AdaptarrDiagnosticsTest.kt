package com.aeriotv.android.core.network.adaptarr

import com.aeriotv.android.core.debug.DebugLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class AdaptarrDiagnosticsTest {
    private val logger: DebugLogger = mockk(relaxed = true)
    private val diagnostics = AdaptarrDiagnostics(logger)

    @Test
    fun `connection markers contain only fixed operation and outcome`() {
        diagnostics.connectionStarted()
        verify { logger.log("Adaptarr", DebugLogger.Level.INFO, "connection_test started") }

        val cases = listOf(
            AdaptarrConnectionTestResult.Connected to "connected",
            AdaptarrConnectionTestResult.InvalidSettings to "invalid_settings",
            AdaptarrConnectionTestResult.Unauthorized to "unauthorized",
            AdaptarrConnectionTestResult.IncompatibleProtocol to "incompatible_protocol",
            AdaptarrConnectionTestResult.RateLimited to "rate_limited",
            AdaptarrConnectionTestResult.ServiceUnavailable to "service_unavailable",
            AdaptarrConnectionTestResult.InvalidResponse to "invalid_response",
            AdaptarrConnectionTestResult.Unreachable to "unreachable",
        )
        cases.forEach { (result, outcome) ->
            diagnostics.connectionFinished(result)
            verify {
                logger.log(
                    "Adaptarr",
                    DebugLogger.Level.INFO,
                    "connection_test result=$outcome",
                )
            }
        }

        diagnostics.connectionCancelled()
        verify { logger.log("Adaptarr", DebugLogger.Level.INFO, "connection_test cancelled") }
    }

    @Test
    fun `probe markers contain only fixed operation and outcome`() {
        val measurement = AdaptiveProbeMeasurement(
            networkKey = "sensitive-network-key-" + "a".repeat(42),
            measuredThroughputBps = 9_876_543L,
            sampleCount = 7,
            confidence = AdaptarrTelemetryConfidence.High,
            measuredAtElapsedRealtimeMs = 123_456L,
        )

        diagnostics.probeStarted()
        verify { logger.log("Adaptarr", DebugLogger.Level.INFO, "speed_test started") }

        val cases = listOf(
            AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Fresh) to "fresh",
            AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Cached) to "cached",
            AdaptiveProbeResult.Timeout to "timeout",
            AdaptiveProbeResult.Unavailable to "unavailable",
            AdaptiveProbeResult.Stale to "network_changed",
        )
        cases.forEach { (result, outcome) ->
            diagnostics.probeFinished(result)
            verify {
                logger.log(
                    "Adaptarr",
                    DebugLogger.Level.INFO,
                    "speed_test result=$outcome",
                )
            }
        }

        diagnostics.probeCancelled()
        verify { logger.log("Adaptarr", DebugLogger.Level.INFO, "speed_test cancelled") }
    }

    @Test
    fun `logger failure never changes the observed operation`() {
        every { logger.log(any(), any(), any()) } throws IllegalStateException("logger failed")

        diagnostics.connectionStarted()
        diagnostics.connectionFinished(AdaptarrConnectionTestResult.Connected)
        diagnostics.connectionCancelled()
        diagnostics.probeStarted()
        diagnostics.probeFinished(AdaptiveProbeResult.Timeout)
        diagnostics.probeCancelled()
    }
}
