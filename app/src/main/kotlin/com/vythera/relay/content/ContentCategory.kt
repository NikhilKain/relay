package com.vythera.relay.content

import com.vythera.relay.designsystem.component.RelayContentKind
import com.vythera.relay.protocol.TransferItem
import com.vythera.relay.protocol.TransferKind

/** How received and sent things are grouped in history and the inbox. Stored by [name]. */
enum class ContentCategory(val kind: RelayContentKind) {
    PHOTOS(RelayContentKind.Photos),
    VIDEOS(RelayContentKind.Videos),
    DOCUMENTS(RelayContentKind.Files),
    FOLDER(RelayContentKind.Folder),
    APPS(RelayContentKind.App),
    LINKS(RelayContentKind.Link),
    TEXT(RelayContentKind.Text),
    CLIPBOARD(RelayContentKind.Clipboard);

    companion object {
        fun fromName(name: String): ContentCategory = entries.firstOrNull { it.name == name } ?: DOCUMENTS

        fun ofMime(mime: String?): ContentCategory {
            val type = mime.orEmpty().lowercase()
            return when {
                type.startsWith("image/") -> PHOTOS
                type.startsWith("video/") -> VIDEOS
                type == "application/vnd.android.package-archive" -> APPS
                else -> DOCUMENTS
            }
        }

        fun ofTransfer(kind: TransferKind, items: List<TransferItem>): ContentCategory = when {
            kind == TransferKind.CLIPBOARD -> CLIPBOARD
            kind == TransferKind.FOLDER || items.any { it.relativePath.isNotEmpty() } -> FOLDER
            items.isNotEmpty() && items.all { it.mimeType.startsWith("video/") } -> VIDEOS
            items.isNotEmpty() && items.all { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") } -> PHOTOS
            items.any { it.mimeType == "application/vnd.android.package-archive" } -> APPS
            else -> DOCUMENTS
        }

        fun ofText(text: String): ContentCategory = if (Links.isWebLink(text)) LINKS else TEXT
    }
}

object Links {
    private val WEB = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    fun isWebLink(text: String): Boolean = WEB.matches(text.trim())

    /** Only plain web links may be opened on request from another device. */
    fun safeWebUrl(text: String): String? = text.trim().takeIf(::isWebLink)
}
