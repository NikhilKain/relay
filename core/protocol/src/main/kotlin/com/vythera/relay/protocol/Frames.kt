package com.vythera.relay.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/*
 * Relay's framing layer, carried inside a TLS 1.3 stream.
 *
 *   connection preface:  'R' 'L' 'Y' <wire version: u8>
 *   frame:               <kind: u8> <payload length: u32 big-endian> <payload>
 *
 * The wire version covers only this framing. Message semantics are negotiated
 * separately through ProtocolVersion in RelayMessage.Hello.
 */

enum class FrameKind(val code: Int) {
    CONTROL(0x01),
    DATA(0x02);

    companion object {
        fun of(code: Int): FrameKind? = entries.firstOrNull { it.code == code }
    }
}

sealed interface Frame {
    class Control(val payload: ByteArray) : Frame
    class Data(val chunk: DataChunk) : Frame
}

/**
 * A slice of one file of one transfer. The data frame payload is
 * `<transfer id: 16 byte UUID> <file index: u32> <offset: u64> <bytes>`.
 */
class DataChunk(
    val transferId: UUID,
    val index: Int,
    val offset: Long,
    val bytes: ByteArray,
    val length: Int = bytes.size,
) {
    init {
        require(index >= 0 && offset >= 0 && length in 0..bytes.size) { "Invalid chunk header" }
    }
}

/** The peer sent bytes that are not valid Relay framing. The connection must be closed. */
class ProtocolException(message: String) : IOException(message)

object FrameFormat {
    val PREFACE_MAGIC: ByteArray = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'Y'.code.toByte())
    const val WIRE_VERSION = 1

    const val MAX_CONTROL_PAYLOAD = 256 * 1024
    const val DATA_HEADER_SIZE = 16 + 4 + 8
    const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
    const val DEFAULT_CHUNK_SIZE = 512 * 1024
}

/** Writes frames. Not thread-safe: callers serialize access. */
class FrameWriter(output: OutputStream) {
    private val out = DataOutputStream(output)

    fun writePreface() {
        out.write(FrameFormat.PREFACE_MAGIC)
        out.writeByte(FrameFormat.WIRE_VERSION)
        out.flush()
    }

    fun writeControl(message: RelayMessage) {
        val payload = MessageCodec.encode(message)
        if (payload.size > FrameFormat.MAX_CONTROL_PAYLOAD) {
            throw ProtocolException("Control frame of ${payload.size} bytes exceeds limit")
        }
        out.writeByte(FrameKind.CONTROL.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /**
     * Writes a data frame without flushing. The sender flushes after a batch of chunks
     * (or before any control frame, which always flushes), which saves a syscall per chunk.
     */
    fun writeData(chunk: DataChunk) {
        if (chunk.length > FrameFormat.MAX_CHUNK_SIZE) throw ProtocolException("Chunk too large")
        out.writeByte(FrameKind.DATA.code)
        out.writeInt(FrameFormat.DATA_HEADER_SIZE + chunk.length)
        out.writeLong(chunk.transferId.mostSignificantBits)
        out.writeLong(chunk.transferId.leastSignificantBits)
        out.writeInt(chunk.index)
        out.writeLong(chunk.offset)
        out.write(chunk.bytes, 0, chunk.length)
    }

    fun flush() = out.flush()
}

/** Reads frames, validating sizes before allocating so a hostile peer cannot exhaust memory. */
class FrameReader(input: InputStream) {
    private val input = DataInputStream(input)

    fun readPreface() {
        val magic = ByteArray(FrameFormat.PREFACE_MAGIC.size)
        readFully(magic)
        if (!magic.contentEquals(FrameFormat.PREFACE_MAGIC)) throw ProtocolException("Not a Relay connection")
        val version = input.read()
        if (version != FrameFormat.WIRE_VERSION) throw ProtocolException("Unsupported wire version $version")
    }

    /** Returns the next frame, or null if the peer closed the stream cleanly between frames. */
    fun read(): Frame? {
        val kindCode = input.read()
        if (kindCode == -1) return null
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            throw ProtocolException("Stream ended mid-frame")
        }
        return when (FrameKind.of(kindCode)) {
            FrameKind.CONTROL -> {
                if (length !in 0..FrameFormat.MAX_CONTROL_PAYLOAD) throw ProtocolException("Bad control length $length")
                Frame.Control(ByteArray(length).also(::readFully))
            }
            FrameKind.DATA -> {
                val bodyLength = length - FrameFormat.DATA_HEADER_SIZE
                if (bodyLength !in 0..FrameFormat.MAX_CHUNK_SIZE) throw ProtocolException("Bad data length $length")
                val header = ByteArray(FrameFormat.DATA_HEADER_SIZE).also(::readFully)
                val headerIn = DataInputStream(header.inputStream())
                val id = UUID(headerIn.readLong(), headerIn.readLong())
                val index = headerIn.readInt()
                val offset = headerIn.readLong()
                if (index < 0 || offset < 0) throw ProtocolException("Bad chunk header")
                Frame.Data(DataChunk(id, index, offset, ByteArray(bodyLength).also(::readFully)))
            }
            null -> throw ProtocolException("Unknown frame kind $kindCode")
        }
    }

    private fun readFully(buffer: ByteArray) {
        try {
            input.readFully(buffer)
        } catch (e: EOFException) {
            throw ProtocolException("Stream ended mid-frame")
        }
    }
}
