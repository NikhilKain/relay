package com.vythera.relay.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.theme.RelayShapes

data class RelayToolEntry(val id: String, val title: String, val subtitle: String, val kind: String)

/** Same compact, progressively disclosed controls on phone and desktop. */
@Composable
fun RelayToolsPanel(
    peerKey: String,
    reachable: Boolean,
    busy: Boolean,
    available: Set<String>,
    localGroups: Set<String>,
    allowed: Set<String>,
    action: String,
    message: String,
    success: Boolean,
    values: Map<String, String>,
    entries: List<RelayToolEntry>,
    onRequest: (String, Map<String, String>) -> Unit,
    onAllow: (String, Boolean) -> Unit,
    localSetup: @Composable () -> Unit = {},
    liveStatus: Map<String, String> = emptyMap(),
    remoteSetup: @Composable () -> Unit = {},
) {
    var section by remember(peerKey) { mutableStateOf<String?>(null) }
    var showAccess by remember(peerKey) { mutableStateOf(false) }
    var text by remember(peerKey, section) { mutableStateOf("") }
    var reply by remember(peerKey) { mutableStateOf<RelayToolEntry?>(null) }
    var command by remember(peerKey) { mutableStateOf<RelayToolEntry?>(null) }
    var folders by remember(peerKey) { mutableStateOf(listOf("")) }
    var player by remember(peerKey) { mutableStateOf("") }
    val enabled = reachable && !busy
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(action, values, success, busy) {
        if (!busy && success && action.startsWith("media.")) values["Player"]?.let { player = it }
    }
    fun send(action: String, vararg args: Pair<String, String>) = onRequest(action, mapOf(*args))

    RelaySectionHeader("Device tools", Modifier.padding(top = 24.dp))
    Surface(shape = RelayShapes.Small, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!reachable) Text("Device is away. Tools will be available when it reconnects.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { send("discover") }, enabled = enabled) { Text(if (available.isEmpty()) "Load tools" else "Refresh tools") }
                if ("status" in available) TextButton(onClick = { send("status.get") }, enabled = enabled) { Text("Status") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (reachable && liveStatus.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    liveStatus.forEach { (key, value) ->
                        Surface(shape = RelayShapes.Small, color = colors.surfaceContainerHigh) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(key, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
            if ("ring" in available) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { send("ring.start") }, enabled = enabled) { Icon(Icons.Rounded.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text("Find device") }
                TextButton(onClick = { send("ring.stop") }, enabled = enabled) { Text("Stop ringing") }
            }
            if ("ping" in available) TextButton(onClick = { send("ping.send") }, enabled = enabled) { Text("Send a ping") }
            if (available.isNotEmpty()) {
                val sections = listOf("media", "volume", "input", "presentation", "keepawake", "notifications", "calls", "files", "commands", "contacts", "lock")
                sections.filter { it in available }.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { group ->
                            Surface(
                                onClick = {
                                    section = if (section == group) null else group
                                    if (section != null) when (group) {
                                        "files" -> { folders = listOf(""); send("files.list") }
                                        "notifications", "contacts", "commands" -> send("$group.list")
                                        "calls" -> send("calls.status")
                                    }
                                },
                                enabled = enabled,
                                shape = RelayShapes.Small,
                                color = if (section == group) colors.primaryContainer else when (group) {
                                    "media", "volume", "presentation", "keepawake" -> colors.secondaryContainer
                                    "notifications", "calls", "contacts" -> colors.tertiaryContainer
                                    else -> colors.surfaceContainerHigh
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 104.dp),
                            ) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(toolGroupIcon(group), null, Modifier.size(28.dp))
                                    Text(toolGroupLabel(group), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
            when (section) {
                "keepawake" -> ToolButtons(listOf("Keep awake for 30 min" to "start", "Allow sleep" to "stop"), enabled) { send("keepawake.$it") }
                "calls" -> {
                    TextButton(onClick = { send("calls.status") }, enabled = enabled) { Text("Refresh call state") }
                    if (action == "calls.status") values.forEach { (key, value) -> Text("$key: $value") }
                    TextButton(onClick = { send("calls.silence") }, enabled = enabled) { Text("Silence phone ringer") }
                }
                "media" -> {
                    if (player.isNotBlank()) TextButton(onClick = { player = ""; send("media.status") }, enabled = enabled) { Text("Use active player") }
                    TextButton(onClick = { send("media.status", "player" to player) }, enabled = enabled) { Text("Players & playback details") }
                    ToolButtons(listOf("Previous" to "previous", "Play / pause" to "playpause", "Next" to "next", "Stop" to "stop"), enabled) { send("media.control", "action" to it, "player" to player) }
                    if (action.startsWith("media.") && success) {
                        values.filterKeys { it != "canSeek" }.forEach { (key, value) -> if (value.isNotBlank()) Text("$key: $value", style = MaterialTheme.typography.bodyMedium) }
                        if (values["canSeek"] == "true") ToolButtons(listOf("Back 10 seconds" to "-10", "Forward 10 seconds" to "10"), enabled) {
                            send("media.seek", "seconds" to it, "player" to player)
                        }
                    }
                }
                "volume" -> ToolButtons(listOf("Quieter" to "down", "Louder" to "up", "Mute / unmute" to "mute"), enabled) { send("volume.control", "action" to it) }
                "presentation" -> ToolButtons(listOf("Start slides" to "start", "Previous" to "previous", "Next" to "next", "Black screen" to "black", "End" to "escape"), enabled) { send("presentation.key", "key" to it) }
                "lock" -> Button(onClick = { send("lock.now") }, enabled = enabled) { Text("Lock the other device now") }
                "input" -> {
                    Text("Drag to move the pointer. Uses a basic Latin keyboard layout.", style = MaterialTheme.typography.bodySmall)
                    // Batch each gesture into one bounded request; avoids flooding the socket.
                    var dx by remember(peerKey) { mutableStateOf(0f) }
                    var dy by remember(peerKey) { mutableStateOf(0f) }
                    Box(Modifier.fillMaxWidth().height(120.dp).background(colors.surfaceContainerHighest, RelayShapes.Small)
                        .pointerInput(enabled) { if (enabled) detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragEnd = { send("input.move", "x" to dx.toInt().toString(), "y" to dy.toInt().toString()) },
                            onDrag = { change, amount -> change.consume(); dx += amount.x; dy += amount.y },
                        ) }, contentAlignment = Alignment.Center) { Text("Trackpad", color = colors.onSurfaceVariant) }
                    ToolButtons(listOf("Click" to "left", "Right click" to "right"), enabled) { send("input.click", "button" to it) }
                    ToolButtons(listOf("Scroll up" to "-3", "Scroll down" to "3"), enabled) { send("input.scroll", "amount" to it) }
                    OutlinedTextField(text, { text = it.take(500) }, label = { Text("Type on this device") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { send("input.text", "text" to text); text = "" }, enabled = enabled && text.isNotEmpty()) { Text("Type text") }
                    ToolButtons(listOf("Enter" to "enter", "Backspace" to "backspace", "Tab" to "tab", "Escape" to "escape"), enabled) { send("input.key", "key" to it) }
                }
                "contacts" -> {
                    OutlinedTextField(text, { text = it.take(100) }, label = { Text("Search contacts") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { send("contacts.list", "query" to text) }, enabled = enabled) { Text("Search") }
                }
                "files" -> Row {
                    TextButton(onClick = { folders = listOf(""); send("files.list") }, enabled = enabled) { Text("Shared folder") }
                    TextButton(onClick = { folders = folders.dropLast(1); send("files.list", "path" to folders.last()) }, enabled = enabled && folders.size > 1) { Text("Back") }
                }
                "notifications", "commands" -> TextButton(onClick = { send("$section.list") }, enabled = enabled) { Text("Refresh") }
            }
            if (message.isNotBlank()) Text(message, color = if (success) colors.onSurfaceVariant else colors.error, style = MaterialTheme.typography.bodyMedium)
            if (action == "status.get" && success) values.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodyLarge) }
            if (section != null && action.substringBefore('.') == section) {
                entries.forEach { entry ->
                    Surface(shape = RelayShapes.Small, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall)
                            if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodySmall)
                            when (entry.kind) {
                                "player" -> TextButton(onClick = { player = entry.id; send("media.status", "player" to player) }, enabled = enabled) { Text(if (player == entry.id) "Selected" else "Use player") }
                                "folder" -> TextButton(onClick = { folders = folders + entry.id; send("files.list", "path" to entry.id) }, enabled = enabled) { Text("Open") }
                                "file" -> TextButton(onClick = { send("files.get", "path" to entry.id) }, enabled = enabled) { Text("Download") }
                                "command" -> TextButton(onClick = { command = entry }, enabled = enabled) { Text("Run") }
                                "notification", "reply" -> Row {
                                    if (entry.kind == "reply") TextButton(onClick = { reply = entry; text = "" }, enabled = enabled) { Text("Reply") }
                                    TextButton(onClick = { send("notifications.dismiss", "id" to entry.id) }, enabled = enabled) { Text("Dismiss") }
                                }
                            }
                        }
                    }
                }
                if (section == "files" && values["next"] != null) TextButton(onClick = { send("files.list", "path" to values["path"].orEmpty(), "offset" to values.getValue("next")) }, enabled = enabled) { Text("Next page") }
            }
            HorizontalDivider()
            TextButton(onClick = { showAccess = !showAccess }) { Icon(Icons.Rounded.Security, null); Spacer(Modifier.width(8.dp)); Text("Permissions & setup") }
            if (showAccess) {
                remoteSetup()
                Text("Choose what this paired device can access here. Status, finding this device, and pings are available after pairing.", style = MaterialTheme.typography.bodySmall)
                localGroups.filter { it !in setOf("status", "ring", "ping") }.sorted().forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(toolGroupLabel(group), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = group in allowed, onCheckedChange = { onAllow(group, it) })
                    }
                }
                localSetup()
            }
        }
    }
    reply?.let { notification -> AlertDialog(
        onDismissRequest = { reply = null }, title = { Text("Reply to ${notification.title}") },
        text = { OutlinedTextField(text, { text = it.take(4096) }, label = { Text("Message") }) },
        confirmButton = { TextButton(onClick = { send("notifications.reply", "id" to notification.id, "text" to text); reply = null }, enabled = enabled && text.isNotBlank()) { Text("Send reply") } },
        dismissButton = { TextButton(onClick = { reply = null }) { Text("Cancel") } },
    ) }
    command?.let { item -> AlertDialog(
        onDismissRequest = { command = null }, title = { Text("Run ${item.title}?") }, text = { Text("This runs the command configured on the other computer.") },
        confirmButton = { TextButton(onClick = { send("commands.run", "id" to item.id); command = null }, enabled = enabled) { Text("Run") } },
        dismissButton = { TextButton(onClick = { command = null }) { Text("Cancel") } },
    ) }
}

private fun toolGroupIcon(group: String) = when (group) {
    "media" -> Icons.Rounded.PlayCircle
    "volume" -> Icons.Rounded.VolumeUp
    "input" -> Icons.Rounded.Mouse
    "presentation" -> Icons.Rounded.Slideshow
    "keepawake" -> Icons.Rounded.LightMode
    "notifications" -> Icons.Rounded.Notifications
    "calls" -> Icons.Rounded.Call
    "files" -> Icons.Rounded.Folder
    "commands" -> Icons.Rounded.Terminal
    "contacts" -> Icons.Rounded.Contacts
    else -> Icons.Rounded.Lock
}

fun toolGroupLabel(group: String): String = when (group) {
    "media" -> "Media remote"; "volume" -> "Volume"; "input" -> "Mouse & keyboard"; "presentation" -> "Presentation"
    "notifications" -> "Notifications"; "files" -> "Shared files"; "commands" -> "Run commands"; "contacts" -> "Contacts"; "lock" -> "Lock device"
    "calls" -> "Call controls"
    "keepawake" -> "Keep awake"
    else -> group
}

@Composable
private fun ToolButtons(buttons: List<Pair<String, String>>, enabled: Boolean, onClick: (String) -> Unit) {
    buttons.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) -> FilledTonalButton(onClick = { onClick(value) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) } }
        }
    }
}
