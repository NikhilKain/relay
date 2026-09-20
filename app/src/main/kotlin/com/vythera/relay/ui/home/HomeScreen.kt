package com.vythera.relay.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vythera.relay.R
import com.vythera.relay.ui.common.ActivityUi
import com.vythera.relay.ui.common.DeviceUi
import com.vythera.relay.ui.common.relaySharedBounds
import com.vythera.relay.designsystem.component.RelayActivityItem
import com.vythera.relay.designsystem.component.relayEntrance
import com.vythera.relay.designsystem.component.RelayDeviceActivity
import com.vythera.relay.designsystem.component.RelayDeviceCard
import com.vythera.relay.designsystem.component.RelayDeviceCardStyle
import com.vythera.relay.designsystem.component.RelayEmptyState
import com.vythera.relay.designsystem.component.RelayExpressiveSheet
import com.vythera.relay.designsystem.component.RelayHeroDeviceCard
import com.vythera.relay.designsystem.component.RelayListPosition
import com.vythera.relay.designsystem.component.RelayLockup
import com.vythera.relay.designsystem.component.RelayPresence
import com.vythera.relay.designsystem.component.RelaySectionHeader
import com.vythera.relay.designsystem.theme.RelayShapes
import com.vythera.relay.designsystem.theme.RelaySpacing
import com.vythera.relay.protocol.DeviceId

/**
 * "These are my devices."
 *
 * Composed rather than listed: one device gets the stage (the hero), the next ones are
 * set in mixed proportions beside and below it, devices you have not connected yet sit
 * in their own quieter group, and recent activity closes the page as a single grouped
 * surface. No two sections share a treatment.
 */
@Composable
fun HomeScreen(
    devices: List<DeviceUi>,
    activity: List<ActivityUi>,
    onWifi: Boolean,
    onOpenDevice: (DeviceId) -> Unit,
    onSend: (DeviceId) -> Unit,
    onConnect: (DeviceId) -> Unit,
    onHelp: () -> Unit,
    onShareContact: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    selectedId: DeviceId? = null,
    horizontalMargin: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val trusted = devices.filter { it.trusted }
    val newDevices = devices.filter { !it.trusted && it.presence != RelayPresence.Offline }
    val hero = trusted.firstOrNull()
    val others = trusted.drop(1)
    val nearbyCount = devices.count { it.presence != RelayPresence.Offline }

    // The screen assembles itself once per visit; recycled rows do not replay it.
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(900); intro = false }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = horizontalMargin,
            end = horizontalMargin,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = RelaySpacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelayLockup(markSize = 30.dp)
                    Spacer(Modifier.weight(1f))
                    NetworkChip(onWifi)
                    androidx.compose.material3.IconButton(onClick = onShareContact) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ContactPage, contentDescription = stringResource(R.string.contact_open))
                    }
                    androidx.compose.material3.IconButton(onClick = onHelp) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = stringResource(R.string.help_open))
                    }
                }
                Spacer(Modifier.height(RelaySpacing.xxl))
                Text(
                    stringResource(R.string.home_title),
                    style = MaterialTheme.typography.displayMediumEmphasized,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (nearbyCount > 0) pluralStringResource(R.plurals.home_nearby_count, nearbyCount, nearbyCount) else stringResource(R.string.home_none_nearby),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (hero != null) {
            item(key = "hero-${hero.id}") {
                RelayHeroDeviceCard(
                    name = hero.name,
                    platformLabel = hero.platformLabel,
                    kind = hero.kind,
                    presence = hero.presence,
                    trusted = true,
                    onClick = { onOpenDevice(hero.id) },
                    onSend = { onSend(hero.id) },
                    // Trusted devices can be sent to while away: delivery happens when they return.
                    sendEnabled = hero.compatible,
                    activity = hero.activeTransfer?.let {
                        RelayDeviceActivity.Transferring(it.progress.fraction, it.items.singleOrNull()?.name ?: "${it.items.size} files")
                    },
                    modifier = Modifier.animateItem().relayEntrance(1, intro).relaySharedBounds("device-${hero.id}", RelayShapes.Hero),
                )
            }
        }

        if (others.isNotEmpty()) {
            item(key = "others") {
                OtherDevices(others, selectedId, onOpenDevice, Modifier.animateItem().relayEntrance(2, intro))
            }
        }

        if (newDevices.isNotEmpty()) {
            item(key = "new-header") {
                RelaySectionHeader(stringResource(R.string.home_new_devices), Modifier.padding(top = RelaySpacing.l).animateItem().relayEntrance(3, intro))
            }
            items(newDevices, key = { "new-${it.id}" }) { device ->
                RelayDeviceCard(
                    name = device.name,
                    platformLabel = stringResource(R.string.home_new_device_hint),
                    kind = device.kind,
                    presence = device.presence,
                    onClick = { onConnect(device.id) },
                    style = RelayDeviceCardStyle.Wide,
                    modifier = Modifier.fillMaxWidth().animateItem().relayEntrance(4, intro),
                )
            }
        }

        if (devices.isEmpty()) {
            item(key = "empty") {
                RelayEmptyState(
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    actionLabel = stringResource(R.string.home_empty_action),
                    onAction = onHelp,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (activity.isNotEmpty()) {
            item(key = "recent-header") {
                RelaySectionHeader(stringResource(R.string.home_recent), Modifier.padding(top = RelaySpacing.xl).animateItem().relayEntrance(5, intro))
            }
            item(key = "recent") {
                ActivityGroup(activity.take(8), Modifier.animateItem().relayEntrance(6, intro))
            }
        }
    }
}

/**
 * Secondary devices in mixed proportions: one tall card beside a stack of compact
 * pills, then wide cards. The shapes change, the grid never repeats.
 */
@Composable
private fun OtherDevices(devices: List<DeviceUi>, selectedId: DeviceId?, onOpen: (DeviceId) -> Unit, modifier: Modifier) {
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val first = devices.first()
        val stack = devices.drop(1).take(2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeviceCard(first, if (stack.isEmpty()) RelayDeviceCardStyle.Wide else RelayDeviceCardStyle.Tall, selectedId, onOpen, Modifier.weight(1f))
            if (stack.isNotEmpty()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    stack.forEach { DeviceCard(it, RelayDeviceCardStyle.Compact, selectedId, onOpen, Modifier.fillMaxWidth()) }
                }
            }
        }
        devices.drop(3).forEach { DeviceCard(it, RelayDeviceCardStyle.Wide, selectedId, onOpen, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun DeviceCard(device: DeviceUi, style: RelayDeviceCardStyle, selectedId: DeviceId?, onOpen: (DeviceId) -> Unit, modifier: Modifier) {
    RelayDeviceCard(
        name = device.name,
        platformLabel = device.platformLabel,
        kind = device.kind,
        presence = device.presence,
        onClick = { onOpen(device.id) },
        style = style,
        trusted = device.trusted,
        busy = device.activeTransfer != null,
        selected = device.id == selectedId,
        modifier = modifier.relaySharedBounds("device-${device.id}", RelayShapes.Device),
    )
}

/** "Local network": Relay works without internet, and says so. */
@Composable
private fun NetworkChip(onWifi: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RelayShapes.Pill,
        color = if (onWifi) colors.surfaceContainerHigh else colors.errorContainer,
        contentColor = if (onWifi) colors.onSurfaceVariant else colors.onErrorContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (onWifi) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, contentDescription = null, modifier = Modifier.padding(end = 6.dp).widthIn(max = 16.dp))
            Text(
                stringResource(if (onWifi) R.string.home_local_connection else R.string.home_offline_network),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Activity rows as one grouped surface: 3dp seams, large outer corners. */
@Composable
fun ActivityGroup(items: List<ActivityUi>, modifier: Modifier = Modifier) {
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { index, item ->
            RelayActivityItem(
                title = item.title,
                fromName = item.from,
                toName = item.to,
                timeLabel = item.time,
                kind = item.category.kind,
                statusLabel = item.status,
                position = listPosition(index, items.size),
            )
        }
    }
}

fun listPosition(index: Int, size: Int): RelayListPosition = when {
    size == 1 -> RelayListPosition.Single
    index == 0 -> RelayListPosition.First
    index == size - 1 -> RelayListPosition.Last
    else -> RelayListPosition.Middle
}
