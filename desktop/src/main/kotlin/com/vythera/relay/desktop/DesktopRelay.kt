package com.vythera.relay.desktop

import com.vythera.relay.node.JsonFileTrustStore
import com.vythera.relay.node.NodeConfig
import com.vythera.relay.node.NodeEvent
import com.vythera.relay.node.PairingSession
import com.vythera.relay.node.RelayDevice
import com.vythera.relay.node.RelayNode
import com.vythera.relay.node.SendResult
import com.vythera.relay.protocol.ContinueActivity
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.TransferItem
import com.vythera.relay.protocol.TransferKind
import com.vythera.relay.transfer.DirectoryStorage
import com.vythera.relay.transfer.IncomingStorage
import com.vythera.relay.transfer.OutgoingFile
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferSnapshot
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLConnection
import java.util.UUID

/** One line of history, shown in Recent activity and the Inbox. */
@Serializable
data class HistoryEntry(
    val id: String,
    val incoming: Boolean,
    val peerId: String,
    val peerName: String,
    val title: String,
    /** "photos", "videos", "files", "folder", "text", "link", "clipboard". */
    val category: String,
    /** File path for received files, the text itself for messages and links. */
    val detail: String? = null,
    val timeMillis: Long,
    /** "completed", "opened", "failed", "declined", "cancelled". */
    val outcome: String,
)

/** Something the desktop shell should tell the user about outside the window (tray balloon). */
data class DesktopNotice(val title: String, val message: String)

/**
 * The desktop side of Relay: owns the engine and turns its events into history,
 * notifications, opened links and clipboard writes. The UI and the tray talk only to this.
 */
class DesktopRelay {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = DesktopSettingsStore(File(DesktopPaths.data, "settings.json"))
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    private val storage = SwitchingStorage(DirectoryStorage(inboxFolder()))
    val node = RelayNode(
        config = NodeConfig(
            deviceName = settings.value.deviceName.ifBlank { DesktopPaths.defaultDeviceName() },
            deviceType = DeviceType.DESKTOP,
            platform = DesktopPaths.platform,
            appVersion = VERSION,
        ),
        identity = FileIdentityStore(File(DesktopPaths.data, "identity.json")).loadOrCreate(),
        trustStore = JsonFileTrustStore(File(DesktopPaths.data, "trusted.json")),
        storage = storage,
        parentScope = scope,
        clipboardStorage = DirectoryStorage(File(DesktopPaths.cache, "clipboard")),
        log = { DesktopLog.write("relay: $it") },
    )
    private val clipboard = DesktopClipboard(node, scope, File(DesktopPaths.cache, "clipboard-out").apply { mkdirs() }) { DesktopLog.write(it) }
    private val analytics = DesktopAnalytics(scope, clientId = { settings.value.analyticsClientId }, log = { DesktopLog.write(it) })

    val devices: StateFlow<List<RelayDevice>> = node.devices
    val transfers: StateFlow<List<TransferSnapshot>> = node.transfers
    val pairing: StateFlow<List<PairingSession>> = node.pairingSessions

    private val historyFile = File(DesktopPaths.data, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _notices = MutableSharedFlow<DesktopNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<DesktopNotice> = _notices.asSharedFlow()

    val deviceName: String get() = node.config.deviceName

    fun start() {
        node.start()
        clipboard.enabled = settings.value.clipboard == DesktopClipboardMode.AUTOMATIC
        applyEcosystem(settings.value.ecosystem)
        analytics.enabled = settings.value.analytics
        clipboard.start()
        scope.launch { node.events.collect(::onEvent) }
    }

    fun stop() {
        node.close()
    }

    fun name(id: DeviceId): String = devices.value.firstOrNull { it.id == id }?.name?.ifBlank { null } ?: "Device"

    // Actions ----------------------------------------------------------------------------

    fun sendFiles(to: DeviceId, files: List<File>) {
        val regular = files.filter { it.isFile }
        val folders = files.filter { it.isDirectory }
        if (regular.isNotEmpty()) node.sendFiles(to, regular.map(::LocalFile), kindOf(regular))
        folders.forEach { folder ->
            val contents = folder.walkTopDown().filter { it.isFile }.map { file ->
                val relative = file.parentFile.relativeTo(folder.parentFile).invariantSeparatorsPath
                LocalFile(file, relative)
            }.toList()
            if (contents.isNotEmpty()) node.sendFiles(to, contents, TransferKind.FOLDER)
        }
    }

    suspend fun sendText(to: DeviceId, text: String): SendResult {
        val result = node.sendText(to, text)
        record(false, to, text.lineSequence().first().take(120), if (isLink(text)) "link" else "text", text, if (result == SendResult.Sent) "completed" else "failed")
        return result
    }

    suspend fun continueOn(to: DeviceId, url: String): SendResult {
        if (!isLink(url)) return sendText(to, url)
        val result = node.continueOn(to, ContinueActivity(ContinueActivity.KIND_WEB_PAGE, url.trim()))
        record(false, to, url.trim(), "link", url.trim(), if (result == SendResult.Sent) "opened" else "failed")
        return result
    }

    /** Sends whatever is on this computer's clipboard to every trusted device around. */
    suspend fun sendClipboard(): Int {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrNull()
        val targets = devices.value.filter { it.isTrusted && it.isReachable }.map { it.id }
        if (text.isNullOrBlank() || targets.isEmpty()) return 0
        return targets.count { node.sendText(it, text) == SendResult.Sent }
    }

    suspend fun pair(id: DeviceId) = node.pair(id)
    suspend fun respondToPairing(requestId: String, accept: Boolean, alwaysAllow: Boolean) = node.respondToPairing(requestId, accept, alwaysAllow)
    suspend fun cancelPairing(requestId: String) = node.cancelPairing(requestId)
    fun dismissPairing(requestId: String) = node.dismissPairing(requestId)

    suspend fun accept(transferId: String, alwaysAllowFrom: DeviceId?) {
        alwaysAllowFrom?.let { node.setAutoAcceptTransfers(it, true) }
        node.acceptTransfer(transferId)
    }
    suspend fun decline(transferId: String) = node.declineTransfer(transferId)
    suspend fun pause(transferId: String) = node.pauseTransfer(transferId)
    suspend fun resume(transferId: String) = node.resumeTransfer(transferId)
    suspend fun cancel(transferId: String) = node.cancelTransfer(transferId)
    fun retry(transferId: String) = node.retryTransfer(transferId)
    fun dismiss(transferId: String) = node.dismissTransfer(transferId)
    suspend fun forget(id: DeviceId) = node.forget(id)
    suspend fun setAutoAccept(id: DeviceId, enabled: Boolean) = node.setAutoAcceptTransfers(id, enabled)

    fun rename(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        node.rename(clean)
        updateSettings { it.copy(deviceName = clean) }
    }

    fun setSaveFolder(folder: File?) {
        updateSettings { it.copy(saveFolder = folder?.absolutePath.orEmpty()) }
        storage.current = DirectoryStorage(inboxFolder())
    }

    fun setClipboard(mode: DesktopClipboardMode) {
        updateSettings { it.copy(clipboard = mode) }
        clipboard.enabled = mode == DesktopClipboardMode.AUTOMATIC
    }

    fun setEcosystem(enabled: Boolean) {
        updateSettings { it.copy(ecosystem = enabled) }
        applyEcosystem(enabled)
    }

    private fun applyEcosystem(enabled: Boolean) {
        node.setEcosystem(enabled)
        clipboard.pasteReceivedFiles = enabled
    }

    fun setDropShelf(enabled: Boolean) = updateSettings { it.copy(dropShelf = enabled) }

    /** Statistics are opt-in; turning them off forgets the installation id as well. */
    fun setAnalytics(enabled: Boolean) {
        updateSettings {
            it.copy(
                analytics = enabled,
                analyticsClientId = if (enabled) it.analyticsClientId.ifBlank { DesktopAnalytics.newClientId() } else "",
            )
        }
        analytics.enabled = enabled
    }

    fun setStartWithComputer(enabled: Boolean) {
        if (Autostart.set(enabled)) updateSettings { it.copy(startWithComputer = enabled) }
    }

    fun inboxFolder(): File = settings.value.saveFolder.takeIf { it.isNotBlank() }?.let(::File) ?: DesktopPaths.defaultInbox

    fun open(file: File) {
        runCatching { Desktop.getDesktop().open(file) }
    }

    fun reveal(file: File) {
        runCatching {
            if (DesktopPaths.platform == com.vythera.relay.protocol.Platform.WINDOWS) {
                ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            } else {
                Desktop.getDesktop().open(file.parentFile ?: file)
            }
        }
    }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    fun deleteHistory(id: String) {
        _history.update { list -> list.filterNot { it.id == id } }
        saveHistory()
    }

    // Events -----------------------------------------------------------------------------

    private suspend fun onEvent(event: NodeEvent) {
        when (event) {
            is NodeEvent.TransferFinished -> onTransferFinished(event.transfer)
            is NodeEvent.TextReceived -> {
                val link = isLink(event.text)
                record(true, event.from, event.text.lineSequence().first().take(120), if (link) "link" else "text", event.text, "completed")
                _notices.tryEmit(DesktopNotice(name(event.from), event.text.take(200)))
            }
            is NodeEvent.ContinueRequested -> {
                val url = event.activity.uri.trim()
                // Only plain web pages are opened on request from another device.
                if (isLink(url)) {
                    record(true, event.from, url, "link", url, "opened")
                    withContext(Dispatchers.IO) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
                }
            }
            is NodeEvent.PairingRequested -> {
                DesktopLog.write("pairing: ${event.session.peerName} (${event.session.peerId.value}) asked to connect")
                _notices.tryEmit(DesktopNotice("${event.session.peerName} wants to connect", "Open Relay to check the shapes."))
            }
            // Trust is the whole security model, so every change to it is written down.
            is NodeEvent.DevicePaired -> {
                val device = node.trustedDevices.value[event.deviceId]
                analytics.log(DesktopAnalytics.Event.DevicePaired, device?.platform?.name?.lowercase())
                DesktopLog.write("pairing: now trusting ${device?.name ?: event.deviceId.value} (${event.deviceId.value}, key ${device?.fingerprint?.take(16)})")
            }
            is NodeEvent.DeviceForgotten ->
                DesktopLog.write("pairing: forgot ${event.deviceId.value}${if (event.byPeer) " (the other device asked)" else ""}")
            is NodeEvent.TransferOffered -> _notices.tryEmit(
                DesktopNotice("${name(event.transfer.peerId)} wants to send", summary(event.transfer.items)),
            )
            else -> Unit
        }
    }

    private fun onTransferFinished(transfer: TransferSnapshot) {
        if (transfer.kind == TransferKind.CLIPBOARD) return // shown by the clipboard, not the inbox
        val incoming = transfer.direction == TransferDirection.INCOMING
        val outcome = when (transfer.status) {
            TransferStatus.Completed -> "completed"
            TransferStatus.Declined -> "declined"
            is TransferStatus.Cancelled -> "cancelled"
            else -> "failed"
        }
        analytics.log(
            if (outcome == "completed") DesktopAnalytics.Event.TransferCompleted else DesktopAnalytics.Event.TransferFailed,
            categoryOf(transfer),
        )
        val detail = transfer.storedFiles.singleOrNull()?.location ?: transfer.storedFiles.firstOrNull()?.location?.let { File(it).parent }
        _history.update { list ->
            (listOf(
                HistoryEntry(transfer.id, incoming, transfer.peerId.value, name(transfer.peerId), summary(transfer.items), categoryOf(transfer), detail, System.currentTimeMillis(), outcome),
            ) + list.filterNot { it.id == transfer.id }).take(MAX_HISTORY)
        }
        saveHistory()
        if (incoming && transfer.status == TransferStatus.Completed) {
            _notices.tryEmit(DesktopNotice("From ${name(transfer.peerId)}", "${summary(transfer.items)} saved to ${inboxFolder().name}"))
        }
    }

    private fun record(incoming: Boolean, peer: DeviceId, title: String, category: String, detail: String?, outcome: String) {
        _history.update { list ->
            (listOf(HistoryEntry(UUID.randomUUID().toString(), incoming, peer.value, name(peer), title, category, detail, System.currentTimeMillis(), outcome)) + list).take(MAX_HISTORY)
        }
        saveHistory()
    }

    private fun loadHistory(): List<HistoryEntry> =
        if (!historyFile.isFile) emptyList() else runCatching { json.decodeFromString(ListSerializer(HistoryEntry.serializer()), historyFile.readText()) }.getOrDefault(emptyList())

    private fun saveHistory() {
        scope.launch(Dispatchers.IO) {
            runCatching { historyFile.writeText(json.encodeToString(ListSerializer(HistoryEntry.serializer()), _history.value)) }
        }
    }

    private fun updateSettings(transform: (DesktopSettings) -> DesktopSettings) {
        _settings.update(transform)
        settingsStore.save(_settings.value)
    }

    private class LocalFile(private val file: File, override val relativePath: String = "") : OutgoingFile {
        override val name: String = file.name
        override val sizeBytes: Long = file.length()
        override val mimeType: String = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        override val lastModifiedMillis: Long = file.lastModified()
        override fun open(): InputStream = file.inputStream()
    }

    /** Received files go to the folder chosen now, even if it changes while Relay runs. */
    private class SwitchingStorage(@Volatile var current: IncomingStorage) : IncomingStorage {
        override fun availableBytes() = current.availableBytes()
        override fun findCommitted(transferId: String, item: TransferItem) = current.findCommitted(transferId, item)
        override fun openPartial(transferId: String, item: TransferItem) = current.openPartial(transferId, item)
        override fun discardPartials(transferId: String) = current.discardPartials(transferId)
    }

    companion object {
        const val VERSION = "0.1.0"
        private const val MAX_HISTORY = 200

        fun isLink(text: String) = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE).matches(text.trim())

        fun summary(items: List<TransferItem>): String = when {
            items.size == 1 -> items.first().name
            items.all { it.mimeType.startsWith("image/") } -> "${items.size} photos"
            items.any { it.relativePath.isNotEmpty() } -> items.first().relativePath.substringBefore('/')
            else -> "${items.size} files"
        }

        private fun categoryOf(transfer: TransferSnapshot): String = when {
            transfer.kind == TransferKind.FOLDER || transfer.items.any { it.relativePath.isNotEmpty() } -> "folder"
            transfer.items.all { it.mimeType.startsWith("video/") } -> "videos"
            transfer.items.all { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") } -> "photos"
            else -> "files"
        }

        private fun kindOf(files: List<File>): TransferKind {
            val mimes = files.map { URLConnection.guessContentTypeFromName(it.name).orEmpty() }
            return if (mimes.all { it.startsWith("image/") || it.startsWith("video/") }) TransferKind.MEDIA else TransferKind.FILES
        }
    }
}
