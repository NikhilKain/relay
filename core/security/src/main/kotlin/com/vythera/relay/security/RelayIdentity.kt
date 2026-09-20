package com.vythera.relay.security

import com.vythera.relay.protocol.DeviceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The long-lived cryptographic identity of this Relay installation: an ECDSA P-256
 * key pair and a self-signed certificate used for mutual TLS.
 *
 * Generated once on first launch. Platforms persist it through [IdentityCodec] inside
 * their secure storage (Android wraps it with a Keystore key, desktops use the OS
 * keychain). Losing it means peers see a new, untrusted device.
 */
class RelayIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val fingerprint: Fingerprint = Fingerprint.of(certificate)
    val deviceId: DeviceId get() = fingerprint.deviceId

    companion object {
        private const val VALIDITY_YEARS = 30L

        fun generate(random: SecureRandom = SecureRandom()): RelayIdentity {
            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), random)
            }.generateKeyPair()

            // The subject carries no personal data; the device name travels in the
            // encrypted Hello instead so it can change without a new certificate.
            val subject = X500Name("CN=Relay Device")
            val now = System.currentTimeMillis()
            val notBefore = Date(now - TimeUnit.DAYS.toMillis(1)) // tolerate clock skew
            val notAfter = Date(now + TimeUnit.DAYS.toMillis(365 * VALIDITY_YEARS))
            val serial = BigInteger(127, random)

            val holder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
                .build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private))
            val certificate = JcaX509CertificateConverter().getCertificate(holder)
            return RelayIdentity(keyPair.private, certificate)
        }
    }
}

/** Portable serialization of a [RelayIdentity]. The output is secret key material. */
object IdentityCodec {
    @Serializable
    private data class Stored(val version: Int, val privateKey: String, val certificate: String)

    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(identity: RelayIdentity): ByteArray {
        val encoder = Base64.getEncoder()
        val stored = Stored(
            version = VERSION,
            privateKey = encoder.encodeToString(identity.privateKey.encoded),
            certificate = encoder.encodeToString(identity.certificate.encoded),
        )
        return json.encodeToString(Stored.serializer(), stored).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): RelayIdentity {
        val stored = json.decodeFromString(Stored.serializer(), bytes.decodeToString())
        require(stored.version == VERSION) { "Unsupported identity version ${stored.version}" }
        val decoder = Base64.getDecoder()
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(stored.privateKey)))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(decoder.decode(stored.certificate).inputStream()) as X509Certificate
        return RelayIdentity(privateKey, certificate)
    }
}

/** Platform-specific secure persistence of the identity. */
interface IdentityStore {
    fun load(): RelayIdentity?
    fun save(identity: RelayIdentity)

    fun loadOrCreate(): RelayIdentity = load() ?: RelayIdentity.generate().also(::save)
}
