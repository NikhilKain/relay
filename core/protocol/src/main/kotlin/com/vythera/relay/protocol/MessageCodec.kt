package com.vythera.relay.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface DecodeResult {
    data class Message(val message: RelayMessage) : DecodeResult

    /** A well-formed message of a type this build does not implement. */
    data class Unknown(val type: String) : DecodeResult

    data class Malformed(val reason: String) : DecodeResult
}

/** JSON encoding of [RelayMessage]s. Stateless and thread-safe. */
object MessageCodec {
    private const val TYPE_KEY = "type"

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        classDiscriminator = TYPE_KEY
    }

    /** Wire names of every message this build understands, read from the sealed hierarchy. */
    val knownTypes: Set<String> by lazy {
        // A sealed descriptor has two elements, "type" and "value"; the children of
        // "value" are the subclasses, named by their @SerialName.
        val subclasses = RelayMessage.serializer().descriptor.getElementDescriptor(1)
        (0 until subclasses.elementsCount).map(subclasses::getElementName).toSet()
    }

    fun encode(message: RelayMessage): ByteArray =
        json.encodeToString(RelayMessage.serializer(), message).encodeToByteArray()

    fun decode(bytes: ByteArray): DecodeResult {
        val element = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return DecodeResult.Malformed("Not a JSON object")

        val type = (element[TYPE_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return DecodeResult.Malformed("Missing type")
        if (type !in knownTypes) return DecodeResult.Unknown(type)

        return try {
            DecodeResult.Message(json.decodeFromJsonElement(RelayMessage.serializer(), element))
        } catch (e: SerializationException) {
            DecodeResult.Malformed("Invalid $type")
        } catch (e: IllegalArgumentException) {
            // `require` checks in message constructors.
            DecodeResult.Malformed("Invalid $type: ${e.message}")
        }
    }
}
