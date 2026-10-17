package com.vythera.relay.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.theme.RelayShapes

/**
 * This device's connection code, ready to be scanned. The code is drawn as soon as there
 * is one; there is no "show" step, because a QR that has to be asked for is a QR nobody
 * finds. [secondaryLabel] is the quieter way in from the other direction (reading a code
 * instead of showing one), and [networkLabel] lets the person pick another network when
 * the device has more than one.
 */
@Composable
fun ConnectionQrCard(
    title: String,
    body: String,
    matrix: List<List<Boolean>>,
    caption: String,
    message: String,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
    networkLabel: String? = null,
    onNetwork: () -> Unit = {},
    qrSize: Dp = 240.dp,
    modifier: Modifier = Modifier,
) {
    Surface(shape = RelayShapes.Hero, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(32.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(body, style = MaterialTheme.typography.bodyLarge)

            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                if (matrix.isNotEmpty()) {
                    // A white quiet zone round the code: scanners need it, and it keeps the
                    // code readable on a dark theme.
                    Surface(color = Color.White, shape = RelayShapes.Small) {
                        // Painted once into a bitmap with one pixel per module, then scaled up
                        // without smoothing. Drawn as a rectangle per module instead, the
                        // code cost more than a thousand draw calls on every frame it was on
                        // screen, and made opening Settings stutter.
                        val code = remember(matrix) { qrBitmap(matrix) }
                        Image(code, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.padding(16.dp).size(qrSize))
                    }
                } else {
                    Box(Modifier.size(qrSize + 32.dp), contentAlignment = Alignment.Center) {
                        if (message.isBlank()) CircularProgressIndicator()
                    }
                }
            }

            if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                if (networkLabel != null) TextButton(onClick = onNetwork) { Text(networkLabel) }
                if (secondaryLabel != null) TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}

/** The code as a black-on-white bitmap, one pixel per module. */
private fun qrBitmap(matrix: List<List<Boolean>>): ImageBitmap {
    val size = matrix.size
    val bitmap = ImageBitmap(size, size)
    val canvas = Canvas(bitmap)
    val white = Paint().apply { color = Color.White }
    val black = Paint().apply { color = Color.Black }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), white)
    matrix.forEachIndexed { y, row ->
        row.forEachIndexed { x, dark -> if (dark) canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, black) }
    }
    return bitmap
}
