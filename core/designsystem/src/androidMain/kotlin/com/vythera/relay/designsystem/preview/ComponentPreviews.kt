package com.vythera.relay.designsystem.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.component.RelayActionEmphasis
import com.vythera.relay.designsystem.component.RelayActionSize
import com.vythera.relay.designsystem.component.RelayActionTile
import com.vythera.relay.designsystem.component.RelayActivityItem
import com.vythera.relay.designsystem.component.RelayContentKind
import com.vythera.relay.designsystem.component.RelayDeviceActivity
import com.vythera.relay.designsystem.component.RelayDeviceAvatar
import com.vythera.relay.designsystem.component.RelayDeviceCard
import com.vythera.relay.designsystem.component.RelayDeviceCardStyle
import com.vythera.relay.designsystem.component.RelayEmptyState
import com.vythera.relay.designsystem.component.RelayHeroDeviceCard
import com.vythera.relay.designsystem.component.RelayListPosition
import com.vythera.relay.designsystem.component.RelayLockup
import com.vythera.relay.designsystem.component.RelayMark
import com.vythera.relay.designsystem.component.RelayPairingCode
import com.vythera.relay.designsystem.component.RelayPresence
import com.vythera.relay.designsystem.component.RelayProgressIndicator
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelayStatusChip
import com.vythera.relay.designsystem.component.RelayTransferCard
import com.vythera.relay.designsystem.component.RelayTransferDirection
import com.vythera.relay.designsystem.component.RelayTransferPhase
import com.vythera.relay.designsystem.component.RelayTransferPill
import com.vythera.relay.designsystem.component.RelayTransferUi
import com.vythera.relay.designsystem.theme.RelayDeviceKind

private val sampleTransfer = RelayTransferUi(
    direction = RelayTransferDirection.Sending,
    peerName = "Gaming PC",
    peerKind = RelayDeviceKind.Desktop,
    title = "vacation.zip",
    subtitle = "2.4 GB",
    progress = 0.67f,
    phase = RelayTransferPhase.Running,
    speedLabel = "36 MB/s",
    remainingLabel = "18 seconds remaining",
)

@RelayThemePreviews
@Composable
internal fun MarkPreview() = PreviewTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        RelayMark(size = 96.dp)
        RelayMark(size = 48.dp)
        RelayMark(size = 24.dp)
        RelayLockup()
    }
}

@RelayThemePreviews
@Composable
internal fun HeroDeviceCardPreview() = PreviewTheme {
    RelayHeroDeviceCard(
        name = "Gaming PC",
        platformLabel = "Windows",
        kind = RelayDeviceKind.Desktop,
        presence = RelayPresence.Nearby,
        trusted = true,
        onClick = {},
        onSend = {},
        modifier = Modifier.width(380.dp),
    )
}

@RelayDynamicPreviews
@Composable
internal fun HeroDeviceCardDynamicPreview() = PreviewTheme(dynamic = true) {
    RelayHeroDeviceCard(
        name = "Gaming PC",
        platformLabel = "Windows",
        kind = RelayDeviceKind.Desktop,
        presence = RelayPresence.Connected,
        trusted = true,
        onClick = {},
        onSend = {},
        activity = RelayDeviceActivity.Transferring(0.42f, "Sending 12 photos"),
        modifier = Modifier.width(380.dp),
    )
}

@RelayThemePreviews
@Composable
internal fun HeroDeviceAwayPreview() = PreviewTheme {
    RelayHeroDeviceCard(
        name = "Tab S9 FE+",
        platformLabel = "Android tablet",
        kind = RelayDeviceKind.Tablet,
        presence = RelayPresence.Offline,
        trusted = true,
        onClick = {},
        onSend = {},
        modifier = Modifier.width(380.dp),
    )
}

@RelayThemePreviews
@Composable
internal fun DeviceCardsPreview() = PreviewTheme {
    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RelayDeviceCard("Pixel 6a", "Android", RelayDeviceKind.Phone, RelayPresence.Nearby, {}, Modifier.weight(1f), RelayDeviceCardStyle.Tall, trusted = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RelayDeviceCard("Tab S9 FE+", "Tablet", RelayDeviceKind.Tablet, RelayPresence.Connected, {}, Modifier.fillMaxWidth(), RelayDeviceCardStyle.Compact, busy = true)
                RelayDeviceCard("MacBook", "macOS", RelayDeviceKind.Laptop, RelayPresence.Offline, {}, Modifier.fillMaxWidth(), RelayDeviceCardStyle.Compact)
            }
        }
        RelayDeviceCard("Work laptop", "Linux", RelayDeviceKind.Laptop, RelayPresence.Nearby, {}, Modifier.fillMaxWidth(), RelayDeviceCardStyle.Wide, selected = true)
    }
}

@RelayThemePreviews
@Composable
internal fun AvatarsAndStatusPreview() = PreviewTheme {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RelayDeviceKind.entries.forEach { RelayDeviceAvatar(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayStatusChip(RelayPresence.Connected, trusted = true)
            RelayStatusChip(RelayPresence.Nearby)
            RelayStatusChip(RelayPresence.Offline)
        }
    }
}

@RelayThemePreviews
@Composable
internal fun SendButtonPreview() = PreviewTheme {
    RelaySendButton(onClick = {}, modifier = Modifier.width(340.dp))
}

@RelayThemePreviews
@Composable
internal fun ActionTilesPreview() = PreviewTheme {
    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayActionTile("Photos", Icons.Rounded.Photo, {}, Modifier.weight(1.15f), "Gallery", RelayActionEmphasis.Primary, RelayActionSize.Large, MaterialShapes.Flower)
            RelayActionTile("Files", Icons.Rounded.Description, {}, Modifier.weight(1f), "Any file", RelayActionEmphasis.Tertiary, RelayActionSize.Large, MaterialShapes.Square)
        }
        RelayActionTile("Clipboard", Icons.Rounded.ContentPaste, {}, Modifier.fillMaxWidth(), "Send what you copied", RelayActionEmphasis.Secondary, RelayActionSize.Medium, MaterialShapes.Gem)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayActionTile("Link", Icons.Rounded.Link, {}, emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact)
            RelayActionTile("Text", Icons.Rounded.Description, {}, emphasis = RelayActionEmphasis.Neutral, size = RelayActionSize.Compact)
        }
    }
}

@RelayThemePreviews
@Composable
internal fun TransferCardRunningPreview() = PreviewTheme {
    RelayTransferCard(sampleTransfer, Modifier.width(380.dp))
}

@RelayThemePreviews
@Composable
internal fun TransferCardStatesPreview() = PreviewTheme {
    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RelayTransferCard(sampleTransfer.copy(phase = RelayTransferPhase.Completed, progress = 1f), onOpen = {})
        RelayTransferCard(
            sampleTransfer.copy(
                phase = RelayTransferPhase.Failed,
                failureMessage = "Connection lost. We'll continue when Gaming PC is back.",
            ),
        )
    }
}

@RelayThemePreviews
@Composable
internal fun TransferPillPreview() = PreviewTheme {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RelayTransferPill(sampleTransfer, onClick = {})
        RelayTransferPill(sampleTransfer.copy(phase = RelayTransferPhase.Completed, progress = 1f), onClick = {}, extraCount = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RelayProgressIndicator({ 0.3f }, RelayTransferPhase.Running)
            RelayProgressIndicator({ 0.3f }, RelayTransferPhase.Paused)
            RelayProgressIndicator({ 1f }, RelayTransferPhase.Completed)
            RelayProgressIndicator({ 0.5f }, RelayTransferPhase.Failed)
        }
    }
}

@RelayThemePreviews
@Composable
internal fun ActivityPreview() = PreviewTheme {
    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RelayActivityItem("12 photos", "Pixel 6a", "Gaming PC", "Just now", RelayContentKind.Photos, position = RelayListPosition.First)
        RelayActivityItem("github.com/project", "Gaming PC", "Tab S9 FE+", "4 min", RelayContentKind.Link, position = RelayListPosition.Middle, statusLabel = "Opened")
        RelayActivityItem("Meeting moved to 3 PM", "MacBook", "Pixel 6a", "Yesterday", RelayContentKind.Text, position = RelayListPosition.Last)
    }
}

@RelayThemePreviews
@Composable
internal fun EmptyStatePreview() = PreviewTheme {
    RelayEmptyState(
        title = "Nothing nearby",
        body = "Relay will show your other devices here automatically.",
        actionLabel = "How to connect",
        onAction = {},
        modifier = Modifier.width(380.dp),
    )
}

@RelayThemePreviews
@Composable
internal fun PairingCodePreview() = PreviewTheme {
    RelayPairingCode(digits = "482913", shapeIndices = listOf(3, 6, 0), modifier = Modifier.width(360.dp))
}

@Preview(name = "Large text (160%)", group = "Accessibility", showBackground = true, fontScale = 1.6f)
@Composable
internal fun LargeFontPreview() = PreviewTheme {
    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RelayHeroDeviceCard("Gaming PC", "Windows", RelayDeviceKind.Desktop, RelayPresence.Nearby, true, {}, {})
        RelayTransferPill(sampleTransfer, onClick = {})
    }
}
