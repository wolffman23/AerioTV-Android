package com.aeriotv.android.core.network.adaptarr

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptarrClientTest {

    private val token = "t".repeat(32)
    private val key = "a".repeat(64)

    @Test
    fun `connection test checks health then authenticated config through reverse proxy path`() = runTest {
        val paths = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        val client = testClient { request ->
            paths += request.url.encodedPath
            auth += request.headers[HttpHeaders.Authorization]
            when (request.url.encodedPath) {
                "/proxy/adaptarr/health" -> jsonResponse(HEALTH)
                "/proxy/adaptarr/v1/config" -> jsonResponse(CONFIG, noStore = true)
                else -> error("unexpected route")
            }
        }

        val result = client.testConnection("http://adaptarr.local:9192/proxy/adaptarr/", token)

        assertEquals(AdaptarrConnectionTestResult.Connected, result)
        assertEquals(listOf("/proxy/adaptarr/health", "/proxy/adaptarr/v1/config"), paths)
        assertEquals(listOf(null, "Bearer $token"), auth)
        assertFalse(result.toString().contains(token))
        assertFalse(result.toString().contains("adaptarr.local"))
    }

    @Test
    fun `connection test rejects invalid local settings before network`() = runTest {
        var calls = 0
        val client = testClient {
            calls += 1
            jsonResponse(HEALTH)
        }

        assertEquals(
            AdaptarrConnectionTestResult.InvalidSettings,
            client.testConnection("ftp://user:pass@example.invalid?q=x", token),
        )
        assertEquals(
            AdaptarrConnectionTestResult.InvalidSettings,
            client.testConnection("http://adaptarr.local", "short token"),
        )
        // Non-ASCII tokens survive storage validation but cannot be placed in an
        // OkHttp Authorization header, so they must fail closed before any request.
        assertEquals(
            AdaptarrConnectionTestResult.InvalidSettings,
            client.testConnection("http://adaptarr.local", "\uD83D\uDE00".repeat(32)),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `connection test maps auth availability protocol response and transport failures safely`() = runTest {
        suspend fun resultFor(secondStatus: HttpStatusCode): AdaptarrConnectionTestResult {
            var calls = 0
            return testClient {
                calls += 1
                if (calls == 1) jsonResponse(HEALTH)
                else respond("", secondStatus, headersOf(HttpHeaders.CacheControl, "no-store"))
            }.testConnection("https://adaptarr.local", token)
        }

        assertEquals(AdaptarrConnectionTestResult.Unauthorized, resultFor(HttpStatusCode.Unauthorized))
        assertEquals(AdaptarrConnectionTestResult.Unauthorized, resultFor(HttpStatusCode.Forbidden))
        assertEquals(AdaptarrConnectionTestResult.RateLimited, resultFor(HttpStatusCode.TooManyRequests))
        assertEquals(AdaptarrConnectionTestResult.ServiceUnavailable, resultFor(HttpStatusCode.ServiceUnavailable))

        val incompatible = testClient { jsonResponse("""{"status":"ok","protocol_version":2}""") }
        assertEquals(
            AdaptarrConnectionTestResult.IncompatibleProtocol,
            incompatible.testConnection("https://adaptarr.local", token),
        )

        var malformedCalls = 0
        val malformed = testClient {
            malformedCalls += 1
            if (malformedCalls == 1) jsonResponse(HEALTH) else jsonResponse("{}", noStore = true)
        }
        assertEquals(
            AdaptarrConnectionTestResult.InvalidResponse,
            malformed.testConnection("https://adaptarr.local", token),
        )

        val unavailable = AdaptarrClient.createForTest(MockEngine { throw IOException("secret endpoint failure") })
        assertEquals(
            AdaptarrConnectionTestResult.Unreachable,
            unavailable.testConnection("https://adaptarr.local/private", token),
        )
    }

    @Test
    fun `redirect is never followed and bearer token is not sent to redirect target`() = runTest {
        var calls = 0
        val client = testClient {
            calls += 1
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://evil.invalid/collect"),
            )
        }

        assertEquals(
            AdaptarrConnectionTestResult.InvalidResponse,
            client.testConnection("https://adaptarr.local", token),
        )
        assertEquals(1, calls)
    }

    @Test
    fun `oversized JSON response fails closed`() = runTest {
        val client = testClient { jsonResponse(" ".repeat(262_145)) }
        assertClientError<AdaptarrClientException.InvalidResponse> {
            client.health("https://adaptarr.local")
        }
    }

    @Test
    fun `dishonest JSON content length fails closed`() = runTest {
        val client = testClient {
            respond(
                content = HEALTH,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ContentLength to listOf("999999"),
                ),
            )
        }
        assertClientError<AdaptarrClientException.InvalidResponse> {
            client.health("https://adaptarr.local")
        }
    }

    @Test
    fun `malformed JSON primitive type fails closed`() = runTest {
        val client = testClient { jsonResponse("""{"status":"ok","protocol_version":"1"}""") }
        assertClientError<AdaptarrClientException.InvalidResponse> {
            client.health("https://adaptarr.local")
        }
    }

    @Test
    fun `authenticated protocol methods emit exact paths auth and JSON bodies`() = runTest {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val client = testClient { request ->
            paths += request.url.encodedPath + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            if (request.method.value == "POST") {
                bodies += request.body.toByteArray().decodeToString()
            }
            when (request.url.encodedPath) {
                "/v1/probe/download" -> respond(
                    content = ByteArray(65_536) { (it % 251).toByte() },
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.OctetStream.toString()),
                        HttpHeaders.ContentLength to listOf("65536"),
                        HttpHeaders.CacheControl to listOf("no-store"),
                    ),
                )
                "/v1/probe/report" -> jsonResponse(OBSERVATION, noStore = true)
                "/v1/telemetry/report", "/v1/telemetry/summary" -> jsonResponse(AGGREGATE, noStore = true)
                "/v1/recommendation" -> jsonResponse(RECOMMENDATION, noStore = true)
                else -> error("unexpected route")
            }
        }

        val probe = client.downloadProbe("https://adaptarr.local", token, 65_536)
        assertEquals(65_536, probe.size)
        assertArrayEquals(ByteArray(32) { (it % 251).toByte() }, probe.copyOf(32))
        client.reportProbe("https://adaptarr.local", token, AdaptarrProbeReport(65_536, 1_000, 10))
        client.reportTelemetry(
            "https://adaptarr.local",
            token,
            AdaptarrTelemetryReport(key, 65_536, 1_000, 10),
        )
        client.telemetrySummary("https://adaptarr.local", token, AdaptarrTelemetryLookup(key))
        client.recommendation("https://adaptarr.local", token, AdaptarrRecommendationRequest(key, 1080))

        assertEquals(
            listOf(
                "/v1/probe/download?size_bytes=65536",
                "/v1/probe/report",
                "/v1/telemetry/report",
                "/v1/telemetry/summary",
                "/v1/recommendation",
            ),
            paths,
        )
        assertEquals(
            listOf(
                """{"bytes_transferred":65536,"duration_ms":1000,"latency_ms":10}""",
                """{"network_key":"$key","bytes_transferred":65536,"duration_ms":1000,"latency_ms":10}""",
                """{"network_key":"$key"}""",
                """{"network_key":"$key","max_height":1080}""",
            ),
            bodies,
        )
    }

    @Test
    fun `probe download requires exact bounded length and content type`() = runTest {
        val wrongLength = testClient {
            respond(
                content = ByteArray(65_535),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.OctetStream.toString()),
                    HttpHeaders.ContentLength to listOf("65535"),
                    HttpHeaders.CacheControl to listOf("no-store"),
                ),
            )
        }
        assertClientError<AdaptarrClientException.InvalidResponse> {
            wrongLength.downloadProbe("https://adaptarr.local", token, 65_536)
        }

        val wrongType = testClient {
            respond(
                content = ByteArray(65_536),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                    HttpHeaders.ContentLength to listOf("65536"),
                    HttpHeaders.CacheControl to listOf("no-store"),
                ),
            )
        }
        assertClientError<AdaptarrClientException.InvalidResponse> {
            wrongType.downloadProbe("https://adaptarr.local", token, 65_536)
        }
    }

    @Test
    fun `coroutine cancellation propagates without conversion`() = runTest {
        val client = AdaptarrClient.createForTest(MockEngine { throw CancellationException("cancel") })
        try {
            client.health("https://adaptarr.local")
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private fun testClient(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): AdaptarrClient =
        AdaptarrClient.createForTest(MockEngine(handler))

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(
        content: String,
        noStore: Boolean = false,
    ) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(
            *buildList {
                add(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
                add(HttpHeaders.ContentLength to listOf(content.encodeToByteArray().size.toString()))
                if (noStore) add(HttpHeaders.CacheControl to listOf("no-store"))
            }.toTypedArray(),
        ),
    )

    private suspend inline fun <reified T : AdaptarrClientException> assertClientError(
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName}")
        } catch (error: AdaptarrClientException) {
            assertTrue("Expected ${T::class.simpleName}, got ${error::class.simpleName}", error is T)
            assertFalse(error.toString().contains(token))
            assertFalse(error.toString().contains("adaptarr.local"))
        }
    }

    private companion object {
        const val HEALTH = """{"status":"ok","protocol_version":1}"""
        const val CONFIG = """{"schema_version":1,"protocol_version":1,"generation_id":"12345678-1234-4abc-8def-1234567890ab","generated_at":"2026-07-23T01:02:03Z","profiles":{"1080p":{"id":1001,"name":"Adaptarr 1080p Passthrough","width":1920,"height":1080,"mode":"passthrough","estimated_bitrate_bps":12000000,"minimum_throughput_bps":12000000}}}"""
        const val OBSERVATION = """{"status":"observed","bytes_transferred":65536,"duration_ms":1000,"latency_ms":10,"throughput_bps":524288,"observed_at":"2026-07-23T01:02:03.456789Z"}"""
        const val AGGREGATE = """{"status":"aggregated","sample_count":3,"conservative_throughput_bps":524288,"median_latency_ms":10,"confidence":"medium","last_observed_at":"2026-07-23T01:02:03.456789Z","expires_at":"2026-07-23T01:07:03.456789Z"}"""
        const val RECOMMENDATION = """{"status":"recommended","dry_run":true,"profile":{"output_profile_id":1002,"name":"Adaptarr 720p Passthrough","height":720,"minimum_throughput_bps":480000},"sample_count":3,"conservative_throughput_bps":524288,"confidence":"medium","reason":"threshold_met"}"""
    }
}
