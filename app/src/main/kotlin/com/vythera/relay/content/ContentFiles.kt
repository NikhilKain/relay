package com.vythera.relay.content

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.vythera.relay.protocol.TransferKind
import com.vythera.relay.transfer.OutgoingFile
import java.io.IOException
import java.io.InputStream

/** A file chosen through a picker or shared from another app, read through its content URI. */
class ContentUriFile(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val name: String,
    override val sizeBytes: Long,
    override val mimeType: String,
    override val relativePath: String = "",
    override val lastModifiedMillis: Long? = null,
) : OutgoingFile {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
}

object ContentFiles {
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** Resolves names, sizes and types. URIs that cannot be read are skipped rather than failing the whole send. */
    fun fromUris(context: Context, uris: List<Uri>): List<ContentUriFile> {
        val resolver = context.contentResolver
        return uris.mapNotNull { uri ->
            runCatching {
                var name: String? = null
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
                if (size < 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                if (size < 0) return@runCatching null
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                ContentUriFile(
                    resolver = resolver,
                    uri = uri,
                    name = readableName(resolver, uri, name, mime),
                    sizeBytes = size,
                    mimeType = mime,
                )
            }.getOrNull()
        }
    }

    /** Every file under a folder picked with the document tree picker, keeping the folder structure. */
    fun fromTree(context: Context, treeUri: Uri): List<ContentUriFile> {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDisplayName(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: "Folder"
        val files = mutableListOf<ContentUriFile>()

        fun walk(documentId: String, path: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, "$path/$name")
                    } else {
                        files += ContentUriFile(
                            resolver = resolver,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name = name,
                            sizeBytes = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                            mimeType = mime,
                            relativePath = path,
                            lastModifiedMillis = if (cursor.isNull(4)) null else cursor.getLong(4),
                        )
                    }
                }
            }
        }
        walk(rootId, rootName)
        return files
    }

    // The photo picker and some gallery apps hand over names like "file_00000000d980...png".
    private val GENERIC_NAME = Regex("""^(file|image|video|media)?_?[0-9a-f]{16,}(\.\w+)?$""", RegexOption.IGNORE_CASE)
    private val STAMP = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US)

    /**
     * The name the file will have on the other device. Meaningless generated names are
     * replaced with "Photo 2026-09-19 14.43.05.png", dated when the photo was taken.
     */
    private fun readableName(resolver: ContentResolver, uri: Uri, name: String?, mime: String): String {
        val given = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
        if (!GENERIC_NAME.matches(given)) return given
        val taken = runCatching {
            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        }.getOrNull() ?: System.currentTimeMillis()
        val label = when {
            mime.startsWith("image/") -> "Photo"
            mime.startsWith("video/") -> "Video"
            else -> "File"
        }
        val extension = given.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != given }
            ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return "$label ${synchronized(STAMP) { STAMP.format(java.util.Date(taken)) }}" + (extension?.let { ".$it" } ?: "")
    }

    fun kindOf(files: List<OutgoingFile>, isFolder: Boolean = false): TransferKind = when {
        isFolder -> TransferKind.FOLDER
        files.isNotEmpty() && files.all { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") } -> TransferKind.MEDIA
        files.any { it.mimeType == APK_MIME } -> TransferKind.APP
        else -> TransferKind.FILES
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
}
