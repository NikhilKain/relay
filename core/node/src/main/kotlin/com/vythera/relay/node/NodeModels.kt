package com.vythera.relay.node

import com.vythera.relay.discovery.LanDiscovery
import com.vythera.relay.protocol.Capability
import com.vythera.relay.protocol.ClipItem
import com.vythera.relay.protocol.ContinueActivity
import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceInfo
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.protocol.ProtocolVersion
import com.vythera.relay.protocol.VersionRange
import com.vythera.relay.security.PairingCode
import com.vythera.relay.transfer.TransferFailure
import com.vythera.relay.transfer.TransferSnapshot
import java.io.IOException
import java.net.InetSocketAddress

data class NodeConfig(
    val deviceName: String,
    val deviceType: DeviceType,
    val platform: Platform,
    val appVersion: String,
    /** Preferred TCP port. If taken, the node listens on any free port and announces that one. */
    val listenPort: Int = DEFAULT_LISTEN_PORT,
    val discoveryPort: Int = LanDiscovery.DEFAULT_PORT,
    /** Where announcements are sent. Null means the LAN multicast group and broadcast. */
    val discoveryTargets: List<InetSocketAddress>? = null,
    val protocol: VersionRange = ProtocolVersion.SUPPORTED,
    val capabilities: Set<Capability> = DEFAULT_CAPABILITIES,
) {
    companion object {
        const val DEFAULT_LISTEN_PORT = 47_800

        val DEFAULT_CAPABILITIES: Set<Capability> = setOf(
            Capability.TransferFiles,
            Capability.TransferFolders,
            Capability.TransferResume,
            Capability.Text,
            Capability.ContinueWebPage,
        )
    }
}

enum class Presence {
    /** A connection is open right now. */
    CONNECTED,

    /** Announcing on the local network. */
    NEARBY,

    /** Trusted, but not currently seen. */
    OFFLINE,
}

/** Everything the UI needs to draw a device, merged from discovery, trust and connections. */
data class RelayDevice(
    val id: DeviceId,
    val name: String,
    val type: DeviceType,
    val platform: Platform,
    val presence: Presence,
    val isTrusted: Boolean,
    val autoAcceptTransfers: Boolean,
    /** Known once connected; empty before. */
    val capabilities: Set<Capability>,
    /** False when the device speaks no protocol version this build does. */
    val isCompatible: Boolean,
    val lastSeenMillis: Long?,
) {
    val isReachable: Boolean get() = presence != Presence.OFFLINE && isCompatible
}

enum class PairingRole { INITIATOR, RESPONDER }

enum class PairingFailure { UNREACHABLE, CONNECTION_LOST, TIMED_OUT, INCOMPATIBLE_VERSION, VERIFICATION_FAILED }

sealed interface PairingStage {
    /** Nonces are being exchanged; no code yet. Lasts a few milliseconds on a healthy network. */
    data object Exchanging : PairingStage

    /** Responder: show [code] and ask the user whether to trust the device. */
    data class Confirm(val code: PairingCode) : PairingStage

    /** Initiator: show [code] while the other device's user decides. */
    data class WaitingForPeer(val code: PairingCode) : PairingStage

    data object Completed : PairingStage
    data class Declined(val byPeer: Boolean) : PairingStage
    data class Failed(val reason: PairingFailure) : PairingStage

    val isFinished: Boolean get() = this is Completed || this is Declined || this is Failed
}

data class PairingSession(
    val requestId: String,
    val peerId: DeviceId,
    val peerName: String,
    val peerType: DeviceType,
    val peerPlatform: Platform,
    val role: PairingRole,
    val stage: PairingStage,
    val startedAtMillis: Long,
)

/** Things that happened which the app may want to react to (notifications, history, haptics). */
sealed interface NodeEvent {
    data class DeviceAppeared(val deviceId: DeviceId) : NodeEvent
    data class PairingRequested(val session: PairingSession) : NodeEvent
    data class DevicePaired(val deviceId: DeviceId) : NodeEvent
    data class DeviceForgotten(val deviceId: DeviceId, val byPeer: Boolean) : NodeEvent
    data class TransferOffered(val transfer: TransferSnapshot) : NodeEvent
    data class TransferFinished(val transfer: TransferSnapshot) : NodeEvent
    data class TextReceived(val from: DeviceId, val messageId: String, val text: String, val receivedAtMillis: Long) : NodeEvent
    data class ClipboardReceived(val from: DeviceId, val clip: ClipItem) : NodeEvent
    data class ContinueRequested(val from: DeviceId, val requestId: String, val activity: ContinueActivity) : NodeEvent
}

sealed interface SendResult {
    data object Sent : SendResult
    data class Failed(val reason: TransferFailure) : SendResult
}

/** No endpoint of the device accepted a connection. */
class UnreachableException(message: String = "Device unreachable") : IOException(message)

/** The device speaks no protocol version this build supports. */
class IncompatibleVersionException(val peer: DeviceInfo?) : IOException("Incompatible protocol version")

/** The key presented over TLS is not the key the device announced. */
class IdentityMismatchException : IOException("Peer identity mismatch")
