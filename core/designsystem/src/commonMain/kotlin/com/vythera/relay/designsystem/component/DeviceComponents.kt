package com.vythera.relay.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.resources.Res
import com.vythera.relay.designsystem.resources.*
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import com.vythera.relay.designsystem.theme.SmoothRoundedShape

enum class RelayPresence { Connected, Nearby, Offline }

fun RelayDeviceKind.icon(): ImageVector = when (this) {
    RelayDeviceKind.Phone -> Icons.Rounded.PhoneAndroid
    RelayDeviceKind.Tablet -> Icons.Rounded.TabletAndroid
    RelayDeviceKind.Laptop -> Icons.Rounded.Laptop
    RelayDeviceKind.Desktop -> Icons.Rounded.DesktopWindows
    RelayDeviceKind.Browser -> Icons.Rounded.Public
    RelayDeviceKind.Unknown -> Icons.Rounded.DevicesOther
}

/**
 * A device's face: its kind's organic silhouette with a glyph inside.
 *
 * While [busy] (a transfer is running) the silhouette turns slowly, the same language
 * as Material's loading indicator, so activity is visible on every card that shows the
 * device without adding a separate spinner. The glyph stays upright.
 */
@Composable
fun RelayDeviceAvatar(
    kind: RelayDeviceKind,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    busy: Boolean = false,
) {
    val shape = RelayShapes.avatarFor(kind).toShape()
    val motion = LocalRelayMotion.current
    val rotation = if (busy && !motion.reduced) {
        rememberInfiniteTransition(label = "avatarBusy").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 6_000, easing = LinearEasing), RepeatMode.Restart),
            label = "avatarRotation",
        )
    } else {
        null
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = rotation?.value ?: 0f }
                .background(containerColor, shape),
        )
        Icon(kind.icon(), contentDescription = null, tint = contentColor, modifier = Modifier.size(size * 0.44f))
    }
}

/**
 * Presence as a dot. When a device becomes nearby the dot sends out a single ring,
 * the "hello" moment, then sits still: presence is not animated continuously.
 */
@Composable
fun RelayConnectionIndicator(presence: RelayPresence, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val color by animateColorAsState(
        when (presence) {
            RelayPresence.Connected -> colors.primary
            RelayPresence.Nearby -> colors.tertiary
            RelayPresence.Offline -> colors.outline
        },
        animationSpec = motion.effects(),
        label = "presenceColor",
    )
    val ring = remember { Animatable(1f) }
    LaunchedEffect(presence) {
        if (presence != RelayPresence.Offline && !motion.reduced) {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(durationMillis = 900))
        }
    }
    Box(
        modifier
            .size(size)
            .drawBehind {
                val progress = ring.value
                if (progress < 1f) {
                    drawCircle(color.copy(alpha = 0.35f * (1f - progress)), radius = this.size.minDimension / 2f * (1f + 1.6f * progress))
                }
                drawCircle(color, radius = this.size.minDimension / 2f)
            },
    )
}

/** Small pill stating presence and, if relevant, trust. */
@Composable
fun RelayStatusChip(
    presence: RelayPresence,
    modifier: Modifier = Modifier,
    trusted: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val label = stringResource(
        when (presence) {
            RelayPresence.Connected -> Res.string.relay_presence_connected
            RelayPresence.Nearby -> Res.string.relay_presence_nearby
            RelayPresence.Offline -> Res.string.relay_presence_offline
        },
    )
    val trustedLabel = stringResource(Res.string.relay_trusted)
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (trusted) "$label, $trustedLabel" else label
        },
        shape = RelayShapes.Pill,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RelayConnectionIndicator(presence, size = 8.dp)
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (trusted) {
                Icon(Icons.Rounded.Verified, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

enum class RelaySendButtonSize(val height: Dp, val iconSize: Dp, val horizontalPadding: Dp) {
    Large(height = 64.dp, iconSize = 24.dp, horizontalPadding = 24.dp),
    Hero(height = 76.dp, iconSize = 28.dp, horizontalPadding = 28.dp),
}

/**
 * Relay's signature control. A pill that squares off while pressed (Material's
 * ButtonShapes morph), with the icon on its own raised circle so the button reads as
 * "an action", not a text link, even at a glance.
 */
@Composable
fun RelaySendButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = stringResource(Res.string.relay_send_something),
    icon: ImageVector = Icons.AutoMirrored.Rounded.Send,
    size: RelaySendButtonSize = RelaySendButtonSize.Hero,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(shape = RelayShapes.Pill, pressedShape = RoundedCornerShape(size.height * 0.3f)),
        modifier = modifier.heightIn(min = size.height),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = PaddingValues(start = 10.dp, end = size.horizontalPadding),
        interactionSource = interaction,
    ) {
        Box(
            Modifier
                .size(size.height - 20.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size.iconSize))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = if (size == RelaySendButtonSize.Hero) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.titleMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Live state a device card can show in place of its subtitle. */
sealed interface RelayDeviceActivity {
    data class Transferring(val progress: Float, val label: String) : RelayDeviceActivity
    data class Message(val label: String) : RelayDeviceActivity
}

/**
 * The most relevant device, given the stage.
 *
 * Its name is set large enough to be part of the composition rather than a label. The
 * container is the asymmetric [RelayShapes.Hero]; on press it shifts toward
 * [RelayShapes.HeroPressed] so the card visibly "gives" before it expands into the
 * device screen (the expansion itself is a shared-bounds transition applied by the
 * caller through [modifier]).
 */
@Composable
fun RelayHeroDeviceCard(
    name: String,
    platformLabel: String,
    kind: RelayDeviceKind,
    presence: RelayPresence,
    trusted: Boolean,
    onClick: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    activity: RelayDeviceActivity? = null,
    sendLabel: String = stringResource(Res.string.relay_send_something),
    sendEnabled: Boolean = presence != RelayPresence.Offline,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val away = presence == RelayPresence.Offline
    val container by animateColorAsState(if (away) colors.surfaceContainerHigh else colors.primaryContainer, motion.effects(), label = "heroContainer")
    val content by animateColorAsState(if (away) colors.onSurface else colors.onPrimaryContainer, motion.effects(), label = "heroContent")

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tucked by animateDpAsState(if (pressed) 30.dp else RelayShapes.Radius.HeroTucked, motion.spatialFast(), label = "heroTucked")
    val corner by animateDpAsState(if (pressed) 54.dp else RelayShapes.Radius.Hero, motion.spatialFast(), label = "heroCorner")
    val shape = remember(tucked, corner) { SmoothRoundedShape(topStart = corner, topEnd = corner, bottomEnd = corner, bottomStart = tucked) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f),
        shape = shape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                RelayDeviceAvatar(
                    kind = kind,
                    size = 76.dp,
                    containerColor = if (away) colors.surfaceContainerHighest else colors.primary,
                    contentColor = if (away) colors.onSurfaceVariant else colors.onPrimary,
                    busy = activity is RelayDeviceActivity.Transferring,
                )
                Spacer(Modifier.weight(1f))
                RelayStatusChip(
                    presence = presence,
                    trusted = trusted,
                    containerColor = if (away) colors.surfaceContainerHighest else colors.surfaceContainerLowest.copy(alpha = 0.72f),
                    contentColor = content,
                )
            }
            Spacer(Modifier.height(RelaySpacing.xxl))
            Text(
                name,
                style = MaterialTheme.typography.displaySmallEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState = activity,
                transitionSpec = { fadeIn(motion.effects()) togetherWith fadeOut(motion.effectsFast()) },
                contentKey = { it?.javaClass },
                label = "heroActivity",
            ) { current ->
                when (current) {
                    null -> Text(platformLabel, style = MaterialTheme.typography.bodyLarge, color = content.copy(alpha = 0.78f))
                    is RelayDeviceActivity.Message -> Text(current.label, style = MaterialTheme.typography.bodyLarge)
                    is RelayDeviceActivity.Transferring -> Column {
                        Text(current.label, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        RelayLinearProgress(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(RelaySpacing.xl))
            RelaySendButton(
                onClick = onSend,
                text = sendLabel,
                enabled = sendEnabled,
                modifier = Modifier.fillMaxWidth(),
                containerColor = if (away) colors.secondary else colors.primary,
                contentColor = if (away) colors.onSecondary else colors.onPrimary,
            )
        }
    }
}

/** How a secondary device card is proportioned. Home mixes these instead of repeating one. */
enum class RelayDeviceCardStyle {
    /** Portrait card: big avatar on top, name at the bottom. Sits beside a stack of compact cards. */
    Tall,

    /** Landscape card: avatar left, name and status right. */
    Wide,

    /** A pill for devices that are away or in a long list. */
    Compact,
}

@Composable
fun RelayDeviceCard(
    name: String,
    platformLabel: String,
    kind: RelayDeviceKind,
    presence: RelayPresence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RelayDeviceCardStyle = RelayDeviceCardStyle.Wide,
    trusted: Boolean = false,
    busy: Boolean = false,
    selected: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val away = presence == RelayPresence.Offline
    val container by animateColorAsState(
        when {
            selected -> colors.secondaryContainer
            busy -> colors.tertiaryContainer
            away -> colors.surfaceContainerLow
            else -> colors.surfaceContainerHigh
        },
        motion.effects(),
        label = "deviceContainer",
    )
    val content by animateColorAsState(
        when {
            selected -> colors.onSecondaryContainer
            busy -> colors.onTertiaryContainer
            away -> colors.onSurfaceVariant
            else -> colors.onSurface
        },
        motion.effects(),
        label = "deviceContent",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val restingRadius = if (style == RelayDeviceCardStyle.Compact) 28.dp else RelayShapes.Radius.Device
    // Selection is shown by shape as well as colour: a selected card rounds further.
    val radius by animateDpAsState(
        if (pressed || selected) RelayShapes.Radius.DevicePressed else restingRadius,
        motion.spatialFast(),
        label = "deviceRadius",
    )
    val shape = remember(radius) { SmoothRoundedShape(radius) }

    val avatarContainer = when {
        away -> colors.surfaceContainerHighest
        busy -> colors.tertiary
        else -> colors.secondary
    }
    val avatarContent = when {
        away -> colors.onSurfaceVariant
        busy -> colors.onTertiary
        else -> colors.onSecondary
    }

    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(interaction),
        shape = shape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        when (style) {
            RelayDeviceCardStyle.Tall -> Column(Modifier.height(196.dp).padding(20.dp)) {
                RelayDeviceAvatar(kind, size = 64.dp, containerColor = avatarContainer, contentColor = avatarContent, busy = busy)
                Spacer(Modifier.weight(1f))
                Text(name, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                PresenceLine(presence, platformLabel, trusted)
            }
            RelayDeviceCardStyle.Wide -> Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RelayDeviceAvatar(kind, size = 56.dp, containerColor = avatarContainer, contentColor = avatarContent, busy = busy)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    PresenceLine(presence, platformLabel, trusted)
                }
            }
            RelayDeviceCardStyle.Compact -> Row(
                Modifier.padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RelayDeviceAvatar(kind, size = 40.dp, containerColor = avatarContainer, contentColor = avatarContent, busy = busy)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(platformLabel, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.72f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PresenceLine(presence: RelayPresence, platformLabel: String, trusted: Boolean) {
    val presenceLabel = stringResource(
        when (presence) {
            RelayPresence.Connected -> Res.string.relay_presence_connected
            RelayPresence.Nearby -> Res.string.relay_presence_nearby
            RelayPresence.Offline -> Res.string.relay_presence_offline
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        RelayConnectionIndicator(presence, size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            "$presenceLabel · $platformLabel",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trusted) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Verified, contentDescription = stringResource(Res.string.relay_trusted), modifier = Modifier.size(16.dp))
        }
    }
}
