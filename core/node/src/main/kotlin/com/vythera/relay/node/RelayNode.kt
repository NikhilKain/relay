package com.vythera.relay.node

import com.vythera.relay.discovery.Announcement
import com.vythera.relay.discovery.DeviceRegistry
import com.vythera.relay.discovery.DiscoveredDevice
import com.vythera.relay.discovery.Endpoint
import com.vythera.relay.discovery.LanDiscovery
import com.vythera.relay.protocol.Capability
import com.vythera.relay.protocol.ClipItem
import com.vythera.relay.protocol.ContinueActivity
import com.vythera.relay.protocol.DecodeResult
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceInfo
import com.vythera.relay.protocol.ErrorCode
import com.vythera.relay.protocol.Frame
import com.vythera.relay.protocol.GoodbyeReason
import com.vythera.relay.protocol.MessageCodec
import com.vythera.relay.protocol.ProtocolVersion
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.protocol.TransferKind
import com.vythera.relay.security.RelayIdentity
import com.vythera.relay.security.RelayTls
import com.vythera.relay.transfer.IncomingStorage
import com.vythera.relay.transfer.OutgoingFile
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * A Relay device on the network: the engine every client (Android, desktop) runs.
 *
 * It listens for connections, announces itself, dials peers on demand, and exposes
 * everything as flows for the UI. It contains no platform code: storage, trust
 * persistence and identity persistence are injected.
 *
 * Connections are opened lazily when there is something to do and closed after
 * [idleTimeoutMillis] without traffic, so an idle Relay holds no sockets open and sends
 * nothing but the periodic discovery announcement.
 */
class RelayNode(
    config: NodeConfig,
    private val identity: RelayIdentity,
    private val trustStore: TrustStore,
    storage: IncomingStorage,
    parentScope: CoroutineScope,
    /** Receives copied files for the clipboard. Null disables file clipboard sync on this device. */
    clipboardStorage: IncomingStorage? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default)
    private val tls = RelayTls(identity)

    @Volatile var config: NodeConfig = config
        private set

    val localId: DeviceId = identity.deviceId

    private val registry = DeviceRegistry(localId, clock)
    private val connections = ConcurrentHashMap<DeviceId, PeerConnection>()
    private val connectedPeers = MutableStateFlow<Map<DeviceId, DeviceInfo>>(emptyMap())
    private val incompatiblePeers = MutableStateFlow<Set<DeviceId>>(emptySet())
    private val dialLocks = ConcurrentHashMap<DeviceId, Mutex>()

    private val _events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NodeEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val clipboardGuard = ClipboardLoopGuard(localId, clock)
    private val pairing = PairingCoordinator(scope, { identity.fingerprint }, trustStore, _events, clock, ::connectionTo)
    @Volatile private var clipboardSyncEnabled = false
    private val clipboardStorageAvailable = clipboardStorage != null

    /**
     * Turns clipboard sync on or off for this device: whether clips from trusted devices
     * are accepted, and whether this device advertises that it takes them.
     */
    fun setClipboardSync(enabled: Boolean) {
        clipboardSyncEnabled = enabled
        val clipboard = buildSet {
            add(Capability.ClipboardPush)
            if (clipboardStorageAvailable) add(Capability.ClipboardFiles)
        }
        setCapabilities(if (enabled) config.capabilities + clipboard else config.capabilities - clipboard)
    }

    @Volatile private var ecosystemEnabled = false

    /**
     * Ecosystem mode: trusted devices act as one. Whatever they send, including drag and
     * drop, arrives without an Accept prompt. Pairing stays the gate, so only devices the
     * user confirmed on both screens ever get this far.
     */
    fun setEcosystem(enabled: Boolean) {
        ecosystemEnabled = enabled
        if (enabled) scope.launch { transfersCoordinator.acceptAllWaiting() }
    }

    private val transfersCoordinator = TransferCoordinator(
        scope = scope,
        storageFor = { kind -> if (kind == TransferKind.CLIPBOARD && clipboardStorage != null) clipboardStorage else storage },
        acceptsClipboard = { clipboardStorage != null && clipboardSyncEnabled },
        acceptsTrustedWithoutAsking = { ecosystemEnabled },
        trustStore = trustStore,
        events = _events,
        clock = clock,
        connect = ::connectionTo,
    )

    private var runtime: Job? = null
    private var serverSocket: SSLServerSocket? = null
    private var discovery: LanDiscovery? = null

    /** Nearby, connected and trusted devices, merged and sorted for display. */
    val devices: StateFlow<List<RelayDevice>> =
        combine(registry.devices, trustStore.devices, connectedPeers, incompatiblePeers) { nearby, trusted, connected, incompatible ->
            mergeDevices(nearby, trusted, connected, incompatible)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The trust list itself: who this device has paired with, and with which key. */
    val trustedDevices: StateFlow<Map<DeviceId, TrustedDevice>> get() = trustStore.devices

    val transfers: StateFlow<List<TransferSnapshot>> = transfersCoordinator.transfers

    val pairingSessions: StateFlow<List<PairingSession>> =
        pairing.sessions.map { it.values.sortedBy(PairingSession::startedAtMillis) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Lifecycle ------------------------------------------------------------------

    /** Starts listening and discovery. Idempotent. Networking failures degrade rather than throw. */
    @Synchronized
    fun start() {
        if (runtime != null) return
        val job = SupervisorJob(scope.coroutineContext[Job])
        runtime = job
        val runtimeScope = CoroutineScope(scope.coroutineContext + job)

        val server = openServerSocket()
        serverSocket = server
        runtimeScope.launch(Dispatchers.IO) { acceptLoop(server, runtimeScope) }

        val lan = LanDiscovery(
            registry,
            config.discoveryPort,
            config.discoveryTargets ?: LanDiscovery.defaultTargets(config.discoveryPort),
            clock = clock,
        )
        try {
            lan.start(runtimeScope, announcement())
            discovery = lan
        } catch (e: IOException) {
            // Another app holds the discovery port. Direct connections from peers that
            // can see us still work, and so does everything with already-known devices.
            log("Discovery unavailable: ${e.message}")
        }

        runtimeScope.launch { watchForAppearingDevices() }
        _isRunning.value = true
    }

    @Synchronized
    fun stop() {
        val job = runtime ?: return
        runtime = null
        _isRunning.value = false
        val open = connections.values.toList()
        scope.launch {
            open.forEach { connection ->
                withTimeoutOrNull(500) { runCatching { connection.send(RelayMessage.Goodbye(GoodbyeReason.SHUTTING_DOWN)) } }
                connection.close()
            }
        }
        discovery?.stop()
        discovery = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        job.cancel()
    }

    /** Releases everything. The node cannot be restarted afterwards. */
    fun close() {
        stop()
        scope.cancel()
    }

    /** Ask devices to announce themselves now, e.g. when the app returns to the foreground or Wi-Fi changes. */
    fun refresh() {
        discovery?.refresh()
    }

    fun rename(name: String) {
        config = config.copy(deviceName = name.trim().take(DeviceInfo.MAX_NAME_LENGTH))
        discovery?.update(announcement())
    }

    fun setCapabilities(capabilities: Set<Capability>) {
        if (capabilities == config.capabilities) return
        config = config.copy(capabilities = capabilities)
        scope.launch {
            connections.values.forEach { runCatching { it.send(RelayMessage.Capabilities(capabilities)) } }
        }
    }

    // Pairing & trust ---------------------------------------------------------------

    suspend fun pair(deviceId: DeviceId): String {
        val name = devices.value.firstOrNull { it.id == deviceId }?.name ?: ""
        return pairing.start(deviceId, name)
    }

    suspend fun respondToPairing(requestId: String, accept: Boolean, autoAcceptTransfers: Boolean = false) =
        pairing.respond(requestId, accept, autoAcceptTransfers)

    suspend fun cancelPairing(requestId: String) = pairing.cancel(requestId)

    fun dismissPairing(requestId: String) = pairing.dismiss(requestId)

    suspend fun setAutoAcceptTransfers(deviceId: DeviceId, enabled: Boolean) =
        trustStore.update(deviceId) { it.copy(autoAcceptTransfers = enabled) }

    /** Removes trust locally and tells the device, if it can be reached right now. */
    suspend fun forget(deviceId: DeviceId) {
        connections[deviceId]?.let { runCatching { it.send(RelayMessage.Unpair) } }
        trustStore.remove(deviceId)
        _events.emit(NodeEvent.DeviceForgotten(deviceId, byPeer = false))
    }

    // Content ------------------------------------------------------------------------

    fun sendFiles(deviceId: DeviceId, files: List<OutgoingFile>, kind: TransferKind = TransferKind.FILES): String =
        transfersCoordinator.send(deviceId, files, kind)

    suspend fun acceptTransfer(transferId: String) = transfersCoordinator.accept(transferId)
    suspend fun declineTransfer(transferId: String) = transfersCoordinator.decline(transferId)
    suspend fun pauseTransfer(transferId: String) = transfersCoordinator.pause(transferId)
    suspend fun resumeTransfer(transferId: String) = transfersCoordinator.resume(transferId)
    suspend fun cancelTransfer(transferId: String) = transfersCoordinator.cancel(transferId)
    fun retryTransfer(transferId: String) = transfersCoordinator.retry(transferId)
    fun dismissTransfer(transferId: String) = transfersCoordinator.dismiss(transferId)

    suspend fun sendText(deviceId: DeviceId, text: String): SendResult {
        if (text.isEmpty() || text.length > RelayMessage.MAX_TEXT_LENGTH) return SendResult.Failed(TransferFailure.PROTOCOL_ERROR)
        return sendToTrusted(deviceId, RelayMessage.Text(UUID.randomUUID().toString(), text))
    }

    suspend fun continueOn(deviceId: DeviceId, activity: ContinueActivity): SendResult =
        sendToTrusted(deviceId, RelayMessage.Continue(UUID.randomUUID().toString(), activity))

    /**
     * Reports a change of the local clipboard. Sends it to [targets] unless it is an echo
     * of a clip that just arrived from another device. Returns the devices it was sent to.
     */
    suspend fun publishClipboard(text: String, targets: Collection<DeviceId>, connectIfNeeded: Boolean = true): List<DeviceId> {
        val clip = clipboardGuard.onLocalChange(text) ?: return emptyList()
        return targets.filter { sendToTrusted(it, RelayMessage.ClipboardUpdate(clip), connectIfNeeded) == SendResult.Sent }
    }

    /**
     * Sends copied files (a photo, a video, anything) to the clipboards of [targets].
     * Devices that have said they cannot take clipboard files are skipped; devices not
     * connected yet are tried, since their capabilities are only known after connecting.
     * Returns the transfer ids started.
     */
    fun publishClipboardFiles(files: List<OutgoingFile>, targets: Collection<DeviceId>): List<String> {
        if (files.isEmpty()) return emptyList()
        val known = devices.value.associateBy { it.id }
        return targets
            .filter { id -> trustStore.devices.value.containsKey(id) }
            .filter { id -> known[id]?.capabilities?.let { it.isEmpty() || Capability.ClipboardFiles in it } ?: true }
            .map { id -> transfersCoordinator.send(id, files, TransferKind.CLIPBOARD) }
    }

    /** Call after writing a received clip to the local clipboard, so the write is not sent back. */
    fun markClipboardApplied(clip: ClipItem) = clipboardGuard.markApplied(clip.text)

    private suspend fun sendToTrusted(deviceId: DeviceId, message: RelayMessage, connectIfNeeded: Boolean = true): SendResult {
        if (trustStore.devices.value[deviceId] == null) return SendResult.Failed(TransferFailure.NOT_TRUSTED)
        val connection = try {
            if (connectIfNeeded) connectionTo(deviceId) else connections[deviceId]?.takeIf { it.isOpen } ?: throw UnreachableException()
        } catch (e: IncompatibleVersionException) {
            return SendResult.Failed(TransferFailure.INCOMPATIBLE_VERSION)
        } catch (e: IOException) {
            return SendResult.Failed(TransferFailure.PEER_UNAVAILABLE)
        }
        return try {
            connection.send(message)
            SendResult.Sent
        } catch (e: IOException) {
            SendResult.Failed(TransferFailure.CONNECTION_LOST)
        }
    }

    // Connections --------------------------------------------------------------------

    /** Returns an open, handshaken connection to [deviceId], dialing if needed. */
    internal suspend fun connectionTo(deviceId: DeviceId): PeerConnection {
        connections[deviceId]?.takeIf { it.isOpen }?.let { return it }
        return dialLocks.getOrPut(deviceId) { Mutex() }.withLock {
            connections[deviceId]?.takeIf { it.isOpen }?.let { return@withLock it }
            if (runtime == null) throw UnreachableException("Relay is not running")
            val device = registry.devices.value[deviceId] ?: throw UnreachableException()
            var lastError: IOException = UnreachableException()
            for (endpoint in device.endpoints) {
                try {
                    return@withLock dial(device, endpoint)
                } catch (e: IncompatibleVersionException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                    registry.demoteEndpoint(deviceId, endpoint)
                }
            }
            throw if (lastError is IdentityMismatchException) lastError else UnreachableException()
        }
    }

    private suspend fun dial(device: DiscoveredDevice, endpoint: Endpoint): PeerConnection {
        val socket = withContext(Dispatchers.IO) {
            val plain = Socket()
            try {
                plain.tcpNoDelay = true
                plain.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MILLIS)
                tls.wrapClient(plain)
            } catch (e: IOException) {
                runCatching { plain.close() }
                throw e
            }
        }
        val connection = PeerConnection(socket, isInbound = false, clock)
        try {
            connection.handshake(hello(), config.protocol, expected = device.fingerprint)
        } catch (e: IncompatibleVersionException) {
            incompatiblePeers.update { it + device.id }
            connection.close()
            throw e
        } catch (e: Exception) {
            connection.close()
            throw e as? IOException ?: IOException(e)
        }
        return register(connection)
    }

    private suspend fun acceptLoop(server: SSLServerSocket, runtimeScope: CoroutineScope) {
        while (runtimeScope.isActive && !server.isClosed) {
            val socket = try {
                server.accept() as SSLSocket
            } catch (e: IOException) {
                if (server.isClosed) return
                continue
            }
            runtimeScope.launch {
                tls.configureAccepted(socket)
                socket.tcpNoDelay = true
                val connection = PeerConnection(socket, isInbound = true, clock)
                try {
                    connection.handshake(hello(), config.protocol, expected = null)
                    val peer = connection.peer
                    registry.onDirectContact(
                        peer.id, peer.name, peer.type, peer.platform, connection.fingerprint, peer.protocol,
                        connection.peerListenPort?.let { Endpoint(connection.remoteHost, it) },
                    )
                    register(connection)
                } catch (e: IncompatibleVersionException) {
                    e.peer?.let { peer -> incompatiblePeers.update { it + peer.id } }
                    connection.close()
                } catch (e: Exception) {
                    // Port scanners, non-Relay clients and failed handshakes end here quietly.
                    connection.close()
                }
            }
        }
    }

    /**
     * Makes [connection] the active connection to its peer, resolving the case where both
     * devices dialed each other at the same moment: both sides keep the connection that
     * was dialed by the device with the smaller id, so they always agree.
     */
    private suspend fun register(connection: PeerConnection): PeerConnection {
        val peerId = connection.peer.id
        val kept: PeerConnection
        val dropped: PeerConnection?
        synchronized(connections) {
            val existing = connections[peerId]?.takeIf { it.isOpen }
            if (existing == null || prefer(connection, over = existing)) {
                connections[peerId] = connection
                kept = connection
                dropped = existing
            } else {
                kept = existing
                dropped = connection
            }
        }
        if (dropped != null) {
            runCatching { dropped.send(RelayMessage.Goodbye(GoodbyeReason.DUPLICATE_CONNECTION)) }
            dropped.close()
        }
        if (kept !== connection) return kept

        incompatiblePeers.update { it - peerId }
        connectedPeers.update { it + (peerId to connection.peer) }
        registry.markSeen(peerId)
        if (trustStore.devices.value.containsKey(peerId)) {
            trustStore.update(peerId) { it.copy(name = connection.peer.name, lastConnectedMillis = clock()) }
        }
        val runtimeScope = runtime?.let { CoroutineScope(scope.coroutineContext + it) } ?: scope
        runtimeScope.launch { serve(connection) }
        runtimeScope.launch { keepAlive(connection) }
        transfersCoordinator.onPeerAvailable(peerId)
        log("Connected to ${connection.peer.name} (${if (connection.isInbound) "inbound" else "outbound"})")
        return connection
    }

    private fun prefer(candidate: PeerConnection, over: PeerConnection): Boolean {
        fun dialer(c: PeerConnection) = if (c.isInbound) c.peer.id.value else localId.value
        val a = dialer(candidate)
        val b = dialer(over)
        // Same dialer twice means the peer reconnected; the old socket is probably dead.
        return if (a == b) true else a < b
    }

    private suspend fun serve(connection: PeerConnection) {
        try {
            connection.readFrames { frame -> onFrame(connection, frame) }
        } catch (e: Exception) {
            log("Connection to ${connection.peer.name} ended: ${e.stackTraceToString().take(1500)}")
        } finally {
            // Cleanup must finish even when the node is stopping and this coroutine is cancelled.
            withContext(NonCancellable) {
                connection.close()
                onClosed(connection)
            }
        }
    }

    private suspend fun onClosed(connection: PeerConnection) {
        val peerId = connection.peer.id
        val wasActive = connections.remove(peerId, connection)
        log("Connection to ${connection.peer.name} closed (active=$wasActive)")
        if (wasActive) connectedPeers.update { it - peerId }
        pairing.onConnectionClosed(connection)
        transfersCoordinator.onConnectionClosed(connection)
    }

    private suspend fun keepAlive(connection: PeerConnection) {
        while (connection.isOpen) {
            delay(PeerConnection.PING_INTERVAL_MILLIS)
            if (!connection.isOpen) return
            val busy = transfers.value.any { it.peerId == connection.peer.id && !it.status.isFinished } ||
                pairingSessions.value.any { it.peerId == connection.peer.id && !it.stage.isFinished }
            if (!busy && clock() - connection.lastActivityMillis > idleTimeoutMillis) {
                log("Closing idle connection to ${connection.peer.name}")
                runCatching { connection.send(RelayMessage.Goodbye(GoodbyeReason.IDLE)) }
                connection.close()
                return
            }
            try {
                connection.send(RelayMessage.Ping(clock()))
            } catch (e: IOException) {
                return
            }
        }
    }

    private fun isTrusted(connection: PeerConnection): Boolean =
        trustStore.devices.value[connection.peer.id]?.fingerprint == connection.fingerprint.hex

    private suspend fun onFrame(connection: PeerConnection, frame: Frame) {
        when (frame) {
            is Frame.Data -> if (isTrusted(connection)) transfersCoordinator.onChunk(connection, frame.chunk)
            is Frame.Control -> when (val decoded = MessageCodec.decode(frame.payload)) {
                is DecodeResult.Message -> onMessage(connection, decoded.message)
                is DecodeResult.Unknown -> if (isTrusted(connection)) {
                    connection.send(RelayMessage.Error(ErrorCode.UNSUPPORTED_MESSAGE, relatesTo = decoded.type))
                }
                is DecodeResult.Malformed -> connection.send(RelayMessage.Error(ErrorCode.MALFORMED_MESSAGE, decoded.reason))
            }
        }
    }

    private suspend fun onMessage(connection: PeerConnection, message: RelayMessage) {
        val peerId = connection.peer.id
        // Messages any connected device may send, trusted or not.
        when (message) {
            is RelayMessage.Ping -> return connection.send(RelayMessage.Pong(message.nonce))
            is RelayMessage.Pong, is RelayMessage.Hello -> return
            is RelayMessage.Goodbye -> return connection.close()
            is RelayMessage.PairRequest, is RelayMessage.PairNonce, is RelayMessage.PairReveal,
            is RelayMessage.PairAccept, is RelayMessage.PairDecline -> return pairing.onMessage(connection, message)
            is RelayMessage.Error -> {
                pairing.onError(connection, message)
                message.relatesTo?.let { transfersCoordinator.onMessage(connection, it, message) }
                return
            }
            else -> Unit
        }

        if (!isTrusted(connection)) {
            val relatesTo = (message as? RelayMessage.TransferRequest)?.transferId
            connection.send(RelayMessage.Error(ErrorCode.NOT_TRUSTED, relatesTo = relatesTo))
            return
        }
        registry.markSeen(peerId)

        when (message) {
            is RelayMessage.Capabilities -> {
                connection.updatePeer(connection.peer.copy(capabilities = message.capabilities))
                connectedPeers.update { it + (peerId to connection.peer) }
            }
            is RelayMessage.Unpair -> {
                trustStore.remove(peerId)
                _events.emit(NodeEvent.DeviceForgotten(peerId, byPeer = true))
            }
            is RelayMessage.TransferRequest -> transfersCoordinator.onRequest(connection, message)
            is RelayMessage.TransferAccept -> transfersCoordinator.onMessage(connection, message.transferId, message)
            is RelayMessage.TransferDecline -> transfersCoordinator.onMessage(connection, message.transferId, message)
            is RelayMessage.TransferFileComplete -> transfersCoordinator.onMessage(connection, message.transferId, message)
            is RelayMessage.TransferComplete -> transfersCoordinator.onMessage(connection, message.transferId, message)
            is RelayMessage.TransferPause -> transfersCoordinator.onMessage(connection, message.transferId, message)
            is RelayMessage.TransferResume -> transfersCoordinator.onMessage(connection, message.transferId, message)
            is RelayMessage.TransferCancel -> transfersCoordinator.onMessage(connection, message.transferId, message)
            is RelayMessage.Text -> _events.emit(NodeEvent.TextReceived(peerId, message.messageId, message.text, clock()))
            is RelayMessage.ClipboardUpdate -> if (clipboardGuard.acceptRemote(message.clip)) {
                _events.emit(NodeEvent.ClipboardReceived(peerId, message.clip))
            }
            is RelayMessage.Continue -> {
                val kind = Capability.forContinue(message.activity.kind)
                if (kind in config.capabilities) {
                    _events.emit(NodeEvent.ContinueRequested(peerId, message.requestId, message.activity))
                } else {
                    connection.send(RelayMessage.Error(ErrorCode.UNSUPPORTED_MESSAGE, relatesTo = message.requestId))
                }
            }
            else -> Unit
        }
    }

    // Helpers ------------------------------------------------------------------------

    private fun openServerSocket(): SSLServerSocket =
        try {
            tls.createServerSocket(config.listenPort)
        } catch (e: BindException) {
            tls.createServerSocket(0)
        }

    private fun listenPort(): Int = serverSocket?.localPort ?: config.listenPort

    private fun localInfo() = DeviceInfo(
        id = localId,
        name = config.deviceName,
        type = config.deviceType,
        platform = config.platform,
        appVersion = config.appVersion,
        protocol = config.protocol,
        capabilities = config.capabilities,
    )

    private fun hello() = RelayMessage.Hello(localInfo(), listenPort())

    private fun announcement() = Announcement(
        kind = Announcement.Kind.HELLO,
        id = localId,
        name = config.deviceName,
        type = config.deviceType,
        platform = config.platform,
        fingerprint = identity.fingerprint.hex,
        port = listenPort(),
        protocol = config.protocol,
    )

    private suspend fun watchForAppearingDevices() {
        var known = emptySet<DeviceId>()
        registry.devices.collect { current ->
            val appeared = current.keys - known
            known = current.keys
            for (id in appeared) {
                val device = current.getValue(id)
                if (ProtocolVersion.negotiate(config.protocol, device.protocol) == null) {
                    incompatiblePeers.update { it + id }
                } else {
                    transfersCoordinator.onPeerAvailable(id)
                }
                _events.emit(NodeEvent.DeviceAppeared(id))
            }
        }
    }

    private fun mergeDevices(
        nearby: Map<DeviceId, DiscoveredDevice>,
        trusted: Map<DeviceId, TrustedDevice>,
        connected: Map<DeviceId, DeviceInfo>,
        incompatible: Set<DeviceId>,
    ): List<RelayDevice> {
        val ids = nearby.keys + trusted.keys + connected.keys
        return ids.map { id ->
            val seen = nearby[id]
            val trust = trusted[id]
            val live = connected[id]
            RelayDevice(
                id = id,
                name = live?.name ?: seen?.name ?: trust?.name ?: "",
                type = live?.type ?: seen?.type ?: trust!!.type,
                platform = live?.platform ?: seen?.platform ?: trust!!.platform,
                presence = when {
                    live != null -> Presence.CONNECTED
                    seen != null -> Presence.NEARBY
                    else -> Presence.OFFLINE
                },
                isTrusted = trust != null,
                autoAcceptTransfers = trust?.autoAcceptTransfers == true,
                capabilities = live?.capabilities.orEmpty(),
                isCompatible = id !in incompatible,
                lastSeenMillis = seen?.lastSeenMillis ?: trust?.lastConnectedMillis,
            )
        }.sortedWith(
            compareBy<RelayDevice>({ it.presence == Presence.OFFLINE }, { !it.isTrusted }, { it.name.lowercase() }),
        )
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 60_000L
        private const val CONNECT_TIMEOUT_MILLIS = 3_000
    }
}
