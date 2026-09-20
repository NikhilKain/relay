package com.vythera.relay.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.component.RelayActionEmphasis
import com.vythera.relay.designsystem.component.RelayActionSize
import com.vythera.relay.designsystem.component.RelayActionTile
import com.vythera.relay.designsystem.component.RelayActivityItem
import com.vythera.relay.designsystem.component.RelayContentKind
import com.vythera.relay.designsystem.component.RelayDeviceAvatar
import com.vythera.relay.designsystem.component.RelayDeviceCard
import com.vythera.relay.designsystem.component.RelayDeviceCardStyle
import com.vythera.relay.designsystem.component.RelayEmptyState
import com.vythera.relay.designsystem.component.RelayListPosition
import com.vythera.relay.designsystem.component.RelayLockup
import com.vythera.relay.designsystem.component.RelayPresence
import com.vythera.relay.designsystem.component.RelaySectionHeader
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelayStatusChip
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import com.vythera.relay.designsystem.theme.SmoothRoundedShape
import com.vythera.relay.desktop.HistoryEntry
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.protocol.DeviceId
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.DataFlavor
import java.io.File

/** What the device pane can ask the shell to do. */
class DeviceActions(
    val select: (DeviceId) -> Unit,
    val connect: (DeviceId) -> Unit,
    val sendFiles: (DeviceId, List<File>) -> Unit,
    val chooseFiles: (DeviceId) -> Unit,
    val chooseFolder: (DeviceId) -> Unit,
    val sendClipboard: (DeviceId) -> Unit,
    val composeText: (DeviceId) -> Unit,
    val composeLink: (DeviceId) -> Unit,
    val setAutoAccept: (DeviceId, Boolean) -> Unit,
    val forget: (DeviceId) -> Unit,
    val help: () -> Unit,
)

/**
 * Devices, desktop-style: the list stays on the left and the chosen device fills the
 * rest of the window, so picking a device never leaves the list. The device side is
 * built around a drop zone, the desktop's most natural way to send.
 */
@Composable
fun DevicesPage(devices: List<DeviceView>, selected: DeviceView?, history: List<HistoryEntry>, localName: String, actions: DeviceActions) {
    Row(Modifier.fillMaxSize()) {
        DeviceList(devices, selected?.id, actions, Modifier.width(400.dp).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (selected != null) {
                DeviceDetail(selected, history.filter { it.peerId == selected.id.value }, localName, actions)
            } else if (devices.isNotEmpty()) {
                Text(stringResource(Res.string.home_pick), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<DeviceView>, selectedId: DeviceId?, actions: DeviceActions, modifier: Modifier) {
    val trusted = devices.filter { it.trusted }
    val fresh = devices.filter { !it.trusted && it.presence != RelayPresence.Offline }
    val nearby = devices.count { it.presence != RelayPresence.Offline }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RelayLockup(markSize = 28.dp)
        Spacer(Modifier.height(RelaySpacing.xl))
        Text(stringResource(Res.string.home_title), style = MaterialTheme.typography.displaySmallEmphasized, modifier = Modifier.semantics { heading() })
        Text(
            if (nearby > 0) stringResource(Res.string.home_nearby, nearby) else stringResource(Res.string.home_looking),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(RelaySpacing.s))
        trusted.forEach { device ->
            RelayDeviceCard(
                name = device.name,
                platformLabel = device.platformLabel,
                kind = device.kind,
                presence = device.presence,
                onClick = { actions.select(device.id) },
                style = RelayDeviceCardStyle.Wide,
                trusted = true,
                busy = device.busy,
                selected = device.id == selectedId,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (fresh.isNotEmpty()) {
            RelaySectionHeader(stringResource(Res.string.home_new), Modifier.padding(top = RelaySpacing.m))
            fresh.forEach { device ->
                RelayDeviceCard(
                    name = device.name,
                    platformLabel = stringResource(Res.string.home_tap_to_connect),
                    kind = device.kind,
                    presence = device.presence,
                    onClick = { actions.select(device.id) },
                    style = RelayDeviceCardStyle.Wide,
                    selected = device.id == selectedId,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (devices.isEmpty()) {
            RelayEmptyState(
                title = stringResource(Res.string.home_empty_title),
                body = stringResource(Res.string.home_empty_body),
                actionLabel = stringResource(Res.string.home_empty_action),
                onAction = actions.help,
            )
        }
    }
}

@Composable
private fun DeviceDetail(device: DeviceView, history: List<HistoryEntry>, localName: String, actions: DeviceActions) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 40.dp, top = 40.dp, bottom = 40.dp).widthIn(max = 880.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RelayDeviceAvatar(
                device.kind,
                size = 96.dp,
                containerColor = if (device.presence == RelayPresence.Offline) colors.surfaceContainerHighest else colors.primary,
                contentColor = if (device.presence == RelayPresence.Offline) colors.onSurfaceVariant else colors.onPrimary,
                busy = device.busy,
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.displaySmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(device.platformLabel, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
            }
            RelayStatusChip(device.presence, trusted = device.trusted)
        }
        Spacer(Modifier.height(RelaySpacing.xxl))

        when {
            !device.compatible -> Text(stringResource(Res.string.device_incompatible, device.name), style = MaterialTheme.typography.bodyLarge)
            !device.trusted -> {
                Text(stringResource(Res.string.device_connect_body, device.name), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(RelaySpacing.xl))
                RelaySendButton(onClick = { actions.connect(device.id) }, text = stringResource(Res.string.device_connect), icon = Icons.Rounded.Link, enabled = device.reachable, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
            }
            else -> {
                if (device.presence == RelayPresence.Offline) {
                    Text(stringResource(Res.string.device_away_body, device.name), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(RelaySpacing.l))
                }
                DropZone(device, onFiles = { actions.sendFiles(device.id, it) }, onChoose = { actions.chooseFiles(device.id) })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RelayActionTile(stringResource(Res.string.device_folder), Icons.Rounded.Folder, { actions.chooseFolder(device.id) }, Modifier.weight(1f), stringResource(Res.string.device_folder_hint), RelayActionEmphasis.Tertiary, RelayActionSize.Medium, MaterialShapes.Bun)
                    RelayActionTile(stringResource(Res.string.device_clipboard), Icons.Rounded.ContentPaste, { actions.sendClipboard(device.id) }, Modifier.weight(1f), stringResource(Res.string.device_clipboard_hint), RelayActionEmphasis.Secondary, RelayActionSize.Medium, MaterialShapes.Gem)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RelayActionTile(stringResource(Res.string.device_text), Icons.AutoMirrored.Rounded.TextSnippet, { actions.composeText(device.id) }, Modifier.weight(1f), stringResource(Res.string.device_text_hint), RelayActionEmphasis.Neutral, RelayActionSize.Medium, MaterialShapes.Ghostish)
                    RelayActionTile(stringResource(Res.string.device_continue), Icons.AutoMirrored.Rounded.OpenInNew, { actions.composeLink(device.id) }, Modifier.weight(1f), stringResource(Res.string.device_continue_hint, device.name), RelayActionEmphasis.Neutral, RelayActionSize.Medium, MaterialShapes.Cookie7Sided)
                }

                RelaySectionHeader(stringResource(Res.string.device_recent), Modifier.padding(top = RelaySpacing.section))
                if (history.isEmpty()) {
                    Text(stringResource(Res.string.device_nothing_yet), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                } else {
                    HistoryGroup(history.take(6), localName)
                }

                Spacer(Modifier.height(RelaySpacing.section))
                Surface(shape = RelayShapes.Small, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.device_auto_accept, device.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(checked = device.autoAccept, onCheckedChange = { actions.setAutoAccept(device.id, it) })
                    }
                }
                TextButton(onClick = { actions.forget(device.id) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(Res.string.device_forget), color = colors.error, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/**
 * The big target for dragged files. While something is dragged over it the surface
 * turns primary and its corners soften, so there is no doubt where a drop will land.
 */
@Composable
private fun DropZone(device: DeviceView, onFiles: (List<File>) -> Unit, onChoose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    var hovering by remember { mutableStateOf(false) }
    val container by animateColorAsState(if (hovering) colors.primary else colors.primaryContainer, motion.effects(), label = "dropContainer")
    val content by animateColorAsState(if (hovering) colors.onPrimary else colors.onPrimaryContainer, motion.effects(), label = "dropContent")
    val corner by animateDpAsState(if (hovering) 64.dp else 44.dp, motion.spatial(), label = "dropCorner")
    val target = remember(device.id) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                hovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                if (files.isEmpty()) return false
                onFiles(files)
                return true
            }
        }
    }
    Surface(
        shape = remember(corner) { SmoothRoundedShape(corner, corner, corner, 18.dp) },
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .dragAndDropTarget(shouldStartDragAndDrop = { event -> event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }, target = target),
    ) {
        Row(Modifier.padding(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(120.dp).background(content.copy(alpha = 0.14f), MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(52.dp))
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (hovering) stringResource(Res.string.device_drop_release, device.name) else stringResource(Res.string.device_drop_title),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.device_drop_body, device.name), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                RelaySendButton(
                    onClick = onChoose,
                    text = stringResource(Res.string.device_choose_files),
                    size = com.vythera.relay.designsystem.component.RelaySendButtonSize.Large,
                    containerColor = if (hovering) colors.onPrimary else colors.primary,
                    contentColor = if (hovering) colors.primary else colors.onPrimary,
                )
            }
        }
    }
}

@Composable
fun HistoryGroup(entries: List<HistoryEntry>, localName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        entries.forEachIndexed { index, entry ->
            RelayActivityItem(
                title = entry.title,
                fromName = if (entry.incoming) entry.peerName else localName,
                toName = if (entry.incoming) localName else entry.peerName,
                timeLabel = relativeTime(entry.timeMillis),
                kind = contentKind(entry.category),
                position = when {
                    entries.size == 1 -> RelayListPosition.Single
                    index == 0 -> RelayListPosition.First
                    index == entries.lastIndex -> RelayListPosition.Last
                    else -> RelayListPosition.Middle
                },
            )
        }
    }
}

fun contentKind(category: String) = when (category) {
    "photos" -> RelayContentKind.Photos
    "videos" -> RelayContentKind.Videos
    "folder" -> RelayContentKind.Folder
    "link" -> RelayContentKind.Link
    "text" -> RelayContentKind.Text
    "clipboard" -> RelayContentKind.Clipboard
    else -> RelayContentKind.Files
}
