package com.aeriotv.android.core.network.adaptarr

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.preferences.normalizeAdaptarrBaseUrl
import com.aeriotv.android.core.preferences.normalizeAdaptarrToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local entry point for bounded, network-keyed Adaptarr measurements.
 *
 * This coordinator is intentionally not wired to playback or remote telemetry yet.
 * It observes only transport class, metered state, VPN presence, and Adaptarr base
 * URL—never SSID/location—and invalidates synchronously on network callbacks.
 */
@Singleton
class AdaptiveProbeCoordinator private constructor(
    @ApplicationContext context: Context,
    private val client: AdaptarrClient,
    preferences: AppPreferences,
    private val elapsedRealtimeMs: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        client: AdaptarrClient,
        preferences: AppPreferences,
    ) : this(context, client, preferences, SystemClock::elapsedRealtime)

    companion object {
        internal fun forTesting(
            context: Context,
            client: AdaptarrClient,
            preferences: AppPreferences,
            elapsedRealtimeMs: () -> Long,
        ) = AdaptiveProbeCoordinator(context, client, preferences, elapsedRealtimeMs)
    }
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processSecret = ByteArray(32).also(SecureRandom()::nextBytes)
    private val persistence = LatestProbePersistence(
        preferences::setAdaptarrLastMeasuredThroughputBps,
    )
    private val runner = AdaptiveProbeRunner(
        transport = AdaptiveProbeTransport(client::downloadProbe),
        elapsedRealtimeMs = elapsedRealtimeMs,
    )
    private val cache = AdaptiveProbeCache(
        scope = scope,
        elapsedRealtimeMs = elapsedRealtimeMs,
        processSecret = processSecret,
    )
    private val networkChangeTracker = AdaptiveNetworkChangeTracker(currentIdentity())

    /** Privacy-safe identity of the current default network for local policy decisions. */
    internal val defaultNetworkIdentity: StateFlow<AdaptiveNetworkIdentity> = networkChangeTracker.identity

    // Process-lifetime registration: this object and its SupervisorJob are singletons.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkChangeTracker.update(currentIdentity())
            cache.invalidateNow()
        }

        override fun onLost(network: Network) {
            networkChangeTracker.update(currentIdentity())
            cache.invalidateNow()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            if (networkChangeTracker.update(networkCapabilities.toAdaptiveNetworkIdentity())) {
                cache.invalidateNow()
            }
        }
    }

    init {
        runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
    }

    internal suspend fun probe(baseUrl: String, token: String): AdaptiveProbeResult {
        val normalizedBase = normalizeAdaptarrBaseUrl(baseUrl)
            ?.takeIf { it.isNotEmpty() }
            ?: return AdaptiveProbeResult.Unavailable
        val normalizedToken = normalizeAdaptarrToken(token)
            ?.takeIf { it.isNotEmpty() }
            ?: return AdaptiveProbeResult.Unavailable

        // Capture generation before capabilities. A callback anywhere during/after
        // this snapshot changes the generation, causing get() to return Stale.
        val generation = cache.generationToken()
        val identity = currentIdentity()
        val result = cache.get(
            identity = identity,
            baseUrl = normalizedBase,
            expectedGeneration = generation,
            operation = { networkKey ->
                runner.execute(normalizedBase, normalizedToken, networkKey)
            },
        )
        if (result is AdaptiveProbeResult.Success && result.source == AdaptiveProbeSource.Fresh) {
            persistence.persist(result.measurement)
        }
        return result
    }

    /**
     * Fresh, process-only measurement for an eligible automatic playback session.
     *
     * This bypasses completed cache entries and intentionally does not persist the
     * measurement or contact telemetry/recommendation endpoints. A network change
     * after the generation snapshot resolves to [AdaptiveProbeResult.Stale].
     */
    internal suspend fun probeForAutomaticSession(
        baseUrl: String,
        token: String,
    ): AdaptiveProbeResult {
        val normalizedBase = normalizeAdaptarrBaseUrl(baseUrl)
            ?.takeIf { it.isNotEmpty() }
            ?: return AdaptiveProbeResult.Unavailable
        val normalizedToken = normalizeAdaptarrToken(token)
            ?.takeIf { it.isNotEmpty() }
            ?: return AdaptiveProbeResult.Unavailable
        val generation = cache.generationToken()
        val identity = defaultNetworkIdentity.value
        return cache.getFresh(
            identity = identity,
            baseUrl = normalizedBase,
            expectedGeneration = generation,
            operation = { networkKey ->
                runner.execute(normalizedBase, normalizedToken, networkKey)
            },
        )
    }

    internal fun invalidate() = cache.invalidateNow()

    private fun currentIdentity(): AdaptiveNetworkIdentity {
        val capabilities = runCatching {
            connectivityManager?.let { manager ->
                manager.getNetworkCapabilities(manager.activeNetwork)
            }
        }.getOrNull()
        return capabilities?.toAdaptiveNetworkIdentity()
            ?: AdaptiveNetworkIdentity(AdaptiveTransport.None, metered = true, vpn = false)
    }
}

private fun NetworkCapabilities.toAdaptiveNetworkIdentity(): AdaptiveNetworkIdentity {
    val transport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> AdaptiveTransport.Ethernet
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> AdaptiveTransport.Wifi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> AdaptiveTransport.Cellular
        else -> AdaptiveTransport.Other
    }
    return AdaptiveNetworkIdentity(
        transport = transport,
        metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        vpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
    )
}

/** Tracks only the privacy-safe identity fields that partition the probe cache. */
internal class AdaptiveNetworkChangeTracker(initialIdentity: AdaptiveNetworkIdentity) {
    private val mutableIdentity = MutableStateFlow(initialIdentity)
    val identity: StateFlow<AdaptiveNetworkIdentity> = mutableIdentity.asStateFlow()

    @Synchronized
    fun update(nextIdentity: AdaptiveNetworkIdentity): Boolean {
        if (nextIdentity == mutableIdentity.value) return false
        mutableIdentity.value = nextIdentity
        return true
    }
}
