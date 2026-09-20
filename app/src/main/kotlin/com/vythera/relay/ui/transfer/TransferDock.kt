package com.vythera.relay.ui.transfer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vythera.relay.R
import com.vythera.relay.Texts
import com.vythera.relay.content.ContentCategory
import com.vythera.relay.ui.common.LocalNavAnimatedScope
import com.vythera.relay.ui.common.LocalSharedTransitionScope
import com.vythera.relay.ui.common.relaySharedBounds
import com.vythera.relay.ui.common.toUi
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelayTransferCard
import com.vythera.relay.designsystem.component.RelayTransferPill
import com.vythera.relay.designsystem.component.RelayDeviceAvatar
import com.vythera.relay.designsystem.component.rememberRelayHaptics
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferSnapshot
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.delay

/** What the dock needs to act on transfers. */
class TransferActions(
    val pause: (String) -> Unit,
    val resume: (String) -> Unit,
    val cancel: (String) -> Unit,
    val retry: (String) -> Unit,
    val dismiss: (String) -> Unit,
)

/**
 * The transfer that matters right now, floating above whatever the user is doing.
 *
 * Collapsed it is a pill; tapped, the pill grows into the full transfer card (shared
 * bounds, so it is visibly the same object) over a scrim. When the transfer finishes,
 * the pill morphs to its success state, gives one confirming haptic, and leaves on its
 * own a few seconds later.
 */
@Composable
fun BoxScope.TransferDock(
    transfers: List<TransferSnapshot>,
    peerName: (DeviceId) -> String,
    peerKind: (DeviceId) -> RelayDeviceKind,
    actions: TransferActions,
    bottomPadding: Dp,
) {
    val context = LocalContext.current
    val haptics = rememberRelayHaptics()
    val motion = LocalRelayMotion.current
    // Offers waiting for a decision have their own sheet; everything else can dock.
    val visible = transfers.filterNot { it.direction == TransferDirection.INCOMING && it.status == TransferStatus.AwaitingDecision }
    val current = visible.firstOrNull { it.status.isActive } ?: visible.firstOrNull()
    val others = visible.count { it.status.isActive } - if (current?.status?.isActive == true) 1 else 0
    var expanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(current?.id, current?.status) {
        val status = current?.status ?: return@LaunchedEffect
        when {
            status == TransferStatus.Completed -> {
                haptics.confirm()
                if (!expanded) {
                    delay(4_000)
                    actions.dismiss(current.id)
                }
            }
            status is TransferStatus.Failed && status.reason in RECOVERABLE -> Unit // stays: it will continue by itself
            status.isFinished -> {
                if (status is TransferStatus.Failed) haptics.reject()
                if (!expanded) {
                    delay(6_000)
                    actions.dismiss(current.id)
                }
            }
        }
    }
    if (current == null) {
        expanded = false
        return
    }
    BackHandler(enabled = expanded) { expanded = false }

    val ui = current.toUi(context, peerName(current.peerId), peerKind(current.peerId))
    val key = "transfer-${current.id}"

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(motion.effects()),
        exit = fadeOut(motion.effects()),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = false },
        )
    }

    SharedTransitionLayout(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding).padding(horizontal = 16.dp)) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn(motion.effects()) togetherWith fadeOut(motion.effectsFast()) },
            label = "transferDock",
        ) { isExpanded ->
            CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout, LocalNavAnimatedScope provides this@AnimatedContent) {
                if (isExpanded) {
                    RelayTransferCard(
                        transfer = ui,
                        modifier = Modifier.widthIn(max = 560.dp).relaySharedBounds(key, RelayShapes.Transfer),
                        onPause = { actions.pause(current.id) },
                        onResume = { actions.resume(current.id) },
                        onCancel = {
                            if (current.status.isFinished) actions.dismiss(current.id) else actions.cancel(current.id)
                            expanded = false
                        },
                        onRetry = { actions.retry(current.id) },
                        onOpen = openAction(context, current)?.let { open -> { open(); actions.dismiss(current.id); expanded = false } },
                    )
                } else {
                    RelayTransferPill(
                        transfer = ui,
                        onClick = { expanded = true },
                        extraCount = others,
                        modifier = Modifier.widthIn(max = 420.dp).relaySharedBounds(key, RelayShapes.Pill),
                    )
                }
            }
        }
    }
}

/** Failures Relay recovers from by itself when the device comes back. */
private val RECOVERABLE = setOf(TransferFailure.PEER_UNAVAILABLE, TransferFailure.CONNECTION_LOST)

private fun openAction(context: android.content.Context, transfer: TransferSnapshot): (() -> Unit)? {
    if (transfer.direction != TransferDirection.INCOMING || transfer.status != TransferStatus.Completed) return null
    val file = transfer.storedFiles.singleOrNull() ?: return null
    return {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(file.location), file.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * Someone wants to send something. The sheet leads with what it is (a big content
 * badge and the file name), then who, then the decision.
 */
@Composable
fun OfferSheet(
    transfer: TransferSnapshot,
    peerName: String,
    peerKind: RelayDeviceKind,
    onAccept: (alwaysAllow: Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    val category = ContentCategory.ofTransfer(transfer.kind, transfer.items)
    var alwaysAllow by remember { mutableStateOf(false) }
    RelayExpressiveSheet(
        onDismissRequest = onDecline,
        title = stringResource(R.string.offer_title, peerName),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RelayDeviceAvatar(peerKind, size = 64.dp, containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(Texts.itemsSummary(context, transfer.items, category), style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2)
                Text(Texts.size(context, transfer.totalBytes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
            Text(stringResource(R.string.pair_always_allow), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(16.dp))
        RelaySendButton(
            onClick = { onAccept(alwaysAllow) },
            text = stringResource(R.string.offer_receive),
            icon = category.kind.icon,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onDecline,
            shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.offer_decline), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A clip another device offered while clipboard mode is Ask. */
@Composable
fun ClipOfferBanner(fromName: String, preview: String, onCopy: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RelayShapes.Transfer,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.notification_clip_title, fromName), style = MaterialTheme.typography.titleMedium)
            Text(preview, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onCopy, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                    Text(stringResource(R.string.notification_clip_action))
                }
                FilledTonalButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                    Text(stringResource(R.string.pair_not_now))
                }
            }
        }
    }
}

