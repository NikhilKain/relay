package com.vythera.relay.desktop

import com.vythera.relay.node.NodeEvent
import com.vythera.relay.node.Presence
import com.vythera.relay.node.RelayNode
import com.vythera.relay.protocol.TransferKind
import com.vythera.relay.transfer.OutgoingFile
import com.vythera.relay.transfer.TransferDirection
import com.vythera.relay.transfer.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Universal clipboard for a desktop: watches the system clipboard and mirrors it to
 * trusted devices, and puts what they copy onto this clipboard.
 *
 * - text is sent as a clipboard message;
 * - a copied image (screenshot, "Copy image" in a browser) is saved as PNG and sent;
 * - copied files (Ctrl+C in Explorer / Files) are sent as they are.
 *
 * Received files are offered to the desktop as a file list, plus as an image when there
 * is exactly one picture, so they paste into Explorer, Paint, Word or a chat window.
 *
 * Desktop clipboards have no change notification in AWT, so the clipboard is sampled a
 * few times a second; the signature check keeps that cheap and stops echoes.
 */
class DesktopClipboard(
    private val node: RelayNode,
    private val scope: CoroutineScope,
    private val workDir: File,
    private val log: (String) -> Unit,
) {
    private val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
    private var lastSeen: String? = null

    /** Follows the user's clipboard setting. When off, nothing is read or sent, and nothing is accepted. */
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            node.setClipboardSync(value)
        }

    /** Ecosystem mode: files a trusted device sends or drops here are also put on the clipboard, ready to paste. */
    @Volatile var pasteReceivedFiles: Boolean = false

    fun start() {
        if (GraphicsEnvironment.isHeadless()) {
            log("clipboard: no desktop session, clipboard sync disabled")
            return
        }
        node.setClipboardSync(enabled)
        lastSeen = runCatching { signatureOf(clipboard.getContents(null)) }.getOrNull()
        scope.launch(Dispatchers.IO) { watch() }
        scope.launch { node.events.collect(::onEvent) }
        log("clipboard: syncing with trusted devices")
    }

    private suspend fun watch() {
        while (scope.isActive) {
            delay(POLL_MILLIS)
            val contents = runCatching { clipboard.getContents(null) }.getOrNull() ?: continue
            val signature = runCatching { signatureOf(contents) }.getOrNull() ?: continue
            if (signature == lastSeen) continue
            lastSeen = signature
            if (!enabled) continue
            val targets = node.devices.value.filter { it.isTrusted && it.presence != Presence.OFFLINE }.map { it.id }
            if (targets.isEmpty()) continue
            publish(contents, targets)
        }
    }

    private suspend fun publish(contents: Transferable, targets: List<com.vythera.relay.protocol.DeviceId>) {
        when {
            contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val files = (contents.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).filter { it.isFile }
                if (files.isNotEmpty()) {
                    node.publishClipboardFiles(files.map(::LocalFile), targets)
                    log("clipboard: sent ${files.size} file(s) ${files.map { it.name }}")
                }
            }
            contents.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                val image = contents.getTransferData(DataFlavor.imageFlavor) as Image
                val file = File(workDir, "Clipboard image ${LocalDateTime.now().format(STAMP)}.png")
                ImageIO.write(image.toBuffered(), "png", file)
                node.publishClipboardFiles(listOf(LocalFile(file)), targets)
                log("clipboard: sent image ${file.name}")
            }
            contents.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                val text = contents.getTransferData(DataFlavor.stringFlavor) as String
                if (text.isNotBlank()) {
                    val sent = node.publishClipboard(text, targets)
                    log("clipboard: sent text (${text.length} chars) to ${sent.size} device(s)")
                }
            }
        }
    }

    private fun onEvent(event: NodeEvent) {
        when (event) {
            is NodeEvent.ClipboardReceived -> {
                if (!enabled) return
                node.markClipboardApplied(event.clip)
                set(StringSelection(event.clip.text))
                log("clipboard: received text (${event.clip.text.length} chars)")
            }
            is NodeEvent.TransferFinished -> {
                val transfer = event.transfer
                if (transfer.direction != TransferDirection.INCOMING || transfer.status != TransferStatus.Completed) return
                val isClipboard = transfer.kind == TransferKind.CLIPBOARD
                if (!isClipboard && !(pasteReceivedFiles && enabled && transfer.kind != TransferKind.FOLDER)) return
                val files = transfer.storedFiles.map { File(it.location) }.filter { it.isFile }
                if (files.isEmpty()) return
                set(FilesSelection(files))
                log("clipboard: received ${files.map { it.name }}")
            }
            else -> Unit
        }
    }

    private fun set(contents: Transferable) {
        // Record first, so the watcher recognises our own write and does not send it back.
        lastSeen = signatureOf(contents)
        runCatching { clipboard.setContents(contents, null) }.onFailure { log("clipboard: could not write (${it.message})") }
    }

    private fun signatureOf(contents: Transferable?): String? {
        contents ?: return null
        return when {
            contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                @Suppress("UNCHECKED_CAST")
                val files = contents.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                "files:" + files.joinToString("|") { "${it.absolutePath}:${it.length()}" }
            }
            contents.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
                val image = (contents.getTransferData(DataFlavor.imageFlavor) as Image).toBuffered()
                var hash = 17L
                val stepX = maxOf(1, image.width / 32)
                val stepY = maxOf(1, image.height / 32)
                for (y in 0 until image.height step stepY) for (x in 0 until image.width step stepX) hash = hash * 31 + image.getRGB(x, y)
                "image:${image.width}x${image.height}:$hash"
            }
            contents.isDataFlavorSupported(DataFlavor.stringFlavor) ->
                "text:" + (contents.getTransferData(DataFlavor.stringFlavor) as String).hashCode()
            else -> null
        }
    }

    private fun Image.toBuffered(): BufferedImage {
        if (this is BufferedImage) return this
        val buffered = BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(this@toBuffered, 0, 0, null); dispose() }
        return buffered
    }

    /** Files for the desktop clipboard; a single picture is also offered as an image. */
    private class FilesSelection(private val files: List<File>) : Transferable {
        private val image: Image? = files.singleOrNull()
            ?.takeIf { URLConnection.guessContentTypeFromName(it.name)?.startsWith("image/") == true }
            ?.let { runCatching { ImageIO.read(it) }.getOrNull() }

        override fun getTransferDataFlavors(): Array<DataFlavor> =
            listOfNotNull(DataFlavor.javaFileListFlavor, image?.let { DataFlavor.imageFlavor }).toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in transferDataFlavors

        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            DataFlavor.javaFileListFlavor -> files
            DataFlavor.imageFlavor -> image ?: throw UnsupportedFlavorException(flavor)
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    private class LocalFile(private val file: File) : OutgoingFile {
        override val name: String = file.name
        override val sizeBytes: Long = file.length()
        override val mimeType: String = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        override val lastModifiedMillis: Long = file.lastModified()
        override fun open(): InputStream = file.inputStream()
    }

    private companion object {
        const val POLL_MILLIS = 500L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")
    }
}
