package com.vythera.relay.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {

    @Test
    fun `identity survives serialization with the same fingerprint`() {
        val identity = RelayIdentity.generate()
        val restored = IdentityCodec.decode(IdentityCodec.encode(identity))
        assertEquals(identity.fingerprint, restored.fingerprint)
        assertEquals(identity.deviceId, restored.deviceId)
    }

    @Test
    fun `device ids are distinct, short and url safe`() {
        val a = RelayIdentity.generate().deviceId.value
        val b = RelayIdentity.generate().deviceId.value
        assertNotEquals(a, b)
        assertEquals(26, a.length)
        assertTrue(a.all { it in 'a'..'z' || it in '2'..'7' }, a)
    }

    @Test
    fun `fingerprint hex round trips`() {
        val fp = RelayIdentity.generate().fingerprint
        assertEquals(fp, Fingerprint.fromHex(fp.hex))
    }
}

class TlsHandshakeTest {

    private fun handshake(server: RelayIdentity, client: RelayIdentity): Pair<Fingerprint, Fingerprint> {
        val serverTls = RelayTls(server)
        serverTls.createServerSocket(0).use { serverSocket ->
            val serverSide = CompletableFuture.supplyAsync {
                val accepted = serverSocket.accept() as SSLSocket
                serverTls.configureAccepted(accepted)
                accepted.use {
                    accepted.startHandshake()
                    assertEquals("TLSv1.3", accepted.session.protocol)
                    // Prove the channel carries data both ways.
                    val input = DataInputStream(accepted.inputStream)
                    val output = DataOutputStream(accepted.outputStream)
                    output.writeInt(input.readInt() + 1)
                    output.flush()
                    RelayTls.peerFingerprint(accepted.session)
                }
            }

            val plain = Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverSocket.localPort), 5_000) }
            val clientSide = RelayTls(client).wrapClient(plain).use { socket ->
                socket.startHandshake()
                val output = DataOutputStream(socket.outputStream)
                output.writeInt(41)
                output.flush()
                assertEquals(42, DataInputStream(socket.inputStream).readInt())
                RelayTls.peerFingerprint(socket.session)
            }
            return serverSide.get(10, TimeUnit.SECONDS) to clientSide
        }
    }

    @Test
    fun `mutual tls exposes each peer's key fingerprint`() {
        val server = RelayIdentity.generate()
        val client = RelayIdentity.generate()

        val (seenByServer, seenByClient) = handshake(server, client)

        assertEquals(client.fingerprint, seenByServer)
        assertEquals(server.fingerprint, seenByClient)
    }
}

class PairingCryptoTest {

    private val initiator = RelayIdentity.generate().fingerprint
    private val responder = RelayIdentity.generate().fingerprint

    @Test
    fun `both sides derive the same code`() {
        val nI = PairingCrypto.newNonce()
        val nR = PairingCrypto.newNonce()
        val commitment = PairingCrypto.commitmentFor(nI)

        assertTrue(PairingCrypto.verifyCommitment(commitment, nI))
        assertEquals(
            PairingCrypto.deriveCode(initiator, responder, nI, nR),
            PairingCrypto.deriveCode(initiator, responder, nI, nR),
        )
    }

    @Test
    fun `a revealed nonce that does not match the commitment is rejected`() {
        val commitment = PairingCrypto.commitmentFor(PairingCrypto.newNonce())
        assertFalse(PairingCrypto.verifyCommitment(commitment, PairingCrypto.newNonce()))
        assertFalse(PairingCrypto.verifyCommitment(commitment, "zz"))
        assertFalse(PairingCrypto.verifyCommitment("not-hex", PairingCrypto.newNonce()))
    }

    @Test
    fun `a device in the middle sees a different code`() {
        val attacker = RelayIdentity.generate().fingerprint
        val nI = PairingCrypto.newNonce()
        val nR = PairingCrypto.newNonce()
        // Initiator actually talks to the attacker, responder too.
        val initiatorSees = PairingCrypto.deriveCode(initiator, attacker, nI, nR)
        val responderSees = PairingCrypto.deriveCode(attacker, responder, nI, nR)
        assertNotEquals(initiatorSees, responderSees)
    }

    @Test
    fun `codes are well formed`() {
        repeat(50) {
            val code = PairingCrypto.deriveCode(initiator, responder, PairingCrypto.newNonce(), PairingCrypto.newNonce())
            assertEquals(6, code.digits.length)
            assertEquals(7, code.grouped.length)
            assertEquals(3, code.shapes.size)
        }
    }
}
