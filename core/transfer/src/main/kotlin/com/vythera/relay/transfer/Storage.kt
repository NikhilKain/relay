package com.vythera.relay.transfer

import com.vythera.relay.protocol.TransferItem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Something the user chose to send. Android backs this with a content URI, desktops with a file. */
interface OutgoingFile {
    val name: String
    val sizeBytes: Long
    val mimeType: String

    /** Folder-relative directory for folder transfers, `/` separated. */
    val relativePath: String get() = ""
    val lastModifiedMillis: Long? get() = null

    /** Opens a fresh stream from the first byte. Throws [IOException] if the file is gone. */
    fun open(): InputStream
}

/**
 * Where received files go. Implementations must be crash-safe: bytes written to an
 * [IncomingFile] survive process death so an interrupted transfer can resume.
 */
interface IncomingStorage {
    /** Free space, or null if the platform cannot tell. */
    fun availableBytes(): Long?

    /** A file of this transfer that was already received and verified, e.g. before a reconnect. */
    fun findCommitted(transferId: String, item: TransferItem): StoredFile?

    /**
     * Returns the partial file for this item, creating it if needed. If a partial exists
     * but was created for a different item (name or size changed) it is discarded.
     */
    fun openPartial(transferId: String, item: TransferItem): IncomingFile

    /** Removes every partial of a transfer, e.g. after cancellation. */
    fun discardPartials(transferId: String)
}

interface IncomingFile {
    /** Bytes already on disk. */
    val length: Long

    /** Reads the bytes already on disk, to re-establish the checksum when resuming. */
    fun openExisting(): InputStream

    /** Appends after [length]. */
    fun openAppend(): OutputStream

    /** Moves the verified file to its final, user-visible location, resolving name collisions. */
    fun commit(sha256: String): StoredFile

    fun discard()
}

/** Thrown by storage implementations when the disk is full. */
class StorageFullException(message: String = "Storage full") : IOException(message)
