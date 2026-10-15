package com.vythera.relay.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.component.RelayDeviceAvatar
import com.vythera.relay.designsystem.component.RelayPairingCode
import com.vythera.relay.designsystem.component.RelayProgressIndicator
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelaySendButtonSize
import com.vythera.relay.designsystem.component.RelayTransferCard
import com.vythera.relay.designsystem.component.RelayTransferPhase
import com.vythera.relay.designsystem.component.RelayTransferPill
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.node.PairingRole
import com.vythera.relay.node.PairingSession
import com.vythera.relay.node.PairingStage
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferSnapshot
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val pillShapes @Composable get() = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)

@Composable
fun PairingDialog(session: PairingSession, onRespond: (Boolean, Boolean) -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val stage = session.stage
    val name = session.peerName
    LaunchedEffect(stage) {
        if (stage == PairingStage.Completed) {
            delay(1_800)
            onDismiss()
        }
    }
    val title = when (stage) {
        PairingStage.Exchanging -> stringResource(Res.string.pair_outgoing_connecting, name)
        is PairingStage.Confirm -> stringResource(Res.string.pair_incoming_title, name)
        is PairingStage.WaitingForPeer -> stringResource(Res.string.pair_outgoing_title, name)
        PairingStage.Completed -> stringResource(Res.string.pair_done_title, name)
        else -> stringResource(Res.string.pair_failed_title)
    }
    RelayDialog(title = title, onDismiss = { if (stage.isFinished) onDismiss() else onCancel() }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (stage) {
                PairingStage.Exchanging -> {
                    LoadingIndicator(Modifier.height(96.dp).width(96.dp))
                    if (session.role == PairingRole.INITIATOR) {
                        FilledTonalButton(onClick = onCancel, shapes = pillShapes) { Text(stringResource(Res.string.cancel)) }
                    }
                }
                is PairingStage.Confirm -> {
                    var alwaysAllow by remember { mutableStateOf(false) }
                    Text(stringResource(Res.string.pair_incoming_body, name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    RelayPairingCode(stage.code.digits, stage.code.shapes)
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
                        Text(stringResource(Res.string.pair_always_allow), style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(16.dp))
                    RelaySendButton(onClick = { onRespond(true, alwaysAllow) }, text = stringResource(Res.string.pair_trust), icon = Icons.Rounded.Verified, size = RelaySendButtonSize.Large, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { onRespond(false, false) }, shapes = pillShapes, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(Res.string.pair_not_now))
                    }
                }
                is PairingStage.WaitingForPeer -> {
                    Text(stringResource(Res.string.pair_outgoing_body, name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    RelayPairingCode(stage.code.digits, stage.code.shapes)
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(onClick = onCancel, shapes = pillShapes, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(Res.string.pair_mismatch))
                    }
                }
                PairingStage.Completed -> {
                    RelayProgressIndicator({ 1f }, RelayTransferPhase.Completed, size = 112.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(Res.string.pair_done_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(stringResource(Res.string.pair_failed_body, name), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(20.dp))
                    RelaySendButton(onClick = onDismiss, text = stringResource(Res.string.ok), icon = Icons.Rounded.Check, size = RelaySendButtonSize.Large, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun OfferDialog(transfer: TransferSnapshot, peerName: String, peerKind: RelayDeviceKind, onAccept: (Boolean) -> Unit, onDecline: () -> Unit) {
    var alwaysAllow by remember { mutableStateOf(false) }
    RelayDialog(title = stringResource(Res.string.offer_title, peerName), onDismiss = onDecline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RelayDeviceAvatar(peerKind, size = 64.dp, containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(DesktopRelay.summary(transfer.items), style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(formatSize(transfer.totalBytes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
            Text(stringResource(Res.string.pair_always_allow), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(16.dp))
        RelaySendButton(onClick = { onAccept(alwaysAllow) }, text = stringResource(Res.string.offer_receive), icon = Icons.Rounded.Download, size = RelaySendButtonSize.Large, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onDecline, shapes = pillShapes, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(Res.string.offer_decline))
        }
    }
}

@Composable
fun ComposeDialog(peerName: String, link: Boolean, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    RelayDialog(
        title = stringResource(if (link) Res.string.compose_link_title else Res.string.compose_text_title, peerName),
        onDismiss = onDismiss,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(if (link) Res.string.compose_link_hint else Res.string.compose_text_hint)) },
            textStyle = MaterialTheme.typography.titleLarge,
            singleLine = link,
            shape = RelayShapes.Small,
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (link) 64.dp else 140.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RelaySendButton(
                onClick = { if (text.isNotBlank()) onSend(text.trim()) },
                text = stringResource(if (link) Res.string.compose_open else Res.string.compose_send),
                icon = if (link) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.Send,
                size = RelaySendButtonSize.Large,
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onDismiss, shapes = pillShapes, modifier = Modifier.height(56.dp)) { Text(stringResource(Res.string.cancel)) }
        }
    }
}

/**
 * The current transfer in the bottom-right corner: a pill while you do other things,
 * the full card when clicked. Finished transfers leave on their own; ones waiting for
 * an absent device stay, because they will continue by themselves.
 */
@Composable
fun TransferDock(relay: DesktopRelay, transfers: List<TransferSnapshot>, kindOf: (TransferSnapshot) -> RelayDeviceKind, modifier: Modifier = Modifier) {
    val motion = LocalRelayMotion.current
    val visible = transfers.filterNot {
        (it.direction == TransferDirection.INCOMING && it.status == TransferStatus.AwaitingDecision) || it.kind == com.vythera.relay.protocol.TransferKind.CLIPBOARD
    }
    val current = visible.firstOrNull { it.status.isActive } ?: visible.firstOrNull() ?: return
    var expanded by remember { mutableStateOf(false) }
    val recoverable = (current.status as? TransferStatus.Failed)?.reason in setOf(TransferFailure.PEER_UNAVAILABLE, TransferFailure.CONNECTION_LOST)
    LaunchedEffect(current.id, current.status) {
        if (current.status.isFinished && !recoverable && !expanded) {
            delay(5_000)
            relay.dismiss(current.id)
        }
    }
    val ui = current.toUi(relay, kindOf(current))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AnimatedContent(expanded, transitionSpec = { fadeIn(motion.effects()) togetherWith fadeOut(motion.effectsFast()) }, modifier = modifier, label = "dock") { open ->
        if (open) {
            RelayTransferCard(
                transfer = ui,
                modifier = Modifier.widthIn(max = 460.dp),
                onPause = { scope.launch { relay.pause(current.id) } },
                onResume = { scope.launch { relay.resume(current.id) } },
                onCancel = {
                    if (current.status.isFinished) relay.dismiss(current.id) else scope.launch { relay.cancel(current.id) }
                    expanded = false
                },
                onRetry = { relay.retry(current.id) },
                onOpen = current.storedFiles.singleOrNull()?.let { file -> { relay.open(java.io.File(file.location)); expanded = false } },
            )
        } else {
            RelayTransferPill(ui, onClick = { expanded = true }, modifier = Modifier.widthIn(max = 400.dp))
        }
    }
}
