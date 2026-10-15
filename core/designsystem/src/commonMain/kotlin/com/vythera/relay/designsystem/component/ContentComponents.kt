package com.vythera.relay.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import com.vythera.relay.designsystem.resources.Res
import com.vythera.relay.designsystem.resources.*
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import com.vythera.relay.designsystem.theme.SmoothRoundedShape
import kotlinx.coroutines.delay

/** What kind of thing moved between devices. Each has a glyph and a silhouette. */
enum class RelayContentKind(val icon: ImageVector) {
    Photos(Icons.Rounded.Photo),
    Videos(Icons.Rounded.Movie),
    Files(Icons.Rounded.Description),
    Folder(Icons.Rounded.Folder),
    Link(Icons.Rounded.Link),
    Text(Icons.AutoMirrored.Rounded.TextSnippet),
    App(Icons.Rounded.Android),
    Clipboard(Icons.Rounded.ContentPaste);

    val silhouette: RoundedPolygon
        get() = when (this) {
            Photos -> MaterialShapes.Flower
            Videos -> MaterialShapes.Pill
            Files -> MaterialShapes.Square
            Folder -> MaterialShapes.Bun
            Link -> MaterialShapes.Cookie4Sided
            Text -> MaterialShapes.Ghostish
            App -> MaterialShapes.Cookie7Sided
            Clipboard -> MaterialShapes.Gem
        }
}

enum class RelayActionEmphasis { Primary, Secondary, Tertiary, Neutral }

enum class RelayActionSize { Large, Medium, Compact }

/**
 * A thing the user can send or do. Sizes are meant to be mixed in one composition:
 * the actions people use most get [RelayActionSize.Large], the rest shrink to pills.
 */
@Composable
fun RelayActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    emphasis: RelayActionEmphasis = RelayActionEmphasis.Secondary,
    size: RelayActionSize = RelayActionSize.Medium,
    badgeShape: RoundedPolygon = MaterialShapes.Cookie4Sided,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val (container, content, badge, onBadge) = when (emphasis) {
        RelayActionEmphasis.Primary -> listOf(colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary)
        RelayActionEmphasis.Secondary -> listOf(colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary, colors.onSecondary)
        RelayActionEmphasis.Tertiary -> listOf(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary, colors.onTertiary)
        RelayActionEmphasis.Neutral -> listOf(colors.surfaceContainerHigh, colors.onSurface, colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rest = when (size) {
        RelayActionSize.Large -> 36.dp
        RelayActionSize.Medium -> 28.dp
        RelayActionSize.Compact -> 26.dp
    }
    val radius by animateDpAsState(if (pressed) rest + 14.dp else rest, motion.spatialFast(), label = "actionRadius")
    val shape = remember(radius) { SmoothRoundedShape(radius) }
    val badgeRotation = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        // The badge turns a little under the finger: small, but it makes a tile feel alive.
        if (!motion.reduced) badgeRotation.animateTo(if (pressed) 22f else 0f, motion.spatialFast())
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.pressScale(interaction),
        shape = shape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        when (size) {
            RelayActionSize.Large -> Column(Modifier.height(168.dp).padding(20.dp)) {
                ShapeBadge(icon, badgeShape, badge, onBadge, 56.dp, badgeRotation.value)
                Spacer(Modifier.weight(1f))
                Text(label, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supportingText != null) {
                    Text(supportingText, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.76f), maxLines = 1)
                }
            }
            RelayActionSize.Medium -> Row(Modifier.padding(14.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(icon, badgeShape, badge, onBadge, 48.dp, badgeRotation.value)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (supportingText != null) {
                        Text(supportingText, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.76f), maxLines = 1)
                    }
                }
            }
            RelayActionSize.Compact -> Row(
                Modifier.padding(start = 8.dp, end = 18.dp).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShapeBadge(icon, badgeShape, badge, onBadge, 36.dp, badgeRotation.value)
                Spacer(Modifier.width(10.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    // Compact pills share a row; shrink the label rather than cut it off.
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                )
            }
        }
    }
}

@Composable
private fun ShapeBadge(icon: ImageVector, polygon: RoundedPolygon, container: Color, content: Color, size: Dp, rotation: Float) {
    val shape = polygon.toShape()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { rotationZ = rotation }.background(container, shape))
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.46f))
    }
}

/** Where an item sits in a visually grouped list. Outer corners are large, inner corners tight. */
enum class RelayListPosition { Single, First, Middle, Last }

private fun RelayListPosition.shape(): SmoothRoundedShape {
    val outer = 28.dp
    val inner = 8.dp
    return when (this) {
        RelayListPosition.Single -> SmoothRoundedShape(outer)
        RelayListPosition.First -> SmoothRoundedShape(outer, outer, inner, inner)
        RelayListPosition.Middle -> SmoothRoundedShape(inner)
        RelayListPosition.Last -> SmoothRoundedShape(inner, inner, outer, outer)
    }
}

/**
 * One entry of recent activity: what moved, between which devices, when.
 *
 * Rows of a group share one tonal surface broken by 3dp gaps, with only the outer
 * corners rounded large, so a list reads as one object instead of a stack of cards.
 */
@Composable
fun RelayActivityItem(
    title: String,
    fromName: String,
    toName: String,
    timeLabel: String,
    kind: RelayContentKind,
    modifier: Modifier = Modifier,
    position: RelayListPosition = RelayListPosition.Single,
    statusLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val routeDescription = "$fromName to $toName"
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeBadge(kind.icon, kind.silhouette, colors.secondaryContainer, colors.onSecondaryContainer, 44.dp, 0f)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = routeDescription },
                ) {
                    Text(fromName, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp).size(14.dp))
                    Text(toName, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(timeLabel, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                if (statusLabel != null) {
                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = colors.primary)
                }
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = position.shape(), color = colors.surfaceContainerLow, content = content)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = position.shape(), color = colors.surfaceContainerLow, content = content)
    }
}

/** Section title with an optional trailing action. Marked as a heading for screen readers. */
@Composable
fun RelaySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/**
 * Two device silhouettes and a small shape travelling between them: what Relay does,
 * drawn with the same shapes as the rest of the UI. The travelling shape crosses once
 * every few seconds and rests in between; with reduced motion it rests mid-way.
 */
@Composable
fun RelayHandoffIllustration(modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val travel = remember { Animatable(if (motion.reduced) 0.5f else 0f) }
    LaunchedEffect(motion.reduced) {
        if (motion.reduced) return@LaunchedEffect
        while (true) {
            delay(1_400)
            travel.animateTo(1f, motion.spatialSlow())
            delay(1_400)
            travel.animateTo(0f, motion.spatialSlow())
        }
    }
    val phoneShape = MaterialShapes.Cookie6Sided.toShape()
    val desktopShape = MaterialShapes.Clover4Leaf.toShape()
    val dotShape = MaterialShapes.Sunny.toShape()
    Box(modifier.size(width = size, height = size * 0.62f).clearAndSetSemantics {}) {
        Box(
            Modifier.size(size * 0.42f).align(Alignment.BottomStart)
                .background(colors.primaryContainer, phoneShape),
        )
        Box(
            Modifier.size(size * 0.46f).align(Alignment.TopEnd)
                .background(colors.tertiaryContainer, desktopShape),
        )
        val dot = size * 0.16f
        Box(
            Modifier
                .size(dot)
                .graphicsLayer {
                    val t = travel.value
                    val startX = size.toPx() * 0.13f
                    val endX = size.toPx() * 0.71f
                    val startY = size.toPx() * 0.62f * 0.62f
                    val endY = size.toPx() * 0.62f * 0.12f
                    // A shallow arc rather than a straight line: things are handed over, not shot across.
                    translationX = startX + (endX - startX) * t
                    translationY = startY + (endY - startY) * t - size.toPx() * 0.12f * kotlin.math.sin(kotlin.math.PI * t).toFloat()
                    rotationZ = t * 90f
                }
                .background(colors.primary, dotShape),
        )
    }
}

/** Beautiful emptiness: an illustration, one large sentence, one explanation, one action. */
@Composable
fun RelayEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = RelaySpacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        RelayHandoffIllustration()
        Spacer(Modifier.height(RelaySpacing.xxl))
        Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(RelaySpacing.xs))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = RelaySpacing.xl),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(RelaySpacing.xl))
            androidx.compose.material3.FilledTonalButton(
                onClick = onAction,
                shapes = androidx.compose.material3.ButtonDefaults.shapes(shape = RelayShapes.Pill, pressedShape = RelayShapes.ExtraSmall),
                modifier = Modifier.height(56.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

/**
 * Relay's bottom sheet: large top corners from the shape family, a wide pill handle,
 * and a title set large enough to be the sheet's anchor.
 */
@Composable
fun RelayExpressiveSheet(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    supportingText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RelayShapes.BottomSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 48.dp, height = 5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = RelaySpacing.xl).padding(bottom = RelaySpacing.xl)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMediumEmphasized,
                modifier = Modifier.padding(top = RelaySpacing.xs).semantics { heading() },
            )
            if (supportingText != null) {
                Spacer(Modifier.height(6.dp))
                Text(supportingText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(RelaySpacing.xl))
            content()
        }
    }
}

/**
 * The pairing code: three large shapes to compare at a glance and six digits for
 * certainty. Shapes arrive one after another so the eye reads them left to right.
 */
@Composable
fun RelayPairingCode(digits: String, shapeIndices: List<Int>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val palette = RelayShapes.PairingPalette
    val containers = listOf(colors.primary, colors.secondary, colors.tertiary)
    val grouped = if (digits.length == 6) digits.substring(0, 3) + " " + digits.substring(3) else digits
    val description = stringResource(Res.string.relay_pairing_code_description, grouped)
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            shapeIndices.forEachIndexed { i, index ->
                val scale = remember { Animatable(if (motion.reduced) 1f else 0f) }
                LaunchedEffect(digits) {
                    if (!motion.reduced) {
                        delay(90L * i)
                        scale.animateTo(1f, motion.spatialFast())
                    }
                }
                Box(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        }
                        .background(containers[i % containers.size], palette[index % palette.size].toShape()),
                )
            }
        }
        Spacer(Modifier.height(RelaySpacing.xl))
        Text(grouped, style = MaterialTheme.typography.displaySmallEmphasized.copy(fontFeatureSettings = "tnum"))
    }
}

/** Wrapper used by previews and screens for a device kind label without domain types. */
fun RelayDeviceKind.defaultLabel(): String = when (this) {
    RelayDeviceKind.Phone -> "Phone"
    RelayDeviceKind.Tablet -> "Tablet"
    RelayDeviceKind.Laptop -> "Laptop"
    RelayDeviceKind.Desktop -> "Desktop"
    RelayDeviceKind.Browser -> "Browser"
    RelayDeviceKind.Unknown -> "Device"
}
