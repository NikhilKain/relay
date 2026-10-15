package com.vythera.relay.node

import com.vythera.relay.protocol.DataChunk
import com.vythera.relay.protocol.DeclineReason
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.ErrorCode
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.protocol.TransferKind
import com.vythera.relay.transfer.IncomingStorage
import com.vythera.relay.transfer.IncomingTransfer
import com.vythera.relay.transfer.OutgoingFile
import com.vythera.relay.transfer.OutgoingTransfer
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferSnapshot
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every transfer of the node: queueing per peer, routing protocol messages to the
 * right transfer, and retrying transfers that failed only because the peer was away.
 *
 * Every message and chunk is checked against the peer the transfer belongs to, so a
 * trusted device cannot interfere with a transfer to a different device by guessing
 * its id.
 */
internal class TransferCoordinator(
    private val scope: CoroutineScope,
    /** Where each kind of transfer is received. Clipboard transfers go to a private cache, not the user's files. */
    private val storageFor: (TransferKind) -> IncomingStorage,
    /** Whether copied files from trusted devices may land on this device's clipboard right now. */
    private val acceptsClipboard: () -> Boolean,
    /** Ecosystem mode: every trusted device sends without asking, as if it were this device. */
    private val acceptsTrustedWithoutAsking: () -> Boolean,
    private val trustStore: TrustStore,
    private val events: MutableSharedFlow<NodeEvent>,
    private val clock: () -> Long,
    private val connect: suspend (DeviceId) -> PeerConnection,
) {
    private class Outgoing(val transfer: OutgoingTransfer) {
        @Volatile var job: Job? = null
        @Volatile var link: PeerConnection? = null
        @Volatile var automaticRetries = 0
    }

    private class Incoming(val transfer: IncomingTransfer, val link: PeerConnection)

    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val peerQueues = ConcurrentHashMap<DeviceId, Mutex>()
    private val trackers = ConcurrentHashMap<String, Job>()

    private val snapshotFlows = MutableStateFlow<Map<String, StateFlow<TransferSnapshot>>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val transfers: StateFlow<List<TransferSnapshot>> = snapshotFlows
        .flatMapLatest { flows ->
            if (flows.isEmpty()) flowOf(emptyList()) else combine(flows.values) { it.sortedByDescending(TransferSnapshot::createdAtMillis) }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun send(peerId: DeviceId, files: List<OutgoingFile>, kind: TransferKind): String {
        val transfer = OutgoingTransfer(UUID.randomUUID().toString(), peerId, files, kind, clock)
        val entry = Outgoing(transfer)
        outgoing[transfer.id] = entry
        track(transfer.id, transfer.snapshot)
        launch(entry)
        return transfer.id
    }

    private fun launch(entry: Outgoing) {
        val transfer = entry.transfer
        entry.job = scope.launch {
            peerQueues.getOrPut(transfer.peerId) { Mutex() }.withLock {
                val link = try {
                    connect(transfer.peerId)
                } catch (e: IncompatibleVersionException) {
                    return@withLock transfer.failWithoutAttempt(TransferFailure.INCOMPATIBLE_VERSION)
                } catch (e: IOException) {
                    return@withLock transfer.failWithoutAttempt(TransferFailure.PEER_UNAVAILABLE)
                }
                entry.link = link
                try {
                    transfer.run(link)
                } finally {
                    entry.link = null
                }
            }
        }
    }

    suspend fun onRequest(connection: PeerConnection, request: RelayMessage.TransferRequest) {
        val peerId = connection.peer.id
        val previous = incoming[request.transferId]
        if (previous != null && previous.transfer.peerId != peerId) {
            return connection.send(RelayMessage.Error(ErrorCode.UNKNOWN_TRANSFER, relatesTo = request.transferId))
        }
        val isClipboard = request.kind == TransferKind.CLIPBOARD
        if (isClipboard && !acceptsClipboard()) {
            // Clipboard sync is off here. A clipboard is not something to ask about.
            return connection.send(RelayMessage.TransferDecline(request.transferId, DeclineReason.USER_DECLINED))
        }
        val transfer = IncomingTransfer(request, peerId, storageFor(request.kind), clock)
        if (!transfer.isWellFormed || runCatching { UUID.fromString(request.transferId) }.isFailure) {
            return connection.send(RelayMessage.TransferDecline(request.transferId, DeclineReason.UNKNOWN))
        }
        val waiting = incoming.values.count { it.transfer.snapshot.value.status == TransferStatus.AwaitingDecision }
        if (previous == null && waiting >= MAX_WAITING_REQUESTS) {
            return connection.send(RelayMessage.TransferDecline(request.transferId, DeclineReason.BUSY))
        }

        incoming[request.transferId] = Incoming(transfer, connection)
        track(request.transferId, transfer.snapshot)

        // A re-request of a transfer the user already accepted is the sender resuming
        // after a disconnect: carry on without asking again.
        val wasAccepted = previous != null && previous.transfer.snapshot.value.status.let {
            it != TransferStatus.AwaitingDecision && it != TransferStatus.Declined
        }
        val autoAccept = acceptsTrustedWithoutAsking() || trustStore.devices.value[peerId]?.autoAcceptTransfers == true
        if (wasAccepted || autoAccept || isClipboard) {
            accept(request.transferId)
        } else {
            events.emit(NodeEvent.TransferOffered(transfer.snapshot.value))
        }
    }

    suspend fun onMessage(connection: PeerConnection, transferId: String, message: RelayMessage) {
        val peerId = connection.peer.id
        outgoing[transferId]?.takeIf { it.transfer.peerId == peerId }?.let {
            it.transfer.deliver(message)
            return
        }
        incoming[transferId]?.takeIf { it.transfer.peerId == peerId && it.link === connection }?.transfer?.onMessage(message)
    }

    suspend fun onChunk(connection: PeerConnection, chunk: DataChunk) {
        val entry = incoming[chunk.transferId.toString()] ?: return
        if (entry.link !== connection) return
        entry.transfer.onChunk(chunk)
    }

    suspend fun onConnectionClosed(connection: PeerConnection) {
        outgoing.values.filter { it.link === connection }.forEach { it.transfer.onConnectionLost() }
        incoming.values.filter { it.link === connection }.forEach { it.transfer.onConnectionLost() }
    }

    /** A device became reachable: resend what could not be delivered while it was away. */
    fun onPeerAvailable(peerId: DeviceId) {
        for (entry in outgoing.values) {
            val transfer = entry.transfer
            val status = transfer.snapshot.value.status as? TransferStatus.Failed ?: continue
            if (transfer.peerId != peerId || entry.job?.isActive == true) continue
            if (status.reason != TransferFailure.CONNECTION_LOST && status.reason != TransferFailure.PEER_UNAVAILABLE) continue
            if (entry.automaticRetries >= MAX_AUTOMATIC_RETRIES) continue
            entry.automaticRetries++
            launch(entry)
        }
    }

    suspend fun accept(transferId: String) {
        val entry = incoming[transferId] ?: return
        try {
            entry.transfer.accept(entry.link)
        } catch (e: IOException) {
            entry.transfer.onConnectionLost()
        }
    }

    /** Accepts every offer still waiting for an answer, for when ecosystem mode is switched on. */
    suspend fun acceptAllWaiting() {
        incoming.filterValues { it.transfer.snapshot.value.status == TransferStatus.AwaitingDecision }.keys.forEach { accept(it) }
    }

    suspend fun decline(transferId: String) {
        val entry = incoming[transferId] ?: return
        runCatching { entry.transfer.decline(entry.link) }
    }

    suspend fun pause(transferId: String) {
        outgoing[transferId]?.transfer?.pause() ?: incoming[transferId]?.transfer?.pause()
    }

    suspend fun resume(transferId: String) {
        outgoing[transferId]?.transfer?.resume() ?: incoming[transferId]?.transfer?.resume()
    }

    suspend fun cancel(transferId: String) {
        outgoing[transferId]?.let { entry ->
            entry.transfer.cancel()
            return
        }
        incoming[transferId]?.transfer?.cancel()
    }

    /** Manual retry of a failed outgoing transfer. Resets the automatic retry budget. */
    fun retry(transferId: String) {
        val entry = outgoing[transferId] ?: return
        if (entry.transfer.snapshot.value.status !is TransferStatus.Failed || entry.job?.isActive == true) return
        entry.automaticRetries = 0
        launch(entry)
    }

    /** Forgets a finished transfer. History keeps its own copy. */
    fun dismiss(transferId: String) {
        val finished = (outgoing[transferId]?.transfer?.snapshot ?: incoming[transferId]?.transfer?.snapshot)
            ?.value?.status?.isFinished ?: return
        if (!finished) return
        outgoing.remove(transferId)
        incoming.remove(transferId)
        trackers.remove(transferId)?.cancel()
        snapshotFlows.update { it - transferId }
    }

    /** Emits [NodeEvent.TransferFinished] each time a transfer reaches a final state. */
    private fun track(transferId: String, snapshot: StateFlow<TransferSnapshot>) {
        snapshotFlows.update { it + (transferId to snapshot) }
        trackers.put(transferId, scope.launch {
            snapshot
                .map { it.status }
                .distinctUntilChanged()
                .filter { it.isFinished }
                .collect { events.emit(NodeEvent.TransferFinished(snapshot.value)) }
        })?.cancel() // a re-requested incoming transfer replaces the previous instance
    }

    private companion object {
        const val MAX_WAITING_REQUESTS = 16
        const val MAX_AUTOMATIC_RETRIES = 5
    }
}
