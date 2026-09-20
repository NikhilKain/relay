package com.vythera.relay.designsystem.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Wallpapers
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.theme.RelayColorSource
import com.vythera.relay.designsystem.theme.RelayMotion
import com.vythera.relay.designsystem.theme.RelayTheme

/** Relay palette in light and dark. */
@Preview(name = "Light", group = "Theme", showBackground = true, backgroundColor = 0xFFFFF8F6)
@Preview(name = "Dark", group = "Theme", uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL, showBackground = true, backgroundColor = 0xFF1A120E)
annotation class RelayThemePreviews

/** Wallpaper-derived colour. Pair with `PreviewTheme(dynamic = true)`. */
@Preview(name = "Dynamic · green wallpaper", group = "Dynamic", wallpaper = Wallpapers.GREEN_DOMINATED_EXAMPLE, apiLevel = 35, showBackground = true)
@Preview(name = "Dynamic · blue wallpaper, dark", group = "Dynamic", wallpaper = Wallpapers.BLUE_DOMINATED_EXAMPLE, apiLevel = 35, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL, showBackground = true)
annotation class RelayDynamicPreviews

/** Window sizes Relay designs for. */
@Preview(name = "Compact phone", group = "Size", device = "spec:width=360dp,height=740dp,dpi=420", showSystemUi = true)
@Preview(name = "Large phone", group = "Size", device = "spec:width=412dp,height=915dp,dpi=480", showSystemUi = true)
@Preview(name = "Foldable", group = "Size", device = "spec:width=673dp,height=841dp,dpi=420", showSystemUi = true)
@Preview(name = "Tablet", group = "Size", device = "spec:width=1280dp,height=800dp,dpi=240", showSystemUi = true)
annotation class RelaySizePreviews

@Composable
fun PreviewTheme(
    dynamic: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    RelayTheme(
        colorSource = if (dynamic) RelayColorSource.Dynamic else RelayColorSource.Ember,
        motion = RelayMotion(reduced = false),
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface).padding(padding)) { content() }
    }
}
