package com.aeriotv.android.core.network.adaptarr

import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

internal enum class AdaptiveTransport {
    Wifi,
    Ethernet,
    Cellular,
    Other,
    None,
}

internal data class AdaptiveNetworkIdentity(
    val transport: AdaptiveTransport,
    val metered: Boolean,
    val vpn: Boolean,
)

/** Opaque process-local network key; the Adaptarr server cannot enumerate its inputs. */
internal object AdaptiveNetworkKey {
    fun derive(
        identity: AdaptiveNetworkIdentity,
        adaptarrBaseUrl: String,
        processSecret: ByteArray,
    ): String {
        require(processSecret.size >= 32) { "network key secret too short" }
        val canonical = buildString {
            append("adaptarr-network-v1\n")
            append(identity.transport.name.lowercase())
            append('\n')
            append(if (identity.metered) '1' else '0')
            append('\n')
            append(if (identity.vpn) '1' else '0')
            append('\n')
            append(adaptarrBaseUrl)
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(processSecret, "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

internal data class AdaptiveProbeMeasurement(
    val networkKey: String,
    val measuredThroughputBps: Long,
    val sampleCount: Int,
    val confidence: AdaptarrTelemetryConfidence,
    val measuredAtElapsedRealtimeMs: Long,
    /** Raw bounded probe inputs; process-memory only and never persisted. */
    val bytesTransferred: Long = 1_048_576L,
    val durationMs: Int = 1,
    /** The download probe does not measure latency; zero is the protocol's no-sample value. */
    val latencyMs: Int = 0,
)

internal enum class AdaptiveProbeSource {
    Fresh,
    Cached,
}

internal sealed interface AdaptiveProbeResult {
    data class Success(
        val measurement: AdaptiveProbeMeasurement,
        val source: AdaptiveProbeSource,
    ) : AdaptiveProbeResult

    data object Timeout : AdaptiveProbeResult
    data object Unavailable : AdaptiveProbeResult
    data object Stale : AdaptiveProbeResult
}

private class ProbeInvalidatedCancellation : CancellationException("network changed")

/**
 * Bounded process-local cache for network-keyed probe measurements.
 *
 * All map/generation mutations use one non-suspending monitor so Android network
 * callbacks can invalidate synchronously before returning. Invalidation cancels old
 * work; callers observe [AdaptiveProbeResult.Stale]. Caller cancellation does not
 * cancel a shared Deferred because the Deferred belongs to the process scope.
 */
internal class AdaptiveProbeCache(
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val processSecret: ByteArray,
    private val execute: (suspend (networkKey: String) -> AdaptiveProbeMeasurement)? = null,
    private val ttlMs: Long = CACHE_TTL_MS,
    private val timeoutMs: Long = FIRST_PROBE_TIMEOUT_MS,
    private val maxCacheEntries: Int = MAX_CACHE_ENTRIES,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
) {
    private data class CacheKey(
        val identity: AdaptiveNetworkIdentity,
        val baseUrl: String,
    )

    private data class EpochKey(
        val cacheKey: CacheKey,
        val generation: Long,
    )

    private data class CacheEntry(
        val measurement: AdaptiveProbeMeasurement,
        val expiresAtElapsedMs: Long,
    )

    private val lock = Any()
    private val cache = LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<EpochKey, Deferred<AdaptiveProbeResult>>()
    private var generation = 0L

    init {
        require(processSecret.size >= 32) { "network key secret too short" }
        require(ttlMs > 0 && timeoutMs > 0) { "invalid cache timing" }
        require(maxCacheEntries > 0 && maxInFlight > 0) { "invalid cache capacity" }
    }

    suspend fun get(identity: AdaptiveNetworkIdentity, baseUrl: String): AdaptiveProbeResult =
        get(
            identity,
            baseUrl,
            generationToken(),
            requireNotNull(execute) { "probe executor required" },
        )

    suspend fun get(
        identity: AdaptiveNetworkIdentity,
        baseUrl: String,
        operation: suspend (networkKey: String) -> AdaptiveProbeMeasurement,
    ): AdaptiveProbeResult = get(identity, baseUrl, generationToken(), operation)

    suspend fun getFresh(identity: AdaptiveNetworkIdentity, baseUrl: String): AdaptiveProbeResult =
        get(
            identity,
            baseUrl,
            generationToken(),
            requireNotNull(execute) { "probe executor required" },
            useCachedResult = false,
        )

    suspend fun getFresh(
        identity: AdaptiveNetworkIdentity,
        baseUrl: String,
        expectedGeneration: Long,
        operation: suspend (networkKey: String) -> AdaptiveProbeMeasurement,
    ): AdaptiveProbeResult = get(
        identity,
        baseUrl,
        expectedGeneration,
        operation,
        useCachedResult = false,
    )

    suspend fun get(
        identity: AdaptiveNetworkIdentity,
        baseUrl: String,
        expectedGeneration: Long,
        operation: suspend (networkKey: String) -> AdaptiveProbeMeasurement,
        useCachedResult: Boolean = true,
    ): AdaptiveProbeResult {
        val cacheKey = CacheKey(identity, baseUrl)
        val deferred: Deferred<AdaptiveProbeResult> = synchronized(lock) {
            if (expectedGeneration != generation) return AdaptiveProbeResult.Stale
            val now = elapsedRealtimeMs()
            pruneExpired(now)
            if (useCachedResult) {
                cache[cacheKey]?.let { entry ->
                    if (now < entry.expiresAtElapsedMs) {
                        return AdaptiveProbeResult.Success(entry.measurement, AdaptiveProbeSource.Cached)
                    }
                    cache.remove(cacheKey)
                }
            }

            val epochKey = EpochKey(cacheKey, generation)
            inFlight[epochKey]?.let { return@synchronized it }
            if (inFlight.size >= maxInFlight) return AdaptiveProbeResult.Unavailable

            scope.async(start = CoroutineStart.LAZY) {
                perform(epochKey, operation)
            }.also { created ->
                inFlight[epochKey] = created
                created.start()
            }
        }
        return try {
            deferred.await()
        } catch (_: ProbeInvalidatedCancellation) {
            AdaptiveProbeResult.Stale
        }
    }

    fun generationToken(): Long = synchronized(lock) { generation }

    /** Called directly at Android network-callback entry; completes synchronously. */
    fun invalidateNow() {
        val oldWork = synchronized(lock) {
            generation += 1
            cache.clear()
            inFlight.values.toList().also { inFlight.clear() }
        }
        oldWork.forEach { it.cancel(ProbeInvalidatedCancellation()) }
    }

    internal fun entryCountForTest(): Int = synchronized(lock) { cache.size }
    internal fun inFlightCountForTest(): Int = synchronized(lock) { inFlight.size }

    private suspend fun perform(
        epochKey: EpochKey,
        operation: suspend (networkKey: String) -> AdaptiveProbeMeasurement,
    ): AdaptiveProbeResult {
        val networkKey = AdaptiveNetworkKey.derive(
            epochKey.cacheKey.identity,
            epochKey.cacheKey.baseUrl,
            processSecret,
        )
        val measured = try {
            withTimeout(timeoutMs) { operation(networkKey) }
        } catch (_: TimeoutCancellationException) {
            removeInFlight(epochKey)
            return AdaptiveProbeResult.Timeout
        } catch (cancelled: CancellationException) {
            removeInFlight(epochKey)
            throw cancelled
        } catch (_: Exception) {
            removeInFlight(epochKey)
            return AdaptiveProbeResult.Unavailable
        }

        return synchronized(lock) {
            inFlight.remove(epochKey)
            if (epochKey.generation != generation) return@synchronized AdaptiveProbeResult.Stale
            if (!isValid(measured, networkKey)) return@synchronized AdaptiveProbeResult.Unavailable

            cache[epochKey.cacheKey] = CacheEntry(
                measurement = measured,
                expiresAtElapsedMs = saturatingAdd(elapsedRealtimeMs(), ttlMs),
            )
            trimToCapacity()
            AdaptiveProbeResult.Success(measured, AdaptiveProbeSource.Fresh)
        }
    }

    private fun removeInFlight(epochKey: EpochKey) {
        synchronized(lock) { inFlight.remove(epochKey) }
    }

    private fun pruneExpired(now: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (now >= iterator.next().value.expiresAtElapsedMs) iterator.remove()
        }
    }

    private fun trimToCapacity() {
        while (cache.size > maxCacheEntries) {
            val eldest = cache.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    private fun isValid(measurement: AdaptiveProbeMeasurement, expectedKey: String): Boolean =
        measurement.networkKey == expectedKey &&
            measurement.measuredThroughputBps > 0 &&
            measurement.sampleCount in 1..20 &&
            measurement.measuredAtElapsedRealtimeMs >= 0

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1_000L
        const val FIRST_PROBE_TIMEOUT_MS = 5_000L
        const val MAX_CACHE_ENTRIES = 16
        const val MAX_IN_FLIGHT = 4
    }
}
