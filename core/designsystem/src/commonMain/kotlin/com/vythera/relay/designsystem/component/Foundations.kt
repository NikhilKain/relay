package com.vythera.relay.designsystem.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import com.vythera.relay.designsystem.theme.LocalRelayMotion

/**
 * A shape partway between two Material shapes. Used where a component changes state by
 * changing silhouette (a progress ring becoming a success badge) rather than by
 * swapping one element for another.
 */
@Immutable
class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, Path())
        val matrix = Matrix().apply {
            // Material shapes live in a unit square; scale to the component and spin around its centre.
            translate(size.width / 2f, size.height / 2f)
            rotateZ(rotation)
            translate(-size.width / 2f, -size.height / 2f)
            scale(size.width, size.height)
        }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

/**
 * Press feedback shared by every large tappable surface: a small, quick squash on
 * press and a springy release. Paired with a shape change by the caller, this is
 * Relay's "physical" press.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = LocalRelayMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !motion.reduced) pressedScale else 1f,
        animationSpec = if (pressed) motion.effectsFast() else motion.spatialFast(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The entrance: content arriving for the first time rises into place on a spring, one
 * item after the next, so a screen assembles itself rather than appearing all at once.
 *
 * [play] belongs to the screen, not the item, so a list that recycles a row while
 * scrolling does not animate it again. Reduced motion skips it entirely.
 */
@Composable
fun Modifier.relayEntrance(index: Int = 0, play: Boolean = true): Modifier {
    val motion = LocalRelayMotion.current
    val progress = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(motion.reduced) {
        if (motion.reduced) return@LaunchedEffect
        delay(index * STAGGER_MILLIS)
        progress.animateTo(1f, motion.spatialSlow())
    }
    if (!play || motion.reduced) return this
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 20.dp.toPx()
        scaleX = 0.98f + 0.02f * progress.value
        scaleY = scaleX
    }
}

private const val STAGGER_MILLIS = 45L

/**
 * Relay's haptic vocabulary. Deliberately small: a haptic marks a moment the user
 * should feel (a device arrived, something was accepted or finished), never scrolling
 * or ordinary taps, which Material components already handle.
 *
 * Each platform maps these to its own feedback; on desktop they do nothing.
 */
enum class RelayHapticKind { Tick, GestureStart, Confirm, Reject }

@Stable
class RelayHaptics(private val perform: (RelayHapticKind) -> Unit) {
    /** A device appeared or was selected. */
    fun tick() = perform(RelayHapticKind.Tick)

    /** Something the user started: a send, a Beam, accepting a transfer. */
    fun gestureStart() = perform(RelayHapticKind.GestureStart)

    /** A transfer completed or pairing succeeded. */
    fun confirm() = perform(RelayHapticKind.Confirm)

    /** A failure the user should notice. */
    fun reject() = perform(RelayHapticKind.Reject)
}

@Composable
expect fun rememberRelayHaptics(): RelayHaptics
