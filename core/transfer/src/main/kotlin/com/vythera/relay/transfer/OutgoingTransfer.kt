package com.vythera.relay.transfer

import com.vythera.relay.protocol.CancelReason
import com.vythera.relay.protocol.DataChunk
import com.vythera.relay.protocol.DeclineReason
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.ErrorCode
import com.vythera.relay.protocol.FrameFormat
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.protocol.ResumePoint
import com.vythera.relay.protocol.TransferItem
import com.vythera.relay.protocol.TransferKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Sends a set of files to one peer.
 *
 * The object outlives a single connection: if [run] ends with
 * [TransferFailure.CONNECTION_LOST], calling [run] again with a new link re-requests the
 * same transfer id and the receiver answers with the offsets it already holds.
 *
 * Threading: [run] is called from one coroutine at a time. [deliver], [pause], [resume]
 * and [cancel] may be called from anywhere.
 */
class OutgoingTransfer(
    val id: String,
    val peerId: DeviceId,
    private val files: List<OutgoingFile>,
    kind: TransferKind = TransferKind.FILES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val chunkSize: Int = FrameFormat.DEFAULT_CHUNK_SIZE,
    private val decisionTimeoutMillis: Long = DECISION_TIMEOUT_MILLIS,
    private val completionTimeoutMillis: Long = COMPLETION_TIMEOUT_MILLIS,
) {
    init {
        require(files.isNotEmpty()) { "Nothing to send" }
        require(chunkSize in 1..FrameFormat.MAX_CHUNK_SIZE)
        UUID.fromString(id) // data frames carry the id as a UUID
    }

    val items: List<TransferItem> = files.mapIndexed { index, file ->
        TransferItem(index, file.name, file.sizeBytes, file.mimeType, file.relativePath, file.lastModifiedMillis)
    }
    private val totalBytes = items.sumOf { it.sizeBytes }
    private val uuid = UUID.fromString(id)

    private val _snapshot = MutableStateFlow(
        TransferSnapshot(
            id = id,
            direction = TransferDirection.OUTGOING,
            peerId = peerId,
            kind = kind,
            items = items,
            status = TransferStatus.Queued,
            progress = TransferProgress(0, totalBytes),
            createdAtMillis = clock(),
        ),
    )
    val snapshot: StateFlow<TransferSnapshot> = _snapshot.asStateFlow()

    private data class Control(
        val pausedLocally: Boolean = false,
        val pausedByPeer: Boolean = false,
        /** Set when the peer ends the transfer (complete, cancel, error). */
        val outcome: TransferStatus? = null,
    ) {
        val paused get() = pausedLocally || pausedByPeer
    }

    private val control = MutableStateFlow(Control())
    /** [RelayMessage]s from the peer, or [ConnectionLost]. */
    private val inbox = Channel<Any>(Channel.UNLIMITED)
    private val speed = SpeedMeter(clock)
    private var lastPublishMillis = 0L

    @Volatile private var link: PeerLink? = null
    @Volatile private var runningJob: Job? = null
    @Volatile private var cancelRequested = false

    /** Routes a message about this transfer from the connection. */
    fun deliver(message: RelayMessage) {
        inbox.trySend(message)
    }

    /**
     * The connection closed. A transfer that is waiting for the peer (to accept, or to
     * confirm the checksum) would otherwise only notice at its timeout, because it
     * performs no I/O while waiting.
     */
    fun onConnectionLost() {
        inbox.trySend(ConnectionLost)
    }

    /** Marks a queued or failed transfer as failed without an attempt, e.g. when the peer is unreachable. */
    fun failWithoutAttempt(reason: TransferFailure) {
        val status = _snapshot.value.status
        if (status == TransferStatus.Queued || status is TransferStatus.Failed) finish(TransferStatus.Failed(reason))
    }

    suspend fun pause() {
        control.update { it.copy(pausedLocally = true) }
        if (_snapshot.value.status == TransferStatus.Running) setStatus(TransferStatus.Paused(byPeer = false))
        runCatching { link?.send(RelayMessage.TransferPause(id)) }
    }

    suspend fun resume() {
        control.update { it.copy(pausedLocally = false) }
        runCatching { link?.send(RelayMessage.TransferResume(id)) }
    }

    /** Cancels a running attempt (notifying the peer) or a failed transfer awaiting retry. */
    fun cancel() {
        cancelRequested = true
        val job = runningJob
        if (job != null) job.cancel() else if (!_snapshot.value.status.isFinished || _snapshot.value.status is TransferStatus.Failed) {
            finish(TransferStatus.Cancelled(byPeer = false))
        }
    }

    /**
     * Performs one attempt over [link] and returns the resulting status. Never throws
     * for network or file problems; those become [TransferStatus.Failed].
     */
    suspend fun run(link: PeerLink): TransferStatus {
        val current = _snapshot.value.status
        check(current == TransferStatus.Queued || current is TransferStatus.Failed) { "Transfer $id is $current" }
        if (cancelRequested) return finish(TransferStatus.Cancelled(byPeer = false))

        while (inbox.tryReceive().isSuccess) Unit // stale messages from a previous attempt
        control.value = Control()
        this.link = link
        runningJob = currentCoroutineContext()[Job]
        return try {
            attempt(link)
        } catch (e: CancellationException) {
            if (cancelRequested) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(NOTIFY_TIMEOUT_MILLIS) {
                        runCatching { link.send(RelayMessage.TransferCancel(id, CancelReason.USER_CANCELLED)) }
                    }
                }
                finish(TransferStatus.Cancelled(byPeer = false))
            }
            throw e
        } catch (e: SourceUnavailable) {
            notifyPeer(link, CancelReason.SOURCE_UNAVAILABLE)
            finish(TransferStatus.Failed(TransferFailure.SOURCE_UNAVAILABLE))
        } catch (e: IOException) {
            finish(TransferStatus.Failed(TransferFailure.CONNECTION_LOST))
        } finally {
            this.link = null
            runningJob = null
        }
    }

    private suspend fun attempt(link: PeerLink): TransferStatus {
        setStatus(TransferStatus.AwaitingDecision)
        link.send(RelayMessage.TransferRequest(id, items, _snapshot.value.kind))
        link.flush()

        val decision = withTimeoutOrNull(decisionTimeoutMillis) { awaitDecision() }
            ?: run {
                notifyPeer(link, CancelReason.USER_CANCELLED)
                return finish(TransferStatus.Failed(TransferFailure.NO_RESPONSE))
            }
        val resumeFrom = when (decision) {
            is Decision.Accepted -> decision.resumeFrom
            is Decision.Ended -> return finish(decision.status)
        }

        setStatus(TransferStatus.Running)
        speed.reset()
        return coroutineScope {
            val controlJob = launch { for (message in inbox) onControl(message) }
            try {
                sendFiles(link, resumeFrom)
                link.flush()
                val outcome = withTimeoutOrNull(completionTimeoutMillis) { control.first { it.outcome != null }.outcome }
                    ?: TransferStatus.Failed(TransferFailure.NO_RESPONSE)
                finish(outcome)
            } finally {
                controlJob.cancel()
            }
        }
    }

    private sealed interface Decision {
        data class Accepted(val resumeFrom: Map<Int, Long>) : Decision
        data class Ended(val status: TransferStatus) : Decision
    }

    private suspend fun awaitDecision(): Decision {
        for (message in inbox) {
            when (message) {
                ConnectionLost -> throw IOException("Connection lost")
                is RelayMessage.TransferAccept ->
                    return Decision.Accepted(message.resumeFrom.associate { it.index to it.offset })
                is RelayMessage.TransferDecline -> return Decision.Ended(
                    if (message.reason == DeclineReason.INSUFFICIENT_STORAGE) {
                        TransferStatus.Failed(TransferFailure.STORAGE_FULL)
                    } else {
                        TransferStatus.Declined
                    },
                )
                is RelayMessage.TransferCancel, is RelayMessage.Error -> outcomeOf(message)?.let { return Decision.Ended(it) }
                else -> Unit
            }
        }
        throw IOException("Inbox closed")
    }

    private fun onControl(message: Any) {
        when (message) {
            ConnectionLost -> control.update {
                it.copy(outcome = it.outcome ?: TransferStatus.Failed(TransferFailure.CONNECTION_LOST))
            }
            is RelayMessage.TransferPause -> {
                control.update { it.copy(pausedByPeer = true) }
                setStatus(TransferStatus.Paused(byPeer = true))
            }
            is RelayMessage.TransferResume -> {
                control.update { it.copy(pausedByPeer = false) }
                if (!control.value.paused) setStatus(TransferStatus.Running)
            }
            is RelayMessage -> outcomeOf(message)?.let { outcome -> control.update { it.copy(outcome = outcome) } }
        }
    }

    private fun outcomeOf(message: Any): TransferStatus? = when (message) {
        is RelayMessage.TransferComplete -> TransferStatus.Completed
        is RelayMessage.TransferCancel -> when (message.reason) {
            CancelReason.USER_CANCELLED -> TransferStatus.Cancelled(byPeer = true)
            CancelReason.CHECKSUM_MISMATCH -> TransferStatus.Failed(TransferFailure.CHECKSUM_MISMATCH)
            CancelReason.STORAGE_FULL -> TransferStatus.Failed(TransferFailure.STORAGE_FULL)
            CancelReason.STORAGE_UNAVAILABLE -> TransferStatus.Failed(TransferFailure.STORAGE_UNAVAILABLE)
            CancelReason.SOURCE_UNAVAILABLE -> TransferStatus.Failed(TransferFailure.SOURCE_UNAVAILABLE)
            CancelReason.PROTOCOL_VIOLATION, CancelReason.UNKNOWN -> TransferStatus.Failed(TransferFailure.PROTOCOL_ERROR)
        }
        is RelayMessage.Error -> when (message.code) {
            ErrorCode.NOT_TRUSTED -> TransferStatus.Failed(TransferFailure.NOT_TRUSTED)
            ErrorCode.INCOMPATIBLE_VERSION -> TransferStatus.Failed(TransferFailure.INCOMPATIBLE_VERSION)
            else -> TransferStatus.Failed(TransferFailure.PROTOCOL_ERROR)
        }
        else -> null
    }

    private suspend fun sendFiles(link: PeerLink, resumeFrom: Map<Int, Long>) {
        val buffer = ByteArray(chunkSize)
        var doneBeforeFile = 0L
        for ((index, file) in files.withIndex()) {
            val item = items[index]
            val offset = (resumeFrom[index] ?: 0L).coerceIn(0L, item.sizeBytes)
            val digest = MessageDigest.getInstance("SHA-256")

            openSource(file).use { input ->
                // The checksum covers the whole file, so bytes the receiver already has
                // are read and hashed locally without being sent again.
                hashPrefix(input, offset, buffer, digest)
                var position = offset
                while (true) {
                    // Reads block and a buffered write may not suspend, so neither would
                    // notice cancellation on its own.
                    currentCoroutineContext().ensureActive()
                    if (control.value.outcome != null) return
                    if (control.value.paused) {
                        setStatus(TransferStatus.Paused(byPeer = !control.value.pausedLocally))
                        link.flush()
                        control.first { !it.paused || it.outcome != null }
                        if (control.value.outcome != null) return
                        setStatus(TransferStatus.Running)
                        speed.reset()
                    }
                    val read = readSource(input, buffer)
                    if (read == -1) break
                    if (position + read > item.sizeBytes) throw SourceUnavailable() // file grew
                    digest.update(buffer, 0, read)
                    link.sendData(DataChunk(uuid, index, position, buffer, read))
                    position += read
                    publishProgress(doneBeforeFile + position, index)
                }
                if (position != item.sizeBytes) throw SourceUnavailable() // file shrank
            }
            link.send(RelayMessage.TransferFileComplete(id, index, digest.digest().toHex()))
            doneBeforeFile += item.sizeBytes
            publishProgress(doneBeforeFile, index, force = true)
        }
    }

    private fun hashPrefix(input: InputStream, length: Long, buffer: ByteArray, digest: MessageDigest) {
        var remaining = length
        while (remaining > 0) {
            val read = readSource(input, buffer, maxOf = minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) throw SourceUnavailable()
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }

    private fun openSource(file: OutgoingFile): InputStream =
        try {
            file.open()
        } catch (e: IOException) {
            throw SourceUnavailable()
        } catch (e: SecurityException) {
            throw SourceUnavailable() // Android revoked the URI permission
        }

    private fun readSource(input: InputStream, buffer: ByteArray, maxOf: Int = buffer.size): Int =
        try {
            input.read(buffer, 0, maxOf)
        } catch (e: IOException) {
            throw SourceUnavailable()
        }

    private suspend fun notifyPeer(link: PeerLink, reason: CancelReason) {
        withContext(NonCancellable) {
            withTimeoutOrNull(NOTIFY_TIMEOUT_MILLIS) { runCatching { link.send(RelayMessage.TransferCancel(id, reason)) } }
        }
    }

    private fun publishProgress(bytesDone: Long, index: Int, force: Boolean = false) {
        val now = clock()
        speed.record(bytesDone)
        if (!force && now - lastPublishMillis < PUBLISH_INTERVAL_MILLIS) return
        lastPublishMillis = now
        _snapshot.update {
            it.copy(
                progress = TransferProgress(
                    bytesDone = bytesDone,
                    totalBytes = totalBytes,
                    bytesPerSecond = speed.bytesPerSecond(),
                    remainingMillis = speed.remainingMillis(totalBytes - bytesDone),
                    currentIndex = index,
                ),
            )
        }
    }

    private fun setStatus(status: TransferStatus) {
        _snapshot.update { if (it.status.isFinished && it.status !is TransferStatus.Failed) it else it.copy(status = status) }
    }

    private fun finish(status: TransferStatus): TransferStatus {
        _snapshot.update { snapshot ->
            val progress = if (status == TransferStatus.Completed) {
                snapshot.progress.copy(bytesDone = totalBytes, bytesPerSecond = 0, remainingMillis = 0)
            } else {
                snapshot.progress.copy(bytesPerSecond = 0, remainingMillis = null)
            }
            snapshot.copy(status = status, progress = progress, finishedAtMillis = clock())
        }
        return status
    }

    private class SourceUnavailable : IOException("Source unavailable")

    private object ConnectionLost

    companion object {
        const val DECISION_TIMEOUT_MILLIS = 3 * 60_000L
        const val COMPLETION_TIMEOUT_MILLIS = 2 * 60_000L
        private const val NOTIFY_TIMEOUT_MILLIS = 2_000L
        private const val PUBLISH_INTERVAL_MILLIS = 100L

        fun newId(): String = UUID.randomUUID().toString()
    }
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { i, byte ->
        val v = byte.toInt() and 0xff
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
