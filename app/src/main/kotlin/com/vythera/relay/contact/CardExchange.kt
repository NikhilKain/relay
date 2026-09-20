package com.vythera.relay.contact

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Tap-to-exchange contact cards over NFC, between two phones running Relay.
 *
 * Android has had no phone-to-phone NFC push since Android 10 removed Beam, so Relay
 * speaks ISO 7816 APDUs: one phone emulates a card (Host Card Emulation, [Responder])
 * while the other acts as reader ([Reader]). In a single tap the reader reads the card
 * it touched and writes its own, so both people end up with each other's details.
 *
 * ```
 * SELECT  00 A4 04 00 07 F0 52 45 4C 41 59 01    -> "RLY1" 90 00
 * LENGTH  80 CA 00 00 02                          -> len(2) 90 00
 * READ    80 B0 <offset:2> <n>                    -> bytes 90 00
 * WRITE   80 D6 <offset:2> <n> <bytes>            -> 90 00
 * DONE    80 DA 00 00                             -> 90 00
 * ```
 *
 * Payloads are UTF-8 vCards, a few hundred bytes. Chunks stay under the 255-byte short
 * APDU limit so every NFC controller handles them.
 */
object CardExchange {
    val AID = byteArrayOf(0xF0.toByte(), 0x52, 0x45, 0x4C, 0x41, 0x59, 0x01)
    private val HELLO = "RLY1".encodeToByteArray()
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    private val WRONG = byteArrayOf(0x6D, 0x00)
    private const val CHUNK = 200
    const val MAX_CARD_BYTES = 4_096

    /** The emulated-card side. One instance per HCE service; state resets on SELECT. */
    class Responder(
        /** Our card as vCard bytes, or null when sharing is not active (screen closed). */
        private val ownCard: () -> ByteArray?,
        private val onPeerCard: (ByteArray) -> Unit,
    ) {
        private var incoming = ByteArrayOutputStream()
        private var selected = false

        fun process(apdu: ByteArray): ByteArray {
            if (apdu.size < 4) return WRONG
            val cla = apdu[0].toInt() and 0xff
            val ins = apdu[1].toInt() and 0xff
            if (cla == 0x00 && ins == 0xA4) {
                val lc = apdu.getOrNull(4)?.toInt()?.and(0xff) ?: return NOT_FOUND
                val aid = apdu.copyOfRange(5, minOf(apdu.size, 5 + lc))
                if (!aid.contentEquals(AID) || ownCard() == null) return NOT_FOUND
                selected = true
                incoming = ByteArrayOutputStream()
                return HELLO + OK
            }
            if (!selected || cla != 0x80) return WRONG
            val card = ownCard() ?: return NOT_FOUND
            val offset = ((apdu[2].toInt() and 0xff) shl 8) or (apdu[3].toInt() and 0xff)
            return when (ins) {
                0xCA -> byteArrayOf((card.size shr 8).toByte(), card.size.toByte()) + OK
                0xB0 -> {
                    val length = apdu.getOrNull(4)?.toInt()?.and(0xff)?.takeIf { it > 0 } ?: CHUNK
                    if (offset >= card.size) OK else card.copyOfRange(offset, minOf(card.size, offset + length)) + OK
                }
                0xD6 -> {
                    val length = apdu.getOrNull(4)?.toInt()?.and(0xff) ?: return WRONG
                    if (offset != incoming.size() || offset + length > MAX_CARD_BYTES || apdu.size < 5 + length) return WRONG
                    incoming.write(apdu, 5, length)
                    OK
                }
                0xDA -> {
                    if (incoming.size() > 0) onPeerCard(incoming.toByteArray())
                    incoming = ByteArrayOutputStream()
                    OK
                }
                else -> WRONG
            }
        }
    }

    /**
     * The reader side: reads the other phone's card and gives it ours.
     * [transceive] sends one APDU and returns the response (IsoDep.transceive on Android).
     */
    fun exchange(transceive: (ByteArray) -> ByteArray, ownCard: ByteArray): ByteArray {
        val hello = transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID)
        if (!hello.endsWithOk() || !hello.dropLast(2).toByteArray().contentEquals(HELLO)) {
            throw IOException("The other phone is not sharing a Relay card")
        }
        val lengthResponse = transceive(byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0, 0, 2)).requireOk()
        val length = ((lengthResponse[0].toInt() and 0xff) shl 8) or (lengthResponse[1].toInt() and 0xff)
        if (length !in 1..MAX_CARD_BYTES) throw IOException("Unexpected card size $length")

        val peer = ByteArrayOutputStream()
        while (peer.size() < length) {
            val offset = peer.size()
            val chunk = transceive(byteArrayOf(0x80.toByte(), 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), minOf(CHUNK, length - offset).toByte())).requireOk()
            if (chunk.isEmpty()) break
            peer.write(chunk)
        }

        var offset = 0
        while (offset < ownCard.size) {
            val size = minOf(CHUNK, ownCard.size - offset)
            val header = byteArrayOf(0x80.toByte(), 0xD6.toByte(), (offset shr 8).toByte(), offset.toByte(), size.toByte())
            transceive(header + ownCard.copyOfRange(offset, offset + size)).requireOk()
            offset += size
        }
        transceive(byteArrayOf(0x80.toByte(), 0xDA.toByte(), 0, 0)).requireOk()
        return peer.toByteArray()
    }

    private fun ByteArray.endsWithOk() = size >= 2 && this[size - 2] == 0x90.toByte() && this[size - 1] == 0x00.toByte()

    private fun ByteArray.requireOk(): ByteArray {
        if (!endsWithOk()) throw IOException("Card exchange refused")
        return copyOf(size - 2)
    }
}
