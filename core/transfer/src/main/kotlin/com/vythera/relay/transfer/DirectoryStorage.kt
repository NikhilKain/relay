package com.vythera.relay.transfer

import com.vythera.relay.protocol.TransferItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [IncomingStorage] on a plain directory. Used by desktop clients and by tests.
 * Android stores into MediaStore instead (see `:core:data`).
 *
 * Layout:
 * ```
 * <root>/vacation.zip                      finished files
 * <root>/Photos/IMG_0001.jpg               folder transfers keep their structure
 * <root>/.relay/<transfer>-<index>.part    bytes received so far
 * <root>/.relay/<transfer>-<index>.json    which item the partial belongs to / commit record
 * ```
 */
class DirectoryStorage(private val root: File) : IncomingStorage {
    private val work = File(root, ".relay")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Record(
        val name: String,
        val sizeBytes: Long,
        val relativePath: String,
        val committed: CommittedRecord? = null,
    )

    @Serializable
    private data class CommittedRecord(val location: String, val storedName: String, val sha256: String)

    override fun availableBytes(): Long? = root.apply { mkdirs() }.usableSpace.takeIf { it > 0 }

    override fun findCommitted(transferId: String, item: TransferItem): StoredFile? {
        val record = readRecord(transferId, item.index)?.takeIf { it.matches(item) } ?: return null
        val committed = record.committed ?: return null
        if (!File(committed.location).isFile) return null
        return StoredFile(item.index, committed.storedName, committed.location, item.mimeType, item.sizeBytes, committed.sha256)
    }

    @Synchronized
    override fun openPartial(transferId: String, item: TransferItem): IncomingFile {
        ensureDirectory(work)
        val part = partFile(transferId, item.index)
        val record = readRecord(transferId, item.index)
        if (record == null || !record.matches(item) || record.committed != null) {
            part.delete()
            writeRecord(transferId, item.index, Record(item.name, item.sizeBytes, item.relativePath))
        }
        return Partial(transferId, item, part)
    }

    @Synchronized
    override fun discardPartials(transferId: String) {
        val prefix = "${safeId(transferId)}-"
        work.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private inner class Partial(
        private val transferId: String,
        private val item: TransferItem,
        private val part: File,
    ) : IncomingFile {
        override val length: Long get() = if (part.isFile) part.length() else 0

        override fun openExisting(): InputStream = FileInputStream(part)

        override fun openAppend(): OutputStream = try {
            FileOutputStream(part, true)
        } catch (e: IOException) {
            throw asStorageError(e)
        }

        override fun commit(sha256: String): StoredFile = synchronized(this@DirectoryStorage) {
            if (!part.exists()) part.createNewFile()
            val directory = File(root, FileNames.sanitizeRelativePath(item.relativePath))
            ensureDirectory(directory)
            val name = FileNames.resolveCollision(FileNames.sanitize(item.name)) { File(directory, it).exists() }
            val target = File(directory, name)
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), target.toPath())
            } catch (e: IOException) {
                throw asStorageError(e)
            }
            item.lastModifiedMillis?.let { target.setLastModified(it) }
            writeRecord(
                transferId,
                item.index,
                Record(item.name, item.sizeBytes, item.relativePath, CommittedRecord(target.absolutePath, name, sha256)),
            )
            StoredFile(item.index, name, target.absolutePath, item.mimeType, item.sizeBytes, sha256)
        }

        override fun discard() {
            synchronized(this@DirectoryStorage) {
                part.delete()
                recordFile(transferId, item.index).delete()
            }
        }
    }

    private fun Record.matches(item: TransferItem) =
        name == item.name && sizeBytes == item.sizeBytes && relativePath == item.relativePath

    private fun readRecord(transferId: String, index: Int): Record? {
        val file = recordFile(transferId, index)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(Record.serializer(), file.readText()) }.getOrNull()
    }

    private fun writeRecord(transferId: String, index: Int, record: Record) {
        ensureDirectory(work)
        recordFile(transferId, index).writeText(json.encodeToString(Record.serializer(), record))
    }

    private fun partFile(transferId: String, index: Int) = File(work, "${safeId(transferId)}-$index.part")
    private fun recordFile(transferId: String, index: Int) = File(work, "${safeId(transferId)}-$index.json")

    /** Transfer ids come from the network; keep them from escaping the work directory. */
    private fun safeId(transferId: String) = transferId.filter { it.isLetterOrDigit() || it == '-' }.take(64)

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
    }

    private fun asStorageError(e: IOException): IOException =
        if (root.usableSpace in 0..MIN_FREE_BYTES) StorageFullException() else e

    private companion object {
        const val MIN_FREE_BYTES = 1024L * 1024
    }
}
