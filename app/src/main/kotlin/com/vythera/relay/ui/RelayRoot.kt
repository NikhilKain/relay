package com.vythera.relay.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vythera.relay.R
import com.vythera.relay.RelayController
import com.vythera.relay.Texts
import com.vythera.relay.ui.common.DeviceUi
import com.vythera.relay.ui.common.LocalNavAnimatedScope
import com.vythera.relay.ui.common.LocalSharedTransitionScope
import com.vythera.relay.ui.common.byRelevance
import com.vythera.relay.ui.common.toUi
import com.vythera.relay.ui.device.DeviceScreen
import com.vythera.relay.ui.device.SendAction
import com.vythera.relay.ui.home.HomeScreen
import com.vythera.relay.ui.inbox.InboxScreen
import com.vythera.relay.ui.pairing.PairingSheet
import com.vythera.relay.ui.send.ComposeMode
import com.vythera.relay.ui.send.SendSheet
import com.vythera.relay.ui.send.rememberSendLaunchers
import com.vythera.relay.ui.settings.SettingsScreen
import com.vythera.relay.ui.settings.EcosystemSettings
import com.vythera.relay.widget.requestRelayWidget
import com.vythera.relay.ui.transfer.ClipOfferBanner
import com.vythera.relay.ui.transfer.OfferSheet
import com.vythera.relay.ui.transfer.TransferActions
import com.vythera.relay.ui.transfer.TransferDock
import com.vythera.relay.designsystem.component.rememberRelayHaptics
import com.vythera.relay.designsystem.theme.LocalRelayMotion
import com.vythera.relay.designsystem.theme.RelayDeviceKind
import com.vythera.relay.designsystem.theme.RelayWidthClass
import com.vythera.relay.node.PairingRole
import com.vythera.relay.node.PairingStage
import com.vythera.relay.node.SendResult
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable data class DeviceRoute(val id: String)
@Serializable object InboxRoute
@Serializable object SettingsRoute
@Serializable object HelpRoute
@Serializable object ContactRoute

private enum class Section { Devices, Inbox, Settings }

/**
 * Relay's app shell. Adapts by window width rather than device:
 *
 * - **compact / medium**: one pane, a floating toolbar with the Send button attached;
 * - **expanded** (tablets, desktops, unfolded foldables): a navigation rail, and the
 *   devices page becomes list | device, so choosing a device never leaves the list.
 *
 * Global surfaces live here too: pairing and incoming-transfer sheets, clipboard
 * offers and the transfer dock, because they can appear on top of any screen.
 */
@Composable
fun RelayRoot(controller: RelayController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberRelayHaptics()
    val motion = LocalRelayMotion.current

    val rawDevices by controller.devices.collectAsStateWithLifecycle()
    val transfers by controller.transfers.collectAsStateWithLifecycle()
    val pairing by controller.pairingSessions.collectAsStateWithLifecycle()
    val records by controller.recentActivity.collectAsStateWithLifecycle(emptyList())
    val inbox by controller.inbox.collectAsStateWithLifecycle(emptyList())
    val settings by controller.settings.collectAsStateWithLifecycle()
    val clipOffers by controller.clipOffers.collectAsStateWithLifecycle()
    val onWifi by rememberOnLocalNetwork()
    var runsUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        runsUnrestricted = isIgnoringBatteryOptimizations(context)
        controller.instantClipboard.refreshStatus()
        onPauseOrDispose { }
    }
    val instantStatus by controller.instantClipboard.status.collectAsStateWithLifecycle()

    val devices = remember(rawDevices, transfers) { rawDevices.map { it.toUi(context, transfers) }.byRelevance() }
    val localName = settings.deviceName.ifBlank { controller.node.config.deviceName }
    val activity = remember(records, localName) { records.map { it.toUi(context, localName) } }

    // A device arriving is a moment worth a tick.
    var knownIds by remember { mutableStateOf(emptySet<DeviceId>()) }
    LaunchedEffect(devices) {
        val nearby = devices.filter { it.reachable }.map { it.id }.toSet()
        if ((nearby - knownIds).isNotEmpty() && knownIds.isNotEmpty()) haptics.tick()
        knownIds = nearby
    }

    fun deviceName(id: DeviceId) = devices.firstOrNull { it.id == id }?.name ?: controller.deviceName(id)
    fun deviceKind(id: DeviceId) = devices.firstOrNull { it.id == id }?.kind ?: RelayDeviceKind.Unknown

    // Send flow state ----------------------------------------------------------------
    var sendTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var sendMode by rememberSaveable { mutableStateOf<ComposeMode?>(null) }
    var pickerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val launchers = rememberSendLaunchers(
        onUris = { uris -> pickerTarget?.let { controller.sendUris(DeviceId(it), uris); haptics.gestureStart() } },
        onFolder = { tree -> pickerTarget?.let { controller.sendFolder(DeviceId(it), tree); haptics.gestureStart() } },
    )

    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { controller.setSaveFolder(uri) }
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun report(result: SendResult?, deviceId: DeviceId, success: Int = R.string.send_sent_toast) {
        when (result) {
            SendResult.Sent -> { haptics.confirm(); toast(context.getString(success, deviceName(deviceId))) }
            is SendResult.Failed -> { haptics.reject(); toast(Texts.failure(context, result.reason, deviceName(deviceId))) }
            null -> toast(context.getString(R.string.send_clipboard_empty))
        }
    }

    fun handleSend(deviceId: DeviceId, action: SendAction) {
        when (action) {
            SendAction.Anything -> { sendMode = null; sendTarget = deviceId.value }
            SendAction.Photos -> { pickerTarget = deviceId.value; sendTarget = null; launchers.photos() }
            SendAction.Files -> { pickerTarget = deviceId.value; sendTarget = null; launchers.files() }
            SendAction.Folder -> { pickerTarget = deviceId.value; sendTarget = null; launchers.folder() }
            SendAction.Clipboard -> { sendTarget = null; scope.launch { report(controller.sendClipboard(deviceId), deviceId) } }
            SendAction.Text -> { sendMode = ComposeMode.Text; sendTarget = deviceId.value }
            SendAction.Link -> { sendMode = ComposeMode.Link; sendTarget = deviceId.value }
            SendAction.Continue -> { sendMode = ComposeMode.Continue; sendTarget = deviceId.value }
        }
    }

    fun connect(deviceId: DeviceId) {
        haptics.gestureStart()
        scope.launch { controller.pair(deviceId) }
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val section = when {
        destination?.hasRoute<InboxRoute>() == true -> Section.Inbox
        destination?.hasRoute<SettingsRoute>() == true -> Section.Settings
        else -> Section.Devices
    }
    val onDeviceScreen = destination?.hasRoute<DeviceRoute>() == true || destination?.hasRoute<HelpRoute>() == true || destination?.hasRoute<ContactRoute>() == true

    fun navigate(target: Section) {
        val route: Any = when (target) {
            Section.Devices -> HomeRoute
            Section.Inbox -> InboxRoute
            Section.Settings -> SettingsRoute
        }
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val primarySendTarget = devices.firstOrNull { it.trusted && it.compatible }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val widthClass = RelayWidthClass.fromWidth(maxWidth)
        val expanded = widthClass == RelayWidthClass.Expanded
        val margin = widthClass.screenMargin
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val toolbarSpace = if (expanded) 0.dp else 96.dp
        val contentPadding = PaddingValues(top = statusBar, bottom = navBar + toolbarSpace)
        var selectedDevice by rememberSaveable { mutableStateOf<String?>(null) }

        Row(Modifier.fillMaxSize()) {
            if (widthClass != RelayWidthClass.Compact) {
                RelayRail(section, onSelect = ::navigate, onSend = { primarySendTarget?.let { handleSend(it.id, SendAction.Anything) } })
            }
            SharedTransitionLayout(Modifier.weight(1f).fillMaxHeight()) {
                // Screens arrive on a spring: they slide a little, scale up from just under
                // full size and fade in, so going deeper feels like moving, not cutting.
                NavHost(
                    navController,
                    startDestination = HomeRoute,
                    enterTransition = {
                        slideInHorizontally(motion.spatial()) { it / 5 } +
                            scaleIn(motion.spatial(), initialScale = 0.92f) +
                            fadeIn(motion.effects())
                    },
                    exitTransition = {
                        slideOutHorizontally(motion.spatial()) { -it / 10 } + fadeOut(motion.effectsFast())
                    },
                    popEnterTransition = {
                        slideInHorizontally(motion.spatial()) { -it / 10 } + fadeIn(motion.effects())
                    },
                    popExitTransition = {
                        slideOutHorizontally(motion.spatial()) { it / 5 } +
                            scaleOut(motion.spatial(), targetScale = 0.92f) +
                            fadeOut(motion.effectsFast())
                    },
                ) {
                    composable<HomeRoute> {
                        CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout, LocalNavAnimatedScope provides this) {
                            if (expanded) {
                                val selected = devices.firstOrNull { it.id.value == selectedDevice } ?: devices.firstOrNull()
                                Row(Modifier.fillMaxSize()) {
                                    HomeScreen(
                                        devices = devices,
                                        activity = activity,
                                        onWifi = onWifi,
                                        onOpenDevice = { selectedDevice = it.value; haptics.tick() },
                                        onSend = { handleSend(it, SendAction.Anything) },
                                        onConnect = ::connect,
                                        onHelp = { navController.navigate(HelpRoute) },
                                        onShareContact = { navController.navigate(ContactRoute) },
                                        contentPadding = contentPadding,
                                        selectedId = selected?.id,
                                        horizontalMargin = margin,
                                        modifier = Modifier.width(460.dp),
                                    )
                                    if (selected != null) {
                                        DevicePane(selected, activity, controller, contentPadding, margin, onBack = null, onSend = { handleSend(selected.id, it) }, onConnect = { connect(selected.id) }, modifier = Modifier.weight(1f))
                                    }
                                }
                            } else {
                                HomeScreen(
                                    devices = devices,
                                    activity = activity,
                                    onWifi = onWifi,
                                    onOpenDevice = { navController.navigate(DeviceRoute(it.value)) },
                                    onSend = { handleSend(it, SendAction.Anything) },
                                    onConnect = ::connect,
                                    onHelp = { navController.navigate(HelpRoute) },
                                    onShareContact = { navController.navigate(ContactRoute) },
                                    contentPadding = contentPadding,
                                    horizontalMargin = margin,
                                )
                            }
                        }
                    }
                    composable<DeviceRoute> { entry ->
                        val id = entry.toRoute<DeviceRoute>().id
                        val device = devices.firstOrNull { it.id.value == id }
                        CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout, LocalNavAnimatedScope provides this) {
                            if (device != null) {
                                DevicePane(
                                    device, activity, controller,
                                    PaddingValues(top = statusBar, bottom = navBar), margin,
                                    onBack = { navController.popBackStack() },
                                    onSend = { handleSend(device.id, it) },
                                    onConnect = { connect(device.id) },
                                    onForgotten = { navController.popBackStack() },
                                )
                            } else {
                                LaunchedEffect(Unit) { navController.popBackStack() }
                            }
                        }
                    }
                    composable<InboxRoute> {
                        InboxScreen(
                            items = inbox,
                            onCopy = { controller.copyToClipboard(it); toast(context.getString(R.string.inbox_copied)) },
                            onDelete = { scope.launch { controller.deleteInboxItem(it) } },
                            contentPadding = contentPadding,
                            horizontalMargin = margin,
                        )
                    }
                    composable<ContactRoute> {
                        com.vythera.relay.contact.ContactScreen(
                            card = remember(settings.contactCard) { controller.contactCard },
                            onSaveCard = { card -> scope.launch { controller.setContactCard(card) } },
                            onBack = { navController.popBackStack() },
                            contentPadding = PaddingValues(top = statusBar, bottom = navBar),
                            horizontalMargin = margin,
                        )
                    }
                    composable<HelpRoute> {
                        com.vythera.relay.ui.help.HelpScreen(
                            onBack = { navController.popBackStack() },
                            contentPadding = PaddingValues(top = statusBar, bottom = navBar),
                            horizontalMargin = margin,
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            settings = settings,
                            currentName = localName,
                            onRename = { scope.launch { controller.setDeviceName(it) } },
                            onClipboardMode = { scope.launch { controller.setClipboardMode(it) } },
                            onColors = { scope.launch { controller.setColors(it) } },
                            onStayAvailable = { scope.launch { controller.setStayAvailable(it) } },
                            runsUnrestricted = runsUnrestricted,
                            onHelp = { navController.navigate(HelpRoute) },
                            onShareContact = { navController.navigate(ContactRoute) },
                            onAllowBackground = {
                                runCatching {
                                    context.startActivity(com.vythera.relay.ui.setup.batterySettingsIntent(context))
                                }
                            },
                            saveFolderName = settings.saveFolder?.let { controller.saveFolderName(it) ?: "…" },
                            onPickSaveFolder = { folderPicker.launch(null) },
                            onResetSaveFolder = { scope.launch { controller.setSaveFolder(null) } },
                            ecosystem = EcosystemSettings(
                                status = instantStatus,
                                grantCommand = controller.instantClipboard.grantCommand(),
                                onEcosystem = { scope.launch { controller.setEcosystem(it) } },
                                onInstantClipboard = { scope.launch { controller.setInstantClipboard(it) } },
                                onAllowOverlay = {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                                .setData(android.net.Uri.parse("package:${context.packageName}")),
                                        )
                                    }
                                },
                                onCopyCommand = { controller.copyToClipboard(controller.instantClipboard.grantCommand()) },
                                onAddWidget = { requestRelayWidget(context) },
                                onAnalytics = { scope.launch { controller.setAnalytics(it) } },
                                onRetryInstant = { controller.instantClipboard.retry() },
                            ),
                            contentPadding = contentPadding,
                            horizontalMargin = margin,
                        )
                    }
                }
            }
        }

        // Clipboard offers slide in from the top.
        val clipOffer = clipOffers.lastOrNull()
        AnimatedVisibility(
            visible = clipOffer != null,
            enter = slideInVertically(motion.spatial()) { -it } + fadeIn(motion.effects()),
            exit = slideOutVertically(motion.spatial()) { -it } + fadeOut(motion.effectsFast()),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(16.dp),
        ) {
            clipOffer?.let { offer ->
                ClipOfferBanner(
                    fromName = offer.fromName,
                    preview = offer.preview,
                    onCopy = { controller.applyClipOffer(offer.id); haptics.confirm() },
                    onDismiss = { controller.dismissClipOffer(offer.id) },
                )
            }
        }

        // Screens scroll edge to edge; this keeps their content from colliding with the clock.
        Box(Modifier.fillMaxWidth().height(statusBar).align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface))

        TransferDock(
            transfers = transfers,
            peerName = ::deviceName,
            peerKind = ::deviceKind,
            actions = TransferActions(
                pause = { id -> scope.launch { controller.pauseTransfer(id) } },
                resume = { id -> scope.launch { controller.resumeTransfer(id) } },
                cancel = { id -> scope.launch { controller.cancelTransfer(id) } },
                retry = controller::retryTransfer,
                dismiss = controller::dismissTransfer,
            ),
            bottomPadding = navBar + if (expanded || onDeviceScreen) 16.dp else 100.dp,
        )

        // Floating toolbar with the Send button attached (phones and small windows).
        AnimatedVisibility(
            visible = !expanded && widthClass == RelayWidthClass.Compact && !onDeviceScreen,
            enter = slideInVertically(motion.spatial()) { it } + fadeIn(motion.effects()),
            exit = slideOutVertically(motion.spatial()) { it } + fadeOut(motion.effectsFast()),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBar + 16.dp),
        ) {
            RelayToolbar(section, onSelect = ::navigate, onSend = {
                primarySendTarget?.let { handleSend(it.id, SendAction.Anything) } ?: navigate(Section.Devices)
            })
        }
    }

    // Sheets --------------------------------------------------------------------------

    sendTarget?.let { id ->
        val target = DeviceId(id)
        SendSheet(
            deviceName = deviceName(target),
            initialMode = sendMode,
            onDismiss = { sendTarget = null },
            onPick = { handleSend(target, it) },
            onSendText = { text, mode ->
                sendTarget = null
                scope.launch {
                    when (mode) {
                        ComposeMode.Continue -> report(controller.continueOn(target, text), target, R.string.send_opened_toast)
                        else -> report(controller.sendText(target, text), target)
                    }
                }
            },
        )
    }

    val session = pairing.firstOrNull { !it.stage.isFinished && (it.role == PairingRole.INITIATOR || it.stage is PairingStage.Confirm) }
        ?: pairing.lastOrNull { it.stage.isFinished }
    session?.let { current ->
        PairingSheet(
            session = current,
            onRespond = { accept, alwaysAllow -> scope.launch { controller.respondToPairing(current.requestId, accept, alwaysAllow) } },
            onCancel = { scope.launch { controller.cancelPairing(current.requestId) } },
            onDismiss = { controller.dismissPairing(current.requestId) },
        )
    }

    val offer = transfers.firstOrNull { it.direction == TransferDirection.INCOMING && it.status == TransferStatus.AwaitingDecision }
    if (offer != null && session == null) {
        OfferSheet(
            transfer = offer,
            peerName = deviceName(offer.peerId),
            peerKind = deviceKind(offer.peerId),
            onAccept = { alwaysAllow ->
                haptics.gestureStart()
                scope.launch { controller.acceptTransfer(offer.id, if (alwaysAllow) offer.peerId else null) }
            },
            onDecline = { scope.launch { controller.declineTransfer(offer.id) } },
        )
    }
}

@Composable
private fun DevicePane(
    device: DeviceUi,
    activity: List<com.vythera.relay.ui.common.ActivityUi>,
    controller: RelayController,
    contentPadding: PaddingValues,
    margin: androidx.compose.ui.unit.Dp,
    onBack: (() -> Unit)?,
    onSend: (SendAction) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onForgotten: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    DeviceScreen(
        device = device,
        activity = activity.filter { it.peerId == device.id.value },
        onBack = onBack,
        onSend = onSend,
        onConnect = onConnect,
        onAutoAcceptChange = { scope.launch { controller.setAutoAccept(device.id, it) } },
        onForget = { scope.launch { controller.forget(device.id); onForgotten() } },
        contentPadding = contentPadding,
        horizontalMargin = margin,
        modifier = modifier,
    )
}

/**
 * Phone navigation: a floating toolbar, not a bar pinned to the edge, with Send as a
 * vibrant attached FAB. The selected section is a filled circle inside the toolbar.
 */
@Composable
private fun RelayToolbar(section: Section, onSelect: (Section) -> Unit, onSend: () -> Unit) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.action_send))
            }
        },
    ) {
        ToolbarItem(Section.Devices, section, Icons.Rounded.Devices, R.string.nav_devices, onSelect)
        ToolbarItem(Section.Inbox, section, Icons.Rounded.Inbox, R.string.nav_inbox, onSelect)
        ToolbarItem(Section.Settings, section, Icons.Rounded.Settings, R.string.nav_settings, onSelect)
    }
}

@Composable
private fun ToolbarItem(item: Section, current: Section, icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onSelect: (Section) -> Unit) {
    val description = stringResource(label)
    if (item == current) {
        FilledIconButton(
            onClick = { onSelect(item) },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                contentColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.width(56.dp),
        ) { Icon(icon, contentDescription = description) }
    } else {
        IconButton(onClick = { onSelect(item) }) { Icon(icon, contentDescription = description) }
    }
}

@Composable
private fun RelayRail(section: Section, onSelect: (Section) -> Unit, onSend: () -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = {
            // A standard FAB: the floating-toolbar variant sizes itself from its toolbar and fills any other parent.
            androidx.compose.material3.FloatingActionButton(
                onClick = onSend,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.action_send))
            }
        },
        modifier = Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars),
    ) {
        listOf(
            Triple(Section.Devices, Icons.Rounded.Devices, R.string.nav_devices),
            Triple(Section.Inbox, Icons.Rounded.Inbox, R.string.nav_inbox),
            Triple(Section.Settings, Icons.Rounded.Settings, R.string.nav_settings),
        ).forEach { (item, icon, label) ->
            NavigationRailItem(
                selected = section == item,
                onClick = { onSelect(item) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/** True while on Wi-Fi or Ethernet: the networks Relay can use. Internet is not required. */
@Composable
private fun rememberOnLocalNetwork(): androidx.compose.runtime.State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(isOnLocalNetwork(context)) }
    DisposableEffect(context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = isOnLocalNetwork(context) }
            override fun onLost(network: Network) { state.value = isOnLocalNetwork(context) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { state.value = isOnLocalNetwork(context) }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    return state
}

private fun isOnLocalNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return connectivity.allNetworks.any { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }
}


private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
