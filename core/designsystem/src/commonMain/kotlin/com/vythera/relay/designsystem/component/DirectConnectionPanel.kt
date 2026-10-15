package com.vythera.relay.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DirectConnectionPanel(localPort: Int, connect: suspend (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47800") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 16.dp)) { Text("Connect by address…") }
    if (!expanded) return
    Text("Use the other device's LAN or VPN address and Relay port. This device listens on port $localPort. Pairing is still required.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(host, { host = it.take(253) }, label = { Text("IP address or hostname") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port on the other device") }, enabled = !busy, singleLine = true)
    Button(onClick = { busy = true; result = ""; scope.launch {
        try { connect(host.trim().removeSurrounding("[", "]"), port.toInt()); result = "Connected. Open Devices and pair if needed." }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { result = "Could not connect. Check the address, port, VPN and firewall on both devices." }
        finally { busy = false }
    } }, enabled = !busy && host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535) { Text(if (busy) "Connecting…" else "Connect") }
    if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodyMedium)
}
