package com.vythera.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.vythera.relay.analytics.RelayAnalytics
import com.vythera.relay.clipboard.ClipboardSync
import com.vythera.relay.clipboard.InstantClipboard
import android.util.Log
import com.vythera.relay.contact.ContactCard
import com.vythera.relay.content.ContentCategory
import com.vythera.relay.content.ContentFiles
import com.vythera.relay.content.Links
import com.vythera.relay.service.RelayNotifications
import com.vythera.relay.share.ShareActivity
import com.vythera.relay.data.db.InboxItemEntity
import com.vythera.relay.data.db.RelayDatabase
import com.vythera.relay.data.db.TransferRecordEntity
import com.vythera.relay.data.settings.ClipboardMode
import com.vythera.relay.data.settings.RelaySettings
import com.vythera.relay.data.settings.SettingsRepository
import com.vythera.relay.data.storage.FolderStorage
import com.vythera.relay.data.storage.SwitchableStorage
import com.vythera.relay.node.NodeEvent
import com.vythera.relay.node.Presence
import com.vythera.relay.widget.WidgetDevice
import com.vythera.relay.widget.WidgetState
import com.vythera.relay.widget.WidgetStore
import com.vythera.relay.widget.refreshRelayWidgets
import com.vythera.relay.node.RelayDevice
import com.vythera.relay.node.RelayNode
import com.vythera.relay.node.SendResult
import com.vythera.relay.protocol.Capability
import com.vythera.relay.protocol.ClipItem
import com.vythera.relay.protocol.ContinueActivity
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.TransferKind
import com.vythera.relay.transfer.OutgoingFile
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferSnapshot
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A clip from another device waiting for the user, when clipboard mode is Ask. Either text or received files. */
data class ClipOffer(
    val id: String,
    val from: DeviceId,
    val fromName: String,
    val preview: String,
    val text: ClipItem? = null,
    val files: TransferSnapshot? = null,
)

/**
 * The Android side of Relay: owns the [RelayNode] and turns what it reports into
 * history, inbox entries, notifications, clipboard writes and share-sheet shortcuts.
 *
 * UI talks only to this class. Screens never touch sockets, files or the database.
 */
class RelayController(
    private val context: Context,
    private val scope: CoroutineScope,
    val node: RelayNode,
    private val storage: SwitchableStorage,
    private val database: RelayDatabase,
    private val settingsRepository: SettingsRepository,
    private val notifications: RelayNotifications,
    initialSettings: RelaySettings,
) {
    val settings: StateFlow<RelaySettings> = settingsRepository.settings
        .stateIn(scope, SharingStarted.Eagerly, initialSettings)

    val devices: StateFlow<List<RelayDevice>> = node.devices
    val transfers: StateFlow<List<TransferSnapshot>> = node.transfers
    val pairingSessions = node.pairingSessions
    val recentActivity: Flow<List<TransferRecordEntity>> = database.transfers().observeRecent(40)
    val inbox: Flow<List<InboxItemEntity>> = database.inbox().observeAll()

    private val _clipOffers = MutableStateFlow<List<ClipOffer>>(emptyList())
    val clipOffers: StateFlow<List<ClipOffer>> = _clipOffers.asStateFlow()

    val localId: DeviceId get() = node.localId

    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clipboardSync = ClipboardSync(context, node)

    /** Copy on this phone, paste elsewhere, even while Relay is in the background. */
    val instantClipboard = InstantClipboard(context, onCopied = ::onLocalClipboardChanged)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        scope.launch { node.events.collect(::onEvent) }
        scope.launch {
            settings.map { it.deviceName }.distinctUntilChanged().collect { name ->
                if (name.isNotBlank() && name != node.config.deviceName) node.rename(name)
            }
        }
        scope.launch {
            settings.map { it.clipboardMode }.distinctUntilChanged().collect { mode ->
                node.setClipboardSync(mode != ClipboardMode.OFF)
            }
        }
        scope.launch {
            settings.map { Triple(it.ecosystem, it.instantClipboard, it.clipboardMode) }.distinctUntilChanged().collect { applyEcosystem(settings.value) }
        }
        scope.launch {
            settings.map { it.analytics }.distinctUntilChanged().collect { RelayAnalytics.setEnabled(context, it) }
        }
        scope.launch {
            devices.map { list -> list.filter { it.isTrusted } }.distinctUntilChanged().collect { trusted ->
                publishShareTargets(trusted)
                // The home-screen widget draws from a file, so it stays right even when
                // Relay is not running.
                WidgetStore.write(
                    context,
                    WidgetState(
                        trusted.map { device ->
                            WidgetDevice(
                                id = device.id.value,
                                name = device.name.ifBlank { deviceName(device.id) },
                                kind = device.type.name,
                                reachable = device.presence != Presence.OFFLINE && device.isCompatible,
                            )
                        },
                    ),
                )
                refreshRelayWidgets(context)
            }
        }
        scope.launch {
            settings.map { it.saveFolder }.distinctUntilChanged().collect { folder ->
                val chosen = folder?.let { uri ->
                    runCatching { FolderStorage(context, Uri.parse(uri)) }.getOrNull()
                }
                if (chosen == null) storage.useDefault() else storage.current = chosen
            }
        }
    }

    // Lifecycle ----------------------------------------------------------------------

    fun startNetworking() = node.start()
    fun stopNetworking() = node.stop()
    fun refresh() = node.refresh()

    private val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun deviceName(id: DeviceId): String =
        devices.value.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.device_platform_unknown)

    // Sending -------------------------------------------------------------------------

    /** Resolves the URIs off the main thread, then queues the transfer. */
    fun sendUris(deviceId: DeviceId, uris: List<Uri>) {
        scope.launch(Dispatchers.IO) { sendFiles(deviceId, ContentFiles.fromUris(context, uris)) }
    }

    fun sendFolder(deviceId: DeviceId, treeUri: Uri) {
        scope.launch(Dispatchers.IO) {
            val files = ContentFiles.fromTree(context, treeUri)
            if (files.isNotEmpty()) node.sendFiles(deviceId, files, ContentFiles.kindOf(files, isFolder = true))
        }
    }

    fun sendFiles(deviceId: DeviceId, files: List<OutgoingFile>) {
        if (files.isNotEmpty()) node.sendFiles(deviceId, files, ContentFiles.kindOf(files))
    }

    suspend fun sendText(deviceId: DeviceId, text: String): SendResult {
        val result = node.sendText(deviceId, text)
        recordText(deviceId, text, outgoing = true, result = result)
        return result
    }

    /** Opens [url] on the other device ("Continue"). Falls back to sending it as a link if it cannot open links. */
    suspend fun continueOn(deviceId: DeviceId, url: String): SendResult {
        val safe = Links.safeWebUrl(url) ?: return sendText(deviceId, url)
        val canContinue = devices.value.firstOrNull { it.id == deviceId }?.capabilities?.let {
            it.isEmpty() || Capability.ContinueWebPage in it
        } ?: true
        if (!canContinue) return sendText(deviceId, safe)
        val result = node.continueOn(deviceId, ContinueActivity(ContinueActivity.KIND_WEB_PAGE, safe))
        recordText(deviceId, safe, outgoing = true, result = result, opened = true)
        return result
    }

    /**
     * "Send clipboard" to one device, explicitly. Text goes as a message (it lands in the
     * inbox), copied files as a normal transfer. The app must be in the foreground.
     */
    suspend fun sendClipboard(deviceId: DeviceId): SendResult? = when (val clip = clipboardSync.readLocal()) {
        null -> null
        is ClipboardSync.LocalClip.Text -> sendText(deviceId, clip.text)
        is ClipboardSync.LocalClip.Files -> {
            sendUris(deviceId, clip.uris)
            SendResult.Sent
        }
    }

    /**
     * The local clipboard may have changed: send it to every trusted device around.
     * Called when Relay gains focus (Android only lets the focused app read the
     * clipboard), from the clipboard listener, and from the Quick Settings tile.
     * Returns the devices it went to.
     */
    suspend fun syncLocalClipboard(force: Boolean = false): List<DeviceId> {
        if (settings.value.clipboardMode == ClipboardMode.OFF) return emptyList()
        val targets = clipboardSync.defaultTargets()
        return if (clipboardSync.publish(targets, force)) targets else emptyList()
    }

    /** Call while Relay has focus: the clip is read right away, before focus can move on. */
    fun onLocalClipboardChanged() {
        if (settings.value.clipboardMode == ClipboardMode.OFF) return
        val clip = clipboardSync.readLocal() ?: return
        scope.launch {
            val targets = clipboardSync.defaultTargets()
            if (clipboardSync.publish(targets, clip = clip)) Log.i("Relay", "Clipboard sent to ${targets.size} device(s)")
        }
    }

    private fun applyEcosystem(settings: RelaySettings) {
        node.setEcosystem(settings.ecosystem)
        if (settings.ecosystem && settings.instantClipboard && settings.clipboardMode != ClipboardMode.OFF) instantClipboard.start() else instantClipboard.stop()
    }

    suspend fun setEcosystem(enabled: Boolean) = settingsRepository.setEcosystem(enabled)
    suspend fun setInstantClipboard(enabled: Boolean) = settingsRepository.setInstantClipboard(enabled)
    suspend fun markSetupAsked() = settingsRepository.setSetupAsked()

    suspend fun setAnalytics(enabled: Boolean) {
        settingsRepository.setAnalytics(enabled)
        RelayAnalytics.setEnabled(context, enabled)
    }

    // Transfers & pairing (thin pass-throughs so the UI depends on one class) --------------

    suspend fun pair(deviceId: DeviceId) = node.pair(deviceId)
    suspend fun respondToPairing(requestId: String, accept: Boolean, alwaysAllow: Boolean) {
        notifications.cancelPairing()
        node.respondToPairing(requestId, accept, alwaysAllow)
    }
    suspend fun cancelPairing(requestId: String) = node.cancelPairing(requestId)
    fun dismissPairing(requestId: String) = node.dismissPairing(requestId)

    suspend fun acceptTransfer(id: String, alwaysAllowFrom: DeviceId? = null) {
        notifications.cancel(id)
        alwaysAllowFrom?.let { node.setAutoAcceptTransfers(it, true) }
        node.acceptTransfer(id)
    }
    suspend fun declineTransfer(id: String) {
        notifications.cancel(id)
        node.declineTransfer(id)
    }
    suspend fun pauseTransfer(id: String) = node.pauseTransfer(id)
    suspend fun resumeTransfer(id: String) = node.resumeTransfer(id)
    suspend fun cancelTransfer(id: String) = node.cancelTransfer(id)
    fun retryTransfer(id: String) = node.retryTransfer(id)
    fun dismissTransfer(id: String) = node.dismissTransfer(id)

    suspend fun setAutoAccept(deviceId: DeviceId, enabled: Boolean) = node.setAutoAcceptTransfers(deviceId, enabled)
    suspend fun forget(deviceId: DeviceId) = node.forget(deviceId)

    // Settings -------------------------------------------------------------------------

    suspend fun setDeviceName(name: String) = settingsRepository.setDeviceName(name.trim())
    suspend fun setClipboardMode(mode: ClipboardMode) = settingsRepository.setClipboardMode(mode)
    suspend fun setColors(colors: com.vythera.relay.data.settings.ColorPreference) = settingsRepository.setColors(colors)
    suspend fun setStayAvailable(enabled: Boolean) = settingsRepository.setStayAvailable(enabled)

    /** Keeps access to the picked folder across restarts, then makes it the place received files go. */
    suspend fun setSaveFolder(treeUri: Uri?) {
        val previous = settings.value.saveFolder
        if (treeUri != null) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settingsRepository.setSaveFolder(treeUri?.toString())
        previous?.takeIf { it != treeUri?.toString() }?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    val contactCard: ContactCard
        get() = settings.value.contactCard?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString(ContactCard.serializer(), it) }.getOrNull() } ?: ContactCard()

    suspend fun setContactCard(card: ContactCard) =
        settingsRepository.setContactCard(kotlinx.serialization.json.Json.encodeToString(ContactCard.serializer(), card))

    /** Human name of the chosen folder, e.g. "Documents / Received". */
    fun saveFolderName(treeUri: String): String? = runCatching {
        val uri = Uri.parse(treeUri)
        val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(document, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    // Inbox & clipboard offers ----------------------------------------------------------

    suspend fun deleteInboxItem(id: Long) {
        val item = database.inbox().get(id) ?: return
        item.uri?.let { uri -> withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) } } }
        database.inbox().delete(id)
    }

    fun copyToClipboard(text: String, label: String = "Relay") {
        mainHandler.post { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
    }

    fun applyClipOffer(id: String) {
        val offer = _clipOffers.value.firstOrNull { it.id == id } ?: return
        offer.text?.let(clipboardSync::applyText)
        offer.files?.let(clipboardSync::applyFiles)
        _clipOffers.update { list -> list.filterNot { it.id == id } }
    }

    fun dismissClipOffer(id: String) = _clipOffers.update { list -> list.filterNot { it.id == id } }

    // Events -------------------------------------------------------------------------

    private suspend fun onEvent(event: NodeEvent) {
        when (event) {
            is NodeEvent.TransferFinished -> onTransferFinished(event.transfer)
            is NodeEvent.TransferOffered -> if (!isForeground) {
                val transfer = event.transfer
                val category = ContentCategory.ofTransfer(transfer.kind, transfer.items)
                notifications.transferOffer(
                    transfer.id,
                    deviceName(transfer.peerId),
                    Texts.itemsSummary(context, transfer.items, category),
                    Texts.size(context, transfer.totalBytes),
                )
            }
            is NodeEvent.PairingRequested -> if (!isForeground) notifications.pairingRequest(event.session.peerName)
            is NodeEvent.TextReceived -> onTextReceived(event)
            is NodeEvent.ClipboardReceived -> onClipboardReceived(event)
            is NodeEvent.ContinueRequested -> onContinueRequested(event)
            // Trust is the whole security model, so every change to it is written down.
            is NodeEvent.DevicePaired -> {
                val device = node.trustedDevices.value[event.deviceId]
                Log.i("Relay", "Now trusting ${device?.name ?: event.deviceId.value} (${event.deviceId.value}, key ${device?.fingerprint?.take(16)})")
                RelayAnalytics.log(context, RelayAnalytics.Event.DevicePaired, device?.platform?.name?.lowercase())
            }
            is NodeEvent.DeviceForgotten ->
                Log.i("Relay", "Forgot ${event.deviceId.value}${if (event.byPeer) " (the other device asked)" else ""}")
            is NodeEvent.DeviceAppeared -> Unit
        }
    }

    private suspend fun onTransferFinished(transfer: TransferSnapshot) {
        val status = transfer.status
        val peerName = deviceName(transfer.peerId)
        val category = ContentCategory.ofTransfer(transfer.kind, transfer.items)
        database.transfers().upsert(
            TransferRecordEntity(
                id = transfer.id,
                direction = if (transfer.direction == TransferDirection.OUTGOING) "outgoing" else "incoming",
                peerId = transfer.peerId.value,
                peerName = peerName,
                category = category.name,
                title = Texts.itemsSummary(context, transfer.items, category),
                itemCount = transfer.items.size,
                totalBytes = transfer.totalBytes,
                outcome = Texts.outcome(status),
                failure = (status as? TransferStatus.Failed)?.reason?.name,
                createdAtMillis = transfer.createdAtMillis,
                finishedAtMillis = transfer.finishedAtMillis ?: System.currentTimeMillis(),
            ),
        )
        if (transfer.kind == TransferKind.CLIPBOARD) {
            if (transfer.direction == TransferDirection.INCOMING && status == TransferStatus.Completed) onClipboardFiles(transfer, peerName)
            return // clipboard content is not inbox content
        }
        RelayAnalytics.log(
            context,
            if (status == TransferStatus.Completed) RelayAnalytics.Event.TransferCompleted else RelayAnalytics.Event.TransferFailed,
            category.name.lowercase(),
        )
        if (transfer.direction == TransferDirection.INCOMING && status == TransferStatus.Completed) {
            transfer.storedFiles.forEach { file ->
                database.inbox().upsert(
                    InboxItemEntity(
                        fromDeviceId = transfer.peerId.value,
                        fromName = peerName,
                        category = ContentCategory.ofMime(file.mimeType).name,
                        title = file.name,
                        uri = file.location,
                        mimeType = file.mimeType,
                        sizeBytes = file.sizeBytes,
                        text = null,
                        transferId = transfer.id,
                        receivedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            if (settings.value.ecosystem && settings.value.clipboardMode != ClipboardMode.OFF && transfer.kind != TransferKind.FOLDER) {
                clipboardSync.applyReceived(transfer)
            }
            if (!isForeground) notifications.received(transfer.id, peerName, Texts.itemsSummary(context, transfer.items, category))
        }
    }

    private fun onClipboardFiles(transfer: TransferSnapshot, peerName: String) {
        when (settings.value.clipboardMode) {
            ClipboardMode.OFF -> Unit
            ClipboardMode.AUTOMATIC -> clipboardSync.applyFiles(transfer)
            ClipboardMode.ASK -> {
                val preview = Texts.itemsSummary(context, transfer.items, ContentCategory.ofTransfer(TransferKind.FILES, transfer.items))
                _clipOffers.update { (it + ClipOffer(transfer.id, transfer.peerId, peerName, preview, files = transfer)).takeLast(5) }
                if (!isForeground) notifications.clipboardOffer(transfer.id, peerName, preview)
            }
        }
    }

    private suspend fun onTextReceived(event: NodeEvent.TextReceived) {
        val peerName = deviceName(event.from)
        val category = ContentCategory.ofText(event.text)
        database.inbox().upsert(
            InboxItemEntity(
                fromDeviceId = event.from.value,
                fromName = peerName,
                category = category.name,
                title = event.text.lineSequence().first().take(120),
                uri = null,
                mimeType = "text/plain",
                sizeBytes = event.text.length.toLong(),
                text = event.text,
                transferId = event.messageId,
                receivedAtMillis = event.receivedAtMillis,
            ),
        )
        recordText(event.from, event.text, outgoing = false, result = SendResult.Sent)
        val open = Links.safeWebUrl(event.text)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
        notifications.received(event.messageId, peerName, event.text.take(200), open)
    }

    private fun onClipboardReceived(event: NodeEvent.ClipboardReceived) {
        val trusted = devices.value.firstOrNull { it.id == event.from }?.isTrusted == true
        if (!trusted) return
        when (settings.value.clipboardMode) {
            ClipboardMode.OFF -> Unit
            ClipboardMode.AUTOMATIC -> clipboardSync.applyText(event.clip)
            ClipboardMode.ASK -> {
                _clipOffers.update { (it + ClipOffer(event.clip.clipId, event.from, deviceName(event.from), event.clip.text, text = event.clip)).takeLast(5) }
                if (!isForeground) notifications.clipboardOffer(event.clip.clipId, deviceName(event.from), event.clip.text)
            }
        }
    }

    private suspend fun onContinueRequested(event: NodeEvent.ContinueRequested) {
        val url = Links.safeWebUrl(event.activity.uri) ?: return
        val peerName = deviceName(event.from)
        recordText(event.from, url, outgoing = false, result = SendResult.Sent, opened = true)
        if (isForeground) {
            mainHandler.post {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } else {
            // Android does not let background apps open activities; a tap on the
            // notification opens the page instead.
            notifications.continueOffer(event.requestId, peerName, url)
        }
    }

    private suspend fun recordText(peer: DeviceId, text: String, outgoing: Boolean, result: SendResult, opened: Boolean = false) {
        val now = System.currentTimeMillis()
        database.transfers().upsert(
            TransferRecordEntity(
                id = UUID.randomUUID().toString(),
                direction = if (outgoing) "outgoing" else "incoming",
                peerId = peer.value,
                peerName = deviceName(peer),
                category = ContentCategory.ofText(text).name,
                title = text.lineSequence().first().take(120),
                itemCount = 1,
                totalBytes = text.length.toLong(),
                outcome = when {
                    result is SendResult.Failed -> "failed"
                    opened -> "opened"
                    else -> "completed"
                },
                failure = (result as? SendResult.Failed)?.reason?.name,
                createdAtMillis = now,
                finishedAtMillis = now,
            ),
        )
    }

    /** Trusted devices appear as direct targets in Android's share sheet. */
    private fun publishShareTargets(trusted: List<RelayDevice>) {
        runCatching {
            val shortcuts = trusted.take(4).map { device ->
                ShortcutInfoCompat.Builder(context, device.id.value)
                    .setShortLabel(device.name.ifBlank { device.id.value.take(6) })
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND))
                    .setCategories(setOf(SHARE_CATEGORY))
                    .setLongLived(true)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    fun failureText(failure: TransferFailure, peer: DeviceId) = Texts.failure(context, failure, deviceName(peer))

    companion object {
        const val SHARE_CATEGORY = "com.vythera.relay.category.SHARE_TO_DEVICE"
    }
}
