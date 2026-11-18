package com.vythera.relay.node

import com.vythera.relay.security.Fingerprint
import kotlin.test.*
import org.junit.Test

class ConnectionInviteTest {
    private val now = 1_000_000L
    private val invite = ConnectionInvite("192.168.1.2", 47800, Fingerprint.fromHex("ab".repeat(32)), now + 300_000)
    @Test fun roundTripAndQrDecode() {
        assertEquals(invite, ConnectionInvite.parse(invite.encode(), now))
        val matrix = ConnectionQr.matrix(invite.encode())
        val side = matrix.size * 4
        val pixels = IntArray(side * side) { i -> if (matrix[i / side / 4][i % side / 4]) 0xff000000.toInt() else 0xffffffff.toInt() }
        assertEquals(invite.encode(), ConnectionQr.decode(side, side, pixels))
    }
    @Test fun vpnEndpointIsVisibleBeforeLanAndContainerAddresses() {
        fun ipv4(s: String) = java.net.InetAddress.getByName(s) as java.net.Inet4Address
        val candidates = listOf(
            "br-a1" to ipv4("172.19.0.1"),
            "wlp2s0" to ipv4("192.168.31.107"),
            "docker0" to ipv4("172.17.0.1"),
            "enp1s0f0" to ipv4("192.168.31.43"),
            "tailnet0" to ipv4("100.73.43.22"),
        )
        assertEquals(
            listOf("100.73.43.22", "192.168.31.107", "192.168.31.43"),
            ConnectionInvite.prioritizeAddresses(candidates),
        )
        assertEquals(
            listOf("172.19.0.1"),
            ConnectionInvite.prioritizeAddresses(listOf("br-a1" to ipv4("172.19.0.1"))),
        )
    }

    @Test fun rejectsExpiredAndUnrelatedCodes() {
        listOf("https://example.com", invite.encode() + "?extra=true", invite.copy(host = "127.0.0.1").encode(),
            invite.copy(host = "example.com").encode(), invite.copy(port = 0).encode(),
            invite.copy(expires = now).encode(), invite.copy(expires = now + 900_000).encode())
            .forEach { assertFails { ConnectionInvite.parse(it, now) } }
    }
}
