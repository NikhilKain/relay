package com.vythera.relay.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.StandardSocketOptions

/**
 * Finds Relay devices on the local network with UDP announcements.
 *
 * Every announcement goes to both an administratively scoped multicast group and the
 * IPv4 limited broadcast address. Many consumer routers filter one or the other
 * (IGMP snooping, client isolation of broadcast), and sending both is far cheaper
 * than a user wondering why their laptop does not show up.
 *
 * Battery: nothing is sent unless the node is running. While running, one ~300 byte
 * datagram goes out every [announceIntervalMillis]; there is no polling of peers.
 * On Android the caller must hold a `WifiManager.MulticastLock` while this runs.
 */
class LanDiscovery(
    private val registry: DeviceRegistry,
    private val discoveryPort: Int = DEFAULT_PORT,
    private val targets: List<InetSocketAddress> = defaultTargets(discoveryPort),
    private val announceIntervalMillis: Long = DEFAULT_ANNOUNCE_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var local: Announcement? = null
    private var jobs: List<Job> = emptyList()
    @Volatile private var scope: CoroutineScope? = null
    private val lastReplyTo = HashMap<String, Long>()

    val isRunning: Boolean get() = socket != null

    /**
     * Starts listening and announcing. [announcement] describes this device; its kind is
     * ignored. Throws [IOException] if the discovery port cannot be bound.
     */
    @Synchronized
    fun start(scope: CoroutineScope, announcement: Announcement) {
        if (socket != null) return
        local = announcement
        val socket = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(discoveryPort))
            broadcast = true
            setOption(StandardSocketOptions.IP_MULTICAST_LOOP, false)
            joinGroupOnUsableInterfaces()
        }
        this.socket = socket
        this.scope = scope
        jobs = listOf(
            scope.launch(Dispatchers.IO) { receiveLoop(socket) },
            scope.launch(Dispatchers.IO) {
                send(Announcement.Kind.QUERY)
                while (isActive) {
                    delay(announceIntervalMillis)
                    send(Announcement.Kind.HELLO)
                    registry.expireStale()
                }
            },
        )
    }

    /** Updates what this device announces (e.g. after a rename) and re-announces. */
    fun update(announcement: Announcement) {
        local = announcement
        sendInBackground(Announcement.Kind.HELLO)
    }

    /** Asks every device to announce itself now. Call when the app returns to the foreground or the network changes. */
    fun refresh() {
        val socket = socket ?: return
        scope?.launch(Dispatchers.IO) {
            // Interfaces may have changed (Wi-Fi switch); re-join so multicast keeps flowing.
            runCatching { socket.joinGroupOnUsableInterfaces() }
            send(Announcement.Kind.QUERY)
        }
    }

    @Synchronized
    fun stop() {
        val socket = socket ?: return
        val bye = local?.copy(kind = Announcement.Kind.BYE)?.encode()
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        this.socket = null
        this.scope = null
        registry.clear()
        // stop() may be called from the main thread, where Android forbids network I/O.
        Thread({
            bye?.let { payload -> targets.forEach { runCatching { socket.send(DatagramPacket(payload, payload.size, it)) } } }
            socket.close()
        }, "relay-discovery-bye").start()
    }

    private fun receiveLoop(socket: MulticastSocket) {
        val buffer = ByteArray(Announcement.MAX_SIZE + 1)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketException) {
                return // closed by stop()
            } catch (e: IOException) {
                continue
            }
            val announcement = Announcement.decode(packet.data, packet.length) ?: continue
            val sender = packet.address.hostAddress ?: continue
            if (!registry.onAnnouncement(announcement, sender)) continue
            if (announcement.kind == Announcement.Kind.QUERY) replyTo(packet.socketAddress as InetSocketAddress)
        }
    }

    private fun replyTo(address: InetSocketAddress) {
        // A burst of queries (several devices waking at once) should not trigger a
        // burst of replies from every device.
        val key = address.address.hostAddress ?: return
        val now = clock()
        synchronized(lastReplyTo) {
            if (now - (lastReplyTo[key] ?: 0L) < REPLY_COOLDOWN_MILLIS) return
            lastReplyTo[key] = now
        }
        sendTo(Announcement.Kind.HELLO, listOf(address))
    }

    private fun send(kind: Announcement.Kind) = sendTo(kind, targets)

    /** Callers may be on any thread (a rename from the UI); datagrams go out on I/O. */
    private fun sendInBackground(kind: Announcement.Kind) {
        scope?.launch(Dispatchers.IO) { send(kind) }
    }

    private fun sendTo(kind: Announcement.Kind, destinations: List<InetSocketAddress>) {
        val socket = socket ?: return
        val payload = local?.copy(kind = kind)?.encode() ?: return
        for (destination in destinations) {
            try {
                socket.send(DatagramPacket(payload, payload.size, destination))
            } catch (e: IOException) {
                // Unreachable network or filtered broadcast. The other target may still work.
            }
        }
    }

    private fun MulticastSocket.joinGroupOnUsableInterfaces() {
        val group = InetSocketAddress(MULTICAST_GROUP, discoveryPort)
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (nif in interfaces) {
            val usable = runCatching {
                nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                    nif.inetAddresses.toList().any { it is Inet4Address }
            }.getOrDefault(false)
            if (!usable) continue
            try {
                joinGroup(group, nif)
            } catch (e: IOException) {
                // Already joined on this interface, or the interface refused.
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 47_801
        const val DEFAULT_ANNOUNCE_INTERVAL_MILLIS = 30_000L
        private const val REPLY_COOLDOWN_MILLIS = 2_000L

        /** Administratively scoped (RFC 2365), so routers never forward it off the LAN. */
        val MULTICAST_GROUP: InetAddress = InetAddress.getByName("239.255.72.76")

        fun defaultTargets(port: Int): List<InetSocketAddress> = listOf(
            InetSocketAddress(MULTICAST_GROUP, port),
            InetSocketAddress(InetAddress.getByName("255.255.255.255"), port),
        )
    }
}
