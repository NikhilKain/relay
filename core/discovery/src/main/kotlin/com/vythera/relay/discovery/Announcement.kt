package com.vythera.relay.discovery

import com.vythera.relay.protocol.DeviceId
import com.vythera.relay.protocol.DeviceType
import com.vythera.relay.protocol.LenientEnumSerializer
import com.vythera.relay.protocol.Platform
import com.vythera.relay.protocol.VersionRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The datagram a device broadcasts to say "I'm here, connect to me on this port".
 *
 * Announcements are unauthenticated by nature. They are only hints: nothing is
 * trusted until a TLS connection proves the announced [fingerprint], and the node
 * refuses connections whose key does not match. See docs/security-model.md.
 *
 * Deliberately small (well under one Ethernet MTU) and free of anything a passive
 * listener should not learn beyond "a Relay device named X exists".
 */
@Serializable
data class Announcement(
    @SerialName("relay") val discoveryVersion: Int = VERSION,
    val kind: Kind,
    val id: DeviceId,
    val name: String,
    val type: DeviceType,
    val platform: Platform,
    @SerialName("fp") val fingerprint: String,
    val port: Int,
    @SerialName("pv") val protocol: VersionRange,
) {
    @Serializable(with = Kind.Serializer::class)
    enum class Kind(val wireName: String) {
        /** "I exist." Sent on start, periodically, and in reply to [QUERY]. */
        HELLO("hello"),

        /** "I exist, and please tell me who else does." */
        QUERY("query"),

        /** "I'm leaving." Lets peers drop the device immediately instead of timing out. */
        BYE("bye"),
        UNKNOWN("unknown");

        object Serializer : LenientEnumSerializer<Kind>("AnnouncementKind", entries.toTypedArray(), UNKNOWN, { it.wireName })
    }

    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        const val VERSION = 1
        const val MAX_SIZE = 1_200

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Returns null for anything that is not a well-formed Relay announcement. */
        fun decode(bytes: ByteArray, length: Int = bytes.size): Announcement? {
            if (length > MAX_SIZE) return null
            return try {
                json.decodeFromString(serializer(), bytes.decodeToString(0, length))
                    .takeIf { it.discoveryVersion >= 1 && it.port in 1..65535 && it.kind != Kind.UNKNOWN }
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
