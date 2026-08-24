package com.aeriotv.android.feature.settings

import android.os.Looper
import com.aeriotv.android.core.network.TMDBService
import com.aeriotv.android.core.network.adaptarr.AdaptarrClient
import com.aeriotv.android.core.preferences.AdaptiveQualityMode
import com.aeriotv.android.core.preferences.AdaptarrConnectionSaveResult
import com.aeriotv.android.core.preferences.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelAdaptarrPreferencesTest {
    private val prefs: AppPreferences = mockk(relaxed = true)

    private fun newViewModel() = SettingsViewModel(
        prefs,
        mockk<TMDBService>(relaxed = true),
        mockk<AdaptarrClient>(relaxed = true),
    )

    @Test
    fun `exposes only device local Adaptarr preference flows`() = runBlocking {
        every { prefs.adaptarrEnabled } returns flowOf(true)
        every { prefs.adaptarrBaseUrl } returns flowOf("https://adaptarr.example.test")
        every { prefs.adaptarrToken } returns flowOf("t".repeat(32))
        every { prefs.adaptiveQualityMode } returns flowOf(AdaptiveQualityMode.Recommend)
        every { prefs.adaptiveMaxHeight } returns flowOf(1080)
        every { prefs.adaptiveCellularMaxHeight } returns flowOf(720)
        every { prefs.adaptiveFallbackHeight } returns flowOf(720)
        every { prefs.adaptarrTelemetryDryRunConsent } returns flowOf(false)
        every { prefs.adaptarrLastMeasuredThroughputBps } returns flowOf(1234L)
        every { prefs.adaptarrLastDecision } returns flowOf("local-only")
        val viewModel = newViewModel()

        assertEquals(true, viewModel.adaptarrEnabled.first())
        assertEquals("https://adaptarr.example.test", viewModel.adaptarrBaseUrl.first())
        assertEquals("t".repeat(32), viewModel.adaptarrToken.first())
        assertEquals(AdaptiveQualityMode.Recommend, viewModel.adaptiveQualityMode.first())
        assertEquals(1080, viewModel.adaptiveMaxHeight.first())
        assertEquals(720, viewModel.adaptiveCellularMaxHeight.first())
        assertEquals(720, viewModel.adaptiveFallbackHeight.first())
        assertEquals(false, viewModel.adaptarrTelemetryDryRunConsent.first())
        assertEquals(1234L, viewModel.adaptarrLastMeasuredThroughputBps.first())
        assertEquals("local-only", viewModel.adaptarrLastDecision.first())
    }

    @Test
    fun `local Adaptarr setters delegate only to preferences`() {
        coEvery { prefs.setAdaptarrEnabled(any()) } returns Unit
        coEvery { prefs.setAdaptiveQualityMode(any()) } returns Unit
        coEvery { prefs.setAdaptiveMaxHeight(any()) } returns Unit
        coEvery { prefs.setAdaptiveCellularMaxHeight(any()) } returns Unit
        coEvery { prefs.setAdaptiveFallbackHeight(any()) } returns Unit
        coEvery { prefs.setAdaptarrTelemetryDryRunConsent(any()) } returns Unit
        val viewModel = newViewModel()

        viewModel.setAdaptarrEnabled(true)
        viewModel.setAdaptiveQualityMode(AdaptiveQualityMode.Auto)
        viewModel.setAdaptiveMaxHeight(720)
        viewModel.setAdaptiveCellularMaxHeight(720)
        viewModel.setAdaptiveFallbackHeight(1080)
        viewModel.setAdaptarrTelemetryDryRunConsent(true)
        shadowOf(Looper.getMainLooper()).idle()

        coVerify(exactly = 1) { prefs.setAdaptarrEnabled(true) }
        coVerify(exactly = 1) { prefs.setAdaptiveQualityMode(AdaptiveQualityMode.Auto) }
        coVerify(exactly = 1) { prefs.setAdaptiveMaxHeight(720) }
        coVerify(exactly = 1) { prefs.setAdaptiveCellularMaxHeight(720) }
        coVerify(exactly = 1) { prefs.setAdaptiveFallbackHeight(1080) }
        coVerify(exactly = 1) { prefs.setAdaptarrTelemetryDryRunConsent(true) }
    }

    @Test
    fun `connection save maps local preference results without network work`() {
        coEvery { prefs.saveAdaptarrConnection(any(), any()) } returns AdaptarrConnectionSaveResult.Saved
        val viewModel = newViewModel()

        viewModel.saveAdaptarrConnection("https://adaptarr.example.test", "t".repeat(32))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SettingsViewModel.AdaptarrConnectionState.Saved, viewModel.adaptarrConnectionState.value)
        coVerify(exactly = 1) {
            prefs.saveAdaptarrConnection("https://adaptarr.example.test", "t".repeat(32))
        }
    }
}
