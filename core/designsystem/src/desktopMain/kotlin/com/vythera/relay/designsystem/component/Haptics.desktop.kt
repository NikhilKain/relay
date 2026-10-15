package com.vythera.relay.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktops have no haptic motor; feedback there is visual only. */
@Composable
actual fun rememberRelayHaptics(): RelayHaptics = remember { RelayHaptics { } }
