package com.vythera.relay.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vythera.relay.R
import com.vythera.relay.designsystem.component.RelayHandoffIllustration
import com.vythera.relay.designsystem.component.RelayLockup
import com.vythera.relay.designsystem.component.RelayMark
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelaySendButtonSize
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayShapes
import kotlinx.coroutines.launch

/** Three screens, no tutorial: what Relay is, why it is safe, and what to call this device. */
@Composable
fun OnboardingScreen(defaultName: String, onFinish: (String) -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(defaultName) }
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        RelayLockup(markSize = 28.dp)
        HorizontalPager(pager, modifier = Modifier.weight(1f), userScrollEnabled = true) { page ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                when (page) {
                    0 -> Page(
                        visual = { RelayHandoffIllustration(size = 300.dp) },
                        title = stringResource(R.string.onboarding_1_title),
                        body = stringResource(R.string.onboarding_1_body),
                    )
                    1 -> Page(
                        visual = { PrivacyVisual() },
                        title = stringResource(R.string.onboarding_2_title),
                        body = stringResource(R.string.onboarding_2_body),
                    )
                    else -> Page(
                        visual = {
                            TextField(
                                value = name,
                                onValueChange = { if (it.length <= 40) name = it },
                                label = { Text(stringResource(R.string.onboarding_name_label)) },
                                textStyle = MaterialTheme.typography.headlineMediumEmphasized,
                                singleLine = true,
                                shape = RelayShapes.Small,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        title = stringResource(R.string.onboarding_3_title),
                        body = stringResource(R.string.onboarding_3_body),
                        visualFirst = false,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageIndicator(pager.currentPage, 3)
            Spacer(Modifier.weight(1f))
            val last = pager.currentPage == 2
            RelaySendButton(
                onClick = {
                    if (last) {
                        if (name.isNotBlank()) onFinish(name.trim())
                    } else {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
                text = stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next),
                icon = if (last) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                size = RelaySendButtonSize.Large,
                enabled = !last || name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun Page(visual: @Composable () -> Unit, title: String, body: String, visualFirst: Boolean = true) {
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
        if (visualFirst) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { visual() }
            Spacer(Modifier.height(48.dp))
        }
        Text(title, style = MaterialTheme.typography.displayMediumEmphasized, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))
        Text(body, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!visualFirst) {
            Spacer(Modifier.height(40.dp))
            visual()
        }
    }
}

/** The mark held inside a sealed, scalloped shape: two devices, closed to everyone else. */
@Composable
private fun PrivacyVisual() {
    Box(
        Modifier
            .size(260.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie12Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        RelayMark(size = 140.dp, contentDescription = null)
    }
}

/** Dots that stretch into a pill for the current page. */
@Composable
private fun PageIndicator(current: Int, count: Int) {
    val motion = LocalRelayMotion.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 28.dp else 10.dp, motion.spatial(), label = "dot")
            Box(
                Modifier
                    .height(10.dp)
                    .width(width)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RelayShapes.Pill,
                    ),
            )
        }
    }
}
