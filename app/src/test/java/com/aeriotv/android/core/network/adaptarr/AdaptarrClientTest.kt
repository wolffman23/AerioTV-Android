package com.aeriotv.android.core.network.adaptarr

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdaptarrClientTest {
    private val token = "t".repeat(32)

    @Test
    fun `test connection checks health then authenticated config under base path`() = runTest {
        val paths = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        val client = client { request ->
            paths += request.url.encodedPath
            auth += request.headers[HttpHeaders.Authorization]
            when (request.url.encodedPath) {
                "/proxy/health" -> json(HEALTH)
                "/proxy/v1/config" -> json(CONFIG, noStore = true)
                else -> error("unexpected request")
            }
        }

        val result = client.testConnection("https://adaptarr.example.test/proxy/", token)

        assertEquals(AdaptarrConnectionTestResult.Connected, result)
        assertEquals(listOf("/proxy/health", "/proxy/v1/config"), paths)
        assertEquals(listOf(null, "Bearer $token"), auth)
        assertFalse(result.toString().contains(token))
    }

    @Test
    fun `test connection rejects invalid settings before network`() = runTest {
        var calls = 0
        val client = client { calls += 1; json(HEALTH) }

        assertEquals(
            AdaptarrConnectionTestResult.InvalidSettings,
            client.testConnection("ftp://user:pass@example.test?q=x", token),
        )
        assertEquals(
            AdaptarrConnectionTestResult.InvalidSettings,
            client.testConnection("https://adaptarr.example.test", "short token"),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `test connection maps fixed safe failures and never follows redirects`() = runTest {
        suspend fun resultFor(status: HttpStatusCode): AdaptarrConnectionTestResult {
            var calls = 0
            return client {
                calls += 1
                if (calls == 1) json(HEALTH) else respond("", status)
            }.testConnection("https://adaptarr.example.test", token)
        }

        assertEquals(AdaptarrConnectionTestResult.Unauthorized, resultFor(HttpStatusCode.Unauthorized))
        assertEquals(AdaptarrConnectionTestResult.RateLimited, resultFor(HttpStatusCode.TooManyRequests))
        assertEquals(AdaptarrConnectionTestResult.ServiceUnavailable, resultFor(HttpStatusCode.ServiceUnavailable))
        assertEquals(
            AdaptarrConnectionTestResult.IncompatibleProtocol,
            client { json("""{"status":"ok","protocol_version":2}""") }
                .testConnection("https://adaptarr.example.test", token),
        )
        assertEquals(
            AdaptarrConnectionTestResult.InvalidResponse,
            client { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.test")) }
                .testConnection("https://adaptarr.example.test", token),
        )
        assertEquals(
            AdaptarrConnectionTestResult.Unreachable,
            AdaptarrClient.createForTest(MockEngine { throw IOException("private endpoint") })
                .testConnection("https://adaptarr.example.test", token),
        )
    }

    @Test
    fun `oversized response fails closed`() = runTest {
        val client = client { json(" ".repeat(262_145)) }

        assertEquals(
            AdaptarrConnectionTestResult.InvalidResponse,
            client.testConnection("https://adaptarr.example.test", token),
        )
    }

    private fun client(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = AdaptarrClient.createForTest(MockEngine(handler))

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.json(content: String, noStore: Boolean = false) =
        respond(
            content,
            HttpStatusCode.OK,
            headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                HttpHeaders.ContentLength to listOf(content.encodeToByteArray().size.toString()),
                *(if (noStore) arrayOf(HttpHeaders.CacheControl to listOf("no-store")) else emptyArray()),
            ),
        )

    private companion object {
        const val HEALTH = """{"status":"ok","protocol_version":1}"""
        const val CONFIG = """{"schema_version":1,"protocol_version":1,"generation_id":"12345678-1234-4abc-8def-1234567890ab","generated_at":"2026-07-23T01:02:03Z","profiles":{"720p":{"id":8,"name":"720p","width":1280,"height":720,"mode":"transcode","estimated_bitrate_bps":2000000,"minimum_throughput_bps":2500000}}}"""
    }
}
