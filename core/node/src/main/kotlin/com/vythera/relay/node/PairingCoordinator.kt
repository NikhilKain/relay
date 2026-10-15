package com.vythera.relay.node

import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.ErrorCode
import com.vythera.relay.protocol.Platform
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.security.Fingerprint
import com.vythera.relay.security.PairingCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Runs the pairing exchange described in `PairingCrypto` and turns a confirmed exchange
 * into a [TrustedDevice].
 *
 * The initiator shows the code and waits; the responder shows the same code and
 * decides. Trust is written on both sides only after the responder accepts, and the
 * initiator can abort at any point if the codes differ.
 */
internal class PairingCoordinator(
    private val scope: CoroutineScope,
    private val localFingerprint: () -> Fingerprint,
    private val trustStore: TrustStore,
    private val events: MutableSharedFlow<NodeEvent>,
    private val clock: () -> Long,
    private val connect: suspend (DeviceId) -> PeerConnection,
    private val timeoutMillis: Long = SESSION_TIMEOUT_MILLIS,
) {
    private class Pending(
        val connection: PeerConnection,
        val ownNonce: String,
        /** Responder only: the initiator's commitment, checked when the nonce is revealed. */
        val commitment: String? = null,
        var peerNonce: String? = null,
    )

    private val _sessions = MutableStateFlow<Map<String, PairingSession>>(emptyMap())
    val sessions: StateFlow<Map<String, PairingSession>> = _sessions.asStateFlow()
    private val pending = HashMap<String, Pending>()

    /** Starts pairing with [peerId]. Returns the request id; progress is published in [sessions]. */
    suspend fun start(peerId: DeviceId, peerName: String): String {
        val requestId = UUID.randomUUID().toString()
        val connection = try {
            connect(peerId)
        } catch (e: IncompatibleVersionException) {
            publishFailed(requestId, peerId, peerName, PairingFailure.INCOMPATIBLE_VERSION)
            return requestId
        } catch (e: IOException) {
            publishFailed(requestId, peerId, peerName, PairingFailure.UNREACHABLE)
            return requestId
        }
        val nonce = PairingCrypto.newNonce()
        synchronized(pending) { pending[requestId] = Pending(connection, nonce) }
        publish(requestId, connection, PairingRole.INITIATOR, PairingStage.Exchanging)
        scheduleTimeout(requestId)
        try {
            connection.send(RelayMessage.PairRequest(requestId, PairingCrypto.commitmentFor(nonce)))
        } catch (e: IOException) {
            fail(requestId, PairingFailure.CONNECTION_LOST)
        }
        return requestId
    }

    /** The responder's user decided. */
    suspend fun respond(requestId: String, accept: Boolean, autoAcceptTransfers: Boolean) {
        val session = _sessions.value[requestId] ?: return
        if (session.role != PairingRole.RESPONDER || session.stage !is PairingStage.Confirm) return
        val connection = synchronized(pending) { pending[requestId]?.connection } ?: return
        try {
            if (accept) {
                trust(connection, autoAcceptTransfers)
                connection.send(RelayMessage.PairAccept(requestId))
                setStage(requestId, PairingStage.Completed)
                events.emit(NodeEvent.DevicePaired(session.peerId))
            } else {
                connection.send(RelayMessage.PairDecline(requestId))
                setStage(requestId, PairingStage.Declined(byPeer = false))
            }
        } catch (e: IOException) {
            fail(requestId, PairingFailure.CONNECTION_LOST)
        } finally {
            synchronized(pending) { pending.remove(requestId) }
        }
    }

    /** Either side abandons pairing, e.g. because the codes did not match. */
    suspend fun cancel(requestId: String) {
        val session = _sessions.value[requestId] ?: return
        if (session.stage.isFinished) return
        val connection = synchronized(pending) { pending.remove(requestId)?.connection }
        setStage(requestId, PairingStage.Declined(byPeer = false))
        runCatching { connection?.send(RelayMessage.PairDecline(requestId)) }
    }

    /** Removes a finished session from [sessions] once the UI has shown its outcome. */
    fun dismiss(requestId: String) {
        _sessions.update { current -> current[requestId]?.takeIf { it.stage.isFinished }?.let { current - requestId } ?: current }
    }

    suspend fun onMessage(connection: PeerConnection, message: RelayMessage) {
        when (message) {
            is RelayMessage.PairRequest -> onRequest(connection, message)
            is RelayMessage.PairNonce -> onNonce(connection, message)
            is RelayMessage.PairReveal -> onReveal(connection, message)
            is RelayMessage.PairAccept -> onAccept(connection, message)
            is RelayMessage.PairDecline -> {
                if (pendingFor(message.requestId, connection) == null) return
                synchronized(pending) { pending.remove(message.requestId) }
                setStage(message.requestId, PairingStage.Declined(byPeer = true))
            }
            else -> Unit
        }
    }

    fun onError(connection: PeerConnection, error: RelayMessage.Error) {
        val requestId = error.relatesTo ?: return
        if (pendingFor(requestId, connection) == null) return
        fail(requestId, PairingFailure.VERIFICATION_FAILED)
    }

    fun onConnectionClosed(connection: PeerConnection) {
        val lost = synchronized(pending) { pending.filterValues { it.connection === connection }.keys.toList() }
        lost.forEach { fail(it, PairingFailure.CONNECTION_LOST) }
    }

    private suspend fun onRequest(connection: PeerConnection, message: RelayMessage.PairRequest) {
        if (_sessions.value.containsKey(message.requestId)) return
        val recentFromPeer = _sessions.value.values.count {
            it.peerId == connection.peer.id && clock() - it.startedAtMillis < RATE_WINDOW_MILLIS
        }
        if (recentFromPeer >= MAX_REQUESTS_PER_WINDOW) {
            // A device spamming pairing requests does not get to keep popping dialogs up.
            runCatching { connection.send(RelayMessage.PairDecline(message.requestId)) }
            return
        }
        val nonce = PairingCrypto.newNonce()
        synchronized(pending) { pending[message.requestId] = Pending(connection, nonce, commitment = message.commitment) }
        publish(message.requestId, connection, PairingRole.RESPONDER, PairingStage.Exchanging)
        scheduleTimeout(message.requestId)
        try {
            connection.send(RelayMessage.PairNonce(message.requestId, nonce))
        } catch (e: IOException) {
            fail(message.requestId, PairingFailure.CONNECTION_LOST)
        }
    }

    private suspend fun onNonce(connection: PeerConnection, message: RelayMessage.PairNonce) {
        val state = pendingFor(message.requestId, connection) ?: return
        val session = _sessions.value[message.requestId] ?: return
        if (session.role != PairingRole.INITIATOR || session.stage != PairingStage.Exchanging || state.peerNonce != null) return
        if (!PairingCrypto.isValidNonce(message.nonce)) return rejectVerification(connection, message.requestId)
        state.peerNonce = message.nonce
        try {
            connection.send(RelayMessage.PairReveal(message.requestId, state.ownNonce))
        } catch (e: IOException) {
            return fail(message.requestId, PairingFailure.CONNECTION_LOST)
        }
        val code = PairingCrypto.deriveCode(localFingerprint(), connection.fingerprint, state.ownNonce, message.nonce)
        setStage(message.requestId, PairingStage.WaitingForPeer(code))
    }

    private suspend fun onReveal(connection: PeerConnection, message: RelayMessage.PairReveal) {
        val state = pendingFor(message.requestId, connection) ?: return
        val session = _sessions.value[message.requestId] ?: return
        if (session.role != PairingRole.RESPONDER || session.stage != PairingStage.Exchanging) return
        if (!PairingCrypto.verifyCommitment(state.commitment.orEmpty(), message.nonce)) {
            return rejectVerification(connection, message.requestId)
        }
        state.peerNonce = message.nonce
        val code = PairingCrypto.deriveCode(connection.fingerprint, localFingerprint(), message.nonce, state.ownNonce)

        val existing = trustStore.devices.value[connection.peer.id]
        if (existing != null && existing.fingerprint == connection.fingerprint.hex) {
            // Already trusted with this very key, e.g. the other device lost its trust
            // list. Nothing new to verify, so do not bother the user.
            setStage(message.requestId, PairingStage.Confirm(code))
            respond(message.requestId, accept = true, autoAcceptTransfers = existing.autoAcceptTransfers)
            return
        }
        setStage(message.requestId, PairingStage.Confirm(code))
        _sessions.value[message.requestId]?.let { events.emit(NodeEvent.PairingRequested(it)) }
    }

    private suspend fun onAccept(connection: PeerConnection, message: RelayMessage.PairAccept) {
        pendingFor(message.requestId, connection) ?: return
        val session = _sessions.value[message.requestId] ?: return
        if (session.role != PairingRole.INITIATOR || session.stage !is PairingStage.WaitingForPeer) return
        synchronized(pending) { pending.remove(message.requestId) }
        trust(connection, autoAcceptTransfers = trustStore.devices.value[connection.peer.id]?.autoAcceptTransfers ?: false)
        setStage(message.requestId, PairingStage.Completed)
        events.emit(NodeEvent.DevicePaired(session.peerId))
    }

    private suspend fun trust(connection: PeerConnection, autoAcceptTransfers: Boolean) {
        val peer = connection.peer
        val previous = trustStore.devices.value[peer.id]
        trustStore.put(
            TrustedDevice(
                id = peer.id,
                name = peer.name,
                type = peer.type,
                platform = peer.platform,
                fingerprint = connection.fingerprint.hex,
                pairedAtMillis = previous?.pairedAtMillis ?: clock(),
                autoAcceptTransfers = autoAcceptTransfers,
                lastConnectedMillis = clock(),
            ),
        )
    }

    private suspend fun rejectVerification(connection: PeerConnection, requestId: String) {
        runCatching { connection.send(RelayMessage.Error(ErrorCode.PAIRING_FAILED, relatesTo = requestId)) }
        fail(requestId, PairingFailure.VERIFICATION_FAILED)
    }

    /** The pending state for [requestId], only if it belongs to this connection. */
    private fun pendingFor(requestId: String, connection: PeerConnection): Pending? =
        synchronized(pending) { pending[requestId]?.takeIf { it.connection === connection } }

    private fun scheduleTimeout(requestId: String) {
        scope.launch {
            delay(timeoutMillis)
            if (_sessions.value[requestId]?.stage?.isFinished == false) {
                val connection = synchronized(pending) { pending[requestId]?.connection }
                runCatching { connection?.send(RelayMessage.PairDecline(requestId)) }
                fail(requestId, PairingFailure.TIMED_OUT)
            }
        }
    }

    private fun fail(requestId: String, reason: PairingFailure) {
        synchronized(pending) { pending.remove(requestId) }
        setStage(requestId, PairingStage.Failed(reason))
    }

    private fun publish(requestId: String, connection: PeerConnection, role: PairingRole, stage: PairingStage) {
        val peer = connection.peer
        _sessions.update {
            it + (requestId to PairingSession(requestId, peer.id, peer.name, peer.type, peer.platform, role, stage, clock()))
        }
    }

    private fun publishFailed(requestId: String, peerId: DeviceId, peerName: String, reason: PairingFailure) {
        _sessions.update {
            it + (requestId to PairingSession(
                requestId, peerId, peerName, DeviceType.UNKNOWN, Platform.UNKNOWN,
                PairingRole.INITIATOR, PairingStage.Failed(reason), clock(),
            ))
        }
    }

    private fun setStage(requestId: String, stage: PairingStage) {
        _sessions.update { current ->
            val session = current[requestId] ?: return@update current
            if (session.stage.isFinished) current else current + (requestId to session.copy(stage = stage))
        }
    }

    companion object {
        const val SESSION_TIMEOUT_MILLIS = 120_000L
        private const val RATE_WINDOW_MILLIS = 60_000L
        private const val MAX_REQUESTS_PER_WINDOW = 3
    }
}
