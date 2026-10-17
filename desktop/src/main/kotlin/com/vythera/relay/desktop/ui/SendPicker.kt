package com.vythera.relay.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelayColorSource
import com.vythera.relay.designsystem.theme.RelayTheme
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.protocol.DeviceId
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * Where to send files that arrived from outside the window: "Send with Relay" in the file
 * manager. Any number of devices can be ticked; nothing is sent until Send is pressed, so
 * a request alone can never move a file.
 */
@Composable
fun SendPicker(relay: DesktopRelay, files: List<File>, onClose: () -> Unit) {
    val devices by relay.devices.collectAsState()
    val trusted = devices.filter { it.isTrusted }.sortedByDescending { it.isReachable }
    val chosen = remember(files) {
        mutableStateListOf<DeviceId>().apply { trusted.singleOrNull { it.isReachable }?.let { add(it.id) } }
    }
    DialogWindow(
        onCloseRequest = onClose,
        title = "Relay",
        state = rememberDialogState(position = WindowPosition(Alignment.Center), size = DpSize(440.dp, 520.dp)),
    ) {
        RelayTheme(colorSource = RelayColorSource.Ember) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp)) {
                    Text(pluralStringResource(Res.plurals.send_picker_title, files.size, files.size), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        files.take(3).joinToString(", ") { it.name } + if (files.size > 3) " …" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(Res.string.send_picker_devices), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (trusted.isEmpty()) {
                            Text(stringResource(Res.string.send_picker_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        trusted.forEach { device ->
                            Surface(
                                onClick = { if (device.id in chosen) chosen.remove(device.id) else chosen.add(device.id) },
                                enabled = device.isReachable,
                                shape = RelayShapes.ExtraSmall,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = device.id in chosen, onCheckedChange = null, enabled = device.isReachable)
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                                        if (!device.isReachable) {
                                            Text(stringResource(Res.string.send_picker_away), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = onClose) { Text(stringResource(Res.string.send_picker_cancel)) }
                        Button(
                            onClick = {
                                relay.sendFiles(chosen.filter { id -> trusted.any { it.id == id && it.isReachable } }, files)
                                onClose()
                            },
                            enabled = chosen.any { id -> trusted.any { it.id == id && it.isReachable } },
                        ) { Text(stringResource(Res.string.send_picker_send)) }
                    }
                }
            }
        }
    }
}
