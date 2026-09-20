package com.vythera.relay.ui.pairing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vythera.relay.R
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.component.RelayPairingCode
import com.vythera.relay.designsystem.component.RelayProgressIndicator
import com.vythera.relay.designsystem.component.RelaySendButton
import com.vythera.relay.designsystem.component.RelaySendButtonSize
import com.vythera.relay.designsystem.component.RelayTransferPhase
import com.vythera.relay.designsystem.component.rememberRelayHaptics
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.node.PairingFailure
import com.vythera.relay.node.PairingRole
import com.vythera.relay.node.PairingSession
import com.vythera.relay.node.PairingStage
import kotlinx.coroutines.delay

/**
 * Pairing, as one sheet whose content changes with the exchange: connecting, then the
 * shapes to compare, then a success mark that grows out of the same place. No
 * cryptographic vocabulary; the shapes are the verification.
 */
@Composable
fun PairingSheet(
    session: PairingSession,
    onRespond: (accept: Boolean, alwaysAllow: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberRelayHaptics()
    val motion = LocalRelayMotion.current
    val stage = session.stage
    val name = session.peerName

    LaunchedEffect(stage) {
        when (stage) {
            PairingStage.Completed -> {
                haptics.confirm()
                delay(1_800)
                onDismiss()
            }
            is PairingStage.Failed, is PairingStage.Declined -> haptics.reject()
            is PairingStage.Confirm -> haptics.tick()
            else -> Unit
        }
    }

    val title = when (stage) {
        PairingStage.Exchanging -> stringResource(R.string.pair_outgoing_connecting, name)
        is PairingStage.Confirm -> stringResource(R.string.pair_incoming_title, name)
        is PairingStage.WaitingForPeer -> stringResource(R.string.pair_outgoing_title, name)
        PairingStage.Completed -> stringResource(R.string.pair_done_title, name)
        is PairingStage.Declined, is PairingStage.Failed -> stringResource(R.string.pair_declined_title)
    }

    RelayExpressiveSheet(
        onDismissRequest = { if (stage.isFinished) onDismiss() else onCancel() },
        title = title,
    ) {
        AnimatedContent(
            targetState = stage,
            contentKey = { it::class },
            transitionSpec = { (scaleIn(motion.spatial(), initialScale = 0.92f) + fadeIn(motion.effects())) togetherWith fadeOut(motion.effectsFast()) },
            label = "pairingStage",
        ) { current ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (current) {
                    PairingStage.Exchanging -> {
                        LoadingIndicator(Modifier.padding(vertical = 40.dp).width(96.dp).height(96.dp))
                        if (session.role == PairingRole.INITIATOR) {
                            FilledTonalButton(onClick = onCancel, shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall)) {
                                Text(stringResource(R.string.settings_cancel))
                            }
                        }
                    }
                    is PairingStage.Confirm -> ResponderContent(name, current, onRespond)
                    is PairingStage.WaitingForPeer -> {
                        Text(
                            stringResource(R.string.pair_outgoing_body, name),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(32.dp))
                        RelayPairingCode(current.code.digits, current.code.shapes)
                        Spacer(Modifier.height(32.dp))
                        FilledTonalButton(
                            onClick = onCancel,
                            shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(stringResource(R.string.pair_mismatch), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    PairingStage.Completed -> {
                        RelayProgressIndicator({ 1f }, RelayTransferPhase.Completed, size = 120.dp, modifier = Modifier.padding(vertical = 24.dp))
                        Text(stringResource(R.string.pair_done_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is PairingStage.Declined, is PairingStage.Failed -> {
                        Text(
                            failureText(current, name),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(24.dp))
                        RelaySendButton(onClick = onDismiss, text = stringResource(R.string.pair_ok), icon = Icons.Rounded.Check, size = RelaySendButtonSize.Large, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponderContent(name: String, stage: PairingStage.Confirm, onRespond: (Boolean, Boolean) -> Unit) {
    var alwaysAllow by remember { mutableStateOf(false) }
    Text(
        stringResource(R.string.pair_incoming_body, name),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    RelayPairingCode(stage.code.digits, stage.code.shapes)
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
        Text(stringResource(R.string.pair_always_allow), style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(Modifier.height(16.dp))
    RelaySendButton(
        onClick = { onRespond(true, alwaysAllow) },
        text = stringResource(R.string.pair_trust),
        icon = Icons.Rounded.Verified,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(
        onClick = { onRespond(false, false) },
        shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(stringResource(R.string.pair_not_now), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun failureText(stage: PairingStage, name: String): String = when (stage) {
    is PairingStage.Declined -> if (stage.byPeer) stringResource(R.string.pair_declined_body, name) else stringResource(R.string.failure_cancelled)
    is PairingStage.Failed -> when (stage.reason) {
        PairingFailure.UNREACHABLE -> stringResource(R.string.pair_failed_unreachable, name)
        PairingFailure.CONNECTION_LOST -> stringResource(R.string.pair_failed_connection, name)
        PairingFailure.TIMED_OUT -> stringResource(R.string.pair_failed_timeout, name)
        PairingFailure.INCOMPATIBLE_VERSION -> stringResource(R.string.pair_failed_version, name)
        PairingFailure.VERIFICATION_FAILED -> stringResource(R.string.pair_failed_verification)
    }
    else -> ""
}

