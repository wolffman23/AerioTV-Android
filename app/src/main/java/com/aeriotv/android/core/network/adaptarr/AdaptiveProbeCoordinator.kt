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

/**
 * Process-local entry point for bounded, network-keyed Adaptarr measurements.
 *
 * This coordinator is intentionally not wired to playback or remote telemetry yet.
 * It observes only transport class, metered state, VPN presence, and Adaptarr base
 * URL—never SSID/location—and invalidates synchronously on network callbacks.
 */
@Singleton
class AdaptiveProbeCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val client: AdaptarrClient,
    preferences: AppPreferences,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processSecret = ByteArray(32).also(SecureRandom()::nextBytes)
    private val persistence = LatestProbePersistence(
        preferences::setAdaptarrLastMeasuredThroughputBps,
    )
    private val runner = AdaptiveProbeRunner(
        transport = AdaptiveProbeTransport(client::downloadProbe),
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
    )
    private val cache = AdaptiveProbeCache(
        scope = scope,
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        processSecret = processSecret,
    )
    private val networkChangeTracker = AdaptiveNetworkChangeTracker(currentIdentity())

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
        val result = cache.get(identity, normalizedBase, generation) { networkKey ->
            runner.execute(normalizedBase, normalizedToken, networkKey)
        }
        if (result is AdaptiveProbeResult.Success && result.source == AdaptiveProbeSource.Fresh) {
            persistence.persist(result.measurement)
        }
        return result
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
    private var identity = initialIdentity

    @Synchronized
    fun update(nextIdentity: AdaptiveNetworkIdentity): Boolean {
        if (nextIdentity == identity) return false
        identity = nextIdentity
        return true
    }
}
