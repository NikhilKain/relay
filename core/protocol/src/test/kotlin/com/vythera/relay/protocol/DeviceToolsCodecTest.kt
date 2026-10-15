package com.vythera.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class DeviceToolsCodecTest {
    @Test fun `tool messages survive the wire`() {
        val messages = listOf(
            RelayMessage.ToolRequest("id", "notifications.reply", mapOf("id" to "notice", "text" to "Hello 🌍")),
            RelayMessage.ToolResponse("id", items = listOf(ToolItem("folder/photo.jpg", "photo.jpg", "42 bytes", "file"))),
            RelayMessage.ToolResponse("id", false, "Permission required"),
        )
        messages.forEach { assertEquals(it, assertIs<DecodeResult.Message>(MessageCodec.decode(MessageCodec.encode(it))).message) }
    }

    @Test fun `unbounded tool arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> { RelayMessage.ToolRequest("id", "input.text", mapOf("text" to "x".repeat(4097))) }
        assertIs<DecodeResult.Malformed>(MessageCodec.decode("""{"type":"TOOL_REQUEST","requestId":"","action":"ring.start"}""".encodeToByteArray()))
    }
}
