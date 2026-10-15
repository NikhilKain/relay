package com.vythera.relay.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.vythera.relay.designsystem.component.ConnectionQrCard
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.node.ConnectionInvite
import com.vythera.relay.node.ConnectionQr
import com.vythera.relay.node.RelayNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import javax.imageio.ImageIO

/** How long before a code expires (five minutes) a fresh one is drawn. */
private const val REFRESH_MILLIS = 4 * 60_000L

/**
 * This computer's connection code, shown the moment the card is on screen and redrawn
 * before it expires, so a phone can scan it at any time. A computer has no camera to
 * scan with, so reading a code from an image is offered as the quiet second option.
 */
@Composable
fun DesktopConnectionQr(node: RelayNode, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var addresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf("") }
    var matrix by remember { mutableStateOf<List<List<Boolean>>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var choosingNetwork by remember { mutableStateOf(false) }

    val noNetwork = stringResource(Res.string.qr_no_network)
    val reading = stringResource(Res.string.qr_reading)
    val unreadable = stringResource(Res.string.qr_unreadable)
    val connecting = stringResource(Res.string.qr_connecting)
    val connected = stringResource(Res.string.qr_connected)
    val failed = stringResource(Res.string.qr_failed)

    LaunchedEffect(chosen) {
        while (true) {
            val found = withContext(Dispatchers.IO) { runCatching { ConnectionInvite.localAddresses() }.getOrDefault(emptyList()) }
            addresses = found
            val target = chosen?.takeIf { it in found } ?: found.firstOrNull()
            if (target == null) {
                host = ""; matrix = emptyList(); message = noNetwork
                delay(5_000) // look again soon: the computer may be joining a network
            } else {
                host = target
                matrix = withContext(Dispatchers.Default) { ConnectionQr.matrix(node.connectionInvite(target)) }
                if (message == noNetwork) message = ""
                delay(REFRESH_MILLIS)
            }
        }
    }

    ConnectionQrCard(
        title = stringResource(Res.string.qr_title),
        body = stringResource(Res.string.qr_body),
        matrix = matrix,
        caption = if (host.isNotBlank()) stringResource(Res.string.qr_caption, host) else "",
        message = message,
        secondaryLabel = stringResource(Res.string.qr_read_image),
        onSecondary = {
            val picker = FileDialog(null as java.awt.Frame?, "Choose a Relay QR image", FileDialog.LOAD)
            picker.isVisible = true
            val file = picker.files.firstOrNull()
            picker.dispose()
            if (file != null) scope.launch {
                message = reading
                try {
                    val raw = withContext(Dispatchers.IO) { decodeImage(file) }
                    ConnectionInvite.parse(raw)
                    pending = raw
                    message = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = unreadable
                }
            }
        },
        networkLabel = if (addresses.size > 1) stringResource(Res.string.qr_change_network) else null,
        onNetwork = { choosingNetwork = true },
        modifier = modifier,
    )

    if (choosingNetwork) AlertDialog(
        onDismissRequest = { choosingNetwork = false },
        title = { Text(stringResource(Res.string.qr_network_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.qr_network_body))
                addresses.forEach { address ->
                    TextButton(onClick = { chosen = address; choosingNetwork = false }) {
                        Text(if (address == host) "$address  ✓" else address)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingNetwork = false }) { Text(stringResource(Res.string.cancel)) } },
    )

    pending?.let { raw ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(Res.string.qr_confirm_title)) },
            text = { Text(stringResource(Res.string.qr_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    message = connecting
                    scope.launch {
                        message = try {
                            val peer = node.connectInvite(raw)
                            if (node.devices.value.none { it.id == peer && it.isTrusted }) node.pair(peer)
                            connected
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed
                        }
                    }
                }) { Text(stringResource(Res.string.device_connect)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** Reads a QR code out of an image file, refusing anything implausibly large. */
private fun decodeImage(file: java.io.File): String {
    require(file.length() <= 20_000_000)
    return ImageIO.createImageInputStream(file).use { input ->
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext())
        val reader = readers.next()
        try {
            reader.input = input
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            require(w in 1..16000 && h in 1..16000)
            val param = reader.defaultReadParam
            val sample = maxOf(1, (maxOf(w, h) + 2047) / 2048)
            param.setSourceSubsampling(sample, sample, 0, 0)
            val image = reader.read(0, param)
            ConnectionQr.decode(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        } finally {
            reader.dispose()
        }
    }
}
