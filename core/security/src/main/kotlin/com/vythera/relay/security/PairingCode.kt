package com.vythera.relay.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What both screens show while pairing. The user compares them; if they match, no one
 * is sitting between the two devices.
 *
 * [digits] carries the security (20 bits, and the commitment scheme means an attacker
 * gets exactly one guess per attempt). [shapes] is derived from independent digest
 * bytes and exists so the comparison is quick and friendly: most people will glance at
 * three shapes rather than read six digits.
 */
data class PairingCode(val digits: String, val shapes: List<Int>) {
    init {
        require(digits.length == DIGITS && digits.all(Char::isDigit))
        require(shapes.size == SHAPE_COUNT && shapes.all { it in 0 until SHAPE_PALETTE_SIZE })
    }

    /** "482 913": grouped for reading aloud. */
    val grouped: String get() = digits.substring(0, 3) + " " + digits.substring(3)

    companion object {
        const val DIGITS = 6
        const val SHAPE_COUNT = 3
        const val SHAPE_PALETTE_SIZE = 8
    }
}

/**
 * Numeric-comparison pairing with a nonce commitment.
 *
 * ```
 * initiator                                   responder
 *   nI = random
 *   PAIR_REQUEST(commit = H(nI))  ──────────▶
 *                                  ◀──────────  PAIR_NONCE(nR = random)
 *   PAIR_REVEAL(nI)               ──────────▶   check H(nI) == commit
 *   code = H(fpI, fpR, nI, nR)                  code = H(fpI, fpR, nI, nR)
 * ```
 *
 * The fingerprints are the TLS-authenticated keys of this very connection, so a
 * man-in-the-middle necessarily produces different fingerprints on each side. Because
 * the initiator is committed before seeing nR, and the responder reveals nR before
 * seeing nI, neither side (nor an attacker playing one of them) can steer the code.
 */
object PairingCrypto {
    private const val NONCE_BYTES = 32
    private val COMMIT_LABEL = "relay/pair/commit/v1".encodeToByteArray()
    private val CODE_LABEL = "relay/pair/code/v1".encodeToByteArray()

    fun newNonce(random: SecureRandom = SecureRandom()): String =
        ByteArray(NONCE_BYTES).also(random::nextBytes).toHex()

    fun commitmentFor(nonceHex: String): String = sha256(COMMIT_LABEL, nonceHex.hexToBytes()).toHex()

    fun verifyCommitment(commitmentHex: String, nonceHex: String): Boolean {
        val expected = runCatching { commitmentHex.hexToBytes() }.getOrNull() ?: return false
        val nonce = runCatching { nonceHex.hexToBytes() }.getOrNull() ?: return false
        if (nonce.size != NONCE_BYTES) return false
        return MessageDigest.isEqual(expected, sha256(COMMIT_LABEL, nonce))
    }

    fun isValidNonce(nonceHex: String): Boolean =
        runCatching { nonceHex.hexToBytes().size == NONCE_BYTES }.getOrDefault(false)

    fun deriveCode(
        initiator: Fingerprint,
        responder: Fingerprint,
        initiatorNonceHex: String,
        responderNonceHex: String,
    ): PairingCode {
        val digest = sha256(
            CODE_LABEL,
            initiator.toByteArray(),
            responder.toByteArray(),
            initiatorNonceHex.hexToBytes(),
            responderNonceHex.hexToBytes(),
        )
        val number = (ByteBuffer.wrap(digest, 0, 4).int.toLong() and 0xffffffffL) % 1_000_000
        val shapes = (0 until PairingCode.SHAPE_COUNT).map {
            (digest[8 + it].toInt() and 0xff) % PairingCode.SHAPE_PALETTE_SIZE
        }
        return PairingCode(number.toString().padStart(PairingCode.DIGITS, '0'), shapes)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length-prefix every part so different splits of the same bytes cannot collide.
        parts.forEach {
            digest.update(ByteBuffer.allocate(4).putInt(it.size).array())
            digest.update(it)
        }
        return digest.digest()
    }
}
