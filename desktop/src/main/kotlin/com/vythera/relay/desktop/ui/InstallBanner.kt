package com.vythera.relay.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.desktop.DesktopPaths
import com.vythera.relay.desktop.resources.*
import com.vythera.relay.protocol.Platform
import org.jetbrains.compose.resources.stringResource

/**
 * Shown on every page while Relay runs from a folder that will be cleaned away: opened
 * from inside the zip, or straight from the mounted disk image. It cannot start with the
 * computer from there, and it will be gone after the next clean-up, so the one useful
 * thing to offer is the installer.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstallBanner(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val mac = DesktopPaths.platform == Platform.MACOS
    Surface(color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer, shape = RelayShapes.Action, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.install_banner_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (mac) Res.string.install_banner_body_mac else Res.string.install_banner_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(20.dp))
            Button(
                onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(if (mac) RelayLinks.MAC_INSTALL_GUIDE else RelayLinks.DESKTOP_DOWNLOAD)) } },
                shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall),
            ) { Text(stringResource(if (mac) Res.string.install_banner_action_mac else Res.string.install_banner_action)) }
        }
    }
}
