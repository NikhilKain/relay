package com.vythera.relay.security

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Mutual TLS 1.3 between Relay devices.
 *
 * Relay has no certificate authority. Instead:
 * 1. both sides present their self-signed device certificate;
 * 2. the trust manager checks only that it is a single, correctly self-signed,
 *    currently valid certificate. That proves the peer holds the key;
 * 3. after the handshake the node compares [peerFingerprint] against the fingerprint
 *    announced during discovery and against the trusted-device store.
 *
 * Step 3 is what makes this pinning rather than "accept anything": an untrusted
 * fingerprint may only pair, never transfer.
 */
class RelayTls(private val identity: RelayIdentity, random: SecureRandom = SecureRandom()) {

    private val context: SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        init(arrayOf(IdentityKeyManager(identity)), arrayOf(SelfSignedTrustManager), random)
    }

    fun createServerSocket(port: Int): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            enabledProtocols = PROTOCOLS
            needClientAuth = true
        }

    /** Wraps an already connected plain socket as the client side of a Relay TLS session. */
    fun wrapClient(socket: Socket): SSLSocket =
        (context.socketFactory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket).apply {
            enabledProtocols = PROTOCOLS
            useClientMode = true
        }

    /** Server-side configuration for sockets accepted from [createServerSocket]. */
    fun configureAccepted(socket: SSLSocket) {
        socket.enabledProtocols = PROTOCOLS
        socket.needClientAuth = true
    }

    companion object {
        private val PROTOCOLS = arrayOf("TLSv1.3")

        /** Fingerprint of the key the peer proved possession of during the handshake. */
        fun peerFingerprint(session: SSLSession): Fingerprint {
            val chain = session.peerCertificates
            if (chain.size != 1) throw CertificateException("Expected a single device certificate")
            return Fingerprint.of(chain[0])
        }
    }
}

private class IdentityKeyManager(private val identity: RelayIdentity) : X509ExtendedKeyManager() {
    private val chain = arrayOf(identity.certificate)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    private companion object {
        const val ALIAS = "relay-device"
    }
}

/**
 * Accepts exactly one self-signed, currently valid certificate. Implemented as an
 * [X509ExtendedTrustManager] so the JSSE does not wrap it with hostname verification,
 * which is meaningless for peers identified by key.
 */
private object SelfSignedTrustManager : X509ExtendedTrustManager() {
    private fun check(chain: Array<out X509Certificate>?) {
        if (chain == null || chain.size != 1) throw CertificateException("Expected a single device certificate")
        val certificate = chain[0]
        certificate.checkValidity()
        try {
            certificate.verify(certificate.publicKey)
        } catch (e: Exception) {
            throw CertificateException("Certificate is not self-signed by its key", e)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
