package com.aeriotv.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.CustomCategoryEntry
import com.aeriotv.android.core.category.ProgramCategory
import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.network.adaptarr.AdaptarrClient
import com.aeriotv.android.core.network.adaptarr.AdaptarrConfigResponse
import com.aeriotv.android.core.network.adaptarr.AdaptarrConnectionTestResult
import com.aeriotv.android.core.network.adaptarr.AdaptarrDiagnostics
import com.aeriotv.android.core.network.adaptarr.AdaptarrRecommendationRequest
import com.aeriotv.android.core.network.adaptarr.AdaptarrTelemetryReport
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeCoordinator
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeMeasurement
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeResult
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeSource
import com.aeriotv.android.core.preferences.AdaptarrConnectionSaveResult
import com.aeriotv.android.core.preferences.AdaptiveQualityMode
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.ui.theme.AppTheme
import com.aeriotv.android.ui.theme.AppearanceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings sub-screens share this ViewModel for read/write access to the
 * DataStore-backed preferences. Keeps each sub-screen stateless and lets
 * AerioTVTheme observe `selectedTheme` from MainActivity at the same time.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val tmdb: TMDBService,
    private val adaptarrClient: AdaptarrClient,
    private val adaptiveProbeCoordinator: AdaptiveProbeCoordinator,
    private val adaptarrDiagnostics: AdaptarrDiagnostics,
) : ViewModel() {

    // Appearance
    val selectedTheme: Flow<AppTheme> = prefs.selectedTheme
    fun setSelectedTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.setSelectedTheme(theme) }
    }

    // Appearance mode (Dark / Light / System), orthogonal to selectedTheme.
    val appearanceMode: Flow<AppearanceMode> = prefs.appearanceMode
    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch { prefs.setAppearanceMode(mode) }
    }

    val displayScaleMovies: Flow<Float> = prefs.displayScaleMovies
    fun setDisplayScaleMovies(value: Float) {
        viewModelScope.launch { prefs.setDisplayScaleMovies(value) }
    }

    val useCustomAccent: Flow<Boolean> = prefs.useCustomAccent
    fun setUseCustomAccent(value: Boolean) {
        viewModelScope.launch { prefs.setUseCustomAccent(value) }
    }

    val showChannelLogos: Flow<Boolean> = prefs.showChannelLogos
    fun setShowChannelLogos(value: Boolean) {
        viewModelScope.launch { prefs.setShowChannelLogos(value) }
    }

    val showChannelNumbers: Flow<Boolean> = prefs.showChannelNumbers
    fun setShowChannelNumbers(value: Boolean) {
        viewModelScope.launch { prefs.setShowChannelNumbers(value) }
    }

    // EPG program badges. Per-device-type: the Settings screen passes the
    // current device's isTv so the right value is read/written and synced.
    fun showEpgBadges(isTv: Boolean): Flow<Boolean> = prefs.showEpgBadges(isTv)
    fun setShowEpgBadges(isTv: Boolean, value: Boolean) {
        viewModelScope.launch { prefs.setShowEpgBadges(isTv, value) }
    }

    val playerAspectMode: Flow<String> = prefs.playerAspectMode
    fun setPlayerAspectMode(value: String) {
        viewModelScope.launch { prefs.setPlayerAspectMode(value) }
    }

    /** Cycle Fit -> Zoom -> Fill -> Fit (iOS Issue #26 aspect toggle). */
    fun cyclePlayerAspectMode(current: String) {
        val next = when (current) {
            "fit" -> "zoom"
            "zoom" -> "fill"
            else -> "fit"
        }
        setPlayerAspectMode(next)
    }

    val customAccentHex: Flow<String> = prefs.customAccentHex
    fun setCustomAccentHex(value: String) {
        viewModelScope.launch { prefs.setCustomAccentHex(value) }
    }

    val displayScaleLiveTV: Flow<Float> = prefs.displayScaleLiveTV
    fun setDisplayScaleLiveTV(value: Float) {
        viewModelScope.launch { prefs.setDisplayScaleLiveTV(value) }
    }

    /** EPG guide timeline zoom (iOS guideScale). Written by pinch + the discrete selector. */
    val guideScale: Flow<Float> = prefs.guideScale
    fun setGuideScale(value: Float) {
        viewModelScope.launch { prefs.setGuideScale(value) }
    }

    val hiddenGroups: Flow<Set<String>> = prefs.hiddenGroups
    fun setHiddenGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenGroups(groups) }
    }

    // Live TV group ordering (Manage Groups reorder). groupSortMode is one of
    // Default / Alphabetical / Manual; groupOrder is the manual order list.
    val groupOrder: Flow<List<String>> = prefs.groupOrder
    fun setGroupOrder(order: List<String>) {
        viewModelScope.launch { prefs.setGroupOrder(order) }
    }
    val groupSortMode: Flow<String> = prefs.groupSortMode
    fun setGroupSortMode(mode: String) {
        viewModelScope.launch { prefs.setGroupSortMode(mode) }
    }

    // VOD group filters (iOS MoviesView hiddenMovieGroups / TVShowsView
    // hiddenSeriesGroups). Surfaced via ManageGroupsSheet from the On Demand
    // tab; applied in MoviesSubScreen / SeriesSubScreen to filter the lists.
    val hiddenMovieGroups: Flow<Set<String>> = prefs.hiddenMovieGroups
    fun setHiddenMovieGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenMovieGroups(groups) }
    }
    val hiddenSeriesGroups: Flow<Set<String>> = prefs.hiddenSeriesGroups
    fun setHiddenSeriesGroups(groups: Set<String>) {
        viewModelScope.launch { prefs.setHiddenSeriesGroups(groups) }
    }


    // App Behaviors
    val liveRewindEnabled: Flow<Boolean> = prefs.liveRewindEnabled
    fun setLiveRewindEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setLiveRewindEnabled(value) }
    }

    val liveRewindDepthMinutes: Flow<Int> = prefs.liveRewindDepthMinutes
    fun setLiveRewindDepthMinutes(value: Int) {
        viewModelScope.launch { prefs.setLiveRewindDepthMinutes(value) }
    }

    val liveRewindRetentionHours: Flow<Int> = prefs.liveRewindRetentionHours
    fun setLiveRewindRetentionHours(value: Int) {
        viewModelScope.launch { prefs.setLiveRewindRetentionHours(value) }
    }

    val liveRewindBudgetGB: Flow<Int> = prefs.liveRewindBudgetGB
    fun setLiveRewindBudgetGB(value: Int) {
        viewModelScope.launch { prefs.setLiveRewindBudgetGB(value) }
    }

    val skipLoadingScreen: Flow<Boolean> = prefs.skipLoadingScreen
    fun setSkipLoadingScreen(value: Boolean) {
        viewModelScope.launch { prefs.setSkipLoadingScreen(value) }
    }

    val appleTVChannelFlip: Flow<Boolean> = prefs.appleTVChannelFlip

    /** Remote Control initiative: decoded button map (tolerant of unknown
     *  slots/actions; defaults when unset). */
    val remoteControlMap: Flow<com.aeriotv.android.core.remote.RemoteControlMap> =
        prefs.effectiveRemoteControlMap
    /** TV guide group-selector style: "pills" (top row) or "sidebar". */
    val guideGroupSelector: Flow<String> = prefs.guideGroupSelector
    fun setGuideGroupSelector(mode: String) {
        viewModelScope.launch { prefs.setGuideGroupSelector(mode) }
    }

    /** Live TV tune target: false = fullscreen (default), true = corner mini. */
    val guideTuneInMini: Flow<Boolean> = prefs.guideTuneInMini
    fun setGuideTuneInMini(value: Boolean) {
        viewModelScope.launch { prefs.setGuideTuneInMini(value) }
    }

    fun setRemoteControlMap(map: com.aeriotv.android.core.remote.RemoteControlMap) {
        viewModelScope.launch { prefs.setRemoteControlMap(map.toJson()) }
    }
    fun setAppleTVChannelFlip(value: Boolean) {
        viewModelScope.launch { prefs.setAppleTVChannelFlip(value) }
    }

    // iOS appBehaviorsAutoRecoverFrozenStreams (#37). Default true; device-local.
    val autoRecoverFrozenStreams: Flow<Boolean> = prefs.autoRecoverFrozenStreams
    fun setAutoRecoverFrozenStreams(value: Boolean) {
        viewModelScope.launch { prefs.setAutoRecoverFrozenStreams(value) }
    }

    // TMDB program posters (opt-in, off by default). Key is device-local.
    val programPostersTmdbEnabled: Flow<Boolean> = prefs.programPostersTmdbEnabled
    fun setProgramPostersTmdbEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setProgramPostersTmdbEnabled(value) }
    }

    // Dolby passthrough (device-specific; off = in-app decode, fixes lip sync
    // on TVs that decode the bitstream late).
    val audioPassthroughEnabled: Flow<Boolean> = prefs.audioPassthroughEnabled
    fun setAudioPassthroughEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setAudioPassthroughEnabled(value) }
    }

    val tmdbApiKey: Flow<String> = prefs.tmdbApiKey

    enum class TmdbKeyTestState { Idle, Testing, Valid, Invalid, Saved }
    private val _tmdbKeyTestState = MutableStateFlow(TmdbKeyTestState.Idle)
    val tmdbKeyTestState: StateFlow<TmdbKeyTestState> = _tmdbKeyTestState.asStateFlow()

    /** Reset the status label (called on each keystroke in the field). */
    fun resetTmdbKeyTestState() { _tmdbKeyTestState.value = TmdbKeyTestState.Idle }

    /** Validate the draft key against TMDB /configuration (no save). */
    fun testTmdbKey(draft: String) {
        viewModelScope.launch {
            _tmdbKeyTestState.value = TmdbKeyTestState.Testing
            val ok = tmdb.validateKey(draft)
            _tmdbKeyTestState.value = if (ok) TmdbKeyTestState.Valid else TmdbKeyTestState.Invalid
        }
    }

    /** Persist the draft key (device-local) and confirm with a Saved status. */
    fun saveTmdbKey(draft: String) {
        viewModelScope.launch {
            prefs.setTmdbApiKey(draft)
            // Misses cached under the previous key must not survive a key
            // change; positives re-resolve cheaply on next lookup.
            tmdb.clearCache()
            _tmdbKeyTestState.value = TmdbKeyTestState.Saved
        }
    }

    // Developer
    val debugLoggingEnabled: Flow<Boolean> = prefs.debugLoggingEnabled
    fun setDebugLoggingEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setDebugLoggingEnabled(value) }
    }

    val autoResumeLastChannel: Flow<Boolean> = prefs.autoResumeLastChannel
    fun setAutoResumeLastChannel(value: Boolean) {
        viewModelScope.launch { prefs.setAutoResumeLastChannel(value) }
    }

    val lastWatchedChannelId: Flow<String> = prefs.lastWatchedChannelId
    fun setLastWatchedChannelId(value: String) {
        viewModelScope.launch { prefs.setLastWatchedChannelId(value) }
    }

    /** LRU recent channel ids (most-recent first). Powers the AddToMultiview "Recent" section. */
    val recentChannelIds: Flow<List<String>> = prefs.recentChannelIds
    fun recordRecentChannel(channelId: String) {
        viewModelScope.launch { prefs.recordRecentChannel(channelId) }
    }

    val defaultTab: Flow<String> = prefs.defaultTab
    fun setDefaultTab(value: String) {
        viewModelScope.launch { prefs.setDefaultTab(value) }
    }

    // Live TV view-mode persistence (Phase 5 hand-off — migrated from
    // rememberSaveable in LiveTVViewMode.kt to DataStore in Phase 8b).
    val defaultLiveTVView: Flow<String> = prefs.defaultLiveTVView
    fun setDefaultLiveTVView(value: String) {
        viewModelScope.launch {
            // Per-device preference (NOT Drive-synced): the correct default is
            // form-factor specific, so a synced value would let one device clobber
            // another (a phone's List overriding a TV's Guide). Matches iOS
            // @AppStorage. Persists locally only; no Drive push.
            prefs.setDefaultLiveTVView(value)
        }
    }

    // Network (Phase 8c)
    val networkTimeoutSecs: Flow<Double> = prefs.networkTimeoutSecs
    fun setNetworkTimeoutSecs(value: Double) {
        viewModelScope.launch { prefs.setNetworkTimeoutSecs(value) }
    }

    val maxRetries: Flow<Int> = prefs.maxRetries
    fun setMaxRetries(value: Int) {
        viewModelScope.launch { prefs.setMaxRetries(value) }
    }

    val streamBufferSize: Flow<String> = prefs.streamBufferSize
    fun setStreamBufferSize(value: String) {
        viewModelScope.launch { prefs.setStreamBufferSize(value) }
    }

    val epgWindowHours: Flow<Int> = prefs.epgWindowHours
    fun setEpgWindowHours(value: Int) {
        viewModelScope.launch { prefs.setEpgWindowHours(value) }
    }

    // Adaptarr adaptive-quality settings are device-local. Connection testing
    // is read-only and cannot mutate playback or Dispatcharr state.
    val adaptarrEnabled: Flow<Boolean> = prefs.adaptarrEnabled
    fun setAdaptarrEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setAdaptarrEnabled(value) }
    }

    val adaptarrBaseUrl: Flow<String> = prefs.adaptarrBaseUrl
    val adaptarrToken: Flow<String> = prefs.adaptarrToken
    val adaptiveQualityMode: Flow<AdaptiveQualityMode> = prefs.adaptiveQualityMode
    fun setAdaptiveQualityMode(value: AdaptiveQualityMode) {
        cancelLocalRecommendationIfRunning()
        viewModelScope.launch { prefs.setAdaptiveQualityMode(value) }
    }

    val adaptiveMaxHeight: Flow<Int> = prefs.adaptiveMaxHeight
    fun setAdaptiveMaxHeight(value: Int) {
        cancelLocalRecommendationIfRunning()
        viewModelScope.launch { prefs.setAdaptiveMaxHeight(value) }
    }

    val adaptiveCellularMaxHeight: Flow<Int> = prefs.adaptiveCellularMaxHeight
    fun setAdaptiveCellularMaxHeight(value: Int) {
        viewModelScope.launch { prefs.setAdaptiveCellularMaxHeight(value) }
    }

    val adaptiveFallbackHeight: Flow<Int> = prefs.adaptiveFallbackHeight
    fun setAdaptiveFallbackHeight(value: Int) {
        viewModelScope.launch { prefs.setAdaptiveFallbackHeight(value) }
    }

    val adaptarrLastMeasuredThroughputBps: Flow<Long> =
        prefs.adaptarrLastMeasuredThroughputBps
    val adaptarrLastDecision: Flow<String> = prefs.adaptarrLastDecision

    enum class AdaptarrConnectionState {
        Idle,
        Saving,
        Saved,
        Testing,
        Connected,
        InvalidBaseUrl,
        InvalidToken,
        InvalidConnectionSettings,
        EncryptionFailed,
        PersistenceFailed,
        Unauthorized,
        IncompatibleProtocol,
        RateLimited,
        ServiceUnavailable,
        InvalidResponse,
        Unreachable,
    }

    enum class AdaptarrProbeState {
        Idle,
        Testing,
        MeasuredFresh,
        MeasuredCached,
        Timeout,
        Unavailable,
        NetworkChanged,
    }

    private val _adaptarrConnectionState = MutableStateFlow(AdaptarrConnectionState.Idle)
    val adaptarrConnectionState: StateFlow<AdaptarrConnectionState> =
        _adaptarrConnectionState.asStateFlow()
    private val _adaptarrProbeState = MutableStateFlow(AdaptarrProbeState.Idle)
    val adaptarrProbeState: StateFlow<AdaptarrProbeState> = _adaptarrProbeState.asStateFlow()
    private var adaptarrConnectionJob: Job? = null
    private var localRecommendationInFlight = false

    private fun cancelLocalRecommendationIfRunning() {
        if (localRecommendationInFlight) {
            adaptarrConnectionJob?.cancel()
            _adaptarrProbeState.value = AdaptarrProbeState.Idle
        }
    }

    fun resetAdaptarrConnectionState() {
        adaptarrConnectionJob?.cancel()
        adaptarrConnectionJob = null
        _adaptarrConnectionState.value = AdaptarrConnectionState.Idle
        _adaptarrProbeState.value = AdaptarrProbeState.Idle
    }

    fun saveAdaptarrConnection(baseUrl: String, token: String) {
        adaptarrConnectionJob?.cancel()
        adaptarrConnectionJob = viewModelScope.launch {
            _adaptarrProbeState.value = AdaptarrProbeState.Idle
            _adaptarrConnectionState.value = AdaptarrConnectionState.Saving
            _adaptarrConnectionState.value = try {
                when (prefs.saveAdaptarrConnection(baseUrl, token)) {
                    AdaptarrConnectionSaveResult.Saved -> AdaptarrConnectionState.Saved
                    AdaptarrConnectionSaveResult.InvalidBaseUrl -> AdaptarrConnectionState.InvalidBaseUrl
                    AdaptarrConnectionSaveResult.InvalidToken -> AdaptarrConnectionState.InvalidToken
                    AdaptarrConnectionSaveResult.EncryptionFailed -> AdaptarrConnectionState.EncryptionFailed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AdaptarrConnectionState.PersistenceFailed
            }
        }
    }

    fun testAdaptarrConnection(baseUrl: String, token: String) {
        adaptarrConnectionJob?.cancel()
        adaptarrConnectionJob = viewModelScope.launch {
            _adaptarrProbeState.value = AdaptarrProbeState.Idle
            _adaptarrConnectionState.value = AdaptarrConnectionState.Testing
            adaptarrDiagnostics.connectionStarted()
            val result = try {
                adaptarrClient.testConnection(baseUrl, token)
            } catch (cancelled: CancellationException) {
                adaptarrDiagnostics.connectionCancelled()
                throw cancelled
            } catch (_: Exception) {
                AdaptarrConnectionTestResult.InvalidResponse
            }
            adaptarrDiagnostics.connectionFinished(result)
            _adaptarrConnectionState.value = when (result) {
                AdaptarrConnectionTestResult.Connected -> AdaptarrConnectionState.Connected
                AdaptarrConnectionTestResult.InvalidSettings ->
                    AdaptarrConnectionState.InvalidConnectionSettings
                AdaptarrConnectionTestResult.Unauthorized -> AdaptarrConnectionState.Unauthorized
                AdaptarrConnectionTestResult.IncompatibleProtocol ->
                    AdaptarrConnectionState.IncompatibleProtocol
                AdaptarrConnectionTestResult.RateLimited -> AdaptarrConnectionState.RateLimited
                AdaptarrConnectionTestResult.ServiceUnavailable ->
                    AdaptarrConnectionState.ServiceUnavailable
                AdaptarrConnectionTestResult.InvalidResponse ->
                    AdaptarrConnectionState.InvalidResponse
                AdaptarrConnectionTestResult.Unreachable -> AdaptarrConnectionState.Unreachable
            }
        }
    }

    val adaptarrTelemetryDryRunConsent: Flow<Boolean> = prefs.adaptarrTelemetryDryRunConsent
    fun setAdaptarrTelemetryDryRunConsent(value: Boolean) {
        if (!value) adaptarrConnectionJob?.cancel()
        viewModelScope.launch { prefs.setAdaptarrTelemetryDryRunConsent(value) }
    }

    fun runAdaptarrSpeedTest(baseUrl: String, token: String) =
        runAdaptarrSpeedTest(baseUrl, token, AdaptiveQualityMode.Off, 720, false)

    /**
     * Runs an explicit local speed test. In Recommend mode a fresh measurement is
     * compared locally with trusted configuration thresholds; no measurement,
     * network key, telemetry, or recommendation request leaves the device.
     */
    fun runAdaptarrSpeedTest(
        baseUrl: String,
        token: String,
        mode: AdaptiveQualityMode,
        maxHeight: Int,
        telemetryConsent: Boolean = false,
    ) {
        adaptarrConnectionJob?.cancel()
        adaptarrConnectionJob = viewModelScope.launch {
            _adaptarrConnectionState.value = AdaptarrConnectionState.Idle
            _adaptarrProbeState.value = AdaptarrProbeState.Testing
            adaptarrDiagnostics.probeStarted()
            val result = try {
                adaptiveProbeCoordinator.probe(baseUrl, token)
            } catch (cancelled: CancellationException) {
                adaptarrDiagnostics.probeCancelled()
                throw cancelled
            } catch (_: Exception) {
                AdaptiveProbeResult.Unavailable
            }
            adaptarrDiagnostics.probeFinished(result)
            _adaptarrProbeState.value = when (result) {
                is AdaptiveProbeResult.Success -> when (result.source) {
                    AdaptiveProbeSource.Fresh -> AdaptarrProbeState.MeasuredFresh
                    AdaptiveProbeSource.Cached -> AdaptarrProbeState.MeasuredCached
                }
                AdaptiveProbeResult.Timeout -> AdaptarrProbeState.Timeout
                AdaptiveProbeResult.Unavailable -> AdaptarrProbeState.Unavailable
                AdaptiveProbeResult.Stale -> AdaptarrProbeState.NetworkChanged
            }
            if (result is AdaptiveProbeResult.Success &&
                result.source == AdaptiveProbeSource.Fresh &&
                mode == AdaptiveQualityMode.Recommend
            ) {
                updateLocalRecommendation(baseUrl, token, maxHeight, result.measurement.measuredThroughputBps)
                if (telemetryConsent) reportTelemetryDryRun(baseUrl, token, maxHeight, result.measurement)
            }
        }
    }

    private suspend fun reportTelemetryDryRun(
        baseUrl: String,
        token: String,
        maxHeight: Int,
        measurement: AdaptiveProbeMeasurement,
    ) {
        try {
            val aggregate = adaptarrClient.reportTelemetry(baseUrl, token, AdaptarrTelemetryReport(
                networkKey = measurement.networkKey,
                bytesTransferred = measurement.bytesTransferred,
                durationMs = measurement.durationMs,
                latencyMs = measurement.latencyMs,
            ))
            val recommendation = adaptarrClient.recommendation(
                baseUrl,
                token,
                AdaptarrRecommendationRequest(measurement.networkKey, maxHeight),
            )
            val advisory = recommendation.profile?.let { profile ->
                "${profile.name} (${profile.height}p)"
            } ?: "${aggregate.sampleCount}/3 samples"
            prefs.setAdaptarrLastDecision("Telemetry dry-run advisory: $advisory; no playback change.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the local recommendation; dry-run failure is non-fatal and never retried.
        }
    }

    private suspend fun updateLocalRecommendation(
        baseUrl: String,
        token: String,
        maxHeight: Int,
        throughputBps: Long,
    ) {
        localRecommendationInFlight = true
        try {
            val configuration = try {
                adaptarrClient.configuration(baseUrl, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                prefs.setAdaptarrLastDecision("Local recommendation unavailable.")
                return
            }
            prefs.setAdaptarrLastDecision(
                localRecommendationText(configuration, maxHeight, throughputBps),
            )
        } finally {
            localRecommendationInFlight = false
        }
    }

    private fun localRecommendationText(
        configuration: AdaptarrConfigResponse,
        maxHeight: Int,
        throughputBps: Long,
    ): String {
        val eligible = configuration.profiles.values
            .filter { it.height <= maxHeight }
            .sortedBy { it.height }
        val selected = eligible.lastOrNull { throughputBps >= it.minimumThroughputBps }
        return when {
            selected != null ->
                "Local recommendation (low confidence): ${selected.name} (${selected.height}p)."
            eligible.isNotEmpty() ->
                "Local recommendation (low confidence): ${eligible.first().name} (${eligible.first().height}p) may exceed measured bandwidth."
            else -> "Local recommendation unavailable."
        }
    }

    // Audit task #48: master toggle for the periodic PlaylistRefreshWorker.
    // The Application collects this Flow and registers/cancels the unique
    // periodic work whenever the user flips it.
    val backgroundRefreshEnabled: Flow<Boolean> = prefs.backgroundRefreshEnabled
    fun setBackgroundRefreshEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setBackgroundRefreshEnabled(value) }
    }

    // GuideStore audit P1 #7: how often the periodic refresh fires.
    // Default 360 minutes (6h) matches the prior hardcoded interval.
    val backgroundRefreshIntervalMins: Flow<Int> = prefs.backgroundRefreshIntervalMins
    fun setBackgroundRefreshIntervalMins(value: Int) {
        viewModelScope.launch { prefs.setBackgroundRefreshIntervalMins(value) }
    }

    // Multiview (Phase 11c)
    val multiviewAudioFocusStyle: Flow<String> = prefs.multiviewAudioFocusStyle
    fun setMultiviewAudioFocusStyle(value: String) {
        viewModelScope.launch { prefs.setMultiviewAudioFocusStyle(value) }
    }

    val multiviewTilePadding: Flow<Boolean> = prefs.multiviewTilePadding
    fun setMultiviewTilePadding(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewTilePadding(value) }
    }

    val multiviewTileCornersRounded: Flow<Boolean> = prefs.multiviewTileCornersRounded
    fun setMultiviewTileCornersRounded(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewTileCornersRounded(value) }
    }

    val multiviewLayoutMode: Flow<String> = prefs.multiviewLayoutMode
    fun setMultiviewLayoutMode(value: String) {
        viewModelScope.launch { prefs.setMultiviewLayoutMode(value) }
    }

    val multiviewPerfWarningSuppressed: Flow<Boolean> = prefs.multiviewPerfWarningSuppressed
    fun setMultiviewPerfWarningSuppressed(value: Boolean) {
        viewModelScope.launch { prefs.setMultiviewPerfWarningSuppressed(value) }
    }

    // DVR (Phase 9b-3)
    val dvrMaxLocalStorageMB: Flow<Int> = prefs.dvrMaxLocalStorageMB
    fun setDvrMaxLocalStorageMB(value: Int) {
        viewModelScope.launch { prefs.setDvrMaxLocalStorageMB(value) }
    }

    val dvrDefaultPreRollMins: Flow<Int> = prefs.dvrDefaultPreRollMins
    fun setDvrDefaultPreRollMins(value: Int) {
        viewModelScope.launch { prefs.setDvrDefaultPreRollMins(value) }
    }

    val dvrDefaultPostRollMins: Flow<Int> = prefs.dvrDefaultPostRollMins
    fun setDvrDefaultPostRollMins(value: Int) {
        viewModelScope.launch { prefs.setDvrDefaultPostRollMins(value) }
    }

    val dvrCustomFolderUri: Flow<String> = prefs.dvrCustomFolderUri
    fun setDvrCustomFolderUri(value: String) {
        viewModelScope.launch { prefs.setDvrCustomFolderUri(value) }
    }

    val dvrKeepAwakeDuringRecording: Flow<Boolean> = prefs.dvrKeepAwakeDuringRecording
    fun setDvrKeepAwakeDuringRecording(value: Boolean) {
        viewModelScope.launch { prefs.setDvrKeepAwakeDuringRecording(value) }
    }

    // Category Palette (Phase 15)
    val categoryPalette: Flow<CategoryPaletteState> = prefs.categoryPalette
    fun setCategoryColorsEnabled(value: Boolean) {
        viewModelScope.launch { prefs.setCategoryColorsEnabled(value) }
    }
    fun setCategoryBucketHex(bucket: ProgramCategory, hex: String?) {
        viewModelScope.launch { prefs.setCategoryBucketHex(bucket, hex) }
    }
    fun setCategoryBucketEnabled(bucket: ProgramCategory, enabled: Boolean) {
        viewModelScope.launch { prefs.setCategoryBucketEnabled(bucket, enabled) }
    }
    fun resetCategoryPalette() {
        viewModelScope.launch { prefs.resetCategoryPalette() }
    }
    fun setCustomCategories(list: List<CustomCategoryEntry>) {
        viewModelScope.launch { prefs.setCustomCategories(list) }
    }
}
