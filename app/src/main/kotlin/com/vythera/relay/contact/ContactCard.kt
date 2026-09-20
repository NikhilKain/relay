package com.vythera.relay.contact

import kotlinx.serialization.Serializable

/**
 * What a person chooses to share about themselves when they tap phones or show their
 * code. Only filled fields travel.
 */
@Serializable
data class ContactCard(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val organization: String = "",
) {
    val isEmpty: Boolean get() = name.isBlank() && phone.isBlank() && email.isBlank()

    /**
     * vCard 3.0, the format every phone's camera and contacts app understands, so the QR
     * code works even on a phone without Relay.
     */
    fun toVCard(): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        if (name.isNotBlank()) {
            append("FN:").append(escape(name)).append("\r\n")
            val parts = name.trim().split(Regex("\\s+"))
            val family = if (parts.size > 1) parts.last() else ""
            val given = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else parts.first()
            append("N:").append(escape(family)).append(';').append(escape(given)).append(";;;\r\n")
        }
        if (phone.isNotBlank()) append("TEL;TYPE=CELL:").append(escape(phone)).append("\r\n")
        if (email.isNotBlank()) append("EMAIL:").append(escape(email)).append("\r\n")
        if (organization.isNotBlank()) append("ORG:").append(escape(organization)).append("\r\n")
        append("END:VCARD\r\n")
    }

    companion object {
        const val MAX_FIELD_LENGTH = 120
        private val UNESCAPED_SEMICOLON = Regex("""(?<!\\);""")

        /** Parses the subset of vCard that [toVCard] writes. Unknown lines are ignored. */
        fun fromVCard(text: String): ContactCard? {
            if (!text.contains("BEGIN:VCARD")) return null
            var card = ContactCard()
            unfold(text).lineSequence().forEach { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon).substringBefore(';').uppercase()
                val raw = line.substring(colon + 1)
                val value = unescape(raw).take(MAX_FIELD_LENGTH)
                card = when (key) {
                    "FN" -> card.copy(name = value)
                    "TEL" -> if (card.phone.isEmpty()) card.copy(phone = value) else card
                    "EMAIL" -> if (card.email.isEmpty()) card.copy(email = value) else card
                    // ORG is "company;department"; only an unescaped ';' separates them.
                    "ORG" -> card.copy(organization = unescape(raw.split(UNESCAPED_SEMICOLON).first()).take(MAX_FIELD_LENGTH))
                    else -> card
                }
            }
            return card.takeUnless { it.isEmpty }
        }

        private fun escape(value: String) = value.trim().take(MAX_FIELD_LENGTH)
            .replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

        private fun unescape(value: String) = value.trim()
            .replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

        /** vCard lines may be folded: a line starting with a space continues the previous one. */
        private fun unfold(text: String) = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "")
    }
}
