package com.vythera.relay.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale. Generous by default: Relay groups content with space, not dividers. */
object RelaySpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val section = 40.dp

    /** Minimum interactive size. Relay's primary controls are much larger. */
    val touchTarget = 48.dp
}

/** Width classes Relay designs for, from the window size rather than the device. */
enum class RelayWidthClass(val screenMargin: Dp, val maxContentWidth: Dp) {
    /** Phones in portrait. */
    Compact(screenMargin = 20.dp, maxContentWidth = 600.dp),

    /** Foldables unfolded, small tablets, phones in landscape. */
    Medium(screenMargin = 32.dp, maxContentWidth = 840.dp),

    /** Tablets and desktops. */
    Expanded(screenMargin = 40.dp, maxContentWidth = 1_280.dp);

    companion object {
        fun fromWidth(width: Dp): RelayWidthClass = when {
            width < 600.dp -> Compact
            width < 840.dp -> Medium
            else -> Expanded
        }
    }
}
