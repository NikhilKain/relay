package com.vythera.relay.discovery

import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.Platform
import com.vythera.relay.protocol.ProtocolVersion
import com.vythera.relay.security.RelayIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun announcementFor(
    identity: RelayIdentity,
    name: String,
    kind: Announcement.Kind = Announcement.Kind.HELLO,
    port: Int = 47_800,
) = Announcement(
    kind = kind,
    id = identity.deviceId,
    name = name,
    type = DeviceType.DESKTOP,
    platform = Platform.WINDOWS,
    fingerprint = identity.fingerprint.hex,
    port = port,
    protocol = ProtocolVersion.SUPPORTED,
)

class AnnouncementTest {
    @Test
    fun `round trips and stays under the size limit`() {
        val announcement = announcementFor(RelayIdentity.generate(), "G".repeat(64))
        val bytes = announcement.encode()
        assertTrue(bytes.size < Announcement.MAX_SIZE, "size ${bytes.size}")
        assertEquals(announcement, Announcement.decode(bytes))
    }

    @Test
    fun `ignores foreign datagrams`() {
        assertNull(Announcement.decode("M-SEARCH * HTTP/1.1".encodeToByteArray()))
        assertNull(Announcement.decode("""{"relay":1,"kind":"hello"}""".encodeToByteArray()))
        assertNull(Announcement.decode(ByteArray(5_000)))
    }
}

class DeviceRegistryTest {
    private val self = RelayIdentity.generate()
    private val pc = RelayIdentity.generate()
    private var now = 1_000_000L
    private val registry = DeviceRegistry(self.deviceId, clock = { now }, expiryMillis = 10_000)

    @Test
    fun `merges repeated sightings into one device with ordered endpoints`() {
        registry.onAnnouncement(announcementFor(pc, "Gaming PC"), "192.168.1.20")
        registry.onAnnouncement(announcementFor(pc, "Gaming PC"), "10.0.0.5")
        registry.onAnnouncement(announcementFor(pc, "Gaming PC"), "10.0.0.5")

        val device = assertNotNull(registry.devices.value[pc.deviceId])
        assertEquals(1, registry.devices.value.size)
        assertEquals(listOf("10.0.0.5", "192.168.1.20"), device.endpoints.map { it.host })
    }

    @Test
    fun `renames are picked up`() {
        registry.onAnnouncement(announcementFor(pc, "Desktop"), "192.168.1.20")
        registry.onAnnouncement(announcementFor(pc, "Gaming PC"), "192.168.1.20")
        assertEquals("Gaming PC", registry.devices.value.getValue(pc.deviceId).name)
    }

    @Test
    fun `ignores itself`() {
        assertFalse(registry.onAnnouncement(announcementFor(self, "Me"), "192.168.1.2"))
        assertTrue(registry.devices.value.isEmpty())
    }

    @Test
    fun `rejects ids that do not belong to the announced key`() {
        val impostor = announcementFor(pc, "Gaming PC").copy(fingerprint = RelayIdentity.generate().fingerprint.hex)
        assertFalse(registry.onAnnouncement(impostor, "192.168.1.66"))
        assertTrue(registry.devices.value.isEmpty())
    }

    @Test
    fun `bye removes immediately and silence expires`() {
        val tablet = RelayIdentity.generate()
        registry.onAnnouncement(announcementFor(pc, "Gaming PC"), "192.168.1.20")
        registry.onAnnouncement(announcementFor(tablet, "Tab S9 FE+"), "192.168.1.30")

        registry.onAnnouncement(announcementFor(pc, "Gaming PC", Announcement.Kind.BYE), "192.168.1.20")
        assertEquals(setOf(tablet.deviceId), registry.devices.value.keys)

        now += 9_000
        assertTrue(registry.expireStale().isEmpty())
        registry.markSeen(tablet.deviceId)
        now += 9_000
        assertTrue(registry.expireStale().isEmpty(), "markSeen should extend presence")
        now += 11_000
        assertEquals(setOf(tablet.deviceId), registry.expireStale())
    }

    @Test
    fun `demoted endpoints move to the back`() {
        registry.onAnnouncement(announcementFor(pc, "Gaming PC"), "192.168.1.20")
        registry.onAnnouncement(announcementFor(pc, "Gaming PC"), "10.0.0.5")
        registry.demoteEndpoint(pc.deviceId, Endpoint("10.0.0.5", 47_800))
        assertEquals("192.168.1.20", registry.devices.value.getValue(pc.deviceId).endpoints.first().host)
    }
}

class LanDiscoveryTest {
    private fun freeUdpPort() = DatagramSocket(0).use { it.localPort }

    @Test
    fun `two devices find each other over loopback`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val phone = RelayIdentity.generate()
        val pc = RelayIdentity.generate()
        val phonePort = freeUdpPort()
        val pcPort = freeUdpPort()
        val loopback = InetAddress.getLoopbackAddress()

        val phoneRegistry = DeviceRegistry(phone.deviceId)
        val pcRegistry = DeviceRegistry(pc.deviceId)
        val phoneDiscovery = LanDiscovery(phoneRegistry, phonePort, listOf(InetSocketAddress(loopback, pcPort)))
        val pcDiscovery = LanDiscovery(pcRegistry, pcPort, listOf(InetSocketAddress(loopback, phonePort)))

        try {
            phoneDiscovery.start(scope, announcementFor(phone, "Pixel 6a", port = 1234))
            pcDiscovery.start(scope, announcementFor(pc, "Gaming PC", port = 5678))

            withTimeout(5_000) {
                val seenByPhone = phoneRegistry.devices.first { pc.deviceId in it }.getValue(pc.deviceId)
                assertEquals("Gaming PC", seenByPhone.name)
                assertEquals(5678, seenByPhone.endpoints.single().port)
                pcRegistry.devices.first { phone.deviceId in it }
            }

            pcDiscovery.stop()
            withTimeout(5_000) { phoneRegistry.devices.first { pc.deviceId !in it } }
        } finally {
            phoneDiscovery.stop()
            pcDiscovery.stop()
            scope.cancel()
        }
    }
}
