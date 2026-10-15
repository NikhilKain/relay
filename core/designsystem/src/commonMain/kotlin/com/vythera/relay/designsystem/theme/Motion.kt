package com.vythera.relay.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Relay's motion vocabulary.
 *
 * Two families, following Material 3 Expressive:
 * - **spatial** springs move and resize things. They may overshoot slightly, which is
 *   what makes a card expanding into a screen feel physical;
 * - **effects** springs change colour and opacity. They never overshoot.
 *
 * Every animation in Relay picks one of these instead of inventing durations, so the
 * whole app shares one feel. When the user has turned animations off in system
 * settings, spatial motion snaps and effects become short fades: state changes stay
 * visible, movement does not.
 */
@Immutable
class RelayMotion(val reduced: Boolean) {
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 800f)

    fun <T> spatial(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> spatialSlow(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.8f, stiffness = 200f)

    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        if (reduced) tween(durationMillis = 90) else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

    fun <T> effects(): FiniteAnimationSpec<T> =
        if (reduced) tween(durationMillis = 120) else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)

    fun <T> effectsSlow(): FiniteAnimationSpec<T> =
        if (reduced) tween(durationMillis = 150) else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)

    /** The same vocabulary handed to Material components through the theme. */
    val scheme: MotionScheme = if (reduced) ReducedMotionScheme(this) else MotionScheme.expressive()

}

private class ReducedMotionScheme(private val motion: RelayMotion) : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = motion.spatial()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = motion.spatialFast()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = motion.spatialSlow()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = motion.effects()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = motion.effectsFast()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = motion.effectsSlow()
}

val LocalRelayMotion = staticCompositionLocalOf { RelayMotion(reduced = false) }
