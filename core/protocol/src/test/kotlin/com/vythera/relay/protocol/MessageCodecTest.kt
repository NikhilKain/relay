package com.vythera.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageCodecTest {

    private val device = DeviceInfo(
        id = DeviceId("k7q2m9x4p1z8c3v6"),
        name = "Gaming PC",
        type = DeviceType.DESKTOP,
        platform = Platform.WINDOWS,
        appVersion = "1.0.0",
        protocol = ProtocolVersion.SUPPORTED,
        capabilities = setOf(Capability.TransferFiles, Capability.Text),
    )

    private fun roundTrip(message: RelayMessage): RelayMessage {
        val result = MessageCodec.decode(MessageCodec.encode(message))
        return assertIs<DecodeResult.Message>(result).message
    }

    @Test
    fun `every message type survives a round trip`() {
        val clip = ClipItem("c1", device.id, 1_700_000_000_000, "adb shell pm list packages")
        val messages = listOf(
            RelayMessage.Hello(device),
            RelayMessage.Capabilities(setOf(Capability.ClipboardPush)),
            RelayMessage.Ping(42),
            RelayMessage.Pong(42),
            RelayMessage.Goodbye(GoodbyeReason.IDLE),
            RelayMessage.Error(ErrorCode.NOT_TRUSTED, relatesTo = "t1"),
            RelayMessage.PairRequest("p1", "ab12"),
            RelayMessage.PairNonce("p1", "cd34"),
            RelayMessage.PairReveal("p1", "ef56"),
            RelayMessage.PairAccept("p1"),
            RelayMessage.PairDecline("p1"),
            RelayMessage.Unpair,
            RelayMessage.TransferRequest(
                "t1",
                listOf(TransferItem(0, "vacation.zip", 1_234_567_890, "application/zip")),
                TransferKind.FILES,
            ),
            RelayMessage.TransferAccept("t1", listOf(ResumePoint(0, 1024))),
            RelayMessage.TransferDecline("t1", DeclineReason.INSUFFICIENT_STORAGE),
            RelayMessage.TransferFileComplete("t1", 0, "00ff"),
            RelayMessage.TransferComplete("t1"),
            RelayMessage.TransferPause("t1"),
            RelayMessage.TransferResume("t1"),
            RelayMessage.TransferCancel("t1", CancelReason.CHECKSUM_MISMATCH),
            RelayMessage.Text("m1", "Meeting moved to 3 PM"),
            RelayMessage.ClipboardUpdate(clip),
            RelayMessage.Continue("r1", ContinueActivity(ContinueActivity.KIND_WEB_PAGE, "https://github.com/project")),
        )
        messages.forEach { assertEquals(it, roundTrip(it)) }
        // Guards against adding a message type without covering it here.
        assertEquals(MessageCodec.knownTypes.size, messages.map { it::class }.toSet().size)
    }

    @Test
    fun `wire type names are stable`() {
        val json = MessageCodec.encode(RelayMessage.Ping(1)).decodeToString()
        assertTrue(json.contains("\"type\":\"PING\""), json)
    }

    @Test
    fun `unknown message types are reported, not thrown`() {
        val result = MessageCodec.decode("""{"type":"RING_PHONE","volume":11}""".encodeToByteArray())
        assertEquals(DecodeResult.Unknown("RING_PHONE"), result)
    }

    @Test
    fun `unknown fields from newer peers are ignored`() {
        val json = """{"type":"PING","nonce":7,"priority":"high"}"""
        assertEquals(RelayMessage.Ping(7), assertIs<DecodeResult.Message>(MessageCodec.decode(json.encodeToByteArray())).message)
    }

    @Test
    fun `unknown enum values fall back instead of failing`() {
        val json = """
            {"type":"DEVICE_HELLO","device":{"id":"k7q2m9x4p1z8c3v6","name":"Vision","type":"headset",
             "platform":"visionos","appVersion":"3.0","protocol":{"min":1,"max":3},
             "capabilities":["transfer.files","spatial.anchor"]}}
        """.trimIndent()
        val hello = assertIs<RelayMessage.Hello>(assertIs<DecodeResult.Message>(MessageCodec.decode(json.encodeToByteArray())).message)
        assertEquals(Platform.UNKNOWN, hello.device.platform)
        assertEquals(DeviceType.UNKNOWN, hello.device.type)
        assertTrue(Capability("spatial.anchor") in hello.device.capabilities)
    }

    @Test
    fun `malformed input is classified`() {
        assertIs<DecodeResult.Malformed>(MessageCodec.decode("not json".encodeToByteArray()))
        assertIs<DecodeResult.Malformed>(MessageCodec.decode("[1,2]".encodeToByteArray()))
        assertIs<DecodeResult.Malformed>(MessageCodec.decode("""{"nonce":1}""".encodeToByteArray()))
        assertIs<DecodeResult.Malformed>(MessageCodec.decode("""{"type":"PING"}""".encodeToByteArray()))
        // Constructor validation: negative size.
        val badItem = """{"type":"TRANSFER_REQUEST","transferId":"t","items":[{"index":0,"name":"a","sizeBytes":-1}]}"""
        assertIs<DecodeResult.Malformed>(MessageCodec.decode(badItem.encodeToByteArray()))
    }

    @Test
    fun `version negotiation picks the highest common version`() {
        assertEquals(2, ProtocolVersion.negotiate(VersionRange(1, 2), VersionRange(1, 3)))
        assertEquals(1, ProtocolVersion.negotiate(VersionRange(1, 1), VersionRange(1, 4)))
        assertEquals(null, ProtocolVersion.negotiate(VersionRange(1, 1), VersionRange(2, 3)))
    }
}
