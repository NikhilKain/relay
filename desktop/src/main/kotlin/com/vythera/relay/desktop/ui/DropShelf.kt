package com.vythera.relay.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.vythera.relay.designsystem.component.RelayDeviceAvatar
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayColorSource
import com.vythera.relay.designsystem.theme.RelayTheme
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.protocol.DeviceId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * The drop shelf: the desktop half of the ecosystem. A slim tab rests on the left edge of
 * the screen; drag anything toward it and it opens into your devices. Drop on one and it
 * goes there, no window to open, no Accept to press on the other side.
 *
 * It is a borderless, always-on-top utility window that never takes focus, so it does not
 * appear in the taskbar or steal keyboard input. It is only as big as it looks: resting,
 * it covers a strip a few pixels wide, so it never blocks clicks on the window behind.
 *
 * Compose registers drop targets when a drag starts, so the whole shelf is one target
 * that exists for the entire drag; which device is under the pointer is worked out from
 * the rows' positions.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DropShelf(relay: DesktopRelay, onChooseFiles: (DeviceId) -> Unit) {
    val raw by relay.devices.collectAsState()
    val transfers by relay.transfers.collectAsState()
    val devices = remember(raw, transfers) { raw.map { it.toView(transfers) }.filter { it.trusted }.byRelevance() }

    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ShelfMode.Resting) }
    var hovered by remember { mutableStateOf<DeviceId?>(null) }
    var sentTo by remember { mutableStateOf<DeviceId?>(null) }
    var closing by remember { mutableStateOf<Job?>(null) }
    val rowBounds = remember { mutableStateMapOf<DeviceId, Rect>() }
    val currentDevices by rememberUpdatedState(devices)
    var density by remember { mutableStateOf(1f) }

    fun cancelClose() {
        closing?.cancel()
        closing = null
    }

    fun closeAfter(millis: Long) {
        cancelClose()
        closing = scope.launch {
            delay(millis)
            hovered = null
            mode = ShelfMode.Resting
        }
    }

    fun deviceAt(event: DragAndDropEvent): DeviceId? {
        // AWT reports the pointer in window units; row bounds are in pixels.
        val location = when (val native = event.nativeEvent) {
            is java.awt.dnd.DropTargetDragEvent -> native.location
            is java.awt.dnd.DropTargetDropEvent -> native.location
            else -> return null
        }
        val point = Offset(location.x * density, location.y * density)
        return rowBounds.entries.firstOrNull { (id, bounds) ->
            bounds.contains(point) && currentDevices.any { it.id == id && it.reachable }
        }?.key
    }

    fun send(to: DeviceId, transferable: Transferable): Boolean {
        val sent = when {
            transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                files.isNotEmpty().also { if (it) relay.sendFiles(to, files) }
            }
            transferable.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                val text = transferable.getTransferData(DataFlavor.stringFlavor) as String
                text.isNotBlank().also { if (it) scope.launch { relay.continueOn(to, text) } }
            }
            else -> false
        }
        if (sent) {
            sentTo = to
            closeAfter(1400)
            scope.launch {
                delay(1400)
                sentTo = null
            }
        }
        return sent
    }

    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                cancelClose()
                if (mode == ShelfMode.Resting || mode == ShelfMode.Clicked) mode = ShelfMode.Dragging
                hovered = deviceAt(event)
            }

            override fun onMoved(event: DragAndDropEvent) {
                cancelClose()
                hovered = deviceAt(event)
            }

            // Leaving the shelf: give the pointer a moment to come back before folding away.
            override fun onExited(event: DragAndDropEvent) {
                hovered = null
                closeAfter(600)
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovered = null
                if (sentTo == null) closeAfter(200)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val to = deviceAt(event) ?: return false
                hovered = null
                return send(to, event.awtTransferable)
            }
        }
    }

    val screen = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val motion = LocalRelayMotion.current
    val width = if (mode == ShelfMode.Resting) RESTING_WIDTH else OPEN_WIDTH
    val height = if (mode == ShelfMode.Resting) RESTING_HEIGHT else (HEADER_HEIGHT + maxOf(devices.size, 1) * ROW_HEIGHT + 20).coerceAtMost(screen.height - 48)
    val openWidth by animateIntAsState(width, motion.spatial(), label = "shelfWidth")
    val openHeight by animateIntAsState(height, motion.spatial(), label = "shelfHeight")

    DialogWindow(
        create = {
            ComposeDialog().apply {
                type = java.awt.Window.Type.UTILITY
                title = "Relay drop shelf"
                isUndecorated = true
                isTransparent = true
                isAlwaysOnTop = true
                isResizable = false
                focusableWindowState = false
            }
        },
        dispose = ComposeDialog::dispose,
        // The window itself is animated: it springs open around its own centre line.
        update = { dialog -> dialog.setBounds(screen.x, screen.y + (screen.height - openHeight) / 2, openWidth, openHeight) },
    ) {
        density = LocalDensity.current.density
        RelayTheme(colorSource = RelayColorSource.Ember) {
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier.fillMaxSize()
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { event ->
                            event.awtTransferable.let { it.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || it.isDataFlavorSupported(DataFlavor.stringFlavor) }
                        },
                        target = target,
                    )
                    .onPointerEvent(PointerEventType.Enter) { if (mode == ShelfMode.Clicked) cancelClose() }
                    .onPointerEvent(PointerEventType.Exit) { if (mode == ShelfMode.Clicked) closeAfter(500) },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (mode == ShelfMode.Resting) {
                    // The tab: a pill hugging the screen edge. Clicking it opens the shelf too.
                    Box(Modifier.fillMaxSize().clickable { cancelClose(); mode = ShelfMode.Clicked }, contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.width(6.dp).height(96.dp).background(colors.primary, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)))
                    }
                } else {
                    Surface(
                        color = colors.surfaceContainerHigh,
                        contentColor = colors.onSurface,
                        shape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(Modifier.padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(if (mode == ShelfMode.Clicked) Res.string.shelf_click_title else Res.string.shelf_title),
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                            )
                            if (devices.isEmpty()) {
                                Text(
                                    stringResource(Res.string.shelf_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                                )
                            }
                            devices.forEach { device ->
                                ShelfRow(
                                    device = device,
                                    highlighted = hovered == device.id || sentTo == device.id,
                                    sent = sentTo == device.id,
                                    clickMode = mode == ShelfMode.Clicked,
                                    onClick = {
                                        mode = ShelfMode.Resting
                                        onChooseFiles(device.id)
                                    },
                                    modifier = Modifier.onGloballyPositioned { rowBounds[device.id] = it.boundsInRoot() },
                                )
                            }
                        }
                    }
                    LaunchedEffect(mode) { if (mode == ShelfMode.Clicked) closeAfter(5000) }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    device: DeviceView,
    highlighted: Boolean,
    sent: Boolean,
    clickMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val reachable = device.reachable
    val container by animateColorAsState(if (highlighted) colors.primary else colors.surfaceContainerHighest)
    val content by animateColorAsState(if (highlighted) colors.onPrimary else colors.onSurface)
    val corner by animateDpAsState(if (highlighted) 20.dp else 28.dp)

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(corner),
        modifier = modifier.fillMaxWidth().height((ROW_HEIGHT - 4).dp)
            .then(if (reachable && clickMode) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RelayDeviceAvatar(
                device.kind,
                size = 48.dp,
                containerColor = when {
                    highlighted -> colors.onPrimary
                    reachable -> colors.primaryContainer
                    else -> colors.surfaceContainerHigh
                },
                contentColor = when {
                    highlighted -> colors.primary
                    reachable -> colors.onPrimaryContainer
                    else -> colors.onSurfaceVariant
                },
                busy = device.busy,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        when {
                            sent -> Res.string.shelf_sent
                            !reachable -> Res.string.shelf_away
                            clickMode -> Res.string.shelf_click
                            else -> Res.string.shelf_ready
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (highlighted) content else colors.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class ShelfMode { Resting, Dragging, Clicked }

private const val RESTING_WIDTH = 10
private const val RESTING_HEIGHT = 120
private const val OPEN_WIDTH = 300
private const val HEADER_HEIGHT = 64
private const val ROW_HEIGHT = 80
