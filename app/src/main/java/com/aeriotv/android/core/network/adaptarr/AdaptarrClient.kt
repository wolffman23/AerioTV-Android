package com.aeriotv.android.core.network.adaptarr

import com.aeriotv.android.core.preferences.normalizeAdaptarrBaseUrl
import com.aeriotv.android.core.preferences.normalizeAdaptarrToken
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.UnresolvedAddressException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal sealed class AdaptarrClientException(message: String) : Exception(message) {
    class InvalidSettings : AdaptarrClientException("Invalid Adaptarr settings")
    class Unauthorized : AdaptarrClientException("Adaptarr authentication failed")
    class IncompatibleProtocol : AdaptarrClientException("Unsupported Adaptarr protocol")
    class RateLimited : AdaptarrClientException("Adaptarr is busy")
    class ServiceUnavailable : AdaptarrClientException("Adaptarr is unavailable")
    class InvalidResponse : AdaptarrClientException("Invalid Adaptarr response")
    class Transport : AdaptarrClientException("Could not reach Adaptarr")
}

internal enum class AdaptarrConnectionTestResult {
    Connected,
    InvalidSettings,
    Unauthorized,
    IncompatibleProtocol,
    RateLimited,
    ServiceUnavailable,
    InvalidResponse,
    Unreachable,
}

@Singleton
class AdaptarrClient private constructor(
    private val httpClient: HttpClient,
) {
    @Inject
    constructor() : this(createHttpClient())

    internal suspend fun testConnection(baseUrl: String, token: String): AdaptarrConnectionTestResult {
        val normalizedBase = normalizeBase(baseUrl) ?: return AdaptarrConnectionTestResult.InvalidSettings
        val normalizedToken = normalizeToken(token) ?: return AdaptarrConnectionTestResult.InvalidSettings
        return try {
            healthNormalized(normalizedBase)
            configurationNormalized(normalizedBase, normalizedToken)
            AdaptarrConnectionTestResult.Connected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AdaptarrClientException.InvalidSettings) {
            AdaptarrConnectionTestResult.InvalidSettings
        } catch (_: AdaptarrClientException.Unauthorized) {
            AdaptarrConnectionTestResult.Unauthorized
        } catch (_: AdaptarrClientException.IncompatibleProtocol) {
            AdaptarrConnectionTestResult.IncompatibleProtocol
        } catch (_: AdaptarrClientException.RateLimited) {
            AdaptarrConnectionTestResult.RateLimited
        } catch (_: AdaptarrClientException.ServiceUnavailable) {
            AdaptarrConnectionTestResult.ServiceUnavailable
        } catch (_: AdaptarrClientException.InvalidResponse) {
            AdaptarrConnectionTestResult.InvalidResponse
        } catch (_: AdaptarrClientException.Transport) {
            AdaptarrConnectionTestResult.Unreachable
        }
    }

    internal suspend fun health(baseUrl: String): AdaptarrHealthResponse =
        healthNormalized(requireBase(baseUrl))

    internal suspend fun configuration(baseUrl: String, token: String): AdaptarrConfigResponse =
        configurationNormalized(requireBase(baseUrl), requireToken(token))

    internal suspend fun downloadProbe(baseUrl: String, token: String, sizeBytes: Int): ByteArray {
        require(sizeBytes in MIN_PROBE_BYTES..MAX_PROBE_BYTES) { "invalid probe size" }
        val base = requireBase(baseUrl)
        val credential = requireToken(token)
        return safeTransport {
            val response = httpClient.get(endpoint(base, "/v1/probe/download")) {
                authenticated(credential)
                accept(ContentType.Application.OctetStream)
                parameter("size_bytes", sizeBytes)
            }
            requireSuccess(response)
            try {
                requireNoStore(response)
                requireContentType(response, ContentType.Application.OctetStream)
                val declared = strictContentLength(response)
                if (declared != sizeBytes.toLong()) reject(response)
                val bytes = readBounded(response, sizeBytes)
                if (bytes.size != sizeBytes) throw AdaptarrClientException.InvalidResponse()
                bytes
            } catch (cancelled: CancellationException) {
                runCatching { response.bodyAsChannel().cancel(cancelled) }
                throw cancelled
            } catch (failure: Throwable) {
                runCatching { response.bodyAsChannel().cancel(null) }
                throw failure
            }
        }
    }

    internal suspend fun reportProbe(
        baseUrl: String,
        token: String,
        report: AdaptarrProbeReport,
    ): AdaptarrProbeObservation = postJson(
        requireBase(baseUrl),
        requireToken(token),
        "/v1/probe/report",
        adaptarrJson.encodeToString(report),
        AdaptarrProbeObservation.serializer(),
    )

    internal suspend fun reportTelemetry(
        baseUrl: String,
        token: String,
        report: AdaptarrTelemetryReport,
    ): AdaptarrTelemetryAggregate = postJson(
        requireBase(baseUrl),
        requireToken(token),
        "/v1/telemetry/report",
        adaptarrJson.encodeToString(report),
        AdaptarrTelemetryAggregate.serializer(),
    )

    internal suspend fun telemetrySummary(
        baseUrl: String,
        token: String,
        lookup: AdaptarrTelemetryLookup,
    ): AdaptarrTelemetryAggregate = postJson(
        requireBase(baseUrl),
        requireToken(token),
        "/v1/telemetry/summary",
        adaptarrJson.encodeToString(lookup),
        AdaptarrTelemetryAggregate.serializer(),
    )

    internal suspend fun recommendation(
        baseUrl: String,
        token: String,
        request: AdaptarrRecommendationRequest,
    ): AdaptarrRecommendationResponse = postJson(
        requireBase(baseUrl),
        requireToken(token),
        "/v1/recommendation",
        adaptarrJson.encodeToString(request),
        AdaptarrRecommendationResponse.serializer(),
    )

    private suspend fun healthNormalized(baseUrl: String): AdaptarrHealthResponse = safeTransport {
        val response = httpClient.get(endpoint(baseUrl, "/health")) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.CacheControl, "no-store")
        }
        requireSuccess(response)
        val payload = readJson(response, requireNoStore = false)
        requireCompatibleVersion(payload, "protocol_version")
        decode(payload, AdaptarrHealthResponse.serializer())
    }

    private suspend fun configurationNormalized(
        baseUrl: String,
        token: String,
    ): AdaptarrConfigResponse = safeTransport {
        val response = httpClient.get(endpoint(baseUrl, "/v1/config")) {
            authenticated(token)
            accept(ContentType.Application.Json)
        }
        requireSuccess(response)
        val payload = readJson(response, requireNoStore = true)
        requireCompatibleVersion(payload, "schema_version")
        requireCompatibleVersion(payload, "protocol_version")
        decode(payload, AdaptarrConfigResponse.serializer())
    }

    private suspend fun <T> postJson(
        baseUrl: String,
        token: String,
        path: String,
        body: String,
        serializer: KSerializer<T>,
    ): T = safeTransport {
        val response = httpClient.post(endpoint(baseUrl, path)) {
            authenticated(token)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        requireSuccess(response)
        decode(readJson(response, requireNoStore = true), serializer)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.CacheControl, "no-store")
    }

    private suspend fun readJson(response: HttpResponse, requireNoStore: Boolean): String {
        try {
            if (requireNoStore) requireNoStore(response)
            requireContentType(response, ContentType.Application.Json)
            return strictUtf8(readBounded(response, MAX_JSON_BYTES))
        } catch (cancelled: CancellationException) {
            runCatching { response.bodyAsChannel().cancel(cancelled) }
            throw cancelled
        } catch (failure: Throwable) {
            runCatching { response.bodyAsChannel().cancel(null) }
            throw failure
        }
    }

    private suspend fun readBounded(response: HttpResponse, maxBytes: Int): ByteArray {
        val declared = strictContentLength(response)
        if (declared != null && declared > maxBytes.toLong()) reject(response)
        val channel = response.bodyAsChannel()
        val bytes = try {
            channel.readRemaining((maxBytes + 1).toLong()).readByteArray()
        } catch (cancelled: CancellationException) {
            runCatching { channel.cancel(cancelled) }
            throw cancelled
        } catch (_: Exception) {
            runCatching { channel.cancel(null) }
            throw AdaptarrClientException.InvalidResponse()
        }
        if (bytes.size > maxBytes) {
            runCatching { channel.cancel(null) }
            throw AdaptarrClientException.InvalidResponse()
        }
        if (declared != null && declared != bytes.size.toLong()) {
            runCatching { channel.cancel(null) }
            throw AdaptarrClientException.InvalidResponse()
        }
        return bytes
    }

    private fun strictContentLength(response: HttpResponse): Long? {
        val values = response.headers.getAll(HttpHeaders.ContentLength) ?: return null
        if (values.size != 1) throw AdaptarrClientException.InvalidResponse()
        return values.single().takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it >= 0L }
            ?: throw AdaptarrClientException.InvalidResponse()
    }

    private fun requireContentType(response: HttpResponse, expected: ContentType) {
        val actual = response.contentType() ?: throw AdaptarrClientException.InvalidResponse()
        if (!actual.match(expected)) throw AdaptarrClientException.InvalidResponse()
        val charset = actual.charset()
        if (charset != null && charset != StandardCharsets.UTF_8) {
            throw AdaptarrClientException.InvalidResponse()
        }
    }

    private fun requireNoStore(response: HttpResponse) {
        val values = response.headers.getAll(HttpHeaders.CacheControl)
            ?: throw AdaptarrClientException.InvalidResponse()
        if (values.size != 1 || values.single().trim().lowercase() != "no-store") {
            throw AdaptarrClientException.InvalidResponse()
        }
    }

    private suspend fun requireSuccess(response: HttpResponse) {
        if (response.status.isSuccess()) return
        runCatching { response.bodyAsChannel().cancel(null) }
        throw when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> AdaptarrClientException.Unauthorized()
            HttpStatusCode.TooManyRequests -> AdaptarrClientException.RateLimited()
            HttpStatusCode.ServiceUnavailable -> AdaptarrClientException.ServiceUnavailable()
            else -> AdaptarrClientException.InvalidResponse()
        }
    }

    private suspend fun reject(response: HttpResponse): Nothing {
        runCatching { response.bodyAsChannel().cancel(null) }
        throw AdaptarrClientException.InvalidResponse()
    }

    private fun requireCompatibleVersion(payload: String, field: String) {
        val root = runCatching { adaptarrJson.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw AdaptarrClientException.InvalidResponse() }
        val primitive = root[field] as? JsonPrimitive
            ?: throw AdaptarrClientException.InvalidResponse()
        if (primitive.isString || primitive.intOrNull == null) {
            throw AdaptarrClientException.InvalidResponse()
        }
        if (primitive.intOrNull != 1) throw AdaptarrClientException.IncompatibleProtocol()
    }

    private fun <T> decode(payload: String, serializer: KSerializer<T>): T = try {
        adaptarrJson.decodeFromString(serializer, payload)
    } catch (_: SerializationException) {
        throw AdaptarrClientException.InvalidResponse()
    } catch (_: IllegalArgumentException) {
        throw AdaptarrClientException.InvalidResponse()
    }

    private suspend fun <T> safeTransport(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: AdaptarrClientException) {
        throw known
    } catch (_: HttpRequestTimeoutException) {
        throw AdaptarrClientException.Transport()
    } catch (_: ConnectTimeoutException) {
        throw AdaptarrClientException.Transport()
    } catch (_: UnresolvedAddressException) {
        throw AdaptarrClientException.Transport()
    } catch (_: IOException) {
        throw AdaptarrClientException.Transport()
    } catch (_: Exception) {
        throw AdaptarrClientException.InvalidResponse()
    }

    private fun requireBase(raw: String): String =
        normalizeBase(raw) ?: throw AdaptarrClientException.InvalidSettings()

    private fun requireToken(raw: String): String =
        normalizeToken(raw) ?: throw AdaptarrClientException.InvalidSettings()

    private fun normalizeBase(raw: String): String? =
        normalizeAdaptarrBaseUrl(raw)?.takeIf { it.isNotEmpty() }

    private fun normalizeToken(raw: String): String? =
        normalizeAdaptarrToken(raw)
            ?.takeIf { it.isNotEmpty() }
            // OkHttp's Request.Builder.addHeader rejects non-ASCII header values,
            // so a token that survives storage validation but cannot be placed in
            // an Authorization header must fail closed as invalid settings rather
            // than surface later as a misclassified transport/response error.
            ?.takeIf { token -> token.all { it.code in 0x21..0x7E } }

    private fun endpoint(baseUrl: String, path: String): String = baseUrl + path

    companion object {
        private const val MAX_JSON_BYTES = 262_144
        private const val MIN_PROBE_BYTES = 65_536
        private const val MAX_PROBE_BYTES = 4_194_304

        internal fun createForTest(engine: HttpClientEngine): AdaptarrClient =
            AdaptarrClient(createHttpClient(engine))
    }
}

private fun createHttpClient(engine: HttpClientEngine? = null): HttpClient =
    if (engine == null) {
        HttpClient(OkHttp) { configureAdaptarrClient() }
    } else {
        HttpClient(engine) { configureAdaptarrClient() }
    }

private fun HttpClientConfig<*>.configureAdaptarrClient() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}

private fun strictUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    throw AdaptarrClientException.InvalidResponse()
}
