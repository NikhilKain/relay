package com.vythera.relay.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.resources.Res
import com.vythera.relay.designsystem.resources.relay_direct_body
import com.vythera.relay.designsystem.resources.relay_direct_connect
import com.vythera.relay.designsystem.resources.relay_direct_connected
import com.vythera.relay.designsystem.resources.relay_direct_connecting
import com.vythera.relay.designsystem.resources.relay_direct_failed
import com.vythera.relay.designsystem.resources.relay_direct_host
import com.vythera.relay.designsystem.resources.relay_direct_port
import com.vythera.relay.designsystem.resources.relay_direct_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DirectConnectionPanel(localPort: Int, connect: suspend (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47800") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StringResource?>(null) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(Res.string.relay_direct_title)) }
    if (!expanded) return
    Text(stringResource(Res.string.relay_direct_body, localPort), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(host, { host = it.take(253) }, label = { Text(stringResource(Res.string.relay_direct_host)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(Res.string.relay_direct_port)) }, enabled = !busy, singleLine = true)
    Button(onClick = { busy = true; result = null; scope.launch {
        try { connect(host.trim().removeSurrounding("[", "]"), port.toInt()); result = Res.string.relay_direct_connected }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { result = Res.string.relay_direct_failed }
        finally { busy = false }
    } }, enabled = !busy && host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535) {
        Text(stringResource(if (busy) Res.string.relay_direct_connecting else Res.string.relay_direct_connect))
    }
    result?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
}
