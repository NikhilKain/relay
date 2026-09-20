package com.vythera.relay.data.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
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
 * Receives straight into shared storage through MediaStore, so nothing is copied twice
 * and finished files appear where people look for them:
 *
 * - photos in Pictures/Relay, videos in Movies/Relay, audio in Music/Relay;
 * - everything else, and folder transfers, in Download/Relay.
 *
 * While receiving, the item is `IS_PENDING`, invisible to other apps. A small record in
 * app-private storage remembers which pending item belongs to which transfer file, which
 * is what lets a transfer resume after the app was killed. Uses scoped storage only: no
 * storage permission is needed on Android 10+.
 */
class MediaStoreStorage(private val context: Context) : IncomingStorage {
    private val resolver: ContentResolver = context.contentResolver
    private val work = File(context.noBackupFilesDir, "incoming")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Record(
        val name: String,
        val sizeBytes: Long,
        val relativePath: String,
        val uri: String,
        val committedName: String? = null,
        val sha256: String? = null,
    )

    override fun availableBytes(): Long? =
        runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()

    override fun findCommitted(transferId: String, item: TransferItem): StoredFile? {
        val record = readRecord(transferId, item.index)?.takeIf { it.matches(item) } ?: return null
        val name = record.committedName ?: return null
        val sha = record.sha256 ?: return null
        val uri = Uri.parse(record.uri)
        if (!exists(uri)) return null
        return StoredFile(item.index, name, record.uri, item.mimeType, item.sizeBytes, sha)
    }

    @Synchronized
    override fun openPartial(transferId: String, item: TransferItem): IncomingFile {
        val existing = readRecord(transferId, item.index)
        if (existing != null && existing.matches(item) && existing.committedName == null) {
            val uri = Uri.parse(existing.uri)
            if (exists(uri)) return Partial(transferId, item, uri)
        }
        existing?.let { runCatching { if (it.committedName == null) resolver.delete(Uri.parse(it.uri), null, null) } }
        val uri = insertPending(item)
        writeRecord(transferId, item.index, Record(item.name, item.sizeBytes, item.relativePath, uri.toString()))
        return Partial(transferId, item, uri)
    }

    @Synchronized
    override fun discardPartials(transferId: String) {
        val prefix = "${safeId(transferId)}-"
        work.listFiles { file -> file.name.startsWith(prefix) }?.forEach { file ->
            val record = runCatching { json.decodeFromString(Record.serializer(), file.readText()) }.getOrNull()
            if (record != null && record.committedName == null) {
                runCatching { resolver.delete(Uri.parse(record.uri), null, null) }
            }
            file.delete()
        }
    }

    private inner class Partial(
        private val transferId: String,
        private val item: TransferItem,
        private val uri: Uri,
    ) : IncomingFile {
        override val length: Long
            get() = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)

        override fun openExisting(): InputStream =
            resolver.openInputStream(uri) ?: throw IOException("Partial file unavailable")

        override fun openAppend(): OutputStream {
            val descriptor = try {
                resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open $uri")
            } catch (e: IOException) {
                throw asStorageError(e)
            }
            val stream = FileOutputStream(descriptor.fileDescriptor)
            stream.channel.position(stream.channel.size())
            return ErrorMappingStream(stream, descriptor)
        }

        override fun commit(sha256: String): StoredFile = synchronized(this@MediaStoreStorage) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // MediaStore renames on collision ("vacation (1).zip"); read back what it chose.
            val finalName = queryName(uri) ?: FileNames.sanitize(item.name)
            writeRecord(
                transferId,
                item.index,
                Record(item.name, item.sizeBytes, item.relativePath, uri.toString(), finalName, sha256),
            )
            StoredFile(item.index, finalName, uri.toString(), item.mimeType, item.sizeBytes, sha256)
        }

        override fun discard() {
            synchronized(this@MediaStoreStorage) {
                runCatching { resolver.delete(uri, null, null) }
                recordFile(transferId, item.index).delete()
            }
        }
    }

    private class ErrorMappingStream(
        private val stream: FileOutputStream,
        private val descriptor: ParcelFileDescriptor,
    ) : FilterOutputStream(stream) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            try {
                stream.write(b, off, len)
            } catch (e: IOException) {
                throw asStorageError(e)
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

    private fun insertPending(item: TransferItem): Uri {
        val mime = item.mimeType.lowercase()
        val folder = FileNames.sanitizeRelativePath(item.relativePath)
        val (collection, base) = when {
            folder.isNotEmpty() -> downloads() to Environment.DIRECTORY_DOWNLOADS
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            else -> downloads() to Environment.DIRECTORY_DOWNLOADS
        }
        val relativePath = listOf(base, "Relay", folder).filter { it.isNotEmpty() }.joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileNames.sanitize(item.name))
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            item.lastModifiedMillis?.let { put(MediaStore.MediaColumns.DATE_MODIFIED, it / 1000) }
        }
        return try {
            resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            // A MIME type the media collection rejects: fall back to Downloads.
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Relay")
            resolver.insert(downloads(), values)
        } ?: throw IOException("MediaStore refused the file")
    }

    private fun downloads(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun exists(uri: Uri): Boolean =
        runCatching { resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false }
            .getOrDefault(false)

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun Record.matches(item: TransferItem) =
        name == item.name && sizeBytes == item.sizeBytes && relativePath == item.relativePath

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
        fun asStorageError(e: IOException): IOException {
            val message = e.message.orEmpty()
            return if ("ENOSPC" in message || "No space" in message) StorageFullException() else e
        }
    }
}
