package com.vythera.relay.contact

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ContactCardTest {
    private val shubham = ContactCard("Shubham Kumar", "+91 98765 43210", "shubham@example.com", "Vythera")

    @Test
    fun `vcard round trips including characters vcard escapes`() {
        assertEquals(shubham, ContactCard.fromVCard(shubham.toVCard()))
        val tricky = ContactCard("Priya, the designer", "", "priya@example.com", "Studio; North")
        assertEquals(tricky, ContactCard.fromVCard(tricky.toVCard()))
    }

    @Test
    fun `vcard has the structured name contacts apps need`() {
        val vcard = shubham.toVCard()
        assert(vcard.contains("N:Kumar;Shubham;;;")) { vcard }
        assert(vcard.contains("TEL;TYPE=CELL:+91 98765 43210")) { vcard }
    }

    @Test
    fun `garbage is not a card`() {
        assertNull(ContactCard.fromVCard("hello"))
        assertNull(ContactCard.fromVCard("BEGIN:VCARD\r\nEND:VCARD"))
    }
}

class CardExchangeTest {
    private val phoneA = ContactCard("Shubham Kumar", "+91 98765 43210").toVCard().encodeToByteArray()
    private val phoneB = ContactCard("Priya Sharma", "", "priya@example.com", "A".repeat(100)).toVCard().encodeToByteArray()

    @Test
    fun `one tap gives each phone the other's card`() {
        var receivedByB: ByteArray? = null
        val b = CardExchange.Responder(ownCard = { phoneB }, onPeerCard = { receivedByB = it })

        val receivedByA = CardExchange.exchange(b::process, phoneA)

        assertEquals(phoneB.decodeToString(), receivedByA.decodeToString())
        assertEquals(phoneA.decodeToString(), receivedByB?.decodeToString())
    }

    @Test
    fun `a phone not on the share screen gives nothing away`() {
        val closed = CardExchange.Responder(ownCard = { null }, onPeerCard = { error("must not receive") })
        assertFailsWith<IOException> { CardExchange.exchange(closed::process, phoneA) }
    }

    @Test
    fun `commands before select are refused`() {
        val b = CardExchange.Responder(ownCard = { phoneB }, onPeerCard = {})
        val response = b.process(byteArrayOf(0x80.toByte(), 0xB0.toByte(), 0, 0, 10))
        assertEquals(listOf(0x6D.toByte(), 0x00.toByte()), response.toList())
    }
}
