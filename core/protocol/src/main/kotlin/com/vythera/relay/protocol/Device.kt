package com.vythera.relay.protocol

import kotlinx.serialization.Serializable

/**
 * Stable identifier of a Relay installation.
 *
 * It is derived from the device's public key (see `com.vythera.relay.security.Fingerprint`),
 * so a peer cannot claim another device's id without also holding its private key:
 * the TLS handshake proves possession, and the node rejects a connection whose
 * certificate does not hash to the id it announced.
 */
@JvmInline
@Serializable
value class DeviceId(val value: String) {
    init {
        require(value.length in 8..64 && value.all { it in 'a'..'z' || it in '0'..'9' }) { "Malformed device id" }
    }

    override fun toString(): String = value
}

@Serializable(with = Platform.Serializer::class)
enum class Platform(val wireName: String) {
    ANDROID("android"),
    WINDOWS("windows"),
    LINUX("linux"),
    MACOS("macos"),
    WEB("web"),
    UNKNOWN("unknown");

    object Serializer : LenientEnumSerializer<Platform>("Platform", entries.toTypedArray(), UNKNOWN, { it.wireName })
}

@Serializable(with = DeviceType.Serializer::class)
enum class DeviceType(val wireName: String) {
    PHONE("phone"),
    TABLET("tablet"),
    LAPTOP("laptop"),
    DESKTOP("desktop"),
    BROWSER("browser"),
    UNKNOWN("unknown");

    object Serializer : LenientEnumSerializer<DeviceType>("DeviceType", entries.toTypedArray(), UNKNOWN, { it.wireName })
}

/** What a device says about itself in [RelayMessage.Hello]. */
@Serializable
data class DeviceInfo(
    val id: DeviceId,
    val name: String,
    val type: DeviceType,
    val platform: Platform,
    val appVersion: String,
    val protocol: VersionRange,
    val capabilities: Set<Capability> = emptySet(),
) {
    init {
        require(name.length <= MAX_NAME_LENGTH) { "Device name too long" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
