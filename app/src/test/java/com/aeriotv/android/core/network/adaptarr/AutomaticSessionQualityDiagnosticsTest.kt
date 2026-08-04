package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AutomaticSessionQualityDiagnosticsTest {
    @Test
    fun `decision marker buckets probe and profile throughput without raw values`() {
        val marker = AutomaticSessionQualityDiagnostics.decision(
            transport = AutomaticSessionTransport.Cellular,
            caps = AutomaticSessionQualityCaps(
                normalMaximumHeight = 1080,
                cellularMaximumHeight = 720,
            ),
            probe = AutomaticSessionProbe.Fresh(4_799_999),
            profiles = listOf(
                AutomaticSessionOutputProfile(
                    id = 8,
                    height = 720,
                    mode = AutomaticSessionProfileMode.Transcode,
                    minimumThroughputBps = 4_800_000,
                ),
                AutomaticSessionOutputProfile(
                    id = 9,
                    height = 480,
                    mode = AutomaticSessionProfileMode.Transcode,
                    minimumThroughputBps = 2_000_000,
                ),
            ),
            decision = AutomaticSessionQualityDecision.NoChange,
        )

        assertEquals(
            "decision=no_change transport=cellular caps=normal_1080/cellular_720 " +
                "probe=fresh_2_to_4_8mbps profile8=present_720_transcode_min_4_8_to_8mbps " +
                "profile9=present_480_transcode_min_2_to_4_8mbps",
            marker,
        )
        assertFalse(marker.contains("4_799_999"))
        assertFalse(marker.contains("4_800_000"))
    }

    @Test
    fun `probe marker records only the fixed outcome label`() {
        assertEquals(
            "probe=timeout",
            AutomaticSessionQualityDiagnostics.probe(AutomaticSessionProbe.Timeout),
        )
    }

    @Test
    fun `decision marker identifies a missing profile eight without identifiers or URLs`() {
        val marker = AutomaticSessionQualityDiagnostics.decision(
            transport = AutomaticSessionTransport.Other,
            caps = AutomaticSessionQualityCaps(
                normalMaximumHeight = 1080,
                cellularMaximumHeight = 720,
            ),
            probe = AutomaticSessionProbe.Unavailable,
            profiles = emptyList(),
            decision = AutomaticSessionQualityDecision.NoChange,
        )

        assertEquals(
            "decision=no_change transport=other caps=normal_1080/cellular_720 " +
                "probe=unavailable profile8=missing profile9=missing",
            marker,
        )
    }
}
