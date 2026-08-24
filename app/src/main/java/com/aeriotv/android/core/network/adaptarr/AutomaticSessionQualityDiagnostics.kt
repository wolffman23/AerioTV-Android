package com.aeriotv.android.core.network.adaptarr

/** Fixed, payload-free markers for one automatic quality decision. */
internal object AutomaticSessionQualityDiagnostics {

    fun probe(probe: AutomaticSessionProbe): String = "probe=${probe.label()}"

    fun decision(
        transport: AutomaticSessionTransport,
        caps: AutomaticSessionQualityCaps,
        probe: AutomaticSessionProbe,
        profiles: List<AutomaticSessionOutputProfile>,
        decision: AutomaticSessionQualityDecision,
    ): String = buildString {
        append("decision=").append(decision.label())
        append(" transport=").append(transport.name.lowercase())
        append(" caps=normal_").append(caps.normalMaximumHeight)
        append("/cellular_").append(caps.cellularMaximumHeight)
        append(" probe=").append(probe.label())
        append(" profile8=").append(profiles.profileLabel(8))
        append(" profile9=").append(profiles.profileLabel(9))
    }

    private fun AutomaticSessionQualityDecision.label(): String = when (this) {
        AutomaticSessionQualityDecision.NoChange -> "no_change"
        is AutomaticSessionQualityDecision.OutputProfile -> "profile_$id"
        AutomaticSessionQualityDecision.Source -> "source"
    }

    private fun AutomaticSessionProbe.label(): String = when (this) {
        is AutomaticSessionProbe.Fresh -> "fresh_${throughputBps.bucket()}"
        is AutomaticSessionProbe.Cached -> "cached_${throughputBps.bucket()}"
        AutomaticSessionProbe.Stale -> "stale"
        AutomaticSessionProbe.Unavailable -> "unavailable"
        AutomaticSessionProbe.Timeout -> "timeout"
    }

    private fun List<AutomaticSessionOutputProfile>.profileLabel(id: Int): String {
        val profile = singleOrNull { it.id == id } ?: return "missing"
        return "present_${profile.height}_${profile.mode.name.lowercase()}_min_${profile.minimumThroughputBps.bucket()}"
    }

    private fun Long.bucket(): String = when {
        this < 2_000_000L -> "lt_2mbps"
        this < 4_800_000L -> "2_to_4_8mbps"
        this < 8_000_000L -> "4_8_to_8mbps"
        else -> "gte_8mbps"
    }
}
