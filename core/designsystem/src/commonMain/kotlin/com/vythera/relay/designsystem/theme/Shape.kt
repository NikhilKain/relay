package com.vythera.relay.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle

/**
 * A rounded rectangle with continuous-curvature ("squircle") corners.
 *
 * Plain rounded corners join the straight edge with a visible kink in curvature; at
 * Relay's large radii that reads as "a card from any app". Smoothing spreads the curve
 * along the edge, which is what makes the large containers feel soft and deliberate.
 *
 * Corners are given individually so the family can include asymmetric shapes.
 */
@Immutable
class SmoothRoundedShape(
    private val topStart: Dp,
    private val topEnd: Dp,
    private val bottomEnd: Dp,
    private val bottomStart: Dp,
    private val smoothing: Float = 0.6f,
) : Shape {
    constructor(all: Dp, smoothing: Float = 0.6f) : this(all, all, all, all, smoothing)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(size.toRect())
        val maxRadius = minOf(size.width, size.height) / 2f
        fun rounding(corner: Dp) = with(density) {
            CornerRounding(corner.toPx().coerceAtMost(maxRadius), smoothing)
        }
        val (tl, tr) = if (layoutDirection == LayoutDirection.Ltr) topStart to topEnd else topEnd to topStart
        val (bl, br) = if (layoutDirection == LayoutDirection.Ltr) bottomStart to bottomEnd else bottomEnd to bottomStart
        // Vertex order of RoundedPolygon.rectangle: bottom-right, bottom-left, top-left, top-right.
        val polygon = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            perVertexRounding = listOf(rounding(br), rounding(bl), rounding(tl), rounding(tr)),
            centerX = size.width / 2f,
            centerY = size.height / 2f,
        )
        return Outline.Generic(polygon.toComposePath())
    }

    override fun equals(other: Any?): Boolean =
        other is SmoothRoundedShape && other.topStart == topStart && other.topEnd == topEnd &&
            other.bottomEnd == bottomEnd && other.bottomStart == bottomStart && other.smoothing == smoothing

    override fun hashCode(): Int = listOf(topStart, topEnd, bottomEnd, bottomStart, smoothing).hashCode()
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

internal fun RoundedPolygon.toComposePath(path: Path = Path()): Path {
    path.rewind()
    val cubics = cubics
    if (cubics.isEmpty()) return path
    path.moveTo(cubics.first().anchor0X, cubics.first().anchor0Y)
    for (cubic in cubics) {
        path.cubicTo(cubic.control0X, cubic.control0Y, cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y)
    }
    path.close()
    return path
}

/**
 * Relay's shape family. Importance maps to size of radius and to asymmetry:
 *
 * | Token         | Used for                                  | Character                       |
 * |---------------|-------------------------------------------|---------------------------------|
 * | Hero          | The most relevant nearby device            | 44dp smooth, one tucked corner  |
 * | Device        | Secondary device cards                     | 32dp smooth                     |
 * | DevicePressed | Device card while pressed / selected       | 44dp: the card "softens"        |
 * | Action        | Large action tiles                         | 32dp smooth                     |
 * | Pill          | Buttons, chips, compact transfer           | fully rounded                   |
 * | Transfer      | Expanded transfer container                | 40dp smooth                     |
 * | Small         | Activity rows, list surfaces               | 20dp                            |
 * | ExtraSmall    | Inline badges                              | 10dp                            |
 * | Dialog        | Dialogs                                    | 36dp smooth                     |
 * | BottomSheet   | Sheets                                     | 36dp top corners                |
 *
 * The tucked corner on [Hero] points at the content below it, so the eye travels from
 * the hero device into its action.
 */
object RelayShapes {
    val Hero: Shape = SmoothRoundedShape(topStart = 44.dp, topEnd = 44.dp, bottomEnd = 44.dp, bottomStart = 18.dp)
    val HeroPressed: Shape = SmoothRoundedShape(topStart = 36.dp, topEnd = 56.dp, bottomEnd = 56.dp, bottomStart = 28.dp)
    val Device: Shape = SmoothRoundedShape(32.dp)
    val DevicePressed: Shape = SmoothRoundedShape(44.dp)
    val Action: Shape = SmoothRoundedShape(32.dp)
    val ActionCompact: Shape = SmoothRoundedShape(24.dp)
    val Transfer: Shape = SmoothRoundedShape(40.dp)
    val Pill: Shape = RoundedCornerShape(percent = 50)
    val Small: Shape = SmoothRoundedShape(20.dp)
    val ExtraSmall: Shape = RoundedCornerShape(10.dp)
    val Dialog: Shape = SmoothRoundedShape(36.dp)
    val BottomSheet: Shape = SmoothRoundedShape(topStart = 36.dp, topEnd = 36.dp, bottomEnd = 0.dp, bottomStart = 0.dp)

    /** Corner radii behind the animatable variants, for components that morph between them. */
    object Radius {
        val Device = 32.dp
        val DevicePressed = 44.dp
        val Hero = 44.dp
        val HeroTucked = 18.dp
    }

    /**
     * Organic container per kind of device, so a device is recognisable by silhouette
     * before its name is read. Chosen to stay distinct at 24dp.
     */
    fun avatarFor(kind: RelayDeviceKind): RoundedPolygon = when (kind) {
        RelayDeviceKind.Phone -> MaterialShapes.Cookie6Sided
        RelayDeviceKind.Tablet -> MaterialShapes.Square
        RelayDeviceKind.Laptop -> MaterialShapes.Slanted
        RelayDeviceKind.Desktop -> MaterialShapes.Clover4Leaf
        RelayDeviceKind.Browser -> MaterialShapes.Sunny
        RelayDeviceKind.Unknown -> MaterialShapes.Pentagon
    }

    /**
     * The eight shapes of the pairing code. Picked for maximum silhouette difference so
     * two people comparing screens across a desk can tell them apart instantly.
     */
    val PairingPalette: List<RoundedPolygon>
        get() = listOf(
            MaterialShapes.Circle,
            MaterialShapes.Triangle,
            MaterialShapes.Diamond,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Heart,
            MaterialShapes.Flower,
            MaterialShapes.Arch,
        )
}

enum class RelayDeviceKind { Phone, Tablet, Laptop, Desktop, Browser, Unknown }

/** Material's own shape slots, tuned so stock components (menus, text fields) sit comfortably next to Relay's. */
val RelayMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
    largeIncreased = RoundedCornerShape(32.dp),
    extraLargeIncreased = RoundedCornerShape(40.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)
