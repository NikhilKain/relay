package com.vythera.relay.transfer

import com.vythera.relay.protocol.CancelReason
import com.vythera.relay.protocol.DataChunk
import com.vythera.relay.protocol.DeclineReason
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.protocol.ResumePoint
import com.vythera.relay.protocol.TransferItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Receives one [RelayMessage.TransferRequest].
 *
 * Chunks are written synchronously on the connection's reader coroutine, so a slow
 * disk applies backpressure all the way to the sender through TCP instead of
 * buffering file data in memory.
 *
 * A sender retrying after a disconnect sends the same transfer id again; the node
 * creates a new [IncomingTransfer] for it and [accept] finds the partial files left in
 * [storage], answering with their offsets.
 */
class IncomingTransfer(
    val request: RelayMessage.TransferRequest,
    val peerId: DeviceId,
    private val storage: IncomingStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val id: String get() = request.transferId
    private val items: List<TransferItem> = request.items
    private val totalBytes = request.totalBytes

    private val _snapshot = MutableStateFlow(
        TransferSnapshot(
            id = id,
            direction = TransferDirection.INCOMING,
            peerId = peerId,
            kind = request.kind,
            items = items,
            status = TransferStatus.AwaitingDecision,
            progress = TransferProgress(0, totalBytes),
            createdAtMillis = clock(),
        ),
    )
    val snapshot: StateFlow<TransferSnapshot> = _snapshot.asStateFlow()

    private class FileState(val item: TransferItem) {
        var partial: IncomingFile? = null
        var output: OutputStream? = null
        var digest: MessageDigest? = null
        var length: Long = 0
        var committed: StoredFile? = null
        var verified: Boolean = false
    }

    private val mutex = Mutex()
    private val files = items.map(::FileState)
    private val speed = SpeedMeter(clock)
    private var lastPublishMillis = 0L
    private var link: PeerLink? = null

    /** Validates the request itself. A request failing this is declined without asking the user. */
    val isWellFormed: Boolean =
        items.isNotEmpty() && items.mapIndexed { i, item -> item.index == i }.all { it }

    suspend fun accept(link: PeerLink) {
        mutex.withLock {
            if (_snapshot.value.status != TransferStatus.AwaitingDecision) return
            this.link = link

            val alreadyHave: Long
            try {
                for (state in files) {
                    val committed = storage.findCommitted(id, state.item)
                    if (committed != null) {
                        state.committed = committed
                        state.length = state.item.sizeBytes
                        continue
                    }
                    var partial = storage.openPartial(id, state.item)
                    if (partial.length > state.item.sizeBytes) {
                        partial.discard()
                        partial = storage.openPartial(id, state.item)
                    }
                    state.partial = partial
                    state.length = partial.length
                }
                alreadyHave = files.sumOf { it.length }
                val available = storage.availableBytes()
                if (available != null && available < totalBytes - alreadyHave) {
                    link.send(RelayMessage.TransferDecline(id, DeclineReason.INSUFFICIENT_STORAGE))
                    finishLocked(TransferStatus.Failed(TransferFailure.STORAGE_FULL))
                    return
                }
            } catch (e: IOException) {
                link.send(RelayMessage.TransferDecline(id, DeclineReason.UNKNOWN))
                finishLocked(TransferStatus.Failed(TransferFailure.STORAGE_UNAVAILABLE))
                return
            }

            val resumeFrom = files.filter { it.length > 0 }.map { ResumePoint(it.item.index, it.length) }
            _snapshot.update {
                it.copy(status = TransferStatus.Running, progress = it.progress.copy(bytesDone = alreadyHave))
            }
            link.send(RelayMessage.TransferAccept(id, resumeFrom))
        }
    }

    suspend fun decline(link: PeerLink, reason: DeclineReason = DeclineReason.USER_DECLINED) {
        mutex.withLock {
            if (_snapshot.value.status != TransferStatus.AwaitingDecision) return
            finishLocked(TransferStatus.Declined)
        }
        link.send(RelayMessage.TransferDecline(id, reason))
    }

    suspend fun onChunk(chunk: DataChunk) {
        mutex.withLock {
            val status = _snapshot.value.status
            if (!status.isActive) return // late chunks after cancel/failure
            val state = files.getOrNull(chunk.index)
            if (state == null || state.committed != null || chunk.offset != state.length ||
                state.length + chunk.length > state.item.sizeBytes
            ) {
                return abortLocked(CancelReason.PROTOCOL_VIOLATION, TransferFailure.PROTOCOL_ERROR)
            }
            try {
                val output = ensureOpen(state) ?: return
                output.write(chunk.bytes, 0, chunk.length)
                state.digest!!.update(chunk.bytes, 0, chunk.length)
                state.length += chunk.length
            } catch (e: StorageFullException) {
                return abortLocked(CancelReason.STORAGE_FULL, TransferFailure.STORAGE_FULL)
            } catch (e: IOException) {
                return abortLocked(CancelReason.STORAGE_UNAVAILABLE, TransferFailure.STORAGE_UNAVAILABLE)
            }
            publishProgressLocked(chunk.index)
        }
    }

    suspend fun onMessage(message: RelayMessage) {
        mutex.withLock {
            when (message) {
                is RelayMessage.TransferFileComplete -> onFileComplete(message)
                is RelayMessage.TransferPause -> if (_snapshot.value.status.isActive) setStatusLocked(TransferStatus.Paused(byPeer = true))
                is RelayMessage.TransferResume -> if (_snapshot.value.status.isActive) setStatusLocked(TransferStatus.Running)
                is RelayMessage.TransferCancel -> if (!_snapshot.value.status.isFinished) {
                    closeOutputs()
                    storage.discardPartials(id)
                    finishLocked(
                        when (message.reason) {
                            CancelReason.USER_CANCELLED -> TransferStatus.Cancelled(byPeer = true)
                            CancelReason.SOURCE_UNAVAILABLE -> TransferStatus.Failed(TransferFailure.SOURCE_UNAVAILABLE)
                            else -> TransferStatus.Failed(TransferFailure.PROTOCOL_ERROR)
                        },
                    )
                }
                else -> Unit
            }
        }
    }

    suspend fun pause() {
        val link = mutex.withLock {
            if (_snapshot.value.status != TransferStatus.Running) return
            setStatusLocked(TransferStatus.Paused(byPeer = false))
            link
        }
        runCatching { link?.send(RelayMessage.TransferPause(id)) }
    }

    suspend fun resume() {
        val (link, points) = mutex.withLock {
            if (_snapshot.value.status !is TransferStatus.Paused) return
            setStatusLocked(TransferStatus.Running)
            link to files.map { ResumePoint(it.item.index, it.length) }
        }
        runCatching { link?.send(RelayMessage.TransferResume(id, points)) }
    }

    suspend fun cancel() {
        val link = mutex.withLock {
            if (_snapshot.value.status.isFinished) return
            closeOutputs()
            storage.discardPartials(id)
            finishLocked(TransferStatus.Cancelled(byPeer = false))
            link
        }
        runCatching { link?.send(RelayMessage.TransferCancel(id, CancelReason.USER_CANCELLED)) }
    }

    /** Keeps partial files so the sender's retry can resume. */
    suspend fun onConnectionLost() {
        mutex.withLock {
            if (_snapshot.value.status.isFinished) return
            closeOutputs()
            finishLocked(TransferStatus.Failed(TransferFailure.CONNECTION_LOST))
        }
    }

    private suspend fun onFileComplete(message: RelayMessage.TransferFileComplete) {
        if (!_snapshot.value.status.isActive) return
        val state = files.getOrNull(message.index)
            ?: return abortLocked(CancelReason.PROTOCOL_VIOLATION, TransferFailure.PROTOCOL_ERROR)
        val expected = message.sha256.lowercase()

        val committed = state.committed
        if (committed != null) {
            if (committed.sha256 != expected) return abortLocked(CancelReason.CHECKSUM_MISMATCH, TransferFailure.CHECKSUM_MISMATCH)
            state.verified = true
        } else {
            if (state.length != state.item.sizeBytes) {
                return abortLocked(CancelReason.PROTOCOL_VIOLATION, TransferFailure.PROTOCOL_ERROR)
            }
            try {
                // Zero-byte files never saw a chunk, so the output may not exist yet.
                ensureOpen(state) ?: return
                state.output?.close()
                state.output = null
                val actual = state.digest!!.digest().toHex()
                if (actual != expected) {
                    state.partial?.discard()
                    return abortLocked(CancelReason.CHECKSUM_MISMATCH, TransferFailure.CHECKSUM_MISMATCH)
                }
                state.committed = state.partial!!.commit(actual)
                state.verified = true
            } catch (e: StorageFullException) {
                return abortLocked(CancelReason.STORAGE_FULL, TransferFailure.STORAGE_FULL)
            } catch (e: IOException) {
                return abortLocked(CancelReason.STORAGE_UNAVAILABLE, TransferFailure.STORAGE_UNAVAILABLE)
            }
        }

        publishProgressLocked(message.index, force = true)
        if (files.all { it.verified }) {
            _snapshot.update { it.copy(storedFiles = files.mapNotNull(FileState::committed)) }
            link?.send(RelayMessage.TransferComplete(id))
            finishLocked(TransferStatus.Completed)
        }
    }

    /**
     * Opens the output lazily. When resuming, the bytes already on disk are hashed first
     * so the final checksum still covers the whole file. Returns null after aborting.
     */
    private suspend fun ensureOpen(state: FileState): OutputStream? {
        state.output?.let { return it }
        val partial = state.partial ?: run {
            abortLocked(CancelReason.PROTOCOL_VIOLATION, TransferFailure.PROTOCOL_ERROR)
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
        if (state.length > 0) {
            var remaining = state.length
            val buffer = ByteArray(256 * 1024)
            partial.openExisting().use { input ->
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) throw IOException("Partial file shorter than recorded")
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        state.digest = digest
        return partial.openAppend().also { state.output = it }
    }

    private suspend fun abortLocked(reason: CancelReason, failure: TransferFailure) {
        closeOutputs()
        if (reason != CancelReason.PROTOCOL_VIOLATION) storage.discardPartials(id)
        finishLocked(TransferStatus.Failed(failure))
        runCatching { link?.send(RelayMessage.TransferCancel(id, reason)) }
    }

    private fun closeOutputs() {
        files.forEach { state ->
            runCatching { state.output?.close() }
            state.output = null
            state.digest = null
        }
    }

    private fun publishProgressLocked(index: Int, force: Boolean = false) {
        val done = files.sumOf { it.length }
        speed.record(done)
        val now = clock()
        if (!force && now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = now
        _snapshot.update {
            it.copy(
                progress = TransferProgress(
                    bytesDone = done,
                    totalBytes = totalBytes,
                    bytesPerSecond = speed.bytesPerSecond(),
                    remainingMillis = speed.remainingMillis(totalBytes - done),
                    currentIndex = index,
                ),
            )
        }
    }

    private fun setStatusLocked(status: TransferStatus) {
        _snapshot.update { it.copy(status = status) }
    }

    private fun finishLocked(status: TransferStatus) {
        _snapshot.update { snapshot ->
            val progress = if (status == TransferStatus.Completed) {
                snapshot.progress.copy(bytesDone = totalBytes, bytesPerSecond = 0, remainingMillis = 0)
            } else {
                snapshot.progress.copy(bytesPerSecond = 0, remainingMillis = null)
            }
            snapshot.copy(status = status, progress = progress, finishedAtMillis = clock())
        }
    }

    private companion object {
        const val PUBLISH_INTERVAL_MILLIS = 100L
    }
}
