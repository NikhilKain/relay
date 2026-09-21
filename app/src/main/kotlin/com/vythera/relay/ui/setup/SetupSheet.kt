package com.vythera.relay.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vythera.relay.R
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing

/** One thing Android has to be asked for before Relay works the way people expect. */
enum class SetupStep { Notifications, Battery }

/** Which steps are still outstanding on this phone, in the order they are best asked. */
fun pendingSetupSteps(context: Context): List<SetupStep> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        add(SetupStep.Notifications)
    }
    val power = context.getSystemService(PowerManager::class.java)
    if (power?.isIgnoringBatteryOptimizations(context.packageName) != true) add(SetupStep.Battery)
}

/**
 * Opens Android.s own list of apps and their battery setting. The direct "allow this app"
 * dialog needs a permission Google Play grants to a short list of app types, so Relay takes
 * the user to the setting instead of asking for that permission.
 */
fun batterySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

/**
 * "Two things and Relay is ready." Shown when the app opens with something missing,
 * because both decide whether other devices can reach this phone at all: without the
 * battery exemption Android stops Relay in the background, and without notifications
 * nothing can say what arrived.
 *
 * Each step says what it is for before Android's own dialog appears. Nothing here grants
 * anything by itself, and Skip closes the sheet: the same switches live in Settings.
 */
@Composable
fun SetupSheet(
    steps: List<SetupStep>,
    onAllowNotifications: () -> Unit,
    onAllowBattery: () -> Unit,
    onSkip: () -> Unit,
) {
    RelayExpressiveSheet(
        onDismissRequest = onSkip,
        title = stringResource(R.string.setup_title),
        supportingText = stringResource(R.string.setup_body),
    ) {
        Column(Modifier.padding(horizontal = 28.dp).padding(bottom = 28.dp)) {

            steps.forEach { step ->
                val title: Int = if (step == SetupStep.Notifications) R.string.setup_notifications_title else R.string.setup_battery_title
                val body: Int = if (step == SetupStep.Notifications) R.string.setup_notifications_body else R.string.setup_battery_body
                Surface(shape = RelayShapes.Small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = if (step == SetupStep.Notifications) onAllowNotifications else onAllowBattery,
                            shapes = ButtonDefaults.shapes(RelayShapes.Pill, RelayShapes.ExtraSmall),
                        ) {
                            Text(stringResource(R.string.setup_allow))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(RelaySpacing.m))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.setup_skip)) }
            }
        }
    }
}
