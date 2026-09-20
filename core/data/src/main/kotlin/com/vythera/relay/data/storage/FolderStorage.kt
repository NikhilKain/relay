package com.vythera.relay.data.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import com.vythera.relay.protocol.TransferItem
import com.vythera.relay.transfer.FileNames
import com.vythera.relay.transfer.IncomingFile
import com.vythera.relay.transfer.IncomingStorage
import com.vythera.relay.transfer.StorageFullException
import com.vythera.relay.transfer.StoredFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Receives into a folder the user picked (any folder on the phone or an SD card), through
 * the Storage Access Framework: Relay only ever gets access to that one folder.
 *
 * Files are written as `name.part` while arriving and renamed when verified, so a
 * half-received file never looks finished. Folder transfers recreate their structure
 * under the chosen folder.
 */
class FolderStorage(private val context: Context, private val treeUri: Uri) : IncomingStorage {
    private val resolver: ContentResolver = context.contentResolver
    private val work = File(context.noBackupFilesDir, "incoming-folder")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Record(
        val name: String,
        val sizeBytes: Long,
        val relativePath: String,
        val tree: String,
        val uri: String,
        val committedName: String? = null,
        val sha256: String? = null,
    )

    private val rootDocument: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    override fun availableBytes(): Long? = runCatching {
        resolver.openFileDescriptor(rootDocument, "r")?.use { StatFs("/proc/self/fd/${it.fd}").availableBytes }
    }.getOrNull()

    override fun findCommitted(transferId: String, item: TransferItem): StoredFile? {
        val record = readRecord(transferId, item.index)?.takeIf { it.matches(item) } ?: return null
        val name = record.committedName ?: return null
        val sha = record.sha256 ?: return null
        if (!exists(Uri.parse(record.uri))) return null
        return StoredFile(item.index, name, record.uri, item.mimeType, item.sizeBytes, sha)
    }

    @Synchronized
    override fun openPartial(transferId: String, item: TransferItem): IncomingFile {
        val existing = readRecord(transferId, item.index)
        if (existing != null && existing.matches(item) && existing.committedName == null && exists(Uri.parse(existing.uri))) {
            return Partial(transferId, item, Uri.parse(existing.uri))
        }
        existing?.takeIf { it.committedName == null }?.let { runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(it.uri)) } }
        val parent = directoryFor(item.relativePath)
        val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", FileNames.sanitize(item.name) + PART)
            ?: throw IOException("Could not create a file in the chosen folder")
        writeRecord(transferId, item.index, Record(item.name, item.sizeBytes, item.relativePath, treeUri.toString(), uri.toString()))
        return Partial(transferId, item, uri)
    }

    @Synchronized
    override fun discardPartials(transferId: String) {
        val prefix = "${safeId(transferId)}-"
        work.listFiles { file -> file.name.startsWith(prefix) }?.forEach { file ->
            val record = runCatching { json.decodeFromString(Record.serializer(), file.readText()) }.getOrNull()
            if (record != null && record.committedName == null) {
                runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(record.uri)) }
            }
            file.delete()
        }
    }

    private inner class Partial(private val transferId: String, private val item: TransferItem, private val uri: Uri) : IncomingFile {
        override val length: Long
            get() = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)

        override fun openExisting(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Partial file unavailable")

        override fun openAppend(): OutputStream {
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open $uri")
            val stream = FileOutputStream(descriptor.fileDescriptor)
            stream.channel.position(stream.channel.size())
            return ErrorMappingStream(stream, descriptor)
        }

        override fun commit(sha256: String): StoredFile = synchronized(this@FolderStorage) {
            val wanted = FileNames.sanitize(item.name)
            // Rename "photo.jpg.part" to "photo.jpg", or "photo (1).jpg" if taken.
            val siblings = childNames(directoryFor(item.relativePath))
            val finalName = FileNames.resolveCollision(wanted) { it in siblings }
            val finalUri = runCatching { DocumentsContract.renameDocument(resolver, uri, finalName) }.getOrNull() ?: uri
            val name = if (finalUri == uri) queryName(uri) ?: finalName else finalName
            writeRecord(transferId, item.index, Record(item.name, item.sizeBytes, item.relativePath, treeUri.toString(), finalUri.toString(), name, sha256))
            StoredFile(item.index, name, finalUri.toString(), item.mimeType, item.sizeBytes, sha256)
        }

        override fun discard() {
            synchronized(this@FolderStorage) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                recordFile(transferId, item.index).delete()
            }
        }
    }

    private class ErrorMappingStream(private val stream: FileOutputStream, private val descriptor: ParcelFileDescriptor) : FilterOutputStream(stream) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            try {
                stream.write(b, off, len)
            } catch (e: IOException) {
                val message = e.message.orEmpty()
                throw if ("ENOSPC" in message || "No space" in message) StorageFullException() else e
            }
        }

        override fun close() {
            try {
                stream.close()
            } finally {
                descriptor.close()
            }
        }
    }

    /** Finds or creates the sub-folder for a folder transfer. */
    private fun directoryFor(relativePath: String): Uri {
        var current = rootDocument
        for (segment in FileNames.sanitizeRelativePath(relativePath).split('/').filter { it.isNotEmpty() }) {
            current = findChild(current, segment, directory = true)
                ?: DocumentsContract.createDocument(resolver, current, DocumentsContract.Document.MIME_TYPE_DIR, segment)
                ?: throw IOException("Could not create folder $segment")
        }
        return current
    }

    private fun findChild(parent: Uri, name: String, directory: Boolean): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (cursor.getString(1) == name && isDirectory == directory) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun childNames(parent: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.orEmpty()
    }

    private fun exists(uri: Uri): Boolean =
        runCatching { resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count > 0 } ?: false }
            .getOrDefault(false)

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun Record.matches(item: TransferItem) =
        tree == treeUri.toString() && name == item.name && sizeBytes == item.sizeBytes && relativePath == item.relativePath

    private fun readRecord(transferId: String, index: Int): Record? {
        val file = recordFile(transferId, index)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(Record.serializer(), file.readText()) }.getOrNull()
    }

    private fun writeRecord(transferId: String, index: Int, record: Record) {
        if (!work.isDirectory) work.mkdirs()
        recordFile(transferId, index).writeText(json.encodeToString(Record.serializer(), record))
    }

    private fun recordFile(transferId: String, index: Int) = File(work, "${safeId(transferId)}-$index.json")
    private fun safeId(transferId: String) = transferId.filter { it.isLetterOrDigit() || it == '-' }.take(64)

    private companion object {
        const val PART = ".part"
    }
}

/**
 * The storage received files go to right now: the user's chosen folder, or Relay's
 * default (Pictures/Movies/Download under "Relay"). Switching applies to transfers that
 * start after the switch.
 */
class SwitchableStorage(private val fallback: IncomingStorage) : IncomingStorage {
    @Volatile var current: IncomingStorage = fallback

    fun useDefault() {
        current = fallback
    }

    override fun availableBytes() = current.availableBytes()
    override fun findCommitted(transferId: String, item: TransferItem) =
        current.findCommitted(transferId, item) ?: fallback.takeIf { it !== current }?.findCommitted(transferId, item)
    override fun openPartial(transferId: String, item: TransferItem) = current.openPartial(transferId, item)
    override fun discardPartials(transferId: String) {
        current.discardPartials(transferId)
        if (fallback !== current) fallback.discardPartials(transferId)
    }
}
