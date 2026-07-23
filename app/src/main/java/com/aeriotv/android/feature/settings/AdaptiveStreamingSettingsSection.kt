package com.aeriotv.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.preferences.AdaptiveQualityMode
import com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions
import com.aeriotv.android.ui.tv.tvFormFieldInput
import java.util.Locale

/**
 * Device-local Adaptarr configuration. This section deliberately performs no
 * protocol requests and cannot mutate playback or Dispatcharr state.
 */
@Composable
internal fun AdaptiveStreamingSettingsSection(
    enabled: Boolean,
    baseUrl: String,
    token: String,
    mode: AdaptiveQualityMode,
    maxHeight: Int,
    cellularMaxHeight: Int,
    fallbackHeight: Int,
    lastMeasuredThroughputBps: Long,
    lastDecision: String,
    connectionState: SettingsViewModel.AdaptarrConnectionState,
    onEnabledChange: (Boolean) -> Unit,
    onSaveConnection: (String, String) -> Unit,
    onConnectionDraftChanged: () -> Unit,
    onModeChange: (AdaptiveQualityMode) -> Unit,
    onMaxHeightChange: (Int) -> Unit,
    onCellularMaxHeightChange: (Int) -> Unit,
    onFallbackHeightChange: (Int) -> Unit,
) {
    var baseUrlDraft by remember { mutableStateOf(baseUrl) }
    // Deliberately remember, not rememberSaveable: a bearer token must not be
    // copied into Android saved-instance-state Bundles.
    var tokenDraft by remember { mutableStateOf(token) }
    var draftsDirty by remember { mutableStateOf(false) }
    var submittedDraft by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(baseUrl, token, connectionState) {
        val submittedWasUnchanged =
            connectionState == SettingsViewModel.AdaptarrConnectionState.Saved &&
                submittedDraft == (baseUrlDraft to tokenDraft)
        if (!draftsDirty || submittedWasUnchanged) {
            baseUrlDraft = baseUrl
            tokenDraft = token
            draftsDirty = false
            submittedDraft = null
        }
    }

    SettingsSection(
        header = "Adaptive Streaming",
        footer = "Adaptarr settings, credentials, and measurements stay on this device and are not included in Drive sync. Recommendations cannot change playback in this build.",
    ) {
        SettingsToggleRow(
            title = "Enable Adaptarr",
            subtitle = "Allow AerioTV to use local adaptive-quality settings.",
            checked = enabled,
            onCheckedChange = { newEnabled ->
                if (!newEnabled) onConnectionDraftChanged()
                onEnabledChange(newEnabled)
            },
        )

        if (enabled) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = {
                        baseUrlDraft = it
                        draftsDirty = true
                        submittedDraft = null
                        onConnectionDraftChanged()
                    },
                    label = { Text("Adaptarr base URL") },
                    placeholder = { Text("http://192.168.1.10:9192") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                    keyboardOptions = aerioTextFieldKeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = {
                        tokenDraft = it
                        draftsDirty = true
                        submittedDraft = null
                        onConnectionDraftChanged()
                    },
                    label = { Text("Bearer token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().tvFormFieldInput(),
                    keyboardOptions = aerioTextFieldKeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            submittedDraft = baseUrlDraft to tokenDraft
                            onSaveConnection(baseUrlDraft, tokenDraft)
                        },
                        enabled = connectionState != SettingsViewModel.AdaptarrConnectionState.Saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (connectionState == SettingsViewModel.AdaptarrConnectionState.Saving) {
                                "Saving…"
                            } else {
                                "Save Connection"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Test Connection")
                    }
                }
                Text(
                    text = connectionStatusText(connectionState),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connectionState.isError()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "Connection testing becomes available with the Adaptarr protocol client.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSelectionRow(
                label = "Off",
                subtitle = "Keep adaptive decisions disabled.",
                selected = mode == AdaptiveQualityMode.Off,
                onClick = { onModeChange(AdaptiveQualityMode.Off) },
            )
            SettingsSelectionRow(
                label = "Recommend only",
                subtitle = "Show a recommendation without changing playback.",
                selected = mode == AdaptiveQualityMode.Recommend,
                onClick = { onModeChange(AdaptiveQualityMode.Recommend) },
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Automatic",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Text(
                    text = "Unavailable until controlled rollout safeguards are implemented.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
        }
    }

    if (enabled) {
        HeightSection(
            header = "Maximum Quality",
            footer = "Maximum height Adaptarr may recommend for this device.",
            current = maxHeight,
            onSelect = onMaxHeightChange,
        )
        HeightSection(
            header = "Cellular Maximum",
            footer = "Conservative cap for metered mobile connections.",
            current = cellularMaxHeight,
            onSelect = onCellularMaxHeightChange,
        )
        HeightSection(
            header = "Fallback Quality",
            footer = "Used when a recommendation cannot be obtained.",
            current = fallbackHeight,
            onSelect = onFallbackHeightChange,
        )

        SettingsSection(header = "Adaptive Status") {
            StatusValue(
                label = "Last measured speed",
                value = throughputLabel(lastMeasuredThroughputBps),
            )
            StatusValue(
                label = "Last decision",
                value = lastDecision.ifBlank { "No recommendation yet" },
            )
        }
    }
}

@Composable
private fun HeightSection(
    header: String,
    footer: String,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    SettingsSection(header = header, footer = footer) {
        listOf(720, 1080).forEach { height ->
            SettingsSelectionRow(
                label = "${height}p",
                selected = current == height,
                onClick = { onSelect(height) },
            )
        }
    }
}

@Composable
private fun StatusValue(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun connectionStatusText(state: SettingsViewModel.AdaptarrConnectionState): String =
    when (state) {
        SettingsViewModel.AdaptarrConnectionState.Idle -> "Save validates and stores the connection locally."
        SettingsViewModel.AdaptarrConnectionState.Saving -> "Saving locally…"
        SettingsViewModel.AdaptarrConnectionState.Saved -> "Connection settings saved securely."
        SettingsViewModel.AdaptarrConnectionState.InvalidBaseUrl ->
            "Enter a valid HTTP or HTTPS base URL without credentials, query, or fragment."
        SettingsViewModel.AdaptarrConnectionState.InvalidToken ->
            "Token must contain 32–512 non-whitespace characters."
        SettingsViewModel.AdaptarrConnectionState.EncryptionFailed ->
            "The token could not be encrypted. Existing settings were kept."
        SettingsViewModel.AdaptarrConnectionState.PersistenceFailed ->
            "The connection could not be saved. Existing settings were kept."
    }

private fun SettingsViewModel.AdaptarrConnectionState.isError(): Boolean =
    this == SettingsViewModel.AdaptarrConnectionState.InvalidBaseUrl ||
        this == SettingsViewModel.AdaptarrConnectionState.InvalidToken ||
        this == SettingsViewModel.AdaptarrConnectionState.EncryptionFailed ||
        this == SettingsViewModel.AdaptarrConnectionState.PersistenceFailed

internal fun throughputLabel(bitsPerSecond: Long): String =
    if (bitsPerSecond <= 0L) {
        "Not measured yet"
    } else {
        String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
    }
