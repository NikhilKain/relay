package com.vythera.relay.protocol

import kotlinx.serialization.Serializable

/**
 * A feature a device can perform on request of a trusted peer.
 *
 * Capabilities are open-ended strings rather than an enum: a device announces what it
 * supports, peers only offer UI for capabilities the other side announced, and ids
 * this build does not recognise are carried through untouched. New remote actions
 * (ring phone, media control, notification mirroring…) are added by defining an id
 * here and a handler in the node — no protocol version bump needed.
 *
 * Naming: `<area>.<action>`, lowercase, dot separated.
 */
@JvmInline
@Serializable
value class Capability(val id: String) {
    override fun toString(): String = id

    companion object {
        val TransferFiles = Capability("transfer.files")
        val TransferFolders = Capability("transfer.folders")
        val TransferResume = Capability("transfer.resume")
        val Text = Capability("text")
        val ClipboardPush = Capability("clipboard.push")
        val ClipboardHistory = Capability("clipboard.history")

        /** Accepts [TransferKind.CLIPBOARD] transfers: copied photos and files, not just text. */
        val ClipboardFiles = Capability("clipboard.files")

        /** Generic prefix; the full id is `continue.<ContinueActivity.kind>`. */
        const val CONTINUE_PREFIX = "continue."
        val ContinueWebPage = Capability(CONTINUE_PREFIX + ContinueActivity.KIND_WEB_PAGE)

        // Reserved for later phases. Declared so ids stay stable across clients;
        // no build advertises them until a handler exists.
        val NotificationMirror = Capability("notification.mirror")
        val RingDevice = Capability("remote.ring")
        val MediaControl = Capability("remote.media")
        val PresentationControl = Capability("remote.presentation")

        fun forContinue(kind: String) = Capability(CONTINUE_PREFIX + kind)
    }
}
