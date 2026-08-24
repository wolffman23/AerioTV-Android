package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.preferences.AdaptiveQualityMode
import com.aeriotv.android.ui.settings.SettingsSection
import com.aeriotv.android.ui.settings.SettingsSelectionRow
import com.aeriotv.android.ui.settings.SettingsToggleRow

data class AdaptiveStreamingModeOption(
    val mode: AdaptiveQualityMode,
    val label: String,
    val subtitle: String,
)

internal fun adaptiveStreamingModeOptions(): List<AdaptiveStreamingModeOption> = listOf(
    AdaptiveStreamingModeOption(AdaptiveQualityMode.Off, "Off", "Keep adaptive quality disabled."),
    AdaptiveStreamingModeOption(AdaptiveQualityMode.Recommend, "Recommend only", "Store a local preference for a future advisory."),
    AdaptiveStreamingModeOption(AdaptiveQualityMode.Auto, "Automatic", "Store a local preference for the controlled rollout."),
)

internal fun adaptiveStreamingActivationStatus(enabled: Boolean): String =
    if (enabled) "Configuration only — playback control is not active yet."
    else "Disabled on this device."

internal fun connectionSaveStatusText(state: SettingsViewModel.AdaptarrConnectionState): String = when (state) {
    SettingsViewModel.AdaptarrConnectionState.Idle -> "Enter connection details to save them locally."
    SettingsViewModel.AdaptarrConnectionState.Saving -> "Saving locally…"
    SettingsViewModel.AdaptarrConnectionState.Saved -> "Connection saved locally."
    SettingsViewModel.AdaptarrConnectionState.InvalidBaseUrl -> "Enter a valid HTTP or HTTPS address without credentials, query, or fragment."
    SettingsViewModel.AdaptarrConnectionState.InvalidToken -> "Token must contain 32–512 non-whitespace characters."
    SettingsViewModel.AdaptarrConnectionState.EncryptionFailed -> "The token could not be encrypted; existing settings were kept."
    SettingsViewModel.AdaptarrConnectionState.PersistenceFailed -> "The connection could not be saved; existing settings were kept."
    SettingsViewModel.AdaptarrConnectionState.Testing -> "Testing connection…"
    SettingsViewModel.AdaptarrConnectionState.Connected -> "Connected to Adaptarr."
    SettingsViewModel.AdaptarrConnectionState.Unauthorized -> "Adaptarr rejected the token."
    SettingsViewModel.AdaptarrConnectionState.IncompatibleProtocol -> "Adaptarr uses an unsupported protocol."
    SettingsViewModel.AdaptarrConnectionState.RateLimited -> "Adaptarr is busy; try again shortly."
    SettingsViewModel.AdaptarrConnectionState.ServiceUnavailable -> "Adaptarr is temporarily unavailable."
    SettingsViewModel.AdaptarrConnectionState.InvalidResponse -> "Adaptarr returned an invalid response."
    SettingsViewModel.AdaptarrConnectionState.Unreachable -> "Adaptarr could not be reached."
}

@Composable
internal fun AdaptiveStreamingSettingsSection(
    enabled: Boolean,
    mode: AdaptiveQualityMode,
    maxHeight: Int,
    cellularMaxHeight: Int,
    fallbackHeight: Int,
    lastDecision: String,
    baseUrl: String,
    token: String,
    connectionState: SettingsViewModel.AdaptarrConnectionState,
    onEnabledChange: (Boolean) -> Unit,
    onSaveConnection: (String, String) -> Unit,
    onTestConnection: (String, String) -> Unit,
    onModeChange: (AdaptiveQualityMode) -> Unit,
    onMaxHeightChange: (Int) -> Unit,
    onCellularMaxHeightChange: (Int) -> Unit,
    onFallbackHeightChange: (Int) -> Unit,
) {
    SettingsSection(
        header = "Adaptive Streaming",
        footer = "These device-local preferences are not included in Drive sync. Connection setup and playback control are not available in this build yet.",
    ) {
        SettingsToggleRow(
            title = "Enable adaptive streaming",
            subtitle = "Enable local configuration only.",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        if (enabled) {
            var baseUrlDraft by remember { mutableStateOf(baseUrl) }
            // Never rememberSaveable: bearer drafts must not enter saved instance state.
            var tokenDraft by remember { mutableStateOf(token) }
            var draftsDirty by remember { mutableStateOf(false) }
            LaunchedEffect(baseUrl, token, connectionState) {
                if (!draftsDirty || connectionState == SettingsViewModel.AdaptarrConnectionState.Saved) {
                    baseUrlDraft = baseUrl
                    tokenDraft = token
                    draftsDirty = false
                }
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = { baseUrlDraft = it; draftsDirty = true },
                    label = { Text("Adaptarr address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it; draftsDirty = true },
                    label = { Text("Bearer token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSaveConnection(baseUrlDraft, tokenDraft) },
                    enabled = connectionState != SettingsViewModel.AdaptarrConnectionState.Saving &&
                        connectionState != SettingsViewModel.AdaptarrConnectionState.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (connectionState == SettingsViewModel.AdaptarrConnectionState.Saving) "Saving…" else "Save locally") }
                Button(
                    onClick = { onTestConnection(baseUrlDraft, tokenDraft) },
                    enabled = connectionState != SettingsViewModel.AdaptarrConnectionState.Saving &&
                        connectionState != SettingsViewModel.AdaptarrConnectionState.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (connectionState == SettingsViewModel.AdaptarrConnectionState.Testing) "Testing…" else "Test connection") }
                StatusRow("Connection", connectionSaveStatusText(connectionState))
            }
            adaptiveStreamingModeOptions().forEach { option ->
                SettingsSelectionRow(
                    label = option.label,
                    subtitle = option.subtitle,
                    selected = mode == option.mode,
                    onClick = { onModeChange(option.mode) },
                )
            }
            StatusRow("Status", adaptiveStreamingActivationStatus(enabled))
            HeightSelector("Maximum quality", maxHeight, onMaxHeightChange)
            HeightSelector("Cellular maximum", cellularMaxHeight, onCellularMaxHeightChange)
            HeightSelector("Fallback quality", fallbackHeight, onFallbackHeightChange)
            StatusRow("Last local decision", lastDecision.ifBlank { "No decision yet." })
        }
    }
}

@Composable
private fun HeightSelector(label: String, current: Int, onSelect: (Int) -> Unit) {
    listOf(720, 1080).forEach { height ->
        SettingsSelectionRow(
            label = "$label: ${height}p",
            selected = current == height,
            onClick = { onSelect(height) },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
