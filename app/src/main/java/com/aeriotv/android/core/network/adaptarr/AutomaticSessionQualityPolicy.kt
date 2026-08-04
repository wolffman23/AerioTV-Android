package com.aeriotv.android.core.network.adaptarr

/** Pure, session-scoped automatic quality decision inputs. */
data class AutomaticSessionQualityInput(
    val mode: AutomaticSessionQualityMode,
    val eligibility: AutomaticSessionEligibility,
    val transport: AutomaticSessionTransport,
    val caps: AutomaticSessionQualityCaps,
    val probe: AutomaticSessionProbe,
    val profiles: List<AutomaticSessionOutputProfile>,
)

enum class AutomaticSessionQualityMode {
    Off,
    Recommend,
    Auto,
}

data class AutomaticSessionEligibility(
    val isTrustedLocalLive: Boolean,
    val isCatchup: Boolean,
    val isTimeshift: Boolean,
    val isRemote: Boolean,
) {
    val isEligible: Boolean
        get() = isTrustedLocalLive && !isCatchup && !isTimeshift && !isRemote
}

enum class AutomaticSessionTransport {
    Wifi,
    Ethernet,
    Cellular,
    Other,
    None,
}

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

enum class AutomaticSessionProfileMode {
    Passthrough,
    Transcode,
}

sealed interface AutomaticSessionQualityDecision {
    data object NoChange : AutomaticSessionQualityDecision
    data object Source : AutomaticSessionQualityDecision
    data class OutputProfile(val id: Int) : AutomaticSessionQualityDecision
}

/**
 * Decides the only automatic session quality changes allowed in this rollout.
 * It neither reads nor stores state and is intentionally independent of Android and transport I/O.
 */
object AutomaticSessionQualityPolicy {
    fun decide(input: AutomaticSessionQualityInput): AutomaticSessionQualityDecision {
        if (input.mode != AutomaticSessionQualityMode.Auto || !input.eligibility.isEligible) {
            return AutomaticSessionQualityDecision.NoChange
        }

        val throughputBps = (input.probe as? AutomaticSessionProbe.Fresh)?.throughputBps
            ?: return AutomaticSessionQualityDecision.NoChange
        val maximumHeight = when (input.transport) {
            AutomaticSessionTransport.Cellular -> input.caps.cellularMaximumHeight
            AutomaticSessionTransport.Wifi,
            AutomaticSessionTransport.Ethernet -> input.caps.normalMaximumHeight
            AutomaticSessionTransport.Other,
            AutomaticSessionTransport.None -> return AutomaticSessionQualityDecision.NoChange
        }
        val trustedProfiles = listOf(
            TrustedOutputProfile(id = TRUSTED_720P_OUTPUT_PROFILE_ID, height = 720),
            TrustedOutputProfile(id = TRUSTED_480P_OUTPUT_PROFILE_ID, height = 480),
        ).mapNotNull { trusted ->
            input.profiles.filter { it.id == trusted.id }.singleOrNull()
                ?.takeIf {
                    it.height == trusted.height &&
                        it.mode == AutomaticSessionProfileMode.Transcode &&
                        it.minimumThroughputBps > 0
                }
        }
        if (trustedProfiles.isEmpty()) return AutomaticSessionQualityDecision.NoChange

        return if (input.transport == AutomaticSessionTransport.Cellular) {
            trustedProfiles
                .filter { it.height <= maximumHeight && throughputBps >= it.minimumThroughputBps }
                .maxByOrNull { it.height }
                ?.let { AutomaticSessionQualityDecision.OutputProfile(it.id) }
                ?: AutomaticSessionQualityDecision.NoChange
        } else {
            if (trustedProfiles.any { it.height <= maximumHeight }) {
                AutomaticSessionQualityDecision.Source
            } else {
                AutomaticSessionQualityDecision.NoChange
            }
        }
    }

    private data class TrustedOutputProfile(val id: Int, val height: Int)

    private const val TRUSTED_720P_OUTPUT_PROFILE_ID = 8
    private const val TRUSTED_480P_OUTPUT_PROFILE_ID = 9
}
