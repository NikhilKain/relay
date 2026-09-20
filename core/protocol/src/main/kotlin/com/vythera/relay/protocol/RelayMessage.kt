package com.vythera.relay.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Control messages of Relay Protocol v1.
 *
 * Encoded as a JSON object inside a [FrameKind.CONTROL] frame; the `type` property
 * carries the [SerialName]. File bytes never travel as JSON: they use
 * [FrameKind.DATA] frames, see [DataChunk].
 *
 * Compatibility rules (enforced by [MessageCodec]):
 * - unknown properties are ignored;
 * - unknown `type`s decode to [DecodeResult.Unknown] rather than failing the connection;
 * - new properties must have defaults so older senders stay valid.
 */
@Serializable
sealed interface RelayMessage {

    // Session ------------------------------------------------------------------

    /** First message in both directions on every connection. */
    @Serializable
    @SerialName("DEVICE_HELLO")
    data class Hello(
        val device: DeviceInfo,
        /**
         * TCP port the sender accepts connections on. Lets the receiver reach it later
         * even when discovery datagrams are filtered in one direction.
         */
        val listenPort: Int? = null,
    ) : RelayMessage

    /** Sent when a device's capabilities change mid-session (e.g. clipboard sync toggled). */
    @Serializable
    @SerialName("CAPABILITIES")
    data class Capabilities(val capabilities: Set<Capability>) : RelayMessage

    @Serializable
    @SerialName("PING")
    data class Ping(val nonce: Long) : RelayMessage

    @Serializable
    @SerialName("PONG")
    data class Pong(val nonce: Long) : RelayMessage

    /** Polite close. The receiver should not treat the disconnect as a failure. */
    @Serializable
    @SerialName("GOODBYE")
    data class Goodbye(val reason: GoodbyeReason = GoodbyeReason.CLOSING) : RelayMessage

    @Serializable
    @SerialName("ERROR")
    data class Error(
        val code: ErrorCode,
        /** Developer-facing detail. Never shown to users verbatim. */
        val detail: String? = null,
        /** Transfer, pairing request or message id this error relates to. */
        val relatesTo: String? = null,
    ) : RelayMessage

    // Pairing ------------------------------------------------------------------
    //
    // Numeric comparison with a nonce commitment, the construction Bluetooth Secure
    // Simple Pairing uses. Both TLS certificates are already bound to the connection;
    // the commitment stops an active attacker from grinding keys until the codes on
    // both screens happen to match. See docs/security-model.md.

    /** Initiator to responder: commits to a random nonce without revealing it. */
    @Serializable
    @SerialName("PAIR_REQUEST")
    data class PairRequest(val requestId: String, val commitment: String) : RelayMessage

    /** Responder to initiator: its own nonce, sent only after the commitment arrived. */
    @Serializable
    @SerialName("PAIR_NONCE")
    data class PairNonce(val requestId: String, val nonce: String) : RelayMessage

    /** Initiator to responder: reveals the committed nonce. Both sides can now derive the code. */
    @Serializable
    @SerialName("PAIR_REVEAL")
    data class PairReveal(val requestId: String, val nonce: String) : RelayMessage

    /** The user confirmed on the sending side. */
    @Serializable
    @SerialName("PAIR_ACCEPT")
    data class PairAccept(val requestId: String) : RelayMessage

    @Serializable
    @SerialName("PAIR_DECLINE")
    data class PairDecline(val requestId: String) : RelayMessage

    /** The sender removed the receiving device from its trusted devices. */
    @Serializable
    @SerialName("UNPAIR")
    data object Unpair : RelayMessage

    // Transfers ----------------------------------------------------------------

    @Serializable
    @SerialName("TRANSFER_REQUEST")
    data class TransferRequest(
        val transferId: String,
        val items: List<TransferItem>,
        val kind: TransferKind = TransferKind.FILES,
    ) : RelayMessage {
        val totalBytes: Long get() = items.sumOf { it.sizeBytes }
    }

    /**
     * Receiver accepts. [resumeFrom] lists files the receiver already holds bytes of,
     * which happens when a sender re-requests the same transfer id after an interruption.
     */
    @Serializable
    @SerialName("TRANSFER_ACCEPT")
    data class TransferAccept(
        val transferId: String,
        val resumeFrom: List<ResumePoint> = emptyList(),
    ) : RelayMessage

    @Serializable
    @SerialName("TRANSFER_DECLINE")
    data class TransferDecline(
        val transferId: String,
        val reason: DeclineReason = DeclineReason.USER_DECLINED,
    ) : RelayMessage

    /** Sender to receiver after the last chunk of a file. */
    @Serializable
    @SerialName("TRANSFER_FILE_COMPLETE")
    data class TransferFileComplete(
        val transferId: String,
        val index: Int,
        /** Lowercase hex SHA-256 of the whole file. */
        val sha256: String,
    ) : RelayMessage

    /** Receiver to sender once every file has been written and verified. */
    @Serializable
    @SerialName("TRANSFER_COMPLETE")
    data class TransferComplete(val transferId: String) : RelayMessage

    /** Either side asks the sender to stop sending chunks without abandoning the transfer. */
    @Serializable
    @SerialName("TRANSFER_PAUSE")
    data class TransferPause(val transferId: String) : RelayMessage

    /** Either side asks to continue a paused transfer. The receiver's copy carries its offsets. */
    @Serializable
    @SerialName("TRANSFER_RESUME")
    data class TransferResume(
        val transferId: String,
        val resumeFrom: List<ResumePoint> = emptyList(),
    ) : RelayMessage

    @Serializable
    @SerialName("TRANSFER_CANCEL")
    data class TransferCancel(
        val transferId: String,
        val reason: CancelReason = CancelReason.USER_CANCELLED,
    ) : RelayMessage

    // Content ------------------------------------------------------------------

    @Serializable
    @SerialName("TEXT_MESSAGE")
    data class Text(val messageId: String, val text: String) : RelayMessage {
        init {
            require(text.length <= MAX_TEXT_LENGTH) { "Text exceeds $MAX_TEXT_LENGTH characters" }
        }
    }

    @Serializable
    @SerialName("CLIPBOARD_UPDATE")
    data class ClipboardUpdate(val clip: ClipItem) : RelayMessage

    /**
     * "Continue this on that device." Generic over [ContinueActivity.kind]; opening a
     * web page (`web.page`) is the first kind and is what earlier drafts called OPEN_URL.
     */
    @Serializable
    @SerialName("CONTINUE")
    data class Continue(val requestId: String, val activity: ContinueActivity) : RelayMessage

    companion object {
        const val MAX_TEXT_LENGTH = 64_000
    }
}

@Serializable
data class TransferItem(
    val index: Int,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String = "application/octet-stream",
    /** Folder-relative directory using `/`, without the file name. Empty for loose files. */
    val relativePath: String = "",
    val lastModifiedMillis: Long? = null,
) {
    init {
        require(index >= 0) { "Negative item index" }
        require(sizeBytes >= 0) { "Negative item size" }
        require(name.isNotBlank()) { "Blank item name" }
    }
}

@Serializable
data class ResumePoint(val index: Int, val offset: Long)

@Serializable(with = TransferKind.Serializer::class)
enum class TransferKind(val wireName: String) {
    FILES("files"),
    MEDIA("media"),
    FOLDER("folder"),
    APP("app"),

    /** Files copied on one device and meant for the other device's clipboard, not its inbox. */
    CLIPBOARD("clipboard");

    object Serializer : LenientEnumSerializer<TransferKind>("TransferKind", entries.toTypedArray(), FILES, { it.wireName })
}

@Serializable(with = DeclineReason.Serializer::class)
enum class DeclineReason(val wireName: String) {
    USER_DECLINED("user_declined"),
    NOT_TRUSTED("not_trusted"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    BUSY("busy"),
    UNKNOWN("unknown");

    object Serializer : LenientEnumSerializer<DeclineReason>("DeclineReason", entries.toTypedArray(), UNKNOWN, { it.wireName })
}

@Serializable(with = CancelReason.Serializer::class)
enum class CancelReason(val wireName: String) {
    USER_CANCELLED("user_cancelled"),
    CHECKSUM_MISMATCH("checksum_mismatch"),
    STORAGE_FULL("storage_full"),
    STORAGE_UNAVAILABLE("storage_unavailable"),
    SOURCE_UNAVAILABLE("source_unavailable"),
    PROTOCOL_VIOLATION("protocol_violation"),
    UNKNOWN("unknown");

    object Serializer : LenientEnumSerializer<CancelReason>("CancelReason", entries.toTypedArray(), UNKNOWN, { it.wireName })
}

@Serializable(with = GoodbyeReason.Serializer::class)
enum class GoodbyeReason(val wireName: String) {
    CLOSING("closing"),
    IDLE("idle"),
    SHUTTING_DOWN("shutting_down"),
    DUPLICATE_CONNECTION("duplicate_connection"),
    UNKNOWN("unknown");

    object Serializer : LenientEnumSerializer<GoodbyeReason>("GoodbyeReason", entries.toTypedArray(), UNKNOWN, { it.wireName })
}

@Serializable(with = ErrorCode.Serializer::class)
enum class ErrorCode(val wireName: String) {
    INCOMPATIBLE_VERSION("incompatible_version"),
    NOT_TRUSTED("not_trusted"),
    UNSUPPORTED_MESSAGE("unsupported_message"),
    MALFORMED_MESSAGE("malformed_message"),
    UNKNOWN_TRANSFER("unknown_transfer"),
    IDENTITY_MISMATCH("identity_mismatch"),
    PAIRING_FAILED("pairing_failed"),
    INTERNAL("internal"),
    UNKNOWN("unknown");

    object Serializer : LenientEnumSerializer<ErrorCode>("ErrorCode", entries.toTypedArray(), UNKNOWN, { it.wireName })
}

/**
 * A clipboard entry travelling between trusted devices.
 *
 * [originDeviceId] plus [clipId] identify the entry across every device; a device that
 * receives a clip it originated or has already applied drops it, which is what stops
 * A to B to A echo loops. See `ClipboardLoopGuard`.
 */
@Serializable
data class ClipItem(
    val clipId: String,
    val originDeviceId: DeviceId,
    val createdAtMillis: Long,
    val text: String,
    val mimeType: String = "text/plain",
) {
    init {
        require(text.length <= MAX_CLIP_LENGTH) { "Clip exceeds $MAX_CLIP_LENGTH characters" }
    }

    companion object {
        const val MAX_CLIP_LENGTH = 64_000
    }
}

@Serializable
data class ContinueActivity(
    val kind: String,
    val uri: String,
    val title: String? = null,
    /** Kind-specific hints, such as a playback position. Unknown keys are ignored. */
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        const val KIND_WEB_PAGE = "web.page"
    }
}
