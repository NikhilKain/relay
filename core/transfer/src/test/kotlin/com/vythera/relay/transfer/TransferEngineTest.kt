package com.vythera.relay.transfer

import com.vythera.relay.protocol.DataChunk
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SENDER = DeviceId("senderdevice0001")
private val RECEIVER = DeviceId("receiverdevice01")

private class BytesFile(
    override val name: String,
    private val content: ByteArray,
    override val relativePath: String = "",
    /** Pretend size, to simulate a file changing between selection and sending. */
    override val sizeBytes: Long = content.size.toLong(),
    private val delayPerReadMillis: Long = 0,
) : OutgoingFile {
    override val mimeType = "application/octet-stream"

    override fun open(): InputStream = object : InputStream() {
        private val inner = ByteArrayInputStream(content)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delayPerReadMillis > 0) Thread.sleep(delayPerReadMillis)
            return inner.read(b, off, len)
        }
    }
}

/** One direction of an in-memory connection. Copies chunks, as a real socket would. */
private class ChannelLink(
    private val out: Channel<Any>,
    private val failAfterDataBytes: Long = Long.MAX_VALUE,
    private val corrupt: Boolean = false,
) : PeerLink {
    @Volatile var dataBytesSent = 0L
    @Volatile private var broken = false

    override suspend fun send(message: RelayMessage) {
        if (broken) throw IOException("Connection lost")
        out.send(message)
    }

    override suspend fun sendData(chunk: DataChunk) {
        if (broken) throw IOException("Connection lost")
        if (dataBytesSent + chunk.length > failAfterDataBytes) {
            broken = true
            throw IOException("Connection lost")
        }
        val bytes = chunk.bytes.copyOf(chunk.length)
        if (corrupt && dataBytesSent == 0L && bytes.isNotEmpty()) bytes[0] = (bytes[0] + 1).toByte()
        dataBytesSent += chunk.length
        out.send(DataChunk(chunk.transferId, chunk.index, chunk.offset, bytes))
    }

    override suspend fun flush() = Unit
}

/** Plays the receiving device: routes whatever arrives to an [IncomingTransfer]. */
private class Receiver(private val storage: IncomingStorage, private val accept: Boolean = true) {
    val current = MutableStateFlow<IncomingTransfer?>(null)

    fun CoroutineScope.run(inbox: Channel<Any>, reply: PeerLink): Job = launch {
        for (item in inbox) {
            when (item) {
                is RelayMessage.TransferRequest -> {
                    val incoming = IncomingTransfer(item, SENDER, storage)
                    current.value = incoming
                    if (accept) incoming.accept(reply) else incoming.decline(reply)
                }
                is DataChunk -> current.value?.onChunk(item)
                is RelayMessage -> current.value?.onMessage(item)
            }
        }
    }
}

class TransferEngineTest {
    @get:Rule val temp = TemporaryFolder()

    private fun randomBytes(size: Int, seed: Int) = Random(seed).nextBytes(size)

    /** Runs one attempt of [outgoing] against [receiver] over fresh channels. */
    private suspend fun attempt(
        outgoing: OutgoingTransfer,
        receiver: Receiver,
        senderLink: (Channel<Any>) -> ChannelLink = { ChannelLink(it) },
    ): Pair<TransferStatus, ChannelLink> = coroutineScope {
        val toReceiver = Channel<Any>(Channel.UNLIMITED)
        val toSender = Channel<Any>(Channel.UNLIMITED)
        val link = senderLink(toReceiver)
        val receiverJob = with(receiver) { run(toReceiver, ChannelLink(toSender)) }
        val senderInbox = launch { for (m in toSender) outgoing.deliver(m as RelayMessage) }
        val status = outgoing.run(link)
        if (status is TransferStatus.Failed && status.reason == TransferFailure.CONNECTION_LOST) {
            receiverJob.cancelAndJoin()
            receiver.current.value?.onConnectionLost()
        } else {
            // Let the receiver drain what the sender already wrote.
            withTimeout(5_000) { receiver.current.filterNotNull().first().snapshot.first { it.status.isFinished } }
            receiverJob.cancelAndJoin()
        }
        senderInbox.cancelAndJoin()
        status to link
    }

    @Test
    fun `sends several files including an empty one and a folder entry`(): Unit = runBlocking(Dispatchers.Default) {
        val root = temp.newFolder("inbox")
        val big = randomBytes(3_000_000, 1)
        val small = randomBytes(10_000, 2)
        val outgoing = OutgoingTransfer(
            OutgoingTransfer.newId(),
            RECEIVER,
            listOf(BytesFile("vacation.zip", big), BytesFile("empty.txt", ByteArray(0)), BytesFile("notes.md", small, "Docs/2026")),
            chunkSize = 64 * 1024,
        )
        val receiver = Receiver(DirectoryStorage(root))

        val (status, _) = attempt(outgoing, receiver)

        assertEquals(TransferStatus.Completed, status)
        assertContentEquals(big, File(root, "vacation.zip").readBytes())
        assertEquals(0, File(root, "empty.txt").length())
        assertContentEquals(small, File(root, "Docs/2026/notes.md").readBytes())
        val incoming = receiver.current.value!!.snapshot.value
        assertEquals(TransferStatus.Completed, incoming.status)
        assertEquals(3, incoming.storedFiles.size)
        assertEquals(1f, outgoing.snapshot.value.progress.fraction)
    }

    @Test
    fun `an interrupted transfer resumes without resending received bytes`(): Unit = runBlocking(Dispatchers.Default) {
        val root = temp.newFolder("inbox")
        val first = randomBytes(700_000, 3)
        val second = randomBytes(1_500_000, 4)
        val outgoing = OutgoingTransfer(
            OutgoingTransfer.newId(),
            RECEIVER,
            listOf(BytesFile("a.bin", first), BytesFile("b.bin", second)),
            chunkSize = 50_000,
        )
        val storage = DirectoryStorage(root)

        val (lost, _) = attempt(outgoing, Receiver(storage)) { ChannelLink(it, failAfterDataBytes = 1_000_000) }
        assertEquals(TransferStatus.Failed(TransferFailure.CONNECTION_LOST), lost)

        val (resumed, link) = attempt(outgoing, Receiver(storage))

        assertEquals(TransferStatus.Completed, resumed)
        assertEquals(2_200_000L - 1_000_000L, link.dataBytesSent)
        assertContentEquals(first, File(root, "a.bin").readBytes())
        assertContentEquals(second, File(root, "b.bin").readBytes())
        assertEquals(listOf("a.bin", "b.bin"), root.listFiles()!!.filter { it.isFile }.map { it.name }.sorted())
    }

    @Test
    fun `corrupted data is detected and nothing is kept`(): Unit = runBlocking(Dispatchers.Default) {
        val root = temp.newFolder("inbox")
        val outgoing = OutgoingTransfer(OutgoingTransfer.newId(), RECEIVER, listOf(BytesFile("photo.jpg", randomBytes(200_000, 5))))
        val receiver = Receiver(DirectoryStorage(root))

        val (status, _) = attempt(outgoing, receiver) { ChannelLink(it, corrupt = true) }

        assertEquals(TransferStatus.Failed(TransferFailure.CHECKSUM_MISMATCH), status)
        assertEquals(TransferStatus.Failed(TransferFailure.CHECKSUM_MISMATCH), receiver.current.value!!.snapshot.value.status)
        assertTrue(root.listFiles()!!.none { it.isFile })
        assertTrue(File(root, ".relay").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `declined transfers end cleanly`(): Unit = runBlocking(Dispatchers.Default) {
        val outgoing = OutgoingTransfer(OutgoingTransfer.newId(), RECEIVER, listOf(BytesFile("a", byteArrayOf(1))))
        val receiver = Receiver(DirectoryStorage(temp.newFolder()), accept = false)

        val (status, link) = attempt(outgoing, receiver)

        assertEquals(TransferStatus.Declined, status)
        assertEquals(0, link.dataBytesSent)
    }

    @Test
    fun `a file that shrank after selection fails as unavailable on both sides`(): Unit = runBlocking(Dispatchers.Default) {
        val outgoing = OutgoingTransfer(
            OutgoingTransfer.newId(),
            RECEIVER,
            listOf(BytesFile("report.pdf", randomBytes(1_000, 6), sizeBytes = 5_000)),
        )
        val receiver = Receiver(DirectoryStorage(temp.newFolder()))

        val (status, _) = attempt(outgoing, receiver)

        assertEquals(TransferStatus.Failed(TransferFailure.SOURCE_UNAVAILABLE), status)
        assertEquals(TransferStatus.Failed(TransferFailure.SOURCE_UNAVAILABLE), receiver.current.value!!.snapshot.value.status)
    }

    @Test
    fun `sender cancellation reaches the receiver and removes partial data`(): Unit = runBlocking(Dispatchers.Default) {
        val root = temp.newFolder("inbox")
        val outgoing = OutgoingTransfer(
            OutgoingTransfer.newId(),
            RECEIVER,
            listOf(BytesFile("movie.mkv", randomBytes(2_000_000, 7), delayPerReadMillis = 20)),
            chunkSize = 32 * 1024,
        )
        val receiver = Receiver(DirectoryStorage(root))
        val toReceiver = Channel<Any>(Channel.UNLIMITED)
        val toSender = Channel<Any>(Channel.UNLIMITED)

        coroutineScope {
            val receiverJob = with(receiver) { run(toReceiver, ChannelLink(toSender)) }
            val senderInbox = launch { for (m in toSender) outgoing.deliver(m as RelayMessage) }
            val run = launch { outgoing.run(ChannelLink(toReceiver)) }

            withTimeout(5_000) { outgoing.snapshot.first { it.progress.bytesDone > 0 } }
            outgoing.cancel()
            run.join()

            val incoming = receiver.current.value!!
            withTimeout(5_000) { incoming.snapshot.first { it.status.isFinished } }
            assertEquals(TransferStatus.Cancelled(byPeer = false), outgoing.snapshot.value.status)
            assertEquals(TransferStatus.Cancelled(byPeer = true), incoming.snapshot.value.status)
            receiverJob.cancelAndJoin()
            senderInbox.cancelAndJoin()
        }
        assertTrue(File(root, ".relay").listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `receiver can pause and resume`(): Unit = runBlocking(Dispatchers.Default) {
        val root = temp.newFolder("inbox")
        val content = randomBytes(1_000_000, 8)
        val outgoing = OutgoingTransfer(
            OutgoingTransfer.newId(),
            RECEIVER,
            listOf(BytesFile("song.flac", content, delayPerReadMillis = 10)),
            chunkSize = 32 * 1024,
        )
        val receiver = Receiver(DirectoryStorage(root))

        val watcher = launch {
            val incoming = receiver.current.filterNotNull().first()
            incoming.snapshot.first { it.progress.bytesDone > 0 }
            incoming.pause()
            outgoing.snapshot.first { it.status == TransferStatus.Paused(byPeer = true) }
            val pausedAt = incoming.snapshot.value.progress.bytesDone
            kotlinx.coroutines.delay(150)
            // At most the chunks already in flight may still arrive while paused.
            assertTrue(incoming.snapshot.value.progress.bytesDone - pausedAt <= 2 * 32 * 1024)
            incoming.resume()
        }

        val (status, _) = attempt(outgoing, receiver)
        watcher.join()

        assertEquals(TransferStatus.Completed, status)
        assertContentEquals(content, File(root, "song.flac").readBytes())
    }
}

class FileNamesTest {
    @Test
    fun `hostile names become harmless`() {
        assertEquals("_.._etc_passwd", FileNames.sanitize("/../etc/passwd"))
        assertEquals("_etc_passwd", FileNames.sanitize("../etc/passwd"))
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"))
        assertEquals("file", FileNames.sanitize("..."))
        assertEquals("a_b", FileNames.sanitize("a b"))
        assertTrue(FileNames.sanitize("x".repeat(500) + ".jpg").let { it.endsWith(".jpg") && it.encodeToByteArray().size <= 200 })
        assertEquals("Photos/2026", FileNames.sanitizeRelativePath("../Photos/./2026/"))
    }

    @Test
    fun `collisions keep the extension`() {
        val taken = setOf("vacation.zip", "vacation (1).zip", "backup.tar.gz", "README")
        assertEquals("vacation (2).zip", FileNames.resolveCollision("vacation.zip") { it in taken })
        assertEquals("backup (1).tar.gz", FileNames.resolveCollision("backup.tar.gz") { it in taken })
        assertEquals("README (1)", FileNames.resolveCollision("README") { it in taken })
        assertEquals("new.txt", FileNames.resolveCollision("new.txt") { it in taken })
    }
}

class SpeedMeterTest {
    @Test
    fun `reports speed over the window and an estimate`() {
        var now = 0L
        val meter = SpeedMeter({ now })
        assertEquals(0, meter.bytesPerSecond())
        for (i in 0..10) {
            meter.record(i * 3_600_000L) // 36 MB/s sampled every 100 ms
            now += 100
        }
        assertEquals(36_000_000, meter.bytesPerSecond())
        assertEquals(18_000L, meter.remainingMillis(648_000_000))
    }
}
