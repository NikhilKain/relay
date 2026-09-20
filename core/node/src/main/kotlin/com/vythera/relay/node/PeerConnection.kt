package com.vythera.relay.node

import com.vythera.relay.protocol.DataChunk
import com.vythera.relay.protocol.DecodeResult
import com.vythera.relay.protocol.DeviceInfo
import com.vythera.relay.protocol.ErrorCode
import com.vythera.relay.protocol.Frame
import com.vythera.relay.protocol.FrameReader
import com.vythera.relay.protocol.FrameWriter
import com.vythera.relay.protocol.MessageCodec
import com.vythera.relay.protocol.ProtocolException
import com.vythera.relay.protocol.ProtocolVersion
import com.vythera.relay.protocol.RelayMessage
import com.vythera.relay.protocol.VersionRange
import com.vythera.relay.security.Fingerprint
import com.vythera.relay.security.RelayTls
import com.vythera.relay.transfer.PeerLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import javax.net.ssl.SSLSocket

/**
 * One authenticated, encrypted connection to a peer.
 *
 * Lifecycle: construct around a connected [SSLSocket] → [handshake] → the node runs
 * [readFrames] until it returns → [close]. All writes are serialized by a mutex, so
 * transfers, pings and pairing messages can share the connection from any coroutine.
 */
class PeerConnection(
    private val socket: SSLSocket,
    /** True if the peer dialed us. */
    val isInbound: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : PeerLink {
    private val writer = FrameWriter(BufferedOutputStream(socket.outputStream, BUFFER_SIZE))
    private val reader = FrameReader(BufferedInputStream(socket.inputStream, BUFFER_SIZE))
    private val writeLock = Mutex()

    lateinit var peer: DeviceInfo
        private set
    lateinit var fingerprint: Fingerprint
        private set
    var peerListenPort: Int? = null
        private set
    var protocolVersion: Int = 0
        private set

    val remoteHost: String get() = socket.inetAddress?.hostAddress ?: ""

    @Volatile var isOpen: Boolean = true
        private set

    /** Last time a non-keepalive message crossed the connection in either direction. */
    @Volatile var lastActivityMillis: Long = clock()
        private set

    /**
     * Completes TLS, then exchanges Hello messages and negotiates the protocol version.
     *
     * @param expected the fingerprint discovery announced, when we dialed; inbound
     *   connections pass null and are identified purely by the key they prove.
     * @throws IdentityMismatchException if the key is not the expected one or does not
     *   match the device id in the peer's Hello.
     * @throws IncompatibleVersionException if no protocol version is shared.
     */
    suspend fun handshake(local: RelayMessage.Hello, localRange: VersionRange, expected: Fingerprint?) =
        withContext(Dispatchers.IO) {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            socket.startHandshake()
            fingerprint = RelayTls.peerFingerprint(socket.session)
            if (expected != null && expected != fingerprint) throw IdentityMismatchException()

            writer.writePreface()
            writer.writeControl(local)
            reader.readPreface()

            val frame = reader.read() as? Frame.Control ?: throw ProtocolException("Expected Hello")
            val hello = (MessageCodec.decode(frame.payload) as? DecodeResult.Message)?.message as? RelayMessage.Hello
                ?: throw ProtocolException("Expected Hello")
            if (hello.device.id != fingerprint.deviceId) throw IdentityMismatchException()
            peer = hello.device
            peerListenPort = hello.listenPort?.takeIf { it in 1..65535 }

            val version = ProtocolVersion.negotiate(localRange, hello.device.protocol)
            if (version == null) {
                runCatching { writer.writeControl(RelayMessage.Error(ErrorCode.INCOMPATIBLE_VERSION)) }
                throw IncompatibleVersionException(hello.device)
            }
            protocolVersion = version
            socket.soTimeout = READ_TIMEOUT_MILLIS
        }

    /**
     * Reads until the connection ends, handing each frame to [onFrame] in order. Returns
     * normally on a clean close; throws on errors. Must be called from one coroutine.
     */
    suspend fun readFrames(onFrame: suspend (Frame) -> Unit) = withContext(Dispatchers.IO) {
        while (isOpen) {
            val frame = reader.read() ?: return@withContext
            if (frame is Frame.Data || !isKeepalive(frame)) lastActivityMillis = clock()
            onFrame(frame)
        }
    }

    fun updatePeer(info: DeviceInfo) {
        peer = info
    }

    override suspend fun send(message: RelayMessage) {
        if (message !is RelayMessage.Ping && message !is RelayMessage.Pong) lastActivityMillis = clock()
        write { writer.writeControl(message) }
    }

    override suspend fun sendData(chunk: DataChunk) {
        lastActivityMillis = clock()
        write { writer.writeData(chunk) }
    }

    override suspend fun flush() = write { writer.flush() }

    private suspend fun write(block: () -> Unit) {
        if (!isOpen) throw IOException("Connection closed")
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: IOException) {
                    closeQuietly()
                    throw e
                }
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) { closeQuietly() }

    private fun closeQuietly() {
        isOpen = false
        runCatching { socket.close() }
    }

    private fun isKeepalive(frame: Frame): Boolean {
        val payload = (frame as? Frame.Control)?.payload ?: return false
        // Cheap check that avoids decoding every frame twice.
        return payload.size < 64 && payload.decodeToString().let { it.contains("\"PING\"") || it.contains("\"PONG\"") }
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000

        /** Pings go out every [PING_INTERVAL_MILLIS]; silence for this long means the peer is gone. */
        const val READ_TIMEOUT_MILLIS = 45_000
        const val PING_INTERVAL_MILLIS = 15_000L
    }
}
