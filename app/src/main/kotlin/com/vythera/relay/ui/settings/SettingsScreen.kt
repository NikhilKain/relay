package com.vythera.relay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vythera.relay.BuildConfig
import com.vythera.relay.R
import com.vythera.relay.clipboard.InstantClipboard
import com.vythera.relay.data.settings.ClipboardMode
import com.vythera.relay.data.settings.ColorPreference
import com.vythera.relay.data.settings.RelaySettings
import com.vythera.relay.designsystem.component.RelayMark
import com.vythera.relay.designsystem.component.RelaySectionHeader
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing

@Composable
fun SettingsScreen(
    settings: RelaySettings,
    currentName: String,
    onRename: (String) -> Unit,
    onClipboardMode: (ClipboardMode) -> Unit,
    onColors: (ColorPreference) -> Unit,
    onStayAvailable: (Boolean) -> Unit,
    runsUnrestricted: Boolean,
    onAllowBackground: () -> Unit,
    onHelp: () -> Unit,
    onShareContact: () -> Unit,
    saveFolderName: String?,
    onPickSaveFolder: () -> Unit,
    onResetSaveFolder: () -> Unit,
    ecosystem: EcosystemSettings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalMargin: Dp = 20.dp,
) {
    var renaming by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalMargin)
            .padding(top = contentPadding.calculateTopPadding() + RelaySpacing.xxl, bottom = contentPadding.calculateBottomPadding() + 32.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.displayMediumEmphasized, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(RelaySpacing.xl))

        // The device's own name is the largest thing here: it is what other devices see.
        Surface(onClick = { renaming = true }, shape = RelayShapes.Hero, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.settings_device), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(currentName, style = MaterialTheme.typography.headlineLargeEmphasized)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_rename), style = MaterialTheme.typography.titleSmall, color = colors.primary)
            }
        }

        Spacer(Modifier.height(8.dp))
        Surface(onClick = onShareContact, shape = RelayShapes.Small, color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.contact_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp))
        }
        Spacer(Modifier.height(8.dp))
        Surface(onClick = onHelp, shape = RelayShapes.Small, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp))
        }

        EcosystemSection(settings, ecosystem)

        RelaySectionHeader(stringResource(R.string.settings_clipboard), Modifier.padding(top = RelaySpacing.xxl))
        Text(stringResource(R.string.settings_clipboard_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(RelaySpacing.m))
        Choice(
            options = listOf(ClipboardMode.OFF to R.string.settings_clipboard_off, ClipboardMode.ASK to R.string.settings_clipboard_ask, ClipboardMode.AUTOMATIC to R.string.settings_clipboard_auto),
            selected = settings.clipboardMode,
            onSelect = onClipboardMode,
        )

        RelaySectionHeader(stringResource(R.string.settings_appearance), Modifier.padding(top = RelaySpacing.xxl))
        Choice(
            options = listOf(ColorPreference.WALLPAPER to R.string.settings_colors_wallpaper, ColorPreference.RELAY to R.string.settings_colors_relay),
            selected = settings.colors,
            onSelect = onColors,
        )

        RelaySectionHeader(stringResource(R.string.settings_save_to), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            Text(
                saveFolderName ?: stringResource(R.string.settings_save_default),
                style = if (saveFolderName != null) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onPickSaveFolder, shapes = androidx.compose.material3.ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                    Text(stringResource(R.string.settings_save_change))
                }
                if (saveFolderName != null) {
                    androidx.compose.material3.FilledTonalButton(onClick = onResetSaveFolder, shapes = androidx.compose.material3.ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                        Text(stringResource(R.string.settings_save_reset))
                    }
                }
            }
        }

        RelaySectionHeader(stringResource(R.string.settings_background), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_background_body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.stayAvailable, onCheckedChange = onStayAvailable)
            }
        }
        Spacer(Modifier.height(8.dp))
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (runsUnrestricted) R.string.settings_battery_done else R.string.settings_battery_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!runsUnrestricted) {
                        Text(stringResource(R.string.settings_battery_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                if (!runsUnrestricted) {
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Button(
                        onClick = onAllowBackground,
                        shapes = androidx.compose.material3.ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall),
                    ) { Text(stringResource(R.string.settings_battery_action)) }
                }
            }
        }

        if (com.vythera.relay.analytics.RelayAnalytics.isAvailable) {
            RelaySectionHeader(stringResource(R.string.settings_privacy), Modifier.padding(top = RelaySpacing.xxl))
            Group {
                SwitchRow(
                    stringResource(R.string.settings_analytics_title),
                    stringResource(R.string.settings_analytics_body),
                    settings.analytics,
                    ecosystem.onAnalytics,
                )
            }
        }

        RelaySectionHeader(stringResource(R.string.settings_about), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RelayMark(size = 40.dp, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            shape = RelayShapes.Dialog,
            title = { Text(stringResource(R.string.settings_device_name)) },
            text = { TextField(value = name, onValueChange = { if (it.length <= 40) name = it }, singleLine = true, shape = RelayShapes.Small) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name); renaming = false }) { Text(stringResource(R.string.settings_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** What the Ecosystem section shows and can do. */
class EcosystemSettings(
    val status: InstantClipboard.Status,
    val grantCommand: String,
    val onEcosystem: (Boolean) -> Unit,
    val onInstantClipboard: (Boolean) -> Unit,
    val onAllowOverlay: () -> Unit,
    val onCopyCommand: () -> Unit,
    val onAddWidget: () -> Unit,
    val onAnalytics: (Boolean) -> Unit,
    val onRetryInstant: () -> Unit,
)

/**
 * Ecosystem: trusted devices act as one. The main switch needs nothing else; Instant
 * clipboard needs two one-time permissions, so it walks through them one step at a time.
 */
@Composable
private fun EcosystemSection(settings: RelaySettings, ecosystem: EcosystemSettings) {
    val colors = MaterialTheme.colorScheme
    RelaySectionHeader(stringResource(R.string.settings_ecosystem), Modifier.padding(top = RelaySpacing.xxl))
    Group {
        SwitchRow(stringResource(R.string.settings_ecosystem_title), stringResource(R.string.settings_ecosystem_body), settings.ecosystem, ecosystem.onEcosystem)
    }
    if (!settings.ecosystem) return

    Spacer(Modifier.height(8.dp))
    Group {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_widget_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_widget_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = ecosystem.onAddWidget, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                Text(stringResource(R.string.settings_widget_action))
            }
        }
    }

    if (!InstantClipboard.isSupportedHere) return

    Spacer(Modifier.height(8.dp))
    Group {
        SwitchRow(stringResource(R.string.settings_instant_title), stringResource(R.string.settings_instant_body), settings.instantClipboard, ecosystem.onInstantClipboard)
        if (!settings.instantClipboard || settings.clipboardMode == ClipboardMode.OFF) return@Group
        Spacer(Modifier.height(16.dp))
        when (ecosystem.status) {
            InstantClipboard.Status.NeedsLogAccess -> {
                Step(stringResource(R.string.settings_instant_step1), stringResource(R.string.settings_instant_step1_body))
                Spacer(Modifier.height(12.dp))
                Surface(shape = RelayShapes.ExtraSmall, color = colors.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        ecosystem.grantCommand,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = ecosystem.onCopyCommand, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                    Text(stringResource(R.string.settings_instant_copy))
                }
            }
            InstantClipboard.Status.NeedsOverlay -> {
                Step(stringResource(R.string.settings_instant_step2), stringResource(R.string.settings_instant_step2_body))
                Spacer(Modifier.height(12.dp))
                Button(onClick = ecosystem.onAllowOverlay, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                    Text(stringResource(R.string.settings_instant_allow))
                }
            }
            InstantClipboard.Status.WaitingForApproval -> {
                Step(stringResource(R.string.settings_instant_waiting), stringResource(R.string.settings_instant_waiting_body))
                Spacer(Modifier.height(12.dp))
                Button(onClick = ecosystem.onRetryInstant, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                    Text(stringResource(R.string.settings_instant_retry))
                }
            }
            InstantClipboard.Status.Active ->
                Text(stringResource(R.string.settings_instant_active), style = MaterialTheme.typography.titleSmall, color = colors.primary)
            InstantClipboard.Status.Off -> Unit
        }
    }
}

@Composable
private fun Step(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Connected toggle buttons: Material 3 Expressive's button group, where the selected one widens. */
@Composable
private fun <T> Choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, (value, label) ->
            val isSelected = value == selected
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { onSelect(value) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(if (isSelected) 1.3f else 1f).height(56.dp).semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RelayShapes.Small, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), content = content)
    }
}
