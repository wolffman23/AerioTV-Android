package com.aeriotv.android.core.network.adaptarr

data class AutomaticSessionQualityInput(
    val mode: AutomaticSessionQualityMode,
    val eligibility: AutomaticSessionEligibility,
    val transport: AutomaticSessionTransport,
    val caps: AutomaticSessionQualityCaps,
    val probe: AutomaticSessionProbe,
    val profiles: List<AutomaticSessionOutputProfile>,
)

enum class AutomaticSessionQualityMode { Off, Recommend, Auto }

data class AutomaticSessionEligibility(
    val isTrustedLocalLive: Boolean,
    val isCatchup: Boolean,
    val isTimeshift: Boolean,
    val isRemote: Boolean,
) {
    val isEligible: Boolean
        get() = isTrustedLocalLive && !isCatchup && !isTimeshift && !isRemote
}

enum class AutomaticSessionTransport { Wifi, Ethernet, Cellular, Other, None }

data class AutomaticSessionQualityCaps(
    val normalMaximumHeight: Int,
    val cellularMaximumHeight: Int,
)

sealed interface AutomaticSessionProbe {
    data class Fresh(val throughputBps: Long) : AutomaticSessionProbe
    data class Cached(val throughputBps: Long) : AutomaticSessionProbe
    data object Stale : AutomaticSessionProbe
    data object Unavailable : AutomaticSessionProbe
    data object Timeout : AutomaticSessionProbe
}

data class AutomaticSessionOutputProfile(
    val id: Int,
    val height: Int,
    val mode: AutomaticSessionProfileMode,
    val minimumThroughputBps: Long,
)

enum class AutomaticSessionProfileMode { Passthrough, Transcode }

sealed interface AutomaticSessionQualityDecision {
    data object NoChange : AutomaticSessionQualityDecision
    data object Source : AutomaticSessionQualityDecision
    data class OutputProfile(val id: Int) : AutomaticSessionQualityDecision
}

/** Pure, default-off decision policy; it never performs I/O or changes playback directly. */
object AutomaticSessionQualityPolicy {

    fun decide(input: AutomaticSessionQualityInput): AutomaticSessionQualityDecision {
        if (input.mode != AutomaticSessionQualityMode.Auto || !input.eligibility.isEligible) {
            return AutomaticSessionQualityDecision.NoChange
        }
        val throughput = (input.probe as? AutomaticSessionProbe.Fresh)?.throughputBps
            ?: return AutomaticSessionQualityDecision.NoChange
        val maximumHeight = when (input.transport) {
            AutomaticSessionTransport.Cellular -> input.caps.cellularMaximumHeight
            AutomaticSessionTransport.Wifi,
            AutomaticSessionTransport.Ethernet -> input.caps.normalMaximumHeight
            AutomaticSessionTransport.Other,
            AutomaticSessionTransport.None -> return AutomaticSessionQualityDecision.NoChange
        }
        val trustedProfiles = TrustedProfiles.mapNotNull { trusted ->
            input.profiles.filter { it.id == trusted.id }.singleOrNull()?.takeIf {
                it.height == trusted.height &&
                    it.mode == AutomaticSessionProfileMode.Transcode &&
                    it.minimumThroughputBps > 0
            }
        }
        if (trustedProfiles.isEmpty()) return AutomaticSessionQualityDecision.NoChange

        val withinCap = trustedProfiles.filter { it.height <= maximumHeight }
        if (withinCap.isEmpty()) return AutomaticSessionQualityDecision.NoChange
        val bestMeasured = withinCap
            .filter { throughput >= it.minimumThroughputBps }
            .maxByOrNull { it.height }
        return when (input.transport) {
            AutomaticSessionTransport.Cellular -> bestMeasured
                ?.let { AutomaticSessionQualityDecision.OutputProfile(it.id) }
                ?: AutomaticSessionQualityDecision.NoChange
            AutomaticSessionTransport.Wifi,
            AutomaticSessionTransport.Ethernet -> when {
                // Source is safe only once the fresh measurement reaches the
                // highest trusted transcode tier permitted on this transport.
                throughput >= withinCap.maxOf { it.minimumThroughputBps } -> AutomaticSessionQualityDecision.Source
                bestMeasured != null -> AutomaticSessionQualityDecision.OutputProfile(bestMeasured.id)
                else -> AutomaticSessionQualityDecision.NoChange
            }
            AutomaticSessionTransport.Other,
            AutomaticSessionTransport.None -> AutomaticSessionQualityDecision.NoChange
        }
    }

    private data class TrustedProfile(val id: Int, val height: Int)

    private val TrustedProfiles = listOf(
        TrustedProfile(id = 8, height = 720),
        TrustedProfile(id = 9, height = 480),
    )
}
