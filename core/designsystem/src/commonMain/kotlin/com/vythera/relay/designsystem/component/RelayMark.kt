package com.vythera.relay.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.theme.RelayTextStyles

/**
 * The Relay symbol: two devices, a soft square and a circle, overlapping.
 *
 * The overlap is the idea: the moment something is in both places at once. It is drawn
 * in its own tone so the handoff reads even at 24dp, and the composition is diagonal so
 * it implies movement without an arrow. Both shapes come from the same smooth-corner
 * family as the rest of the UI.
 */
@Composable
fun RelayMark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    origin: Color = MaterialTheme.colorScheme.primary,
    destination: Color = MaterialTheme.colorScheme.tertiary,
    overlap: Color = MaterialTheme.colorScheme.primaryContainer,
    contentDescription: String? = "Relay",
) {
    Canvas(
        modifier
            .size(size)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val unit = this.size.minDimension
        val square = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0.06f * unit, top = 0.34f * unit, right = 0.66f * unit, bottom = 0.94f * unit,
                    cornerRadius = CornerRadius(0.2f * unit),
                ),
            )
        }
        val circle = Path().apply {
            val radius = 0.3f * unit
            val center = Offset(0.64f * unit, 0.36f * unit)
            addOval(androidx.compose.ui.geometry.Rect(center, radius))
        }
        val shared = Path().apply { op(square, circle, PathOperation.Intersect) }
        drawPath(square, origin)
        drawPath(circle, destination)
        drawPath(shared, overlap)
    }
}

/** Mark plus wordmark, for onboarding and about screens. */
@Composable
fun RelayLockup(modifier: Modifier = Modifier, markSize: Dp = 36.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RelayMark(size = markSize, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text("Relay", style = RelayTextStyles.Wordmark, color = MaterialTheme.colorScheme.onSurface)
    }
}
