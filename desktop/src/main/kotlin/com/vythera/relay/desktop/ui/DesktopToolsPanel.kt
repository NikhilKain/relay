package com.vythera.relay.desktop.ui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import com.vythera.relay.designsystem.component.RelayToolEntry
import com.vythera.relay.designsystem.component.RelayToolsPanel
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.node.DeviceToolState
import com.vythera.relay.protocol.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
fun DesktopToolsPanel(relay: DesktopRelay, peer: DeviceId, reachable: Boolean, chooseFolder: () -> File?) {
    val states by relay.tools.states.collectAsState()
    val grants by relay.tools.permissions.collectAsState()
    val liveStatus by relay.toolMonitor.status.collectAsState()
    val mirrored by relay.toolMonitor.mirroring.collectAsState()
    val state = states[peer] ?: DeviceToolState()
    val scope = rememberCoroutineScope()
    var editCommand by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var setupMessage by remember { mutableStateOf<StringResource?>(null) }
    LaunchedEffect(peer, reachable) { if (reachable) relay.tools.request(peer, "discover") }
    RelayToolsPanel(
        peer.value, reachable, state.busy, state.available, relay.tools.localGroups, grants[peer].orEmpty(),
        state.action, state.response?.message.orEmpty(), state.response?.success != false, state.response?.values.orEmpty(),
        state.response?.items.orEmpty().map { RelayToolEntry(it.id, it.title, it.subtitle, it.kind) },
        onRequest = { action, args -> scope.launch { relay.tools.request(peer, action, args) } },
        onAllow = { group, enabled -> scope.launch(Dispatchers.IO) { relay.tools.setAllowed(peer, group, enabled) } },
        liveStatus = liveStatus[peer].orEmpty(),
        remoteSetup = {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(Res.string.tools_mirror))
                Switch(peer in mirrored, { enabled -> scope.launch(Dispatchers.IO) { relay.toolMonitor.setMirroring(peer, enabled) } })
            }
            if (peer in mirrored) Text(stringResource(Res.string.tools_mirror_hint), style = MaterialTheme.typography.bodySmall)
        },
        localSetup = {
            Text(stringResource(Res.string.tools_shared_folder, relay.toolPlatform.sharedFolder.toString()))
            TextButton(onClick = { chooseFolder()?.let { folder ->
                scope.launch(Dispatchers.IO) { setupMessage = runCatching { relay.toolPlatform.sharedFolder = folder; Res.string.tools_folder_set }.getOrElse { Res.string.tools_folder_failed } }
            } }) { Text(stringResource(Res.string.tools_choose_folder)) }
            TextButton(onClick = { editCommand = true }) { Text(stringResource(Res.string.tools_edit_command)) }
            val commands = relay.toolPlatform.commands().joinToString { it.title }
            Text(stringResource(Res.string.tools_commands, commands.ifBlank { stringResource(Res.string.tools_commands_none) }))
            setupMessage?.let { Text(stringResource(it)) }
        },
    )
    if (editCommand) AlertDialog(
        onDismissRequest = { editCommand = false }, title = { Text(stringResource(Res.string.tools_command_title)) },
        text = { Column {
            Text(stringResource(Res.string.tools_command_body))
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(Res.string.tools_command_name)) })
            OutlinedTextField(command, { command = it.take(4096) }, label = { Text(stringResource(Res.string.tools_command_shell)) })
        } },
        confirmButton = { TextButton(onClick = {
            val savedName = name.trim(); val savedCommand = command
            scope.launch(Dispatchers.IO) { setupMessage = runCatching { relay.toolPlatform.saveCommand(savedName, savedCommand); Res.string.tools_command_saved }.getOrElse { Res.string.tools_command_failed } }
            name = ""; command = ""; editCommand = false
        }, enabled = name.isNotBlank()) { Text(stringResource(Res.string.settings_save)) } },
        dismissButton = { TextButton(onClick = { editCommand = false }) { Text(stringResource(Res.string.cancel)) } },
    )
}
