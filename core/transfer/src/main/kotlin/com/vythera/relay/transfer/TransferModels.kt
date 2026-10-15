package com.vythera.relay.transfer

import com.vythera.relay.protocol.DataChunk
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.protocol.TransferItem
import com.vythera.relay.protocol.TransferKind

enum class TransferDirection { OUTGOING, INCOMING }

/**
 * Why a transfer did not finish. Each value maps to one human sentence in the UI
 * ("Connection lost. We'll retry when Gaming PC is available again."); raw exception
 * text never reaches users.
 */
enum class TransferFailure {
    /** The connection dropped mid-transfer. Retrying resumes where it stopped. */
    CONNECTION_LOST,

    /** Could not reach the device at all. */
    PEER_UNAVAILABLE,

    /** The other device did not answer the request in time. */
    NO_RESPONSE,
    CHECKSUM_MISMATCH,
    STORAGE_FULL,
    STORAGE_UNAVAILABLE,

    /** A file being sent was moved, deleted, changed size or lost its permission grant. */
    SOURCE_UNAVAILABLE,
    NOT_TRUSTED,
    INCOMPATIBLE_VERSION,
    PROTOCOL_ERROR,
}

sealed interface TransferStatus {
    /** Outgoing: request sent, the other person has not answered. Incoming: waiting for this user. */
    data object AwaitingDecision : TransferStatus
    data object Queued : TransferStatus
    data object Running : TransferStatus
    data class Paused(val byPeer: Boolean) : TransferStatus
    data object Completed : TransferStatus
    data object Declined : TransferStatus
    data class Cancelled(val byPeer: Boolean) : TransferStatus
    data class Failed(val reason: TransferFailure) : TransferStatus

    val isFinished: Boolean
        get() = this is Completed || this is Declined || this is Cancelled || this is Failed

    val isActive: Boolean get() = this is Running || this is Paused
}

data class TransferProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long = 0,
    /** Null while the speed is not yet meaningful. */
    val remainingMillis: Long? = null,
    val currentIndex: Int = 0,
) {
    val fraction: Float get() = if (totalBytes <= 0) 1f else (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/** A file that finished receiving and passed its checksum. [location] is platform-specific (path or content URI). */
data class StoredFile(
    val index: Int,
    val name: String,
    val location: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Immutable view of a transfer, published for UI, notifications and history. */
data class TransferSnapshot(
    val id: String,
    val direction: TransferDirection,
    val peerId: DeviceId,
    val kind: TransferKind,
    val items: List<TransferItem>,
    val status: TransferStatus,
    val progress: TransferProgress,
    val createdAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val storedFiles: List<StoredFile> = emptyList(),
) {
    val totalBytes: Long get() = progress.totalBytes
}

/** What a transfer needs from a connection. Implemented by the node's peer connection. */
interface PeerLink {
    suspend fun send(message: RelayMessage)

    /**
     * May buffer; call [flush] before waiting on the peer. Implementations must not retain
     * [DataChunk.bytes] after returning: senders reuse the buffer for the next chunk.
     */
    suspend fun sendData(chunk: DataChunk)

    suspend fun flush()
}
