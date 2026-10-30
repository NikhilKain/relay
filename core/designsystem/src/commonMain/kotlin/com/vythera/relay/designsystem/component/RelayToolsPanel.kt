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
import com.vythera.relay.designsystem.resources.*
import com.vythera.relay.designsystem.theme.RelayShapes
import org.jetbrains.compose.resources.stringResource

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

    RelaySectionHeader(stringResource(Res.string.relay_tools_header), Modifier.padding(top = 24.dp))
    Surface(shape = RelayShapes.Small, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!reachable) Text(stringResource(Res.string.relay_tools_away), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { send("discover") }, enabled = enabled) { Text(if (available.isEmpty()) stringResource(Res.string.relay_tools_load) else stringResource(Res.string.relay_tools_refresh_tools)) }
                if ("status" in available) TextButton(onClick = { send("status.get") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_status)) }
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
                FilledTonalButton(onClick = { send("ring.start") }, enabled = enabled) { Icon(Icons.Rounded.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.relay_tools_find)) }
                TextButton(onClick = { send("ring.stop") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_stop_ring)) }
            }
            if ("ping" in available) TextButton(onClick = { send("ping.send") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_ping)) }
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
                "keepawake" -> ToolButtons(listOf(stringResource(Res.string.relay_tools_keep_awake_30) to "start", stringResource(Res.string.relay_tools_allow_sleep) to "stop"), enabled) { send("keepawake.$it") }
                "calls" -> {
                    TextButton(onClick = { send("calls.status") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_refresh_calls)) }
                    if (action == "calls.status") values.forEach { (key, value) -> Text("$key: $value") }
                    TextButton(onClick = { send("calls.silence") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_silence)) }
                }
                "media" -> {
                    if (player.isNotBlank()) TextButton(onClick = { player = ""; send("media.status") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_active_player)) }
                    TextButton(onClick = { send("media.status", "player" to player) }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_players)) }
                    ToolButtons(listOf(stringResource(Res.string.relay_tools_previous) to "previous", stringResource(Res.string.relay_tools_play_pause) to "playpause", stringResource(Res.string.relay_tools_next) to "next", stringResource(Res.string.relay_tools_stop) to "stop"), enabled) { send("media.control", "action" to it, "player" to player) }
                    if (action.startsWith("media.") && success) {
                        values.filterKeys { it != "canSeek" }.forEach { (key, value) -> if (value.isNotBlank()) Text("$key: $value", style = MaterialTheme.typography.bodyMedium) }
                        if (values["canSeek"] == "true") ToolButtons(listOf(stringResource(Res.string.relay_tools_back10) to "-10", stringResource(Res.string.relay_tools_forward10) to "10"), enabled) {
                            send("media.seek", "seconds" to it, "player" to player)
                        }
                    }
                }
                "volume" -> ToolButtons(listOf(stringResource(Res.string.relay_tools_quieter) to "down", stringResource(Res.string.relay_tools_louder) to "up", stringResource(Res.string.relay_tools_mute) to "mute"), enabled) { send("volume.control", "action" to it) }
                "presentation" -> ToolButtons(listOf(stringResource(Res.string.relay_tools_start_slides) to "start", stringResource(Res.string.relay_tools_previous) to "previous", stringResource(Res.string.relay_tools_next) to "next", stringResource(Res.string.relay_tools_black) to "black", stringResource(Res.string.relay_tools_end) to "escape"), enabled) { send("presentation.key", "key" to it) }
                "lock" -> Button(onClick = { send("lock.now") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_lock_now)) }
                "input" -> {
                    Text(stringResource(Res.string.relay_tools_input_hint), style = MaterialTheme.typography.bodySmall)
                    // Batch each gesture into one bounded request; avoids flooding the socket.
                    var dx by remember(peerKey) { mutableStateOf(0f) }
                    var dy by remember(peerKey) { mutableStateOf(0f) }
                    Box(Modifier.fillMaxWidth().height(120.dp).background(colors.surfaceContainerHighest, RelayShapes.Small)
                        .pointerInput(enabled) { if (enabled) detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragEnd = { send("input.move", "x" to dx.toInt().toString(), "y" to dy.toInt().toString()) },
                            onDrag = { change, amount -> change.consume(); dx += amount.x; dy += amount.y },
                        ) }, contentAlignment = Alignment.Center) { Text(stringResource(Res.string.relay_tools_trackpad), color = colors.onSurfaceVariant) }
                    ToolButtons(listOf(stringResource(Res.string.relay_tools_click) to "left", stringResource(Res.string.relay_tools_right_click) to "right"), enabled) { send("input.click", "button" to it) }
                    ToolButtons(listOf(stringResource(Res.string.relay_tools_scroll_up) to "-3", stringResource(Res.string.relay_tools_scroll_down) to "3"), enabled) { send("input.scroll", "amount" to it) }
                    OutlinedTextField(text, { text = it.take(500) }, label = { Text(stringResource(Res.string.relay_tools_type_label)) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { send("input.text", "text" to text); text = "" }, enabled = enabled && text.isNotEmpty()) { Text(stringResource(Res.string.relay_tools_type_text)) }
                    ToolButtons(listOf(stringResource(Res.string.relay_tools_key_enter) to "enter", stringResource(Res.string.relay_tools_key_backspace) to "backspace", stringResource(Res.string.relay_tools_key_tab) to "tab", stringResource(Res.string.relay_tools_key_escape) to "escape"), enabled) { send("input.key", "key" to it) }
                }
                "contacts" -> {
                    OutlinedTextField(text, { text = it.take(100) }, label = { Text(stringResource(Res.string.relay_tools_search_contacts)) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { send("contacts.list", "query" to text) }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_search)) }
                }
                "files" -> Row {
                    TextButton(onClick = { folders = listOf(""); send("files.list") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_shared_folder)) }
                    TextButton(onClick = { folders = folders.dropLast(1); send("files.list", "path" to folders.last()) }, enabled = enabled && folders.size > 1) { Text(stringResource(Res.string.relay_tools_back)) }
                }
                "notifications", "commands" -> TextButton(onClick = { send("$section.list") }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_refresh)) }
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
                                "player" -> TextButton(onClick = { player = entry.id; send("media.status", "player" to player) }, enabled = enabled) { Text(if (player == entry.id) stringResource(Res.string.relay_tools_selected) else stringResource(Res.string.relay_tools_use_player)) }
                                "folder" -> TextButton(onClick = { folders = folders + entry.id; send("files.list", "path" to entry.id) }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_open)) }
                                "file" -> TextButton(onClick = { send("files.get", "path" to entry.id) }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_download)) }
                                "command" -> TextButton(onClick = { command = entry }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_run)) }
                                "notification", "reply" -> Row {
                                    if (entry.kind == "reply") TextButton(onClick = { reply = entry; text = "" }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_reply)) }
                                    TextButton(onClick = { send("notifications.dismiss", "id" to entry.id) }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_dismiss)) }
                                }
                            }
                        }
                    }
                }
                if (section == "files" && values["next"] != null) TextButton(onClick = { send("files.list", "path" to values["path"].orEmpty(), "offset" to values.getValue("next")) }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_next_page)) }
            }
            HorizontalDivider()
            TextButton(onClick = { showAccess = !showAccess }) { Icon(Icons.Rounded.Security, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.relay_tools_permissions)) }
            if (showAccess) {
                remoteSetup()
                Text(stringResource(Res.string.relay_tools_access_body), style = MaterialTheme.typography.bodySmall)
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
        onDismissRequest = { reply = null }, title = { Text(stringResource(Res.string.relay_tools_reply_to, notification.title)) },
        text = { OutlinedTextField(text, { text = it.take(4096) }, label = { Text(stringResource(Res.string.relay_tools_message)) }) },
        confirmButton = { TextButton(onClick = { send("notifications.reply", "id" to notification.id, "text" to text); reply = null }, enabled = enabled && text.isNotBlank()) { Text(stringResource(Res.string.relay_tools_send_reply)) } },
        dismissButton = { TextButton(onClick = { reply = null }) { Text(stringResource(Res.string.relay_tools_cancel)) } },
    ) }
    command?.let { item -> AlertDialog(
        onDismissRequest = { command = null }, title = { Text(stringResource(Res.string.relay_tools_run_title, item.title)) }, text = { Text(stringResource(Res.string.relay_tools_run_body)) },
        confirmButton = { TextButton(onClick = { send("commands.run", "id" to item.id); command = null }, enabled = enabled) { Text(stringResource(Res.string.relay_tools_run)) } },
        dismissButton = { TextButton(onClick = { command = null }) { Text(stringResource(Res.string.relay_tools_cancel)) } },
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

@Composable
fun toolGroupLabel(group: String): String = when (group) {
    "media" -> stringResource(Res.string.relay_tools_group_media)
    "volume" -> stringResource(Res.string.relay_tools_group_volume)
    "input" -> stringResource(Res.string.relay_tools_group_input)
    "presentation" -> stringResource(Res.string.relay_tools_group_presentation)
    "notifications" -> stringResource(Res.string.relay_tools_group_notifications)
    "files" -> stringResource(Res.string.relay_tools_group_files)
    "commands" -> stringResource(Res.string.relay_tools_group_commands)
    "contacts" -> stringResource(Res.string.relay_tools_group_contacts)
    "lock" -> stringResource(Res.string.relay_tools_group_lock)
    "calls" -> stringResource(Res.string.relay_tools_group_calls)
    "keepawake" -> stringResource(Res.string.relay_tools_group_keepawake)
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
