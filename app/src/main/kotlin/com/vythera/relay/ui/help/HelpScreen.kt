package com.vythera.relay.ui.help

import android.content.Intent
import android.net.Uri

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.vythera.relay.R
import com.vythera.relay.designsystem.component.RelayHandoffIllustration
import com.vythera.relay.designsystem.component.RelaySectionHeader
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import com.vythera.relay.designsystem.theme.SmoothRoundedShape

/**
 * "Connect your devices." Three steps anyone can follow, what to expect afterwards, and
 * answers for when a device does not show up. No networking vocabulary in the steps;
 * the troubleshooting answers name things (Wi-Fi isolation, firewall) only where the
 * user has to act on them.
 */
@Composable
fun HelpScreen(
    onBack: (() -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalMargin: Dp = 20.dp,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalMargin)
            .padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 32.dp),
    ) {
        if (onBack != null) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.height(RelaySpacing.l))
        }
        Text(stringResource(R.string.help_title), style = MaterialTheme.typography.displayMediumEmphasized, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.help_subtitle), style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(RelaySpacing.xl))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RelayHandoffIllustration(size = 240.dp) }
        Spacer(Modifier.height(RelaySpacing.xl))

        val context = LocalContext.current
        fun open(url: String) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        RelaySectionHeader(stringResource(R.string.help_get_title))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Download(
                Icons.Rounded.PhoneAndroid,
                stringResource(R.string.help_get_android),
                stringResource(R.string.help_get_android_where),
                SmoothRoundedShape(28.dp, 28.dp, 8.dp, 8.dp),
            ) { open(RelayLinks.PLAY_STORE) }
            Download(
                Icons.Rounded.DesktopWindows,
                stringResource(R.string.help_get_desktop),
                stringResource(R.string.help_get_desktop_where),
                SmoothRoundedShape(8.dp),
            ) { open(RelayLinks.DESKTOP_RELEASES) }
            Download(
                Icons.Rounded.PhoneIphone,
                stringResource(R.string.help_get_apple),
                stringResource(R.string.help_get_apple_where),
                SmoothRoundedShape(8.dp, 8.dp, 28.dp, 28.dp),
                onClick = null,
            )
        }

        RelaySectionHeader(stringResource(R.string.help_guide_title), Modifier.padding(top = RelaySpacing.section))
        Text(stringResource(R.string.help_guide_pick), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(RelaySpacing.m))
        var pairing by rememberSaveable { mutableStateOf(Pairing.PhoneAndPc) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            Pairing.entries.forEachIndexed { index, option ->
                val selected = option == pairing
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { pairing = option },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        Pairing.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(if (selected) 1.3f else 1f).height(56.dp).semantics { role = Role.RadioButton },
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

        RelaySectionHeader(stringResource(R.string.help_after_title), Modifier.padding(top = RelaySpacing.section))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Tip(Icons.Rounded.Hub, stringResource(R.string.help_tip_ecosystem_title), stringResource(R.string.help_tip_ecosystem_body), SmoothRoundedShape(28.dp, 28.dp, 8.dp, 8.dp))
            Tip(Icons.Rounded.ContentPaste, stringResource(R.string.help_tip_clipboard_title), stringResource(R.string.help_tip_clipboard_body), SmoothRoundedShape(8.dp))
            Tip(Icons.Rounded.Tune, stringResource(R.string.help_tip_tile_title), stringResource(R.string.help_tip_tile_body), SmoothRoundedShape(8.dp))
            Tip(Icons.Rounded.Share, stringResource(R.string.help_tip_share_title), stringResource(R.string.help_tip_share_body), SmoothRoundedShape(8.dp, 8.dp, 28.dp, 28.dp))
        }

        RelaySectionHeader(stringResource(R.string.help_trouble_title), Modifier.padding(top = RelaySpacing.section))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Question(stringResource(R.string.help_q_not_showing), stringResource(R.string.help_a_not_showing))
            Question(stringResource(R.string.help_q_windows), stringResource(R.string.help_a_windows))
            Question(stringResource(R.string.help_q_background), stringResource(R.string.help_a_background))
            Question(stringResource(R.string.help_q_iphone), stringResource(R.string.help_a_iphone))
            Question(stringResource(R.string.help_q_private), stringResource(R.string.help_a_private))
        }
    }
}

/** Where each app comes from. Kept in one place so a new home only changes here. */
object RelayLinks {
    const val PLAY_STORE = "https://play.google.com/store/apps/details?id=com.vythera.relay"
    const val DESKTOP_RELEASES = "https://github.com/NikhilKain/relay/releases"
}

/** The three ways two devices meet. Each has its own four steps. */
private enum class Pairing(val label: Int, val steps: List<Pair<Int, Int>>) {
    PhoneAndPc(
        R.string.help_guide_phone_pc,
        listOf(
            R.string.help_pc_1_title to R.string.help_pc_1_body,
            R.string.help_pc_2_title to R.string.help_pc_2_body,
            R.string.help_pc_3_title to R.string.help_pc_3_body,
            R.string.help_pc_4_title to R.string.help_pc_4_body,
        ),
    ),
    TwoPhones(
        R.string.help_guide_two_phones,
        listOf(
            R.string.help_phones_1_title to R.string.help_phones_1_body,
            R.string.help_phones_2_title to R.string.help_phones_2_body,
            R.string.help_phones_3_title to R.string.help_phones_3_body,
            R.string.help_phones_4_title to R.string.help_phones_4_body,
        ),
    ),
    TwoPcs(
        R.string.help_guide_two_pcs,
        listOf(
            R.string.help_pcs_1_title to R.string.help_pcs_1_body,
            R.string.help_pcs_2_title to R.string.help_pcs_2_body,
            R.string.help_pcs_3_title to R.string.help_pcs_3_body,
            R.string.help_pcs_4_title to R.string.help_pcs_4_body,
        ),
    ),
}

/** A place to get Relay. Tapping opens the store or the download page. */
@Composable
private fun Download(
    icon: ImageVector,
    title: String,
    where: String,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val body = @Composable {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (onClick != null) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    where,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (onClick != null) colors.primary else colors.onSurfaceVariant,
                )
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) { body() }
    } else {
        Surface(shape = shape, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) { body() }
    }
}

/** A step: a large numeral set inside its own shape, so the three steps read at a glance. */
@Composable
private fun Step(
    number: Int,
    title: String,
    body: String,
    shape: RoundedPolygon,
    container: Color,
    content: Color,
    extra: @Composable () -> Unit = {},
) {
    Surface(shape = RelayShapes.Action, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp)) {
            Box(Modifier.size(64.dp).background(content, shape.toShape()), contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.headlineMediumEmphasized, color = container)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyLarge)
                extra()
            }
        }
    }
}

@Composable
private fun PlatformLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Tip(icon: ImageVector, title: String, body: String, shape: androidx.compose.ui.graphics.Shape) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A question that opens in place to show its answer. */
@Composable
private fun Question(question: String, answer: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        onClick = { open = !open },
        shape = RelayShapes.Small,
        color = if (open) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(if (open) 180f else 0f))
            }
            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(answer, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}
