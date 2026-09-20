package com.vythera.relay.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vythera.relay.R
import com.vythera.relay.ui.MainActivity

/**
 * Relay on the home screen: your devices, one tap from sending.
 *
 * Tapping a device opens the file picker and sends what you choose straight to it, with
 * no Relay window in between. Tapping the title opens Relay.
 *
 * It draws from [WidgetStore], never from the engine, so it appears immediately even when
 * Relay has not run since the phone started.
 */
class RelayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetStore.read(context)
        provideContent {
            GlanceTheme {
                Body(state)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Body(state: WidgetState) {
        val context = LocalContext.current
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(14.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                Image(provider = ImageProvider(R.drawable.ic_widget_mark), contentDescription = null, modifier = GlanceModifier.size(18.dp))
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    context.getString(R.string.app_name),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(GlanceModifier.height(10.dp))

            if (state.devices.isEmpty()) {
                Text(
                    context.getString(R.string.widget_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
                return@Column
            }

            state.devices.take(MAX_DEVICES).forEach { device ->
                DeviceRow(device)
                Spacer(GlanceModifier.height(6.dp))
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DeviceRow(device: WidgetDevice) {
        val context = LocalContext.current
        val send = android.content.ComponentName(context, SendToActivity::class.java)
        Row(
            GlanceModifier
                .fillMaxWidth()
                .background(if (device.reachable) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant)
                .cornerRadius(18.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable(actionStartActivity(send, actionParametersOf(DeviceKey to device.id))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                device.name,
                maxLines = 1,
                style = TextStyle(
                    color = if (device.reachable) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                context.getString(if (device.reachable) R.string.widget_send else R.string.widget_away),
                style = TextStyle(
                    color = if (device.reachable) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
        }
    }

    private companion object {
        const val MAX_DEVICES = 4
    }
}

class RelayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RelayWidget()
}

/** The device a widget tap is about, handed to the activity as an intent extra. */
val DeviceKey = ActionParameters.Key<String>("relay.device")

/** Called by the app whenever the device list changes, so the widget follows along. */
suspend fun refreshRelayWidgets(context: Context) {
    runCatching { RelayWidget().updateAll(context) }
}

private val Int.sp: androidx.compose.ui.unit.TextUnit get() = androidx.compose.ui.unit.TextUnit(toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/**
 * Asks the launcher to place the Relay widget. Android shows its own "Add to home
 * screen" card; launchers that do not support this ignore the request.
 */
fun requestRelayWidget(context: Context): Boolean {
    val manager = android.appwidget.AppWidgetManager.getInstance(context) ?: return false
    if (!manager.isRequestPinAppWidgetSupported) return false
    return runCatching {
        manager.requestPinAppWidget(android.content.ComponentName(context, RelayWidgetReceiver::class.java), null, null)
    }.getOrDefault(false)
}
