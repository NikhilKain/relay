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
    @Test fun rejectsExpiredAndUnrelatedCodes() {
        listOf("https://example.com", invite.encode() + "?extra=true", invite.copy(host = "127.0.0.1").encode(),
            invite.copy(host = "example.com").encode(), invite.copy(port = 0).encode(),
            invite.copy(expires = now).encode(), invite.copy(expires = now + 900_000).encode())
            .forEach { assertFails { ConnectionInvite.parse(it, now) } }
    }
}
