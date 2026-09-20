package com.vythera.relay.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.vythera.relay.designsystem.resources.Res
import com.vythera.relay.designsystem.resources.*
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import com.vythera.relay.designsystem.theme.RelayTextStyles

enum class RelayTransferPhase { Waiting, Running, Paused, Completed, Failed }

enum class RelayTransferDirection { Sending, Receiving }

/**
 * Everything a transfer component shows. Formatting (sizes, speeds, durations, error
 * sentences) happens in the app so components stay free of domain types.
 */
data class RelayTransferUi(
    val direction: RelayTransferDirection,
    val peerName: String,
    val peerKind: RelayDeviceKind,
    val title: String,
    val subtitle: String,
    val progress: Float,
    val phase: RelayTransferPhase,
    val speedLabel: String? = null,
    val remainingLabel: String? = null,
    /** Shown in the Failed phase, e.g. "Connection lost. We'll retry when Gaming PC is back." */
    val failureMessage: String? = null,
) {
    val percent: Int get() = (progress * 100).toInt().coerceIn(0, 100)
}

/** Thick wavy bar. Waves while moving, flattens while paused. */
@Composable
fun RelayLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    thickness: Dp = 8.dp,
) {
    val motion = LocalRelayMotion.current
    val amplitude by animateFloatAsState(if (paused || motion.reduced) 0f else 1f, motion.effectsSlow(), label = "waveAmplitude")
    val strokePx = with(LocalDensity.current) { thickness.toPx() }
    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier.height(thickness * 2.2f),
        color = color,
        trackColor = trackColor,
        stroke = Stroke(width = strokePx, cap = StrokeCap.Round),
        trackStroke = Stroke(width = strokePx, cap = StrokeCap.Round),
        amplitude = { p -> WavyProgressIndicatorDefaults.indicatorAmplitude(p) * amplitude },
        wavelength = 40.dp,
    )
}

/**
 * Circular progress that becomes its own outcome. On completion the ring's container
 * morphs from a circle into a scalloped badge and a check springs in; on failure it
 * settles into a softened square. Nothing is swapped out, so the eye stays in place.
 */
@Composable
fun RelayProgressIndicator(
    progress: () -> Float,
    phase: RelayTransferPhase,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val settled = phase == RelayTransferPhase.Completed || phase == RelayTransferPhase.Failed

    val morphProgress = remember { Animatable(if (settled) 1f else 0f) }
    LaunchedEffect(settled) { morphProgress.animateTo(if (settled) 1f else 0f, motion.spatial()) }
    val morph = remember(phase == RelayTransferPhase.Failed) {
        Morph(MaterialShapes.Circle, if (phase == RelayTransferPhase.Failed) MaterialShapes.Square else MaterialShapes.Cookie9Sided)
    }
    val badgeColor by animateColorAsState(
        when (phase) {
            RelayTransferPhase.Completed -> colors.tertiary
            RelayTransferPhase.Failed -> colors.error
            else -> colors.surfaceContainerHighest
        },
        motion.effects(),
        label = "badgeColor",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    shape = MorphShape(morph, morphProgress.value, rotation = morphProgress.value * 30f)
                    clip = true
                }
                .background(badgeColor),
        )
        AnimatedContent(
            targetState = phase,
            transitionSpec = { (scaleIn(motion.spatialFast(), initialScale = 0.4f) + fadeIn(motion.effects())) togetherWith fadeOut(motion.effectsFast()) },
            contentKey = { it == RelayTransferPhase.Completed || it == RelayTransferPhase.Failed },
            label = "progressOutcome",
        ) { current ->
            when (current) {
                RelayTransferPhase.Completed -> Icon(Icons.Rounded.Check, null, tint = colors.onTertiary, modifier = Modifier.size(size * 0.5f))
                RelayTransferPhase.Failed -> Icon(Icons.Rounded.PriorityHigh, null, tint = colors.onError, modifier = Modifier.size(size * 0.46f))
                RelayTransferPhase.Waiting -> LoadingIndicator(Modifier.size(size), color = colors.primary)
                else -> CircularWavyProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(size - 8.dp),
                    color = colors.primary,
                    trackColor = colors.secondaryContainer,
                    amplitude = { p -> if (current == RelayTransferPhase.Paused || motion.reduced) 0f else WavyProgressIndicatorDefaults.indicatorAmplitude(p) },
                )
            }
        }
    }
}

/**
 * The expanded transfer: the moment the user watches. The percentage is the largest
 * type in the app, the bar spans the whole container, and the controls sit in a row
 * of pills. When the transfer finishes the container's colour moves to tertiary, the
 * number becomes "Sent", and the live details collapse away.
 */
@Composable
fun RelayTransferCard(
    transfer: RelayTransferUi,
    modifier: Modifier = Modifier,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpen: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val phase = transfer.phase
    val container by animateColorAsState(
        when (phase) {
            RelayTransferPhase.Completed -> colors.tertiaryContainer
            RelayTransferPhase.Failed -> colors.errorContainer
            else -> colors.surfaceContainerHigh
        },
        motion.effectsSlow(),
        label = "transferContainer",
    )
    val content by animateColorAsState(
        when (phase) {
            RelayTransferPhase.Completed -> colors.onTertiaryContainer
            RelayTransferPhase.Failed -> colors.onErrorContainer
            else -> colors.onSurface
        },
        motion.effectsSlow(),
        label = "transferContent",
    )
    val heading = when {
        phase == RelayTransferPhase.Waiting -> stringResource(Res.string.relay_transfer_waiting, transfer.peerName)
        transfer.direction == RelayTransferDirection.Sending -> stringResource(Res.string.relay_transfer_sending_to, transfer.peerName)
        else -> stringResource(Res.string.relay_transfer_receiving_from, transfer.peerName)
    }

    Surface(modifier.fillMaxWidth(), shape = RelayShapes.Transfer, color = container, contentColor = content) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RelayDeviceAvatar(
                    kind = transfer.peerKind,
                    size = 40.dp,
                    containerColor = colors.secondary,
                    contentColor = colors.onSecondary,
                    busy = phase == RelayTransferPhase.Running,
                )
                Spacer(Modifier.width(12.dp))
                Text(heading, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (phase == RelayTransferPhase.Completed || phase == RelayTransferPhase.Failed) {
                    RelayProgressIndicator({ transfer.progress }, phase, size = 40.dp)
                }
            }
            Spacer(Modifier.height(RelaySpacing.l))
            Text(transfer.title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(transfer.subtitle, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.74f), maxLines = 1)

            Spacer(Modifier.height(RelaySpacing.s))
            PercentHeadline(transfer)

            AnimatedVisibility(
                visible = phase == RelayTransferPhase.Running || phase == RelayTransferPhase.Paused || phase == RelayTransferPhase.Waiting,
                enter = expandVertically(motion.spatial()) + fadeIn(motion.effects()),
                exit = shrinkVertically(motion.spatial()) + fadeOut(motion.effectsFast()),
            ) {
                Column {
                    Spacer(Modifier.height(RelaySpacing.s))
                    if (phase == RelayTransferPhase.Waiting) {
                        RelayLinearProgress(progress = { 0f }, paused = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        RelayLinearProgress(progress = { transfer.progress }, paused = phase == RelayTransferPhase.Paused, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(RelaySpacing.s))
                    Row(Modifier.fillMaxWidth()) {
                        Text(transfer.speedLabel.orEmpty(), style = RelayTextStyles.LiveFigure)
                        Spacer(Modifier.weight(1f))
                        Text(transfer.remainingLabel.orEmpty(), style = RelayTextStyles.LiveFigure, color = content.copy(alpha = 0.74f))
                    }
                }
            }

            AnimatedVisibility(visible = phase == RelayTransferPhase.Failed && transfer.failureMessage != null) {
                Text(
                    transfer.failureMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = RelaySpacing.s).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Spacer(Modifier.height(RelaySpacing.xl))
            TransferActions(phase, onPause, onResume, onCancel, onRetry, onOpen)
        }
    }
}

@Composable
private fun PercentHeadline(transfer: RelayTransferUi) {
    val motion = LocalRelayMotion.current
    val description = stringResource(Res.string.relay_transfer_progress_description, transfer.percent)
    val doneLabel = stringResource(
        if (transfer.direction == RelayTransferDirection.Sending) Res.string.relay_transfer_sent else Res.string.relay_transfer_received,
    )
    val pausedLabel = stringResource(Res.string.relay_transfer_paused)
    AnimatedContent(
        targetState = transfer.phase == RelayTransferPhase.Completed,
        transitionSpec = {
            (scaleIn(motion.spatial(), initialScale = 0.85f) + fadeIn(motion.effects())) togetherWith fadeOut(motion.effectsFast())
        },
        label = "percentOrDone",
        modifier = Modifier.clearAndSetSemantics { contentDescription = if (transfer.phase == RelayTransferPhase.Completed) doneLabel else description },
    ) { done ->
        if (done) {
            Text(doneLabel, style = RelayTextStyles.Percent)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${transfer.percent}", style = RelayTextStyles.Percent)
                Text("%", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 14.dp, start = 2.dp))
                if (transfer.phase == RelayTransferPhase.Paused) {
                    Spacer(Modifier.width(16.dp))
                    Text(pausedLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun TransferActions(
    phase: RelayTransferPhase,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val pillShapes = ButtonDefaults.shapes(shape = RelayShapes.Pill, pressedShape = RelayShapes.ExtraSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when (phase) {
            RelayTransferPhase.Running, RelayTransferPhase.Paused -> {
                val running = phase == RelayTransferPhase.Running
                Button(
                    onClick = if (running) onPause else onResume,
                    shapes = pillShapes,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(stringResource(if (running) Res.string.relay_transfer_pause else Res.string.relay_transfer_resume), style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(onClick = onCancel, shapes = pillShapes, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.relay_transfer_cancel))
                }
            }
            RelayTransferPhase.Waiting -> FilledTonalButton(onClick = onCancel, shapes = pillShapes, modifier = Modifier.height(56.dp)) {
                Text(stringResource(Res.string.relay_transfer_cancel), style = MaterialTheme.typography.titleMedium)
            }
            RelayTransferPhase.Failed -> {
                Button(onClick = onRetry, shapes = pillShapes, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text(stringResource(Res.string.relay_transfer_retry), style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(onClick = onCancel, shapes = pillShapes, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.relay_transfer_cancel))
                }
            }
            RelayTransferPhase.Completed -> if (onOpen != null) {
                Button(
                    onClick = onOpen,
                    shapes = pillShapes,
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(stringResource(Res.string.relay_transfer_open), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/**
 * The transfer, collapsed. Floats above content while the user does something else and
 * expands back into [RelayTransferCard] when tapped (the app pairs the two with a
 * shared-bounds transition so the pill visibly grows into the card).
 */
@Composable
fun RelayTransferPill(
    transfer: RelayTransferUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraCount: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalRelayMotion.current
    val container by animateColorAsState(
        when (transfer.phase) {
            RelayTransferPhase.Completed -> colors.tertiaryContainer
            RelayTransferPhase.Failed -> colors.errorContainer
            else -> colors.inverseSurface
        },
        motion.effects(),
        label = "pillContainer",
    )
    val content by animateColorAsState(
        when (transfer.phase) {
            RelayTransferPhase.Completed -> colors.onTertiaryContainer
            RelayTransferPhase.Failed -> colors.onErrorContainer
            else -> colors.inverseOnSurface
        },
        motion.effects(),
        label = "pillContent",
    )
    val expandLabel = stringResource(Res.string.relay_transfer_expand)
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "$expandLabel: ${transfer.title}, ${transfer.percent}%" },
        shape = RelayShapes.Pill,
        color = container,
        contentColor = content,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 6.dp, end = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RelayShapes.Pill).background(colors.surfaceContainerLowest.copy(alpha = 0.1f))) {
                RelayProgressIndicator({ transfer.progress }, transfer.phase, size = 44.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(transfer.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (transfer.phase) {
                        RelayTransferPhase.Completed -> stringResource(
                            if (transfer.direction == RelayTransferDirection.Sending) Res.string.relay_transfer_sent else Res.string.relay_transfer_received,
                        )
                        RelayTransferPhase.Failed -> transfer.failureMessage.orEmpty()
                        else -> transfer.peerName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = content.copy(alpha = 0.78f),
                )
            }
            if (transfer.phase == RelayTransferPhase.Running || transfer.phase == RelayTransferPhase.Paused) {
                Spacer(Modifier.width(14.dp))
                Text("${transfer.percent}%", style = RelayTextStyles.LiveFigure.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize))
            }
            if (extraCount > 0) {
                Spacer(Modifier.width(10.dp))
                Surface(shape = RelayShapes.Pill, color = colors.primary, contentColor = colors.onPrimary) {
                    Text("+$extraCount", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
}
