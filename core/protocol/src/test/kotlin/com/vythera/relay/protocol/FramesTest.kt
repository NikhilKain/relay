package com.vythera.relay.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FramesTest {

    @Test
    fun `control and data frames round trip in order`() {
        val out = ByteArrayOutputStream()
        val writer = FrameWriter(out)
        val id = UUID.randomUUID()
        val bytes = ByteArray(10_000) { (it % 251).toByte() }

        writer.writePreface()
        writer.writeControl(RelayMessage.Ping(1))
        writer.writeData(DataChunk(id, 3, 5_000_000_000L, bytes, length = 9_000))
        writer.writeControl(RelayMessage.TransferComplete("t"))
        writer.flush()

        val reader = FrameReader(ByteArrayInputStream(out.toByteArray()))
        reader.readPreface()
        val ping = assertIs<Frame.Control>(reader.read())
        assertEquals(DecodeResult.Message(RelayMessage.Ping(1)), MessageCodec.decode(ping.payload))

        val data = assertIs<Frame.Data>(reader.read()).chunk
        assertEquals(id, data.transferId)
        assertEquals(3, data.index)
        assertEquals(5_000_000_000L, data.offset)
        assertContentEquals(bytes.copyOf(9_000), data.bytes)

        assertIs<Frame.Control>(reader.read())
        assertNull(reader.read())
    }

    @Test
    fun `rejects streams that are not Relay`() {
        val reader = FrameReader(ByteArrayInputStream("GET / HTTP/1.1".encodeToByteArray()))
        assertFailsWith<ProtocolException> { reader.readPreface() }
    }

    @Test
    fun `rejects oversized control frames before allocating`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeByte(FrameKind.CONTROL.code)
            writeInt(Int.MAX_VALUE)
        }
        assertFailsWith<ProtocolException> { FrameReader(ByteArrayInputStream(out.toByteArray())).read() }
    }

    @Test
    fun `rejects unknown frame kinds and truncated frames`() {
        val unknown = byteArrayOf(0x7f, 0, 0, 0, 0)
        assertFailsWith<ProtocolException> { FrameReader(ByteArrayInputStream(unknown)).read() }

        val truncated = byteArrayOf(FrameKind.CONTROL.code.toByte(), 0, 0, 0, 10, 1, 2)
        assertFailsWith<ProtocolException> { FrameReader(ByteArrayInputStream(truncated)).read() }
    }
}
