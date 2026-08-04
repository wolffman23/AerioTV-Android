package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSessionQualityPolicyTest {

    @Test
    fun `off and recommend modes never change playback`() {
        assertEquals(
            AutomaticSessionQualityDecision.NoChange,
            AutomaticSessionQualityPolicy.decide(input(mode = AutomaticSessionQualityMode.Off)),
        )
        assertEquals(
            AutomaticSessionQualityDecision.NoChange,
            AutomaticSessionQualityPolicy.decide(input(mode = AutomaticSessionQualityMode.Recommend)),
        )
    }

    @Test
    fun `untrusted local catchup timeshift and remote sessions never change playback`() {
        listOf(
            defaultEligibility.copy(isTrustedLocalLive = false),
            defaultEligibility.copy(isCatchup = true),
            defaultEligibility.copy(isTimeshift = true),
            defaultEligibility.copy(isRemote = true),
        ).forEach { rejectedEligibility ->
            assertEquals(
                AutomaticSessionQualityDecision.NoChange,
                AutomaticSessionQualityPolicy.decide(input(eligibility = rejectedEligibility)),
            )
        }
    }

    @Test
    fun `fresh cellular measurement selects only trusted 720p profile eight`() {
        assertEquals(
            AutomaticSessionQualityDecision.OutputProfile(8),
            AutomaticSessionQualityPolicy.decide(
                input(transport = AutomaticSessionTransport.Cellular),
            ),
        )
    }

    @Test
    fun `fresh weak cellular measurement selects trusted 480p profile nine`() {
        assertEquals(
            AutomaticSessionQualityDecision.OutputProfile(9),
            AutomaticSessionQualityPolicy.decide(
                input(
                    probe = AutomaticSessionProbe.Fresh(2_000_000L),
                    profiles = listOf(profile, lowProfile),
                ),
            ),
        )
    }

    @Test
    fun `fresh noncellular measurement selects source rather than another output profile`() {
        assertEquals(
            AutomaticSessionQualityDecision.Source,
            AutomaticSessionQualityPolicy.decide(input(transport = AutomaticSessionTransport.Wifi)),
        )
    }

    @Test
    fun `unknown and absent transports never change playback`() {
        listOf(AutomaticSessionTransport.Other, AutomaticSessionTransport.None).forEach { transport ->
            assertEquals(
                AutomaticSessionQualityDecision.NoChange,
                AutomaticSessionQualityPolicy.decide(input(transport = transport)),
            )
        }
    }

    @Test
    fun `cap below 720 prevents automatic selection on either transport`() {
        listOf(
            AutomaticSessionQualityCaps(normalMaximumHeight = 719, cellularMaximumHeight = 720) to
                AutomaticSessionTransport.Wifi,
            AutomaticSessionQualityCaps(normalMaximumHeight = 720, cellularMaximumHeight = 719) to
                AutomaticSessionTransport.Cellular,
        ).forEach { (caps, transport) ->
            assertEquals(
                AutomaticSessionQualityDecision.NoChange,
                AutomaticSessionQualityPolicy.decide(input(caps = caps, transport = transport)),
            )
        }
    }

    @Test
    fun `cached stale unavailable and timed out probes never change playback`() {
        listOf(
            AutomaticSessionProbe.Cached(throughputBps = minimumThroughputBps),
            AutomaticSessionProbe.Stale,
            AutomaticSessionProbe.Unavailable,
            AutomaticSessionProbe.Timeout,
        ).forEach { probe ->
            assertEquals(
                AutomaticSessionQualityDecision.NoChange,
                AutomaticSessionQualityPolicy.decide(input(probe = probe)),
            )
        }
    }

    @Test
    fun `missing or ambiguous profile configuration never changes playback`() {
        assertEquals(
            AutomaticSessionQualityDecision.NoChange,
            AutomaticSessionQualityPolicy.decide(input(profiles = emptyList())),
        )
        assertEquals(
            AutomaticSessionQualityDecision.NoChange,
            AutomaticSessionQualityPolicy.decide(input(profiles = listOf(profile, profile))),
        )
    }

    @Test
    fun `wrong output profile id height or mode never changes playback`() {
        listOf(
            profile.copy(id = 7),
            profile.copy(height = 1080),
            profile.copy(mode = AutomaticSessionProfileMode.Passthrough),
        ).forEach { untrustedProfile ->
            assertEquals(
                AutomaticSessionQualityDecision.NoChange,
                AutomaticSessionQualityPolicy.decide(input(profiles = listOf(untrustedProfile))),
            )
        }
    }

    @Test
    fun `fresh throughput below the trusted profile minimum never changes playback`() {
        assertEquals(
            AutomaticSessionQualityDecision.NoChange,
            AutomaticSessionQualityPolicy.decide(
                input(probe = AutomaticSessionProbe.Fresh(minimumThroughputBps - 1)),
            ),
        )
    }

    private fun input(
        mode: AutomaticSessionQualityMode = AutomaticSessionQualityMode.Auto,
        eligibility: AutomaticSessionEligibility = defaultEligibility,
        transport: AutomaticSessionTransport = AutomaticSessionTransport.Cellular,
        caps: AutomaticSessionQualityCaps = AutomaticSessionQualityCaps(
            normalMaximumHeight = 1080,
            cellularMaximumHeight = 720,
        ),
        probe: AutomaticSessionProbe = AutomaticSessionProbe.Fresh(minimumThroughputBps),
        profiles: List<AutomaticSessionOutputProfile> = listOf(profile),
    ) = AutomaticSessionQualityInput(
        mode = mode,
        eligibility = eligibility,
        transport = transport,
        caps = caps,
        probe = probe,
        profiles = profiles,
    )

    private companion object {
        const val minimumThroughputBps = 4_000_000L

        val defaultEligibility = AutomaticSessionEligibility(
            isTrustedLocalLive = true,
            isCatchup = false,
            isTimeshift = false,
            isRemote = false,
        )
        val profile = AutomaticSessionOutputProfile(
            id = 8,
            height = 720,
            mode = AutomaticSessionProfileMode.Transcode,
            minimumThroughputBps = minimumThroughputBps,
        )
        val lowProfile = AutomaticSessionOutputProfile(
            id = 9,
            height = 480,
            mode = AutomaticSessionProfileMode.Transcode,
            minimumThroughputBps = 2_000_000L,
        )
    }
}
