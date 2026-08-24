package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticSessionQualityPolicyTest {

    @Test
    fun `off and recommend modes never change playback`() {
        assertEquals(AutomaticSessionQualityDecision.NoChange, decide(mode = AutomaticSessionQualityMode.Off))
        assertEquals(AutomaticSessionQualityDecision.NoChange, decide(mode = AutomaticSessionQualityMode.Recommend))
    }

    @Test
    fun `untrusted catchup timeshift and remote sessions never change playback`() {
        listOf(
            eligible.copy(isTrustedLocalLive = false),
            eligible.copy(isCatchup = true),
            eligible.copy(isTimeshift = true),
            eligible.copy(isRemote = true),
        ).forEach { rejected ->
            assertEquals(AutomaticSessionQualityDecision.NoChange, decide(eligibility = rejected))
        }
    }

    @Test
    fun `fresh cellular probe selects only trusted 720p profile eight`() {
        assertEquals(AutomaticSessionQualityDecision.OutputProfile(8), decide())
    }

    @Test
    fun `weak cellular probe selects trusted 480p profile nine`() {
        assertEquals(
            AutomaticSessionQualityDecision.OutputProfile(9),
            decide(
                probe = AutomaticSessionProbe.Fresh(2_000_000L),
                profiles = listOf(profile720, profile480),
            ),
        )
    }

    @Test
    fun `weak wifi and ethernet probes select the best trusted lower tier`() {
        listOf(AutomaticSessionTransport.Wifi, AutomaticSessionTransport.Ethernet).forEach { transport ->
            assertEquals(
                AutomaticSessionQualityDecision.OutputProfile(9),
                decide(
                    transport = transport,
                    probe = AutomaticSessionProbe.Fresh(2_000_000L),
                    profiles = listOf(profile720, profile480),
                ),
            )
        }
    }

    @Test
    fun `strong wifi and ethernet probes retain source`() {
        listOf(AutomaticSessionTransport.Wifi, AutomaticSessionTransport.Ethernet).forEach { transport ->
            assertEquals(
                AutomaticSessionQualityDecision.Source,
                decide(transport = transport, profiles = listOf(profile720, profile480)),
            )
        }
    }

    @Test
    fun `unknown absent and nonfresh measurements never change playback`() {
        listOf(
            AutomaticSessionTransport.Other to AutomaticSessionProbe.Fresh(minimumThroughputBps),
            AutomaticSessionTransport.None to AutomaticSessionProbe.Fresh(minimumThroughputBps),
            AutomaticSessionTransport.Cellular to AutomaticSessionProbe.Cached(minimumThroughputBps),
            AutomaticSessionTransport.Cellular to AutomaticSessionProbe.Stale,
            AutomaticSessionTransport.Cellular to AutomaticSessionProbe.Unavailable,
            AutomaticSessionTransport.Cellular to AutomaticSessionProbe.Timeout,
        ).forEach { (transport, probe) ->
            assertEquals(AutomaticSessionQualityDecision.NoChange, decide(transport = transport, probe = probe))
        }
    }

    @Test
    fun `caps and profile validation fail closed`() {
        assertEquals(
            AutomaticSessionQualityDecision.NoChange,
            decide(caps = AutomaticSessionQualityCaps(normalMaximumHeight = 1080, cellularMaximumHeight = 719)),
        )
        listOf(
            emptyList(),
            listOf(profile720, profile720),
            listOf(profile720.copy(id = 7)),
            listOf(profile720.copy(height = 1080)),
            listOf(profile720.copy(mode = AutomaticSessionProfileMode.Passthrough)),
            listOf(profile720.copy(minimumThroughputBps = 0)),
        ).forEach { profiles ->
            assertEquals(AutomaticSessionQualityDecision.NoChange, decide(profiles = profiles))
        }
    }

    @Test
    fun `throughput below trusted profile minimum never changes playback`() {
        assertEquals(
            AutomaticSessionQualityDecision.NoChange,
            decide(probe = AutomaticSessionProbe.Fresh(minimumThroughputBps - 1)),
        )
    }

    private fun decide(
        mode: AutomaticSessionQualityMode = AutomaticSessionQualityMode.Auto,
        eligibility: AutomaticSessionEligibility = eligible,
        transport: AutomaticSessionTransport = AutomaticSessionTransport.Cellular,
        caps: AutomaticSessionQualityCaps = AutomaticSessionQualityCaps(1080, 720),
        probe: AutomaticSessionProbe = AutomaticSessionProbe.Fresh(minimumThroughputBps),
        profiles: List<AutomaticSessionOutputProfile> = listOf(profile720),
    ) = AutomaticSessionQualityPolicy.decide(
        AutomaticSessionQualityInput(mode, eligibility, transport, caps, probe, profiles),
    )

    private companion object {
        const val minimumThroughputBps = 4_000_000L
        val eligible = AutomaticSessionEligibility(true, false, false, false)
        val profile720 = AutomaticSessionOutputProfile(8, 720, AutomaticSessionProfileMode.Transcode, minimumThroughputBps)
        val profile480 = AutomaticSessionOutputProfile(9, 480, AutomaticSessionProfileMode.Transcode, 2_000_000L)
    }
}
