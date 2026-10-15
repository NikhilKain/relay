package com.vythera.relay.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vythera.relay.designsystem.component.RelayPresence
import com.vythera.relay.designsystem.component.RelayTransferDirection
import com.vythera.relay.designsystem.component.RelayTransferPhase
import com.vythera.relay.designsystem.component.RelayTransferUi
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.node.Presence
import com.vythera.relay.node.RelayDevice
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferSnapshot
import com.vythera.relay.transfer.TransferStatus
import org.jetbrains.compose.resources.stringResource

/** A device as the desktop UI shows it. */
data class DeviceView(
    val id: DeviceId,
    val name: String,
    val kind: RelayDeviceKind,
    val platformLabel: String,
    val presence: RelayPresence,
    val trusted: Boolean,
    val compatible: Boolean,
    val autoAccept: Boolean,
    val busy: Boolean,
) {
    val reachable get() = presence != RelayPresence.Offline && compatible
}

fun RelayDevice.toView(transfers: List<TransferSnapshot>) = DeviceView(
    id = id,
    name = name.ifBlank { platformLabel(platform) },
    kind = when (type) {
        DeviceType.PHONE -> RelayDeviceKind.Phone
        DeviceType.TABLET -> RelayDeviceKind.Tablet
        DeviceType.LAPTOP -> RelayDeviceKind.Laptop
        DeviceType.DESKTOP -> RelayDeviceKind.Desktop
        DeviceType.BROWSER -> RelayDeviceKind.Browser
        DeviceType.UNKNOWN -> RelayDeviceKind.Unknown
    },
    platformLabel = platformLabel(platform),
    presence = when (presence) {
        Presence.CONNECTED -> RelayPresence.Connected
        Presence.NEARBY -> RelayPresence.Nearby
        Presence.OFFLINE -> RelayPresence.Offline
    },
    trusted = isTrusted,
    compatible = isCompatible,
    autoAccept = autoAcceptTransfers,
    busy = transfers.any { it.peerId == id && it.status.isActive },
)

fun platformLabel(platform: Platform) = when (platform) {
    Platform.ANDROID -> "Android"
    Platform.WINDOWS -> "Windows"
    Platform.LINUX -> "Linux"
    Platform.MACOS -> "macOS"
    Platform.WEB -> "Browser"
    Platform.UNKNOWN -> "Device"
}

/** Most relevant first: busy, connected, trusted and nearby, trusted, then new devices. */
fun List<DeviceView>.byRelevance() = sortedWith(
    compareByDescending<DeviceView> { it.busy }
        .thenByDescending { it.trusted && it.presence == RelayPresence.Connected }
        .thenByDescending { it.trusted && it.presence == RelayPresence.Nearby }
        .thenByDescending { it.trusted }
        .thenBy { it.name.lowercase() },
)

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}

@Composable
fun relativeTime(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.just_now)
        minutes < 60 -> stringResource(Res.string.minutes_ago, minutes.toInt())
        minutes < 24 * 60 -> stringResource(Res.string.hours_ago, (minutes / 60).toInt())
        else -> stringResource(Res.string.days_ago, (minutes / (24 * 60)).toInt())
    }
}

@Composable
fun TransferSnapshot.toUi(relay: DesktopRelay, kind: RelayDeviceKind): RelayTransferUi {
    val peer = relay.name(peerId)
    val phase = when (status) {
        TransferStatus.Queued, TransferStatus.AwaitingDecision -> RelayTransferPhase.Waiting
        TransferStatus.Running -> RelayTransferPhase.Running
        is TransferStatus.Paused -> RelayTransferPhase.Paused
        TransferStatus.Completed -> RelayTransferPhase.Completed
        else -> RelayTransferPhase.Failed
    }
    val failure = when (val s = status) {
        is TransferStatus.Failed -> when (s.reason) {
            TransferFailure.PEER_UNAVAILABLE -> stringResource(Res.string.failure_away, peer)
            TransferFailure.CONNECTION_LOST -> stringResource(Res.string.failure_lost, peer)
            TransferFailure.CHECKSUM_MISMATCH -> stringResource(Res.string.failure_damaged)
            TransferFailure.STORAGE_FULL -> stringResource(Res.string.failure_space, peer)
            else -> stringResource(Res.string.failure_generic, peer)
        }
        TransferStatus.Declined -> if (direction == TransferDirection.INCOMING) stringResource(Res.string.failure_you_declined) else stringResource(Res.string.failure_declined, peer)
        is TransferStatus.Cancelled -> stringResource(Res.string.failure_cancelled)
        else -> null
    }
    val remaining = progress.remainingMillis?.let { millis ->
        val seconds = millis / 1000
        when {
            seconds < 3 -> stringResource(Res.string.almost_done)
            seconds < 90 -> stringResource(Res.string.remaining_seconds, seconds.toInt())
            else -> stringResource(Res.string.remaining_minutes, (seconds / 60 + 1).toInt())
        }
    }
    return RelayTransferUi(
        direction = if (direction == TransferDirection.OUTGOING) RelayTransferDirection.Sending else RelayTransferDirection.Receiving,
        peerName = peer,
        peerKind = kind,
        title = DesktopRelay.summary(items),
        subtitle = formatSize(totalBytes),
        progress = progress.fraction,
        phase = phase,
        speedLabel = progress.bytesPerSecond.takeIf { it > 0 }?.let { stringResource(Res.string.speed, formatSize(it)) },
        remainingLabel = remaining,
        failureMessage = failure,
    )
}

/** Relay's dialog: a large title and content on a Dialog-shaped tonal surface. */
@Composable
fun RelayDialog(title: String, onDismiss: () -> Unit, supporting: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RelayShapes.Dialog, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.widthIn(min = 420.dp, max = 560.dp)) {
            Column(Modifier.padding(32.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.semantics { heading() })
                if (supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Column(Modifier.padding(top = 24.dp), content = content)
            }
        }
    }
}

/**
 * The Relay mark as a Painter, for the window and tray icons: the soft square and the
 * circle overlapping, the overlap in its own tone. Same geometry as `RelayMark`.
 */
class RelayMarkPainter(
    private val origin: Color = Color(0xFF9F4200),
    private val destination: Color = Color(0xFF006972),
    private val overlap: Color = Color(0xFFF2762E),
) : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val unit = size.minDimension
        val square = Path().apply {
            addRoundRect(RoundRect(0.06f * unit, 0.34f * unit, 0.66f * unit, 0.94f * unit, CornerRadius(0.2f * unit)))
        }
        val circle = Path().apply { addOval(Rect(Offset(0.64f * unit, 0.36f * unit), 0.3f * unit)) }
        val shared = Path().apply { op(square, circle, PathOperation.Intersect) }
        drawPath(square, origin)
        drawPath(circle, destination)
        drawPath(shared, overlap)
    }
}

/** A list of files for drag and drop out of Relay. */
class FileListSelection(private val files: List<java.io.File>) : java.awt.datatransfer.Transferable {
    override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
        return files
    }
}
