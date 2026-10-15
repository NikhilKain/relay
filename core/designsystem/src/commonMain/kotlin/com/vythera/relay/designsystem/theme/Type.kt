package com.vythera.relay.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Relay's type scale is deliberately top-heavy. Screens are composed around one very
 * large element (a device name, a percentage, "Your devices") and small supporting
 * text, so hierarchy comes from scale contrast before it comes from containers.
 *
 * Weights use the variable axis of the system sans (Roboto is variable on Android 12+),
 * so display text can sit at 450 while emphasized headlines go to 700 without
 * shipping font files. Large sizes get negative tracking; small labels get positive
 * tracking so they stay legible at a glance.
 */

private val Sans = FontFamily.Default

private val TrimmedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private fun style(size: Int, lineHeight: Int, weight: Int, tracking: Double) = TextStyle(
    fontFamily = Sans,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    lineHeightStyle = TrimmedLineHeight,
)

val RelayTypography: Typography = Typography(
    displayLarge = style(76, 80, 450, -0.03),
    displayMedium = style(58, 62, 450, -0.025),
    displaySmall = style(44, 50, 500, -0.02),
    headlineLarge = style(36, 42, 500, -0.015),
    headlineMedium = style(30, 36, 500, -0.01),
    headlineSmall = style(24, 30, 520, -0.005),
    titleLarge = style(22, 28, 520, 0.0),
    titleMedium = style(17, 24, 540, 0.005),
    titleSmall = style(14, 20, 600, 0.008),
    bodyLarge = style(16, 24, 400, 0.01),
    bodyMedium = style(14, 20, 400, 0.015),
    bodySmall = style(12, 16, 420, 0.025),
    labelLarge = style(15, 20, 600, 0.01),
    labelMedium = style(13, 16, 600, 0.03),
    labelSmall = style(11, 16, 640, 0.045),
    displayLargeEmphasized = style(76, 80, 700, -0.035),
    displayMediumEmphasized = style(58, 62, 680, -0.03),
    displaySmallEmphasized = style(44, 50, 680, -0.025),
    headlineLargeEmphasized = style(36, 42, 680, -0.02),
    headlineMediumEmphasized = style(30, 36, 660, -0.015),
    headlineSmallEmphasized = style(24, 30, 650, -0.01),
    titleLargeEmphasized = style(22, 28, 650, 0.0),
    titleMediumEmphasized = style(17, 24, 680, 0.005),
    titleSmallEmphasized = style(14, 20, 700, 0.008),
    bodyLargeEmphasized = style(16, 24, 560, 0.01),
    bodyMediumEmphasized = style(14, 20, 560, 0.015),
    bodySmallEmphasized = style(12, 16, 580, 0.025),
    labelLargeEmphasized = style(15, 20, 720, 0.01),
    labelMediumEmphasized = style(13, 16, 720, 0.03),
    labelSmallEmphasized = style(11, 16, 720, 0.045),
)

/** Styles outside the Material scale. */
object RelayTextStyles {
    /** Oversized transfer percentage. Tabular figures so the number does not jitter as it counts. */
    val Percent = style(96, 96, 380, -0.045).copy(fontFeatureSettings = "tnum")

    /** Figures that update live (speed, remaining time). */
    val LiveFigure = style(15, 20, 560, 0.0).copy(fontFeatureSettings = "tnum")

    /** The "Relay" wordmark. */
    val Wordmark = style(28, 32, 760, -0.04)
}
