package com.vythera.relay.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.vythera.relay.content.ContentFiles
import com.vythera.relay.node.Presence
import com.vythera.relay.node.RelayNode
import com.vythera.relay.protocol.ClipItem
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.transfer.TransferSnapshot
import java.io.File

/**
 * Universal clipboard on Android: copy on one device, paste on another. Text, photos,
 * videos and any other file.
 *
 * **Receiving** works in the background: Android lets any app write the clipboard. Files
 * arrive in a private cache ([directory]) and are put on the clipboard as content URIs
 * served by Relay's FileProvider, which is what apps accept when pasting images.
 *
 * **Sending** needs Relay to be in front, because since Android 10 only the focused app
 * may read the clipboard. Relay reads it whenever it gains focus, and the Quick Settings
 * tile and notification action exist to give it focus for a moment.
 *
 * Echo prevention: text is guarded by the node's `ClipboardLoopGuard`; files Relay
 * placed on the clipboard are recognised by their URI authority and never sent back.
 */
class ClipboardSync(
    private val context: Context,
    private val node: RelayNode,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface LocalClip {
        data class Text(val text: String) : LocalClip
        data class Files(val uris: List<Uri>) : LocalClip
    }

    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val authority = "${context.packageName}.clipboard"
    /** Received files Relay put on the clipboard itself; never sent back. */
    private val placedUris = mutableSetOf<String>()
    private var lastFilesSignature: String? = null
    private var lastFilesAt = 0L

    /** Private folder that clipboard transfers are received into. Only the latest clip is kept. */
    val directory: File = File(context.cacheDir, "clipboard").apply { mkdirs() }

    /** What is on the clipboard now, or null if empty, unreadable, or placed there by Relay. */
    fun readLocal(): LocalClip? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isNotEmpty()) {
            if (uris.any { it.authority == authority || it.toString() in placedUris }) return null // our own write
            return LocalClip.Files(uris)
        }
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        LocalClip.Text(text)
    }.getOrNull()

    /**
     * Sends the current clipboard to [targets]. Returns true if something was sent.
     * [force] skips the "already sent this" check, for explicit user requests.
     */
    suspend fun publish(targets: List<DeviceId>, force: Boolean = false, clip: LocalClip? = readLocal()): Boolean {
        if (targets.isEmpty()) return false
        return when (clip) {
            null -> false
            is LocalClip.Text -> node.publishClipboard(clip.text, targets).isNotEmpty()
            is LocalClip.Files -> {
                val files = ContentFiles.fromUris(context, clip.uris)
                if (files.isEmpty()) return false
                val signature = files.joinToString("|") { "${it.name}:${it.sizeBytes}" }
                val now = clock()
                if (!force && signature == lastFilesSignature && now - lastFilesAt < REPEAT_WINDOW_MILLIS) return false
                lastFilesSignature = signature
                lastFilesAt = now
                node.publishClipboardFiles(files, targets).isNotEmpty()
            }
        }
    }

    /** Trusted devices that are around right now. */
    fun defaultTargets(): List<DeviceId> =
        node.devices.value.filter { it.isTrusted && it.presence != Presence.OFFLINE && it.isCompatible }.map { it.id }

    fun applyText(clip: ClipItem) {
        node.markClipboardApplied(clip)
        setClip(ClipData.newPlainText(LABEL, clip.text))
    }

    /** Puts received files on the clipboard and drops files from earlier clips. */
    fun applyFiles(transfer: TransferSnapshot) {
        val files = transfer.storedFiles.map { File(it.location) }.filter { it.isFile }
        if (files.isEmpty()) return
        pruneExcept(files.toSet())
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val mimeTypes = transfer.storedFiles.map { it.mimeType }.distinct().toTypedArray()
        val data = ClipData(ClipDescription(LABEL, mimeTypes), ClipData.Item(uris.first()))
        uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
        setClip(data)
    }

    /**
     * Ecosystem: files a trusted device sent or dropped are also put on the clipboard, so
     * they paste straight into a chat or an email. They stay where they were saved.
     */
    fun applyReceived(transfer: TransferSnapshot) {
        val files = transfer.storedFiles.filter { it.location.startsWith("content://") }
        if (files.isEmpty()) return
        val uris = files.map { Uri.parse(it.location) }
        placedUris.clear()
        placedUris += uris.map(Uri::toString)
        val data = ClipData(ClipDescription(LABEL, files.map { it.mimeType }.distinct().toTypedArray()), ClipData.Item(uris.first()))
        uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
        setClip(data)
    }

    /** Files of the previous clips are no longer on the clipboard; delete them. */
    private fun pruneExcept(keep: Set<File>) {
        val keepPaths = keep.map { it.absolutePath }.toSet()
        directory.walkBottomUp()
            .filter { it != directory && it.name != ".relay" && !it.absolutePath.contains("${File.separator}.relay") }
            .forEach { file ->
                if (file.isFile && file.absolutePath !in keepPaths) file.delete()
                if (file.isDirectory && file.listFiles().isNullOrEmpty()) file.delete()
            }
    }

    private fun setClip(data: ClipData) {
        mainHandler.post {
            runCatching { clipboard.setPrimaryClip(data) }
                .onSuccess { Log.i(TAG, "Clipboard set: ${data.description.mimeTypes()} x${data.itemCount}") }
                .onFailure { Log.w(TAG, "Could not set clipboard", it) }
        }
    }

    private fun ClipDescription.mimeTypes() = (0 until mimeTypeCount).map(::getMimeType)

    private companion object {
        const val TAG = "Relay"
        const val LABEL = "Relay"
        const val REPEAT_WINDOW_MILLIS = 60_000L
    }
}
