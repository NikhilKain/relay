package com.vythera.relay.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vythera.relay.R
import com.vythera.relay.RelayController
import com.vythera.relay.Texts
import com.vythera.relay.content.Links
import com.vythera.relay.relay
import com.vythera.relay.service.RelayService
import com.vythera.relay.ui.common.byRelevance
import com.vythera.relay.ui.common.toUi
import com.vythera.relay.data.settings.ColorPreference
import com.vythera.relay.designsystem.component.RelayDeviceCard
import com.vythera.relay.designsystem.component.RelayDeviceCardStyle
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.component.rememberRelayHaptics
import com.vythera.relay.designsystem.theme.RelayColorSource
import com.vythera.relay.designsystem.theme.RelayTheme
import com.vythera.relay.node.SendResult
import com.vythera.relay.protocol.DeviceId
import kotlinx.coroutines.launch

/**
 * "Share → Relay → Gaming PC". A sheet over the app the user is in; Relay's main UI
 * never opens. Direct-share shortcuts (a trusted device picked straight from Android's
 * share sheet) skip the picker and send at once.
 */
class ShareActivity : ComponentActivity() {
    private sealed interface Shared {
        data class Files(val uris: List<Uri>) : Shared
        data class Text(val text: String) : Shared
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val shared = parse(intent) ?: return finish()
        val container = relay
        RelayService.start(this)

        val shortcutTarget = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        if (shortcutTarget != null) {
            lifecycleScope.launch {
                val controller = container.controller()
                send(controller, DeviceId(shortcutTarget), shared, openThere = shared is Shared.Text && Links.isWebLink(shared.text))
                finish()
            }
            return
        }

        setContent {
            val controller by container.controllerState.collectAsStateWithLifecycle()
            container.warmUp()
            controller?.let { Picker(it, shared) }
        }
    }

    @Composable
    private fun Picker(controller: RelayController, shared: Shared) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptics = rememberRelayHaptics()
        val settings by controller.settings.collectAsStateWithLifecycle()
        val rawDevices by controller.devices.collectAsStateWithLifecycle()
        val transfers by controller.transfers.collectAsStateWithLifecycle()
        val devices = remember(rawDevices, transfers) { rawDevices.map { it.toUi(context, transfers) }.byRelevance().filter { it.trusted } }
        val isLink = shared is Shared.Text && Links.isWebLink(shared.text)
        var openThere by remember { mutableStateOf(isLink) }

        RelayTheme(colorSource = if (settings.colors == ColorPreference.RELAY) RelayColorSource.Ember else RelayColorSource.Dynamic) {
            RelayExpressiveSheet(
                onDismissRequest = { finish() },
                title = stringResource(R.string.share_title),
                supportingText = when (shared) {
                    is Shared.Files -> resources.getQuantityString(R.plurals.offer_files, shared.uris.size, shared.uris.size)
                    is Shared.Text -> shared.text.take(80)
                },
            ) {
                if (isLink) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        ToggleButton(checked = openThere, onCheckedChange = { openThere = true }, shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), modifier = Modifier.weight(1f).height(52.dp)) {
                            Text(stringResource(R.string.send_open_there))
                        }
                        ToggleButton(checked = !openThere, onCheckedChange = { openThere = false }, shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(), modifier = Modifier.weight(1f).height(52.dp)) {
                            Text(stringResource(R.string.send_link))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.share_no_devices), style = MaterialTheme.typography.bodyLarge)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        RelayDeviceCard(
                            name = device.name,
                            platformLabel = device.platformLabel,
                            kind = device.kind,
                            presence = device.presence,
                            trusted = true,
                            style = RelayDeviceCardStyle.Wide,
                            onClick = {
                                haptics.gestureStart()
                                scope.launch {
                                    send(controller, device.id, shared, openThere)
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }

    private suspend fun send(controller: RelayController, target: DeviceId, shared: Shared, openThere: Boolean) {
        val name = controller.deviceName(target)
        when (shared) {
            is Shared.Files -> {
                controller.sendUris(target, shared.uris)
                toast(getString(R.string.send_sent_toast, name))
            }
            is Shared.Text -> {
                val result = if (openThere) controller.continueOn(target, shared.text) else controller.sendText(target, shared.text)
                toast(
                    when (result) {
                        SendResult.Sent -> getString(if (openThere) R.string.send_opened_toast else R.string.send_sent_toast, name)
                        is SendResult.Failed -> Texts.failure(this, result.reason, name)
                    },
                )
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private fun parse(intent: Intent): Shared? = when (intent.action) {
        Intent.ACTION_SEND -> {
            val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            when {
                stream != null -> Shared.Files(listOf(stream))
                else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { Shared.Text(it) }
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.takeIf { it.isNotEmpty() }?.let { Shared.Files(it) }
        else -> null
    }
}
