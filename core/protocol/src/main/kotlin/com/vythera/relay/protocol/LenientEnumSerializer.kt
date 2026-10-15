package com.vythera.relay.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes an enum by its wire name and maps any value this build does not know to
 * [fallback] instead of failing.
 *
 * Relay versions coexist on the same network, so a newer peer announcing
 * `"platform": "visionos"` must not make an older peer drop the whole message.
 */
abstract class LenientEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val values: Array<T>,
    private val fallback: T,
    private val wireName: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    private val byWireName = values.associateBy(wireName)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(wireName(value))

    override fun deserialize(decoder: Decoder): T = byWireName[decoder.decodeString()] ?: fallback
}
