package com.aeriotv.android.feature.settings

import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.network.adaptarr.AdaptarrClient
import com.aeriotv.android.core.network.adaptarr.AdaptarrConnectionTestResult
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeCoordinator
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeMeasurement
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeResult
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeSource
import com.aeriotv.android.core.network.adaptarr.AdaptarrTelemetryConfidence
import com.aeriotv.android.core.preferences.AdaptarrConnectionSaveResult
import com.aeriotv.android.core.preferences.AppPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelAdaptarrTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: AppPreferences
    private lateinit var tmdb: TMDBService
    private lateinit var client: AdaptarrClient
    private lateinit var probeCoordinator: AdaptiveProbeCoordinator
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs = mockk(relaxed = true)
        tmdb = mockk(relaxed = true)
        client = mockk()
        probeCoordinator = mockk()
        viewModel = SettingsViewModel(prefs, tmdb, client, probeCoordinator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test connection maps every client result to a stable UI state`() = runTest(dispatcher.scheduler) {
        val cases = listOf(
            AdaptarrConnectionTestResult.Connected to SettingsViewModel.AdaptarrConnectionState.Connected,
            AdaptarrConnectionTestResult.InvalidSettings to SettingsViewModel.AdaptarrConnectionState.InvalidConnectionSettings,
            AdaptarrConnectionTestResult.Unauthorized to SettingsViewModel.AdaptarrConnectionState.Unauthorized,
            AdaptarrConnectionTestResult.IncompatibleProtocol to SettingsViewModel.AdaptarrConnectionState.IncompatibleProtocol,
            AdaptarrConnectionTestResult.RateLimited to SettingsViewModel.AdaptarrConnectionState.RateLimited,
            AdaptarrConnectionTestResult.ServiceUnavailable to SettingsViewModel.AdaptarrConnectionState.ServiceUnavailable,
            AdaptarrConnectionTestResult.InvalidResponse to SettingsViewModel.AdaptarrConnectionState.InvalidResponse,
            AdaptarrConnectionTestResult.Unreachable to SettingsViewModel.AdaptarrConnectionState.Unreachable,
        )
        cases.forEach { (result, expected) ->
            coEvery { client.testConnection(any(), any()) } returns result
            viewModel.testAdaptarrConnection("https://adaptarr.local", "t".repeat(32))
            runCurrent()
            assertEquals(expected, viewModel.adaptarrConnectionState.value)
        }
    }

    @Test
    fun `new test cancels old test and stale result cannot overwrite latest`() = runTest(dispatcher.scheduler) {
        coEvery { client.testConnection("https://one.local", any()) } coAnswers { awaitCancellation() }
        coEvery { client.testConnection("https://two.local", any()) } returns AdaptarrConnectionTestResult.Connected

        viewModel.testAdaptarrConnection("https://one.local", "a".repeat(32))
        runCurrent()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Testing, viewModel.adaptarrConnectionState.value)

        viewModel.testAdaptarrConnection("https://two.local", "b".repeat(32))
        advanceUntilIdle()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Connected, viewModel.adaptarrConnectionState.value)
    }

    @Test
    fun `draft reset cancels in flight test and returns idle`() = runTest(dispatcher.scheduler) {
        coEvery { client.testConnection(any(), any()) } coAnswers { awaitCancellation() }

        viewModel.testAdaptarrConnection("https://adaptarr.local", "a".repeat(32))
        runCurrent()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Testing, viewModel.adaptarrConnectionState.value)

        viewModel.resetAdaptarrConnectionState()
        advanceUntilIdle()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Idle, viewModel.adaptarrConnectionState.value)
    }

    @Test
    fun `test cancels in flight save and publishes only test result`() = runTest(dispatcher.scheduler) {
        coEvery { prefs.saveAdaptarrConnection(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { client.testConnection(any(), any()) } returns AdaptarrConnectionTestResult.Connected

        viewModel.saveAdaptarrConnection("https://one.local", "a".repeat(32))
        runCurrent()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Saving, viewModel.adaptarrConnectionState.value)

        viewModel.testAdaptarrConnection("https://two.local", "b".repeat(32))
        advanceUntilIdle()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Connected, viewModel.adaptarrConnectionState.value)
    }

    @Test
    fun `save cancels in flight test and publishes only save result`() = runTest(dispatcher.scheduler) {
        coEvery { client.testConnection(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { prefs.saveAdaptarrConnection(any(), any()) } returns AdaptarrConnectionSaveResult.Saved

        viewModel.testAdaptarrConnection("https://one.local", "a".repeat(32))
        runCurrent()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Testing, viewModel.adaptarrConnectionState.value)

        viewModel.saveAdaptarrConnection("https://two.local", "b".repeat(32))
        advanceUntilIdle()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Saved, viewModel.adaptarrConnectionState.value)
    }

    @Test
    fun `speed test maps fresh cached timeout unavailable and stale results`() = runTest(dispatcher.scheduler) {
        val measurement = AdaptiveProbeMeasurement(
            networkKey = "a".repeat(64),
            measuredThroughputBps = 8_000_000L,
            sampleCount = 1,
            confidence = AdaptarrTelemetryConfidence.Low,
            measuredAtElapsedRealtimeMs = 100L,
        )
        val cases = listOf(
            AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Fresh) to
                SettingsViewModel.AdaptarrProbeState.MeasuredFresh,
            AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Cached) to
                SettingsViewModel.AdaptarrProbeState.MeasuredCached,
            AdaptiveProbeResult.Timeout to SettingsViewModel.AdaptarrProbeState.Timeout,
            AdaptiveProbeResult.Unavailable to SettingsViewModel.AdaptarrProbeState.Unavailable,
            AdaptiveProbeResult.Stale to SettingsViewModel.AdaptarrProbeState.NetworkChanged,
        )
        cases.forEach { (result, expected) ->
            coEvery { probeCoordinator.probe(any(), any()) } returns result
            viewModel.runAdaptarrSpeedTest("https://adaptarr.local", "t".repeat(32))
            advanceUntilIdle()
            assertEquals(expected, viewModel.adaptarrProbeState.value)
        }
    }

    @Test
    fun `speed test cancels connection test and draft reset cancels speed test`() = runTest(dispatcher.scheduler) {
        coEvery { client.testConnection(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { probeCoordinator.probe(any(), any()) } coAnswers { awaitCancellation() }

        viewModel.testAdaptarrConnection("https://adaptarr.local", "a".repeat(32))
        runCurrent()
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Testing, viewModel.adaptarrConnectionState.value)

        viewModel.runAdaptarrSpeedTest("https://adaptarr.local", "a".repeat(32))
        runCurrent()
        assertEquals(SettingsViewModel.AdaptarrProbeState.Testing, viewModel.adaptarrProbeState.value)

        viewModel.resetAdaptarrConnectionState()
        advanceUntilIdle()
        assertEquals(SettingsViewModel.AdaptarrProbeState.Idle, viewModel.adaptarrProbeState.value)
        assertEquals(SettingsViewModel.AdaptarrConnectionState.Idle, viewModel.adaptarrConnectionState.value)
    }
}
