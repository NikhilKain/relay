package com.vythera.relay.desktop.ui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import com.vythera.relay.designsystem.component.RelayToolEntry
import com.vythera.relay.designsystem.component.RelayToolsPanel
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.node.DeviceToolState
import com.vythera.relay.protocol.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var setupMessage by remember { mutableStateOf("") }
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
                Text("Mirror new notifications here")
                Switch(peer in mirrored, { enabled -> scope.launch(Dispatchers.IO) { relay.toolMonitor.setMirroring(peer, enabled) } })
            }
            if (peer in mirrored) Text("Enable notification access on the phone. Existing notifications stay quiet; new or changed notifications appear here.", style = MaterialTheme.typography.bodySmall)
        },
        localSetup = {
            Text("Shared folder: ${relay.toolPlatform.sharedFolder}")
            TextButton(onClick = { chooseFolder()?.let { folder ->
                scope.launch(Dispatchers.IO) { setupMessage = runCatching { relay.toolPlatform.sharedFolder = folder; "Shared folder selected" }.getOrElse { "Could not select that folder" } }
            } }) { Text("Choose shared folder") }
            TextButton(onClick = { editCommand = true }) { Text("Add or remove a command") }
            Text("Commands: ${relay.toolPlatform.commands().joinToString { it.title }.ifBlank { "None" }}")
            if (setupMessage.isNotBlank()) Text(setupMessage)
        },
    )
    if (editCommand) AlertDialog(
        onDismissRequest = { editCommand = false }, title = { Text("Command on this computer") },
        text = { Column {
            Text("Only commands saved here can be run by an allowed paired device. Leave the command empty to remove its name.")
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") })
            OutlinedTextField(command, { command = it.take(4096) }, label = { Text("Shell command") })
        } },
        confirmButton = { TextButton(onClick = {
            val savedName = name.trim(); val savedCommand = command
            scope.launch(Dispatchers.IO) { setupMessage = runCatching { relay.toolPlatform.saveCommand(savedName, savedCommand); "Command saved" }.getOrElse { "Could not save command" } }
            name = ""; command = ""; editCommand = false
        }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editCommand = false }) { Text("Cancel") } },
    )
}
