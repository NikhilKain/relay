package com.vythera.relay.security

import com.vythera.relay.protocol.DeviceId
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.Certificate

/**
 * SHA-256 of a device's public key (its DER SubjectPublicKeyInfo).
 *
 * The fingerprint is Relay's notion of identity. Names, addresses and even
 * certificates can change; the key does not. Hashing the key rather than the whole
 * certificate means a device could re-issue its certificate (e.g. on expiry) without
 * losing trust.
 */
class Fingerprint private constructor(private val bytes: ByteArray) {

    val hex: String get() = bytes.toHex()

    /** Short, lowercase, URL- and filename-safe id announced on the network. */
    val deviceId: DeviceId get() = DeviceId(Base32.encode(bytes.copyOf(DEVICE_ID_BYTES)))

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Fingerprint && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "Fingerprint(${hex.take(16)}…)"

    companion object {
        private const val SIZE = 32
        private const val DEVICE_ID_BYTES = 16

        fun of(publicKey: PublicKey): Fingerprint =
            Fingerprint(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))

        fun of(certificate: Certificate): Fingerprint = of(certificate.publicKey)

        fun fromHex(hex: String): Fingerprint {
            require(hex.length == SIZE * 2) { "Fingerprint must be $SIZE bytes" }
            return Fingerprint(hex.hexToBytes())
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Invalid hex" }
        ((hi shl 4) or lo).toByte()
    }
}

/** RFC 4648 base32, lowercase, without padding. */
internal object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return out.toString()
    }
}
