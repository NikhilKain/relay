package com.vythera.relay.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayColorSource
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayTheme
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.node.PairingRole
import com.vythera.relay.node.PairingStage
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

enum class Section { Devices, Inbox, Settings, Help }

/** Asks the tray or another part of the app to show a section or pick a device. */
class DesktopNavigation {
    var section by mutableStateOf(Section.Devices)
    var selected by mutableStateOf<DeviceId?>(null)
}

/**
 * The desktop window: a navigation rail with Send attached, and the chosen section.
 * Pairing, incoming transfers and the transfer dock float above every section, as on
 * Android.
 */
@Composable
fun DesktopApp(relay: DesktopRelay, navigation: DesktopNavigation, window: Frame?) {
    val rawDevices by relay.devices.collectAsState()
    val transfers by relay.transfers.collectAsState()
    val pairing by relay.pairing.collectAsState()
    val history by relay.history.collectAsState()
    val settings by relay.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var compose by remember { mutableStateOf<Pair<DeviceId, Boolean>?>(null) }

    val devices = remember(rawDevices, transfers) { rawDevices.map { it.toView(transfers) }.byRelevance() }
    val selected = devices.firstOrNull { it.id == navigation.selected } ?: devices.firstOrNull()
    fun kindOf(id: DeviceId) = devices.firstOrNull { it.id == id }?.kind ?: RelayDeviceKind.Unknown

    fun chooseFiles(to: DeviceId) {
        val dialog = FileDialog(window, "Send to ${relay.name(to)}", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
        val files = dialog.files.orEmpty().toList()
        if (files.isNotEmpty()) relay.sendFiles(to, files)
    }

    fun chooseFolder(): File? {
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        return if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    val actions = DeviceActions(
        select = { navigation.selected = it },
        connect = { id -> scope.launch { relay.pair(id) } },
        sendFiles = relay::sendFiles,
        chooseFiles = ::chooseFiles,
        chooseFolder = { id -> chooseFolder()?.let { relay.sendFiles(id, listOf(it)) } },
        sendClipboard = { id ->
            scope.launch {
                val text = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrNull()
                if (!text.isNullOrBlank()) relay.sendText(id, text)
            }
        },
        composeText = { compose = it to false },
        composeLink = { compose = it to true },
        setAutoAccept = { id, enabled -> scope.launch { relay.setAutoAccept(id, enabled) } },
        forget = { id -> scope.launch { relay.forget(id) } },
        help = { navigation.section = Section.Help },
    )

    RelayTheme(colorSource = RelayColorSource.Ember) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Rail(navigation, onSend = { selected?.takeIf { it.trusted }?.let { chooseFiles(it.id) } })
                    // Sections cross-fade and rise slightly, on the same springs as the phone.
                    val motion = LocalRelayMotion.current
                    AnimatedContent(
                        targetState = navigation.section,
                        transitionSpec = {
                            (slideInVertically(motion.spatial()) { it / 14 } + fadeIn(motion.effects()) + scaleIn(motion.spatial(), initialScale = 0.98f))
                                .togetherWith(fadeOut(motion.effectsFast()))
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = "section",
                    ) { section ->
                        when (section) {
                            Section.Devices -> DevicesPage(devices, selected, history, relay.deviceName, actions)
                            Section.Inbox -> InboxPage(relay, history)
                            Section.Settings -> SettingsPage(relay, settings, onChooseFolder = { chooseFolder()?.let(relay::setSaveFolder) })
                            Section.Help -> HelpPage()
                        }
                    }
                }
                TransferDock(relay, transfers, { kindOf(it.peerId) }, Modifier.align(Alignment.BottomEnd).padding(24.dp))
            }

            compose?.let { (to, link) ->
                ComposeDialog(
                    peerName = relay.name(to),
                    link = link,
                    onSend = { text ->
                        compose = null
                        scope.launch { if (link) relay.continueOn(to, text) else relay.sendText(to, text) }
                    },
                    onDismiss = { compose = null },
                )
            }

            val session = pairing.firstOrNull { !it.stage.isFinished && (it.role == PairingRole.INITIATOR || it.stage is PairingStage.Confirm) }
                ?: pairing.lastOrNull { it.stage.isFinished }
            session?.let { current ->
                PairingDialog(
                    session = current,
                    onRespond = { accept, always -> scope.launch { relay.respondToPairing(current.requestId, accept, always) } },
                    onCancel = { scope.launch { relay.cancelPairing(current.requestId) } },
                    onDismiss = { relay.dismissPairing(current.requestId) },
                )
            }

            val offer = transfers.firstOrNull { it.direction == TransferDirection.INCOMING && it.status == TransferStatus.AwaitingDecision }
            if (offer != null && session == null) {
                OfferDialog(
                    transfer = offer,
                    peerName = relay.name(offer.peerId),
                    peerKind = kindOf(offer.peerId),
                    onAccept = { always -> scope.launch { relay.accept(offer.id, if (always) offer.peerId else null) } },
                    onDecline = { scope.launch { relay.decline(offer.id) } },
                )
            }
        }
    }

    LaunchedEffect(devices.map { it.id }) {
        if (navigation.selected == null || devices.none { it.id == navigation.selected }) navigation.selected = devices.firstOrNull()?.id
    }
}

@Composable
private fun Rail(navigation: DesktopNavigation, onSend: () -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = {
            // A standard FAB: the floating-toolbar variant sizes itself from its toolbar and fills any other parent.
            androidx.compose.material3.FloatingActionButton(
                onClick = onSend,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(top = 20.dp, bottom = 20.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(Res.string.action_send))
            }
        },
        modifier = Modifier.fillMaxHeight(),
    ) {
        listOf(
            Triple(Section.Devices, Icons.Rounded.Devices, Res.string.nav_devices),
            Triple(Section.Inbox, Icons.Rounded.Inbox, Res.string.nav_inbox),
            Triple(Section.Settings, Icons.Rounded.Settings, Res.string.nav_settings),
            Triple(Section.Help, Icons.AutoMirrored.Rounded.HelpOutline, Res.string.nav_help),
        ).forEach { (section, icon, label) ->
            NavigationRailItem(
                selected = navigation.section == section,
                onClick = { navigation.section = section },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}
