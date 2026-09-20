package com.vythera.relay.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Where colours come from. */
enum class RelayColorSource {
    /** The wallpaper, where the platform offers it (Android 12+). Falls back to [Ember] elsewhere. */
    Dynamic,

    /** Relay's own palette. */
    Ember,
}

/**
 * Root of every Relay screen on every platform. Provides the colour scheme, the
 * expressive motion scheme (or its reduced variant), Relay's type scale and shape slots.
 *
 * @param motion override for previews and tests; defaults to the system animation setting.
 */
@Composable
fun RelayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorSource: RelayColorSource = RelayColorSource.Dynamic,
    motion: RelayMotion? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = (if (colorSource == RelayColorSource.Dynamic) platformDynamicColorScheme(darkTheme) else null)
        ?: if (darkTheme) RelayEmberDark else RelayEmberLight
    val relayMotion = motion ?: rememberSystemRelayMotion()

    CompositionLocalProvider(LocalRelayMotion provides relayMotion) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = relayMotion.scheme,
            shapes = RelayMaterialShapes,
            typography = RelayTypography,
            content = content,
        )
    }
}

/** The wallpaper-derived scheme where the platform has one, otherwise null. */
@Composable
internal expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

/** Motion honouring the system's "remove animations" setting where the platform exposes one. */
@Composable
internal expect fun rememberSystemRelayMotion(): RelayMotion
