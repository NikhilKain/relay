package com.vythera.relay.ui.common

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.vythera.relay.R
import com.vythera.relay.Texts
import com.vythera.relay.content.ContentCategory
import com.vythera.relay.data.db.TransferRecordEntity
import com.vythera.relay.designsystem.component.RelayPresence
import com.vythera.relay.designsystem.component.RelayTransferDirection
import com.vythera.relay.designsystem.component.RelayTransferPhase
import com.vythera.relay.designsystem.component.RelayTransferUi
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.node.Presence
import com.vythera.relay.node.RelayDevice
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferSnapshot
import com.vythera.relay.transfer.TransferStatus

/** A device as screens see it: display strings resolved, no engine types beyond the id. */
data class DeviceUi(
    val id: DeviceId,
    val name: String,
    val kind: RelayDeviceKind,
    val platformLabel: String,
    val presence: RelayPresence,
    val trusted: Boolean,
    val compatible: Boolean,
    val autoAccept: Boolean,
    val activeTransfer: TransferSnapshot?,
) {
    val reachable: Boolean get() = presence != RelayPresence.Offline && compatible
}

fun RelayDevice.toUi(context: Context, transfers: List<TransferSnapshot>) = DeviceUi(
    id = id,
    name = name.ifBlank { Texts.platform(context, platform) },
    kind = Texts.kind(type),
    platformLabel = Texts.platform(context, platform),
    presence = when (presence) {
        Presence.CONNECTED -> RelayPresence.Connected
        Presence.NEARBY -> RelayPresence.Nearby
        Presence.OFFLINE -> RelayPresence.Offline
    },
    trusted = isTrusted,
    compatible = isCompatible,
    autoAccept = autoAcceptTransfers,
    activeTransfer = transfers.firstOrNull { it.peerId == id && it.status.isActive },
)

/**
 * Orders devices by how likely the user wants one right now: busy, then connected,
 * then trusted and nearby, then trusted but away, then new devices.
 */
fun List<DeviceUi>.byRelevance(): List<DeviceUi> = sortedWith(
    compareByDescending<DeviceUi> { it.activeTransfer != null }
        .thenByDescending { it.trusted && it.presence == RelayPresence.Connected }
        .thenByDescending { it.trusted && it.presence == RelayPresence.Nearby }
        .thenByDescending { it.trusted }
        .thenBy { it.name.lowercase() },
)

fun TransferSnapshot.toUi(context: Context, peerName: String, peerKind: RelayDeviceKind): RelayTransferUi {
    val category = ContentCategory.ofTransfer(kind, items)
    val phase = when (status) {
        TransferStatus.Queued, TransferStatus.AwaitingDecision -> RelayTransferPhase.Waiting
        TransferStatus.Running -> RelayTransferPhase.Running
        is TransferStatus.Paused -> RelayTransferPhase.Paused
        TransferStatus.Completed -> RelayTransferPhase.Completed
        TransferStatus.Declined, is TransferStatus.Cancelled, is TransferStatus.Failed -> RelayTransferPhase.Failed
    }
    val failure = when (val s = status) {
        is TransferStatus.Failed -> Texts.failure(context, s.reason, peerName)
        TransferStatus.Declined -> if (direction == TransferDirection.INCOMING) context.getString(R.string.failure_you_declined) else context.getString(R.string.failure_declined, peerName)
        is TransferStatus.Cancelled -> context.getString(R.string.failure_cancelled)
        else -> null
    }
    return RelayTransferUi(
        direction = if (direction == TransferDirection.OUTGOING) RelayTransferDirection.Sending else RelayTransferDirection.Receiving,
        peerName = peerName,
        peerKind = peerKind,
        title = Texts.itemsSummary(context, items, category),
        subtitle = Texts.size(context, totalBytes),
        progress = progress.fraction,
        phase = phase,
        speedLabel = Texts.speed(context, progress.bytesPerSecond),
        remainingLabel = Texts.remaining(context, progress.remainingMillis),
        failureMessage = failure,
    )
}

data class ActivityUi(
    val id: String,
    val title: String,
    val from: String,
    val to: String,
    val time: String,
    val category: ContentCategory,
    val status: String?,
    val peerId: String,
)

fun TransferRecordEntity.toUi(context: Context, localName: String): ActivityUi {
    val outgoing = direction == "outgoing"
    return ActivityUi(
        id = id,
        title = title,
        from = if (outgoing) localName else peerName,
        to = if (outgoing) peerName else localName,
        time = Texts.relativeTime(context, finishedAtMillis),
        category = ContentCategory.fromName(category),
        status = when (outcome) {
            "opened" -> context.getString(R.string.activity_opened)
            "failed", "cancelled" -> context.getString(R.string.activity_failed)
            "declined" -> context.getString(R.string.activity_declined)
            else -> null
        },
        peerId = peerId,
    )
}

/** Shared-element plumbing: screens read these instead of threading scopes through every call. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Container transform between a device card and the device screen (or between the
 * transfer pill and the transfer card). A no-op where no transition scope exists,
 * e.g. in previews.
 */
@Composable
fun Modifier.relaySharedBounds(key: String, shape: Shape): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@relaySharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animated,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
