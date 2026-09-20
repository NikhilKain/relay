package com.vythera.relay.designsystem.component

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** View haptics, so the system "touch feedback" setting is respected. */
@Composable
actual fun rememberRelayHaptics(): RelayHaptics {
    val view = LocalView.current
    return remember(view) {
        RelayHaptics { kind ->
            val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val constant = when (kind) {
                RelayHapticKind.Tick -> HapticFeedbackConstants.CLOCK_TICK
                RelayHapticKind.GestureStart -> if (modern) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY
                RelayHapticKind.Confirm -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
                RelayHapticKind.Reject -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(constant)
        }
    }
}
