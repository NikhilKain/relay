package com.vythera.relay.contact

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shared state between the Share contact screen and the NFC service.
 *
 * The card is offered over NFC only while [sharing] is set, which the screen does while
 * it is visible: a phone left in a pocket never hands out its owner's details.
 */
object ContactExchange {
    @Volatile var sharing: ByteArray? = null

    private val _received = MutableSharedFlow<ContactCard>(extraBufferCapacity = 4)
    val received: SharedFlow<ContactCard> = _received.asSharedFlow()

    fun onReceived(bytes: ByteArray) {
        ContactCard.fromVCard(bytes.decodeToString())?.let { _received.tryEmit(it) }
    }
}

/**
 * The phone's side when it is the one being "read" in a tap. Android routes APDUs for
 * Relay's AID here (see `res/xml/contact_apdu_service.xml`).
 */
class ContactCardService : HostApduService() {
    private val responder = CardExchange.Responder(
        ownCard = { ContactExchange.sharing },
        onPeerCard = ContactExchange::onReceived,
    )

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray = responder.process(commandApdu)

    override fun onDeactivated(reason: Int) = Unit
}
