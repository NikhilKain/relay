package com.vythera.relay.desktop.ui

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.vythera.relay.designsystem.component.RelayEmptyState
import com.vythera.relay.designsystem.component.RelayHandoffIllustration
import com.vythera.relay.designsystem.component.RelayMark
import com.vythera.relay.designsystem.component.RelaySectionHeader
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import com.vythera.relay.desktop.Autostart
import com.vythera.relay.desktop.DesktopClipboardMode
import com.vythera.relay.desktop.DesktopRelay
import com.vythera.relay.desktop.DesktopSettings
import com.vythera.relay.desktop.HistoryEntry
import com.vythera.relay.desktop.resources.*
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 880.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 40.dp),
            content = content,
        )
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.displayMediumEmphasized, modifier = Modifier.semantics { heading() })
    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(RelaySpacing.xl))
}

@Composable
fun InboxPage(relay: DesktopRelay, history: List<HistoryEntry>) {
    val received = history.filter { it.incoming && it.outcome in setOf("completed", "opened") }
    PageColumn {
        PageTitle(stringResource(Res.string.inbox_title), stringResource(Res.string.inbox_subtitle))
        if (received.isEmpty()) {
            RelayEmptyState(stringResource(Res.string.inbox_empty_title), stringResource(Res.string.inbox_empty_body))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            received.forEach { entry -> InboxRow(relay, entry) }
        }
    }
}

@Composable
private fun InboxRow(relay: DesktopRelay, entry: HistoryEntry) {
    val colors = MaterialTheme.colorScheme
    val file = entry.detail?.takeIf { entry.category !in setOf("text", "link") }?.let(::File)
    // Received files can be dragged out: into a folder, an email, or the drop shelf.
    val draggable = file?.takeIf { it.isFile }?.let { dragged ->
        Modifier.dragAndDropSource { _ ->
            DragAndDropTransferData(
                transferable = DragAndDropTransferable(FileListSelection(listOf(dragged))),
                supportedActions = listOf(DragAndDropTransferAction.Copy),
            )
        }
    } ?: Modifier
    Surface(shape = RelayShapes.Small, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth().then(draggable)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(colors.secondaryContainer, contentKind(entry.category).silhouette.toShape()), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(contentKind(entry.category).icon, null, tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entry.peerName} · ${relativeTime(entry.timeMillis)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            val pill = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    file != null && file.exists() -> {
                        Button(onClick = { relay.open(file) }, shapes = pill) { Text(stringResource(Res.string.inbox_open)) }
                        FilledTonalButton(onClick = { relay.reveal(file) }, shapes = pill) { Text(stringResource(Res.string.inbox_show)) }
                    }
                    entry.category == "link" && entry.detail != null -> {
                        Button(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(entry.detail)) } }, shapes = pill) { Text(stringResource(Res.string.inbox_open)) }
                        FilledTonalButton(onClick = { relay.copy(entry.detail) }, shapes = pill) { Text(stringResource(Res.string.inbox_copy)) }
                    }
                    entry.detail != null -> FilledTonalButton(onClick = { relay.copy(entry.detail) }, shapes = pill) { Text(stringResource(Res.string.inbox_copy)) }
                }
                androidx.compose.material3.TextButton(onClick = { relay.deleteHistory(entry.id) }) { Text(stringResource(Res.string.inbox_remove)) }
            }
        }
    }
}

@Composable
fun SettingsPage(relay: DesktopRelay, settings: DesktopSettings, onChooseFolder: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var name by remember(relay.deviceName) { mutableStateOf(relay.deviceName) }
    PageColumn {
        PageTitle(stringResource(Res.string.settings_title))

        Surface(shape = RelayShapes.Hero, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp)) {
                Text(stringResource(Res.string.settings_device), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = name,
                        onValueChange = { if (it.length <= 40) name = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineMediumEmphasized,
                        shape = RelayShapes.Small,
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { relay.rename(name) }, enabled = name.isNotBlank() && name != relay.deviceName, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                        Text(stringResource(Res.string.settings_save))
                    }
                }
            }
        }

        RelaySectionHeader(stringResource(Res.string.settings_save_to), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            Text(relay.inboxFolder().absolutePath, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onChooseFolder, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) { Text(stringResource(Res.string.settings_choose_folder)) }
                if (settings.saveFolder.isNotBlank()) {
                    FilledTonalButton(onClick = { relay.setSaveFolder(null) }, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) { Text(stringResource(Res.string.settings_use_default)) }
                }
            }
        }

        RelaySectionHeader(stringResource(Res.string.settings_ecosystem), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            SwitchRow(stringResource(Res.string.settings_ecosystem_title), stringResource(Res.string.settings_ecosystem_body), settings.ecosystem, relay::setEcosystem)
        }
        Spacer(Modifier.height(8.dp))
        Group {
            SwitchRow(stringResource(Res.string.settings_shelf_title), stringResource(Res.string.settings_shelf_body), settings.dropShelf, relay::setDropShelf)
        }

        RelaySectionHeader(stringResource(Res.string.settings_clipboard), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.settings_clipboard_body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = settings.clipboard == DesktopClipboardMode.AUTOMATIC,
                    onCheckedChange = { relay.setClipboard(if (it) DesktopClipboardMode.AUTOMATIC else DesktopClipboardMode.OFF) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.settings_start), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (Autostart.isAvailable) Res.string.settings_start_body else Res.string.settings_start_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = settings.startWithComputer, enabled = Autostart.isAvailable, onCheckedChange = relay::setStartWithComputer)
            }
        }

        RelaySectionHeader(stringResource(Res.string.settings_privacy), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            SwitchRow(stringResource(Res.string.settings_analytics_title), stringResource(Res.string.settings_analytics_body), settings.analytics, relay::setAnalytics)
        }

        RelaySectionHeader(stringResource(Res.string.settings_about), Modifier.padding(top = RelaySpacing.xxl))
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RelayMark(size = 40.dp, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(Res.string.settings_about_body, DesktopRelay.VERSION), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RelayShapes.Small, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun HelpPage() {
    val colors = MaterialTheme.colorScheme
    PageColumn {
        PageTitle(stringResource(Res.string.help_title), stringResource(Res.string.help_subtitle))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RelayHandoffIllustration(size = 240.dp) }
        Spacer(Modifier.height(RelaySpacing.xl))
        RelaySectionHeader(stringResource(Res.string.help_get_title))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Download(stringResource(Res.string.help_get_android), stringResource(Res.string.help_get_android_where), RelayLinks.PLAY_STORE)
            Download(stringResource(Res.string.help_get_desktop), stringResource(Res.string.help_get_desktop_where), RelayLinks.DESKTOP_RELEASES)
            Download(stringResource(Res.string.help_get_apple), stringResource(Res.string.help_get_apple_where), url = null)
        }

        RelaySectionHeader(stringResource(Res.string.help_guide_title), Modifier.padding(top = RelaySpacing.section))
        Text(stringResource(Res.string.help_guide_pick), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(RelaySpacing.m))
        var pairing by remember { mutableStateOf(HelpPairing.PhoneAndPc) }
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            HelpPairing.entries.forEachIndexed { index, option ->
                val selected = option == pairing
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { pairing = option },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        HelpPairing.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(RelaySpacing.l))
        val shapes = listOf(MaterialShapes.Cookie6Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Pill)
        val palette = listOf(
            colors.primaryContainer to colors.onPrimaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer,
            colors.tertiaryContainer to colors.onTertiaryContainer,
            colors.surfaceContainerHigh to colors.onSurface,
        )
        pairing.steps.forEachIndexed { index, (title, body) ->
            val (container, content) = palette[index]
            Step(index + 1, stringResource(title), stringResource(body), shapes[index], container, content)
            if (index != pairing.steps.lastIndex) Spacer(Modifier.height(10.dp))
        }
        RelaySectionHeader(stringResource(Res.string.help_firewall_title), Modifier.padding(top = RelaySpacing.section))
        Text(stringResource(Res.string.help_firewall_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        RelaySectionHeader(stringResource(Res.string.help_clipboard_title), Modifier.padding(top = RelaySpacing.xl))
        Text(stringResource(Res.string.help_clipboard_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        RelaySectionHeader(stringResource(Res.string.help_ecosystem_title), Modifier.padding(top = RelaySpacing.xl))
        Text(stringResource(Res.string.help_ecosystem_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Step(number: Int, title: String, body: String, shape: RoundedPolygon, container: Color, content: Color) {
    Surface(shape = RelayShapes.Action, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).background(content, shape.toShape()), contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.headlineMediumEmphasized, color = container)
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                Text(body, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Where each app comes from. Kept in one place so a new home only changes here. */
object RelayLinks {
    const val PLAY_STORE = "https://play.google.com/store/apps/details?id=com.vythera.relay"
    const val DESKTOP_RELEASES = "https://github.com/NikhilKain/relay/releases"
}

/** The three ways two devices meet. Each has its own four steps. */
private enum class HelpPairing(val label: org.jetbrains.compose.resources.StringResource, val steps: List<Pair<org.jetbrains.compose.resources.StringResource, org.jetbrains.compose.resources.StringResource>>) {
    PhoneAndPc(
        Res.string.help_guide_phone_pc,
        listOf(
            Res.string.help_pc_1_title to Res.string.help_pc_1_body,
            Res.string.help_pc_2_title to Res.string.help_pc_2_body,
            Res.string.help_pc_3_title to Res.string.help_pc_3_body,
            Res.string.help_pc_4_title to Res.string.help_pc_4_body,
        ),
    ),
    TwoPhones(
        Res.string.help_guide_two_phones,
        listOf(
            Res.string.help_phones_1_title to Res.string.help_phones_1_body,
            Res.string.help_phones_2_title to Res.string.help_phones_2_body,
            Res.string.help_phones_3_title to Res.string.help_phones_3_body,
            Res.string.help_phones_4_title to Res.string.help_phones_4_body,
        ),
    ),
    TwoPcs(
        Res.string.help_guide_two_pcs,
        listOf(
            Res.string.help_pcs_1_title to Res.string.help_pcs_1_body,
            Res.string.help_pcs_2_title to Res.string.help_pcs_2_body,
            Res.string.help_pcs_3_title to Res.string.help_pcs_3_body,
            Res.string.help_pcs_4_title to Res.string.help_pcs_4_body,
        ),
    ),
}

/** A place to get Relay. Clicking opens the store or the download page in a browser. */
@Composable
private fun Download(title: String, where: String, url: String?) {
    val colors = MaterialTheme.colorScheme
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(where, style = MaterialTheme.typography.bodyMedium, color = if (url != null) colors.primary else colors.onSurfaceVariant)
            }
            if (url != null) Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    if (url != null) {
        Surface(
            onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } },
            shape = RelayShapes.Small,
            color = colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) { body() }
    } else {
        Surface(shape = RelayShapes.Small, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) { body() }
    }
}
