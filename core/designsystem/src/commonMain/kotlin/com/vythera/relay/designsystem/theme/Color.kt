package com.vythera.relay.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Relay "Ember" palette, used when the device has no dynamic color (Android 11 and
 * below) or when the user picks Relay colors in settings.
 *
 * Generated with material-color-utilities (Vibrant variant): primary from an apricot
 * seed (#F2762E), tertiary from a deep teal (#00838F) so progress and success states
 * read as a distinct "arrival" colour against the warm primary.
 *
 * The dark scheme is not the light scheme inverted: surfaces are warm near-blacks
 * (#1A120E rather than #000) and containers step up in tone, so cards separate from
 * the background through tone rather than borders or shadows.
 */

val RelayEmberLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF9F4200),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCB),
    onPrimaryContainer = Color(0xFF793000),
    inversePrimary = Color(0xFFFFB692),
    secondary = Color(0xFF7C563B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC5),
    onSecondaryContainer = Color(0xFF613F26),
    tertiary = Color(0xFF006972),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF91F1FF),
    onTertiaryContainer = Color(0xFF004F56),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF221A16),
    surfaceVariant = Color(0xFFF8DDD1),
    onSurfaceVariant = Color(0xFF55433A),
    surfaceTint = Color(0xFF9F4200),
    inverseSurface = Color(0xFF382E2A),
    inverseOnSurface = Color(0xFFFFEDE6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF887369),
    outlineVariant = Color(0xFFDBC1B6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainer = Color(0xFFFCEAE3),
    surfaceContainerHigh = Color(0xFFF6E5DE),
    surfaceContainerHighest = Color(0xFFF0DFD8),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8D7D0),
    primaryFixed = Color(0xFFFFDBCB),
    primaryFixedDim = Color(0xFFFFB692),
    onPrimaryFixed = Color(0xFF341100),
    onPrimaryFixedVariant = Color(0xFF793000),
    secondaryFixed = Color(0xFFFFDCC5),
    secondaryFixedDim = Color(0xFFEEBD9B),
    onSecondaryFixed = Color(0xFF2E1502),
    onSecondaryFixedVariant = Color(0xFF613F26),
    tertiaryFixed = Color(0xFF91F1FF),
    tertiaryFixedDim = Color(0xFF73D5E2),
    onTertiaryFixed = Color(0xFF001F23),
    onTertiaryFixedVariant = Color(0xFF004F56),
)

val RelayEmberDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB692),
    onPrimary = Color(0xFF562000),
    primaryContainer = Color(0xFF793000),
    onPrimaryContainer = Color(0xFFFFDBCB),
    inversePrimary = Color(0xFF9F4200),
    secondary = Color(0xFFEEBD9B),
    onSecondary = Color(0xFF472911),
    secondaryContainer = Color(0xFF613F26),
    onSecondaryContainer = Color(0xFFFFDCC5),
    tertiary = Color(0xFF73D5E2),
    onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF004F56),
    onTertiaryContainer = Color(0xFF91F1FF),
    background = Color(0xFF1A120E),
    onBackground = Color(0xFFF0DFD8),
    surface = Color(0xFF1A120E),
    onSurface = Color(0xFFF0DFD8),
    surfaceVariant = Color(0xFF55433A),
    onSurfaceVariant = Color(0xFFDBC1B6),
    surfaceTint = Color(0xFFFFB692),
    inverseSurface = Color(0xFFF0DFD8),
    inverseOnSurface = Color(0xFF382E2A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA38C82),
    outlineVariant = Color(0xFF55433A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF423732),
    surfaceContainer = Color(0xFF271E19),
    surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D332E),
    surfaceContainerLow = Color(0xFF221A16),
    surfaceContainerLowest = Color(0xFF140C09),
    surfaceDim = Color(0xFF1A120E),
    primaryFixed = Color(0xFFFFDBCB),
    primaryFixedDim = Color(0xFFFFB692),
    onPrimaryFixed = Color(0xFF341100),
    onPrimaryFixedVariant = Color(0xFF793000),
    secondaryFixed = Color(0xFFFFDCC5),
    secondaryFixedDim = Color(0xFFEEBD9B),
    onSecondaryFixed = Color(0xFF2E1502),
    onSecondaryFixedVariant = Color(0xFF613F26),
    tertiaryFixed = Color(0xFF91F1FF),
    tertiaryFixedDim = Color(0xFF73D5E2),
    onTertiaryFixed = Color(0xFF001F23),
    onTertiaryFixedVariant = Color(0xFF004F56),
)
