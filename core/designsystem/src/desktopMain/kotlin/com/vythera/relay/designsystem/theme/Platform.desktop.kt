package com.vythera.relay.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktops have no wallpaper-derived Material palette; Relay's own palette is used. */
@Composable
internal actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

/**
 * Windows exposes "Animation effects" and macOS "Reduce motion", but neither is readable
 * from the JVM without native code; until the desktop app bridges them, motion is full.
 */
@Composable
internal actual fun rememberSystemRelayMotion(): RelayMotion = remember { RelayMotion(reduced = false) }
