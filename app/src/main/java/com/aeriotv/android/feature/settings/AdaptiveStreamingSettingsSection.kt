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
 * Device-local Adaptarr configuration. Connection testing performs read-only
 * health/config requests and cannot mutate playback or Dispatcharr state.
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
    probeState: SettingsViewModel.AdaptarrProbeState,
    onEnabledChange: (Boolean) -> Unit,
    onSaveConnection: (String, String) -> Unit,
    onTestConnection: (String, String) -> Unit,
    onRunSpeedTest: (String, String) -> Unit,
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
                        enabled = !connectionState.isBusy() && !probeState.isBusy(),
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
                        onClick = { onTestConnection(baseUrlDraft, tokenDraft) },
                        enabled = !connectionState.isBusy() && !probeState.isBusy(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (connectionState == SettingsViewModel.AdaptarrConnectionState.Testing) {
                                "Testing…"
                            } else {
                                "Test Connection"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { onRunSpeedTest(baseUrlDraft, tokenDraft) },
                        enabled = !connectionState.isBusy() && !probeState.isBusy(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (probeState == SettingsViewModel.AdaptarrProbeState.Testing) {
                                "Measuring…"
                            } else {
                                "Run Speed Test"
                            },
                        )
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
                    text = probeStatusText(probeState),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probeState.isError()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "Connection test is read-only. Speed test downloads 1.1 MB and changes neither playback nor server telemetry.",
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

private fun probeStatusText(state: SettingsViewModel.AdaptarrProbeState): String =
    when (state) {
        SettingsViewModel.AdaptarrProbeState.Idle -> "Speed has not been tested in this session."
        SettingsViewModel.AdaptarrProbeState.Testing -> "Running bounded local speed test…"
        SettingsViewModel.AdaptarrProbeState.MeasuredFresh -> "Speed measured successfully."
        SettingsViewModel.AdaptarrProbeState.MeasuredCached ->
            "Using the fresh measurement cached for this network."
        SettingsViewModel.AdaptarrProbeState.Timeout -> "Speed test timed out after five seconds."
        SettingsViewModel.AdaptarrProbeState.Unavailable ->
            "Speed test failed. Check the saved address, token, and network."
        SettingsViewModel.AdaptarrProbeState.NetworkChanged ->
            "Network changed during the test. Run it again."
    }

private fun connectionStatusText(state: SettingsViewModel.AdaptarrConnectionState): String =
    when (state) {
        SettingsViewModel.AdaptarrConnectionState.Idle -> "Save validates and stores the connection locally."
        SettingsViewModel.AdaptarrConnectionState.Saving -> "Saving locally…"
        SettingsViewModel.AdaptarrConnectionState.Saved -> "Connection settings saved securely."
        SettingsViewModel.AdaptarrConnectionState.Testing -> "Checking Adaptarr…"
        SettingsViewModel.AdaptarrConnectionState.Connected ->
            "Connection verified. Protocol v1 and token access are valid."
        SettingsViewModel.AdaptarrConnectionState.InvalidBaseUrl ->
            "Enter a valid HTTP or HTTPS base URL without credentials, query, or fragment."
        SettingsViewModel.AdaptarrConnectionState.InvalidToken ->
            "Token must contain 32–512 non-whitespace characters."
        SettingsViewModel.AdaptarrConnectionState.InvalidConnectionSettings ->
            "Enter a valid HTTP or HTTPS URL and a 32–512 character token."
        SettingsViewModel.AdaptarrConnectionState.EncryptionFailed ->
            "The token could not be encrypted. Existing settings were kept."
        SettingsViewModel.AdaptarrConnectionState.PersistenceFailed ->
            "The connection could not be saved. Existing settings were kept."
        SettingsViewModel.AdaptarrConnectionState.Unauthorized ->
            "Adaptarr rejected the bearer token."
        SettingsViewModel.AdaptarrConnectionState.IncompatibleProtocol ->
            "This Adaptarr server does not support protocol v1."
        SettingsViewModel.AdaptarrConnectionState.RateLimited ->
            "Adaptarr is busy. Try the connection test again shortly."
        SettingsViewModel.AdaptarrConnectionState.ServiceUnavailable ->
            "Adaptarr is temporarily unavailable."
        SettingsViewModel.AdaptarrConnectionState.InvalidResponse ->
            "Adaptarr returned an invalid or unexpected response."
        SettingsViewModel.AdaptarrConnectionState.Unreachable ->
            "Adaptarr could not be reached. Check the address and network."
    }

private fun SettingsViewModel.AdaptarrConnectionState.isError(): Boolean =
    this == SettingsViewModel.AdaptarrConnectionState.InvalidBaseUrl ||
        this == SettingsViewModel.AdaptarrConnectionState.InvalidToken ||
        this == SettingsViewModel.AdaptarrConnectionState.InvalidConnectionSettings ||
        this == SettingsViewModel.AdaptarrConnectionState.EncryptionFailed ||
        this == SettingsViewModel.AdaptarrConnectionState.PersistenceFailed ||
        this == SettingsViewModel.AdaptarrConnectionState.Unauthorized ||
        this == SettingsViewModel.AdaptarrConnectionState.IncompatibleProtocol ||
        this == SettingsViewModel.AdaptarrConnectionState.RateLimited ||
        this == SettingsViewModel.AdaptarrConnectionState.ServiceUnavailable ||
        this == SettingsViewModel.AdaptarrConnectionState.InvalidResponse ||
        this == SettingsViewModel.AdaptarrConnectionState.Unreachable

private fun SettingsViewModel.AdaptarrProbeState.isError(): Boolean =
    this == SettingsViewModel.AdaptarrProbeState.Timeout ||
        this == SettingsViewModel.AdaptarrProbeState.Unavailable ||
        this == SettingsViewModel.AdaptarrProbeState.NetworkChanged

private fun SettingsViewModel.AdaptarrProbeState.isBusy(): Boolean =
    this == SettingsViewModel.AdaptarrProbeState.Testing

private fun SettingsViewModel.AdaptarrConnectionState.isBusy(): Boolean =
    this == SettingsViewModel.AdaptarrConnectionState.Saving ||
        this == SettingsViewModel.AdaptarrConnectionState.Testing

internal fun throughputLabel(bitsPerSecond: Long): String =
    if (bitsPerSecond <= 0L) {
        "Not measured yet"
    } else {
        String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
    }
