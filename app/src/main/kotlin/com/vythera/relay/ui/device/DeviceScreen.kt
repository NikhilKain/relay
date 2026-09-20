package com.vythera.relay.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vythera.relay.R
import com.vythera.relay.ui.common.ActivityUi
import com.vythera.relay.ui.common.DeviceUi
import com.vythera.relay.ui.common.relaySharedBounds
import com.vythera.relay.ui.home.ActivityGroup
import com.vythera.relay.designsystem.component.RelayActionEmphasis
import com.vythera.relay.designsystem.component.RelayActionSize
import com.vythera.relay.designsystem.component.RelayActionTile
import com.vythera.relay.designsystem.component.RelayDeviceAvatar
import com.vythera.relay.designsystem.component.RelayPresence
import com.vythera.relay.designsystem.component.RelaySectionHeader
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelayStatusChip
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing

/** What the send actions on this screen can ask for. The root decides how (pickers, sheets). */
enum class SendAction { Anything, Photos, Files, Folder, Text, Link, Clipboard, Continue }

/**
 * One device, full screen. It grows out of the card that was tapped (shared bounds), so
 * the device's surface colour and shape carry over and the name lands where the eye
 * already is. The primary action dominates; the rest are progressively smaller.
 */
@Composable
fun DeviceScreen(
    device: DeviceUi,
    activity: List<ActivityUi>,
    onBack: (() -> Unit)?,
    onSend: (SendAction) -> Unit,
    onConnect: () -> Unit,
    onAutoAcceptChange: (Boolean) -> Unit,
    onForget: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalMargin: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val colors = MaterialTheme.colorScheme
    var confirmForget by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize().relaySharedBounds("device-${device.id}", RelayShapes.Hero),
        color = colors.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalMargin)
                .padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 32.dp),
        ) {
            if (onBack != null) {
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(bottom = RelaySpacing.m)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }

            // Header: the device, large.
            Row(verticalAlignment = Alignment.Top) {
                RelayDeviceAvatar(
                    kind = device.kind,
                    size = 104.dp,
                    containerColor = if (device.presence == RelayPresence.Offline) colors.surfaceContainerHighest else colors.primary,
                    contentColor = if (device.presence == RelayPresence.Offline) colors.onSurfaceVariant else colors.onPrimary,
                    busy = device.activeTransfer != null,
                )
                Spacer(Modifier.weight(1f))
                RelayStatusChip(device.presence, trusted = device.trusted)
            }
            Spacer(Modifier.height(RelaySpacing.xl))
            Text(
                device.name,
                style = MaterialTheme.typography.displayMediumEmphasized,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(device.platformLabel, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(RelaySpacing.xxl))

            when {
                !device.compatible -> Explanation(stringResource(R.string.device_incompatible, device.name))
                !device.trusted -> {
                    Explanation(stringResource(R.string.device_connect_body, device.name))
                    Spacer(Modifier.height(RelaySpacing.xl))
                    RelaySendButton(
                        onClick = onConnect,
                        text = stringResource(R.string.device_connect),
                        icon = Icons.Rounded.Link,
                        enabled = device.reachable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    if (device.presence == RelayPresence.Offline) {
                        Explanation(stringResource(R.string.device_away_body, device.name))
                        Spacer(Modifier.height(RelaySpacing.l))
                    }
                    RelaySendButton(onClick = { onSend(SendAction.Anything) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(RelaySpacing.xl))
                    SendActions(device, onSend)
                }
            }

            if (device.trusted) {
                RelaySectionHeader(stringResource(R.string.device_recent), Modifier.padding(top = RelaySpacing.section))
                if (activity.isEmpty()) {
                    Text(stringResource(R.string.device_nothing_yet), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                } else {
                    ActivityGroup(activity.take(6))
                }

                RelaySectionHeader(stringResource(R.string.device_settings), Modifier.padding(top = RelaySpacing.section))
                Surface(shape = RelayShapes.Small, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.device_auto_accept), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.device_auto_accept_body, device.name), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = device.autoAccept, onCheckedChange = onAutoAcceptChange)
                    }
                }
                Spacer(Modifier.height(RelaySpacing.s))
                TextButton(onClick = { confirmForget = true }) {
                    Text(stringResource(R.string.device_forget), color = colors.error, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            shape = RelayShapes.Dialog,
            title = { Text(stringResource(R.string.device_forget_confirm_title, device.name)) },
            text = { Text(stringResource(R.string.device_forget_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) {
                    Text(stringResource(R.string.device_forget_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** Large tiles for what people send most, compact pills for the rest. */
@Composable
private fun SendActions(device: DeviceUi, onSend: (SendAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayActionTile(
                label = stringResource(R.string.send_photos),
                icon = Icons.Rounded.Photo,
                onClick = { onSend(SendAction.Photos) },
                supportingText = stringResource(R.string.send_photos_hint),
                emphasis = RelayActionEmphasis.Primary,
                size = RelayActionSize.Large,
                badgeShape = MaterialShapes.Flower,
                modifier = Modifier.weight(1.1f),
            )
            RelayActionTile(
                label = stringResource(R.string.send_files),
                icon = Icons.Rounded.Description,
                onClick = { onSend(SendAction.Files) },
                supportingText = stringResource(R.string.send_files_hint),
                emphasis = RelayActionEmphasis.Tertiary,
                size = RelayActionSize.Large,
                badgeShape = MaterialShapes.Square,
                modifier = Modifier.weight(1f),
            )
        }
        RelayActionTile(
            label = stringResource(R.string.send_continue),
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            onClick = { onSend(SendAction.Continue) },
            supportingText = stringResource(R.string.send_continue_hint, device.name),
            emphasis = RelayActionEmphasis.Secondary,
            size = RelayActionSize.Medium,
            badgeShape = MaterialShapes.Cookie7Sided,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RelayActionTile(stringResource(R.string.send_text), Icons.AutoMirrored.Rounded.TextSnippet, { onSend(SendAction.Text) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Ghostish)
            RelayActionTile(stringResource(R.string.send_link), Icons.Rounded.Link, { onSend(SendAction.Link) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Cookie4Sided)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RelayActionTile(stringResource(R.string.send_clipboard), Icons.Rounded.ContentPaste, { onSend(SendAction.Clipboard) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Gem)
            RelayActionTile(stringResource(R.string.send_folder), Icons.Rounded.Folder, { onSend(SendAction.Folder) }, Modifier.weight(1f), emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact, badgeShape = MaterialShapes.Bun)
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
