package com.aeriotv.android.core.network.adaptarr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptarrProtocolTest {

    @Test
    fun `health and configuration decode exact protocol one contract`() {
        val health = adaptarrJson.decodeFromString<AdaptarrHealthResponse>(
            """{"status":"ok","protocol_version":1}""",
        )
        assertEquals(1, health.protocolVersion)

        val config = adaptarrJson.decodeFromString<AdaptarrConfigResponse>(
            """{
                "schema_version":1,
                "protocol_version":1,
                "generation_id":"12345678-1234-4abc-8def-1234567890ab",
                "generated_at":"2026-07-23T01:02:03.456789Z",
                "profiles":{
                    "1080p":{
                        "id":1001,
                        "name":"Adaptarr 1080p Passthrough",
                        "width":1920,
                        "height":1080,
                        "mode":"passthrough",
                        "estimated_bitrate_bps":12000000,
                        "minimum_throughput_bps":12000000
                    }
                }
            }""".trimIndent(),
        )
        assertEquals(1, config.schemaVersion)
        assertEquals(1001, config.profiles.getValue("1080p").id)
    }

    @Test
    fun `strict decoder rejects unknown coercive unsupported and malformed config values`() {
        val invalid = listOf(
            """{"status":"ok","protocol_version":2}""",
            """{"status":"ok","protocol_version":"1"}""",
            """{"status":"ok","protocol_version":1,"extra":true}""",
            """{"status":"wrong","protocol_version":1}""",
        )
        invalid.forEach { payload ->
            assertFailsSerialization { adaptarrJson.decodeFromString<AdaptarrHealthResponse>(payload) }
        }

        val base = """{
            "schema_version":1,"protocol_version":1,
            "generation_id":"12345678-1234-4abc-8def-1234567890ab","generated_at":"2026-07-23T01:02:03.456789Z",
            "profiles":{"1080p":{"id":1001,"name":"Good","width":1920,"height":1080,"mode":"passthrough","estimated_bitrate_bps":1,"minimum_throughput_bps":1}}
        }""".trimIndent()
        assertFailsSerialization {
            adaptarrJson.decodeFromString<AdaptarrConfigResponse>(
                base.replace("\"schema_version\":1", "\"schema_version\":2"),
            )
        }
        assertFailsSerialization {
            adaptarrJson.decodeFromString<AdaptarrConfigResponse>(
                base.replace("\"name\":\"Good\"", "\"name\":\"bad\\nname\""),
            )
        }
    }

    @Test
    fun `probe observation and telemetry aggregate enforce exact ranges and timestamps`() {
        val observed = adaptarrJson.decodeFromString<AdaptarrProbeObservation>(
            """{
                "status":"observed","bytes_transferred":65536,"duration_ms":1000,
                "latency_ms":10,"throughput_bps":524288,
                "observed_at":"2026-07-23T01:02:03.456789Z"
            }""".trimIndent(),
        )
        assertEquals(524288L, observed.throughputBps)

        val aggregate = adaptarrJson.decodeFromString<AdaptarrTelemetryAggregate>(
            """{
                "status":"aggregated","sample_count":3,
                "conservative_throughput_bps":5000000,"median_latency_ms":10,
                "confidence":"medium",
                "last_observed_at":"2026-07-23T01:02:03.456789Z",
                "expires_at":"2026-07-23T01:07:03.456789Z"
            }""".trimIndent(),
        )
        assertEquals(3, aggregate.sampleCount)

        assertFailsSerialization {
            adaptarrJson.decodeFromString<AdaptarrProbeObservation>(
                """{"status":"observed","bytes_transferred":65535,"duration_ms":1000,"latency_ms":10,"throughput_bps":1,"observed_at":"not-time"}""",
            )
        }
        assertFailsSerialization {
            adaptarrJson.decodeFromString<AdaptarrTelemetryAggregate>(
                """{"status":"aggregated","sample_count":21,"conservative_throughput_bps":1,"median_latency_ms":0,"confidence":"high","last_observed_at":"2026-07-23T01:02:03.456789Z","expires_at":"2026-07-23T01:07:03.456789Z"}""",
            )
        }
    }

    @Test
    fun `recommendation response accepts valid states and rejects inconsistent decisions`() {
        val recommended = adaptarrJson.decodeFromString<AdaptarrRecommendationResponse>(
            """{
                "status":"recommended","dry_run":true,
                "profile":{"output_profile_id":1001,"name":"Adaptarr 1080p Passthrough","height":1080,"minimum_throughput_bps":12000000},
                "sample_count":3,"conservative_throughput_bps":20000000,
                "confidence":"medium","reason":"threshold_met"
            }""".trimIndent(),
        )
        assertEquals(1080, recommended.profile?.height)

        val noData = adaptarrJson.decodeFromString<AdaptarrRecommendationResponse>(
            """{
                "status":"insufficient_data","dry_run":true,"profile":null,
                "sample_count":0,"conservative_throughput_bps":null,
                "confidence":"none","reason":"no_telemetry"
            }""".trimIndent(),
        )
        assertNull(noData.profile)

        val invalid = listOf(
            """{"status":"recommended","dry_run":false,"profile":{"output_profile_id":1001,"name":"Profile","height":1080,"minimum_throughput_bps":12000000},"sample_count":3,"conservative_throughput_bps":20000000,"confidence":"medium","reason":"threshold_met"}""",
            """{"status":"recommended","dry_run":true,"profile":null,"sample_count":3,"conservative_throughput_bps":20000000,"confidence":"medium","reason":"threshold_met"}""",
            """{"status":"recommended","dry_run":true,"profile":{"output_profile_id":1001,"name":"Profile","height":1080,"minimum_throughput_bps":12000000},"sample_count":1,"conservative_throughput_bps":20000000,"confidence":"low","reason":"threshold_met"}""",
            """{"status":"constrained","dry_run":true,"profile":{"output_profile_id":1002,"name":"Profile","height":720,"minimum_throughput_bps":4800000},"sample_count":3,"conservative_throughput_bps":5000000,"confidence":"medium","reason":"below_lowest_threshold"}""",
        )
        invalid.forEach { payload ->
            assertFailsSerialization { adaptarrJson.decodeFromString<AdaptarrRecommendationResponse>(payload) }
        }
    }

    @Test
    fun `privacy sensitive request models serialize exact fields without revealing network key in toString`() {
        val key = "a".repeat(64)
        val report = AdaptarrTelemetryReport(
            networkKey = key,
            bytesTransferred = 65536,
            durationMs = 1000,
            latencyMs = 10,
        )
        assertEquals(
            """{"network_key":"$key","bytes_transferred":65536,"duration_ms":1000,"latency_ms":10}""",
            adaptarrJson.encodeToString(report),
        )
        assertFalse(report.toString().contains(key))

        val request = AdaptarrRecommendationRequest(networkKey = key, maxHeight = 1080)
        assertEquals(
            """{"network_key":"$key","max_height":1080}""",
            adaptarrJson.encodeToString(request),
        )
        assertFalse(request.toString().contains(key))
    }

    @Test
    fun `request models reject invalid network keys measurements and max heights`() {
        assertFailsArgument {
            AdaptarrTelemetryLookup("A".repeat(64))
        }
        assertFailsArgument {
            AdaptarrProbeReport(bytesTransferred = 65535, durationMs = 1000, latencyMs = 0)
        }
        assertFailsArgument {
            AdaptarrRecommendationRequest(networkKey = "a".repeat(64), maxHeight = 721)
        }
        assertTrue(
            AdaptarrRecommendationRequest("f".repeat(64), 720).toString().isNotEmpty(),
        )
    }

    private fun assertFailsSerialization(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected serialization failure")
        } catch (_: SerializationException) {
            // Expected.
        } catch (_: IllegalArgumentException) {
            // Constructor invariants surface through kotlinx serialization this way.
        }
    }

    private fun assertFailsArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected argument failure")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
