package com.aeriotv.android.feature.settings

import com.aeriotv.android.core.preferences.AdaptiveQualityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AdaptiveStreamingSettingsSectionTest {

    @Test
    fun `mode options are local configuration only and expose every stored mode`() {
        val options = adaptiveStreamingModeOptions()

        assertEquals(
            listOf(
                AdaptiveQualityMode.Off,
                AdaptiveQualityMode.Recommend,
                AdaptiveQualityMode.Auto,
            ),
            options.map { it.mode },
        )
        assertFalse(options.joinToString(" ") { it.subtitle }.contains("token", ignoreCase = true))
        assertFalse(options.joinToString(" ") { it.subtitle }.contains("telemetry", ignoreCase = true))
    }

    @Test
    fun `inactive shell status does not claim playback behavior`() {
        assertEquals(
            "Configuration only — playback control is not active yet.",
            adaptiveStreamingActivationStatus(enabled = true),
        )
        assertEquals(
            "Disabled on this device.",
            adaptiveStreamingActivationStatus(enabled = false),
        )
    }

    @Test
    fun `save status uses fixed labels without echoing form input`() {
        assertEquals("Connection saved locally.", connectionSaveStatusText(SettingsViewModel.AdaptarrConnectionState.Saved))
        assertEquals("The token could not be encrypted; existing settings were kept.", connectionSaveStatusText(SettingsViewModel.AdaptarrConnectionState.EncryptionFailed))
    }

    @Test
    fun `connection test statuses are fixed safe messages`() {
        assertEquals("Connected to Adaptarr.", connectionSaveStatusText(SettingsViewModel.AdaptarrConnectionState.Connected))
        assertEquals("Adaptarr rejected the token.", connectionSaveStatusText(SettingsViewModel.AdaptarrConnectionState.Unauthorized))
        assertEquals("Adaptarr could not be reached.", connectionSaveStatusText(SettingsViewModel.AdaptarrConnectionState.Unreachable))
    }
}
