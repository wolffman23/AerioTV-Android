package com.aeriotv.android.feature.settings

import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.network.adaptarr.AdaptarrClient
import com.aeriotv.android.core.network.adaptarr.AdaptarrConfigResponse
import com.aeriotv.android.core.network.adaptarr.AdaptarrConnectionTestResult
import com.aeriotv.android.core.network.adaptarr.AdaptarrDiagnostics
import com.aeriotv.android.core.network.adaptarr.AdaptarrProfile
import com.aeriotv.android.core.network.adaptarr.AdaptarrProfileMode
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeCoordinator
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeMeasurement
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeResult
import com.aeriotv.android.core.network.adaptarr.AdaptiveProbeSource
import com.aeriotv.android.core.network.adaptarr.AdaptarrTelemetryConfidence
import com.aeriotv.android.core.preferences.AdaptarrConnectionSaveResult
import com.aeriotv.android.core.preferences.AdaptiveQualityMode
import com.aeriotv.android.core.preferences.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelAdaptarrTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: AppPreferences
    private lateinit var tmdb: TMDBService
    private lateinit var client: AdaptarrClient
    private lateinit var probeCoordinator: AdaptiveProbeCoordinator
    private lateinit var diagnostics: AdaptarrDiagnostics
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs = mockk(relaxed = true)
        tmdb = mockk(relaxed = true)
        client = mockk()
        probeCoordinator = mockk()
        diagnostics = mockk(relaxed = true)
        viewModel = SettingsViewModel(prefs, tmdb, client, probeCoordinator, diagnostics)
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
        verify(exactly = 1) { diagnostics.connectionStarted() }
        verify(exactly = 1) { diagnostics.connectionCancelled() }
        verify(exactly = 0) { diagnostics.connectionFinished(any()) }
        verify(exactly = 1) { diagnostics.probeStarted() }
        verify(exactly = 1) { diagnostics.probeCancelled() }
        verify(exactly = 0) { diagnostics.probeFinished(any()) }
    }

    @Test
    fun `connection test emits safe start and completion diagnostics`() = runTest(dispatcher.scheduler) {
        coEvery { client.testConnection(any(), any()) } returns AdaptarrConnectionTestResult.Connected

        viewModel.testAdaptarrConnection("https://secret-host.local/private", "s".repeat(32))
        advanceUntilIdle()

        verify(exactly = 1) { diagnostics.connectionStarted() }
        verify(exactly = 1) {
            diagnostics.connectionFinished(AdaptarrConnectionTestResult.Connected)
        }
        verify(exactly = 0) { diagnostics.connectionCancelled() }
    }

    @Test
    fun `cancelled probe emits cancellation without completion diagnostics`() = runTest(dispatcher.scheduler) {
        coEvery { probeCoordinator.probe(any(), any()) } coAnswers { awaitCancellation() }

        viewModel.runAdaptarrSpeedTest("https://secret-host.local/private", "s".repeat(32))
        runCurrent()
        viewModel.resetAdaptarrConnectionState()
        advanceUntilIdle()

        verify(exactly = 1) { diagnostics.probeStarted() }
        verify(exactly = 1) { diagnostics.probeCancelled() }
        verify(exactly = 0) { diagnostics.probeFinished(any()) }
    }

    @Test
    fun `unexpected connection exception emits fixed invalid response diagnostics`() = runTest(dispatcher.scheduler) {
        coEvery { client.testConnection(any(), any()) } throws
            IllegalStateException("Bearer secret-token at https://secret-host.local/private")

        viewModel.testAdaptarrConnection("https://secret-host.local/private", "s".repeat(32))
        advanceUntilIdle()

        assertEquals(
            SettingsViewModel.AdaptarrConnectionState.InvalidResponse,
            viewModel.adaptarrConnectionState.value,
        )
        verify(exactly = 1) { diagnostics.connectionStarted() }
        verify(exactly = 1) {
            diagnostics.connectionFinished(AdaptarrConnectionTestResult.InvalidResponse)
        }
        verify(exactly = 0) { diagnostics.connectionCancelled() }
    }

    @Test
    fun `unexpected probe exception emits fixed unavailable diagnostics`() = runTest(dispatcher.scheduler) {
        coEvery { probeCoordinator.probe(any(), any()) } throws
            IllegalStateException("network key and payload must never be logged")

        viewModel.runAdaptarrSpeedTest("https://secret-host.local/private", "s".repeat(32))
        advanceUntilIdle()

        assertEquals(SettingsViewModel.AdaptarrProbeState.Unavailable, viewModel.adaptarrProbeState.value)
        verify(exactly = 1) { diagnostics.probeStarted() }
        verify(exactly = 1) { diagnostics.probeFinished(AdaptiveProbeResult.Unavailable) }
        verify(exactly = 0) { diagnostics.probeCancelled() }
    }

    @Test
    fun `fresh probe emits typed completion diagnostics`() = runTest(dispatcher.scheduler) {
        val result = AdaptiveProbeResult.Success(
            measurement = AdaptiveProbeMeasurement(
                networkKey = "b".repeat(64),
                measuredThroughputBps = 8_000_000L,
                sampleCount = 1,
                confidence = AdaptarrTelemetryConfidence.Low,
                measuredAtElapsedRealtimeMs = 100L,
            ),
            source = AdaptiveProbeSource.Fresh,
        )
        coEvery { probeCoordinator.probe(any(), any()) } returns result

        viewModel.runAdaptarrSpeedTest("https://secret-host.local/private", "s".repeat(32))
        advanceUntilIdle()

        verify(exactly = 1) { diagnostics.probeStarted() }
        verify(exactly = 1) { diagnostics.probeFinished(result) }
        verify(exactly = 0) { diagnostics.probeCancelled() }
    }

    @Test
    fun `fresh recommend-only speed test selects local approved profile without telemetry`() =
        runTest(dispatcher.scheduler) {
            val measurement = AdaptiveProbeMeasurement(
                networkKey = "e".repeat(64),
                measuredThroughputBps = 8_000_000L,
                sampleCount = 1,
                confidence = AdaptarrTelemetryConfidence.Low,
                measuredAtElapsedRealtimeMs = 100L,
            )
            coEvery { probeCoordinator.probe(any(), any()) } returns
                AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Fresh)
            coEvery { client.configuration(any(), any()) } returns AdaptarrConfigResponse(
                schemaVersion = 1,
                protocolVersion = 1,
                generationId = "12345678-1234-4abc-8def-1234567890ab",
                generatedAt = "2026-07-24T00:00:00Z",
                profiles = mapOf(
                    "1080p" to AdaptarrProfile(
                        id = 7,
                        name = "Adaptarr 1080p Passthrough",
                        width = 1920,
                        height = 1080,
                        mode = AdaptarrProfileMode.Passthrough,
                        estimatedBitrateBps = 10_000_000L,
                        minimumThroughputBps = 12_000_000L,
                    ),
                    "720p" to AdaptarrProfile(
                        id = 8,
                        name = "Adaptarr 720p NVENC",
                        width = 1280,
                        height = 720,
                        mode = AdaptarrProfileMode.Transcode,
                        estimatedBitrateBps = 4_000_000L,
                        minimumThroughputBps = 4_800_000L,
                    ),
                ),
            )

            viewModel.runAdaptarrSpeedTest(
                "https://adaptarr.local",
                "t".repeat(32),
                AdaptiveQualityMode.Recommend,
                1080,
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { client.configuration("https://adaptarr.local", "t".repeat(32)) }
            coVerify(exactly = 1) {
                prefs.setAdaptarrLastDecision("Local recommendation (low confidence): Adaptarr 720p NVENC (720p).")
            }
            coVerify(exactly = 0) { client.reportProbe(any(), any(), any()) }
            coVerify(exactly = 0) { client.reportTelemetry(any(), any(), any()) }
            coVerify(exactly = 0) { client.telemetrySummary(any(), any(), any()) }
            coVerify(exactly = 0) { client.recommendation(any(), any(), any()) }
        }

    @Test
    fun `cached and off speed tests do not read configuration or write a decision`() =
        runTest(dispatcher.scheduler) {
            val measurement = AdaptiveProbeMeasurement(
                networkKey = "f".repeat(64),
                measuredThroughputBps = 8_000_000L,
                sampleCount = 1,
                confidence = AdaptarrTelemetryConfidence.Low,
                measuredAtElapsedRealtimeMs = 100L,
            )
            coEvery { probeCoordinator.probe(any(), any()) } returns
                AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Cached)

            viewModel.runAdaptarrSpeedTest(
                "https://adaptarr.local",
                "t".repeat(32),
                AdaptiveQualityMode.Recommend,
                720,
            )
            advanceUntilIdle()

            coVerify(exactly = 0) { client.configuration(any(), any()) }
            coVerify(exactly = 0) { prefs.setAdaptarrLastDecision(any()) }

            coEvery { probeCoordinator.probe(any(), any()) } returns
                AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Fresh)
            viewModel.runAdaptarrSpeedTest(
                "https://adaptarr.local",
                "t".repeat(32),
                AdaptiveQualityMode.Off,
                720,
            )
            advanceUntilIdle()

            coVerify(exactly = 0) { client.configuration(any(), any()) }
            coVerify(exactly = 0) { prefs.setAdaptarrLastDecision(any()) }
        }

    @Test
    fun `cancelling config read after a fresh probe cannot save a stale decision`() =
        runTest(dispatcher.scheduler) {
            val measurement = AdaptiveProbeMeasurement(
                networkKey = "1".repeat(64),
                measuredThroughputBps = 8_000_000L,
                sampleCount = 1,
                confidence = AdaptarrTelemetryConfidence.Low,
                measuredAtElapsedRealtimeMs = 100L,
            )
            coEvery { probeCoordinator.probe(any(), any()) } returns
                AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Fresh)
            var configCancelled = false
            coEvery { client.configuration(any(), any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    configCancelled = true
                }
            }

            viewModel.runAdaptarrSpeedTest(
                "https://adaptarr.local",
                "t".repeat(32),
                AdaptiveQualityMode.Recommend,
                720,
            )
            runCurrent()
            viewModel.resetAdaptarrConnectionState()
            advanceUntilIdle()

            coVerify(exactly = 1) { client.configuration(any(), any()) }
            assertTrue(configCancelled)
            coVerify(exactly = 0) { prefs.setAdaptarrLastDecision(any()) }
        }

    @Test
    fun `config failure saves a fixed local unavailable decision without report calls`() =
        runTest(dispatcher.scheduler) {
            val measurement = AdaptiveProbeMeasurement(
                networkKey = "2".repeat(64),
                measuredThroughputBps = 8_000_000L,
                sampleCount = 1,
                confidence = AdaptarrTelemetryConfidence.Low,
                measuredAtElapsedRealtimeMs = 100L,
            )
            coEvery { probeCoordinator.probe(any(), any()) } returns
                AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Fresh)
            coEvery { client.configuration(any(), any()) } throws
                IllegalStateException("token and endpoint must not enter the decision")

            viewModel.runAdaptarrSpeedTest(
                "https://adaptarr.local",
                "t".repeat(32),
                AdaptiveQualityMode.Recommend,
                720,
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { prefs.setAdaptarrLastDecision("Local recommendation unavailable.") }
            coVerify(exactly = 0) { client.reportProbe(any(), any(), any()) }
            coVerify(exactly = 0) { client.reportTelemetry(any(), any(), any()) }
            coVerify(exactly = 0) { client.telemetrySummary(any(), any(), any()) }
            coVerify(exactly = 0) { client.recommendation(any(), any(), any()) }
        }

    @Test
    fun `changing maximum height cancels an in-flight local recommendation`() =
        runTest(dispatcher.scheduler) {
            val measurement = AdaptiveProbeMeasurement(
                networkKey = "3".repeat(64),
                measuredThroughputBps = 8_000_000L,
                sampleCount = 1,
                confidence = AdaptarrTelemetryConfidence.Low,
                measuredAtElapsedRealtimeMs = 100L,
            )
            coEvery { probeCoordinator.probe(any(), any()) } returns
                AdaptiveProbeResult.Success(measurement, AdaptiveProbeSource.Fresh)
            var configCancelled = false
            coEvery { client.configuration(any(), any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    configCancelled = true
                }
            }

            viewModel.runAdaptarrSpeedTest(
                "https://adaptarr.local",
                "t".repeat(32),
                AdaptiveQualityMode.Recommend,
                1080,
            )
            runCurrent()
            viewModel.setAdaptiveMaxHeight(720)
            advanceUntilIdle()

            assertTrue(configCancelled)
            coVerify(exactly = 0) { prefs.setAdaptarrLastDecision(any()) }
        }
}
