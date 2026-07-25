package com.aeriotv.android.core.network.adaptarr

import com.aeriotv.android.core.debug.DebugLogger
import javax.inject.Inject
import javax.inject.Singleton

/** Emits fixed, payload-free markers for user-exported Adaptarr diagnostics. */
@Singleton
class AdaptarrDiagnostics @Inject constructor(
    private val logger: DebugLogger,
) {
    fun connectionStarted() = log("connection_test started")

    internal fun connectionFinished(result: AdaptarrConnectionTestResult) {
        val outcome = when (result) {
            AdaptarrConnectionTestResult.Connected -> "connected"
            AdaptarrConnectionTestResult.InvalidSettings -> "invalid_settings"
            AdaptarrConnectionTestResult.Unauthorized -> "unauthorized"
            AdaptarrConnectionTestResult.IncompatibleProtocol -> "incompatible_protocol"
            AdaptarrConnectionTestResult.RateLimited -> "rate_limited"
            AdaptarrConnectionTestResult.ServiceUnavailable -> "service_unavailable"
            AdaptarrConnectionTestResult.InvalidResponse -> "invalid_response"
            AdaptarrConnectionTestResult.Unreachable -> "unreachable"
        }
        log("connection_test result=$outcome")
    }

    fun connectionCancelled() = log("connection_test cancelled")

    fun probeStarted() = log("speed_test started")

    internal fun probeFinished(result: AdaptiveProbeResult) {
        val outcome = when (result) {
            is AdaptiveProbeResult.Success -> when (result.source) {
                AdaptiveProbeSource.Fresh -> "fresh"
                AdaptiveProbeSource.Cached -> "cached"
            }
            AdaptiveProbeResult.Timeout -> "timeout"
            AdaptiveProbeResult.Unavailable -> "unavailable"
            AdaptiveProbeResult.Stale -> "network_changed"
        }
        log("speed_test result=$outcome")
    }

    fun probeCancelled() = log("speed_test cancelled")

    private fun log(message: String) {
        runCatching {
            logger.log(TAG, DebugLogger.Level.INFO, message)
        }
    }

    private companion object {
        const val TAG = "Adaptarr"
    }
}
