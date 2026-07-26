package moe.chenxy.headphones.protocol.oppo.message

import moe.chenxy.headphones.protocol.oppo.frame.OppoFrameStreamDecoder

/**
 * Builds and decodes OPOv1 inner packets.
 *
 * Header handling lives here and nowhere else, which is the point: the previous
 * design had every parser re-read the command, sequence and length off raw
 * bytes, and they did not all apply the same validation.
 */
object OppoMessageCodec {

    private const val INNER_HEADER_SIZE = 5
    private const val CONTROL_SIZE = 2
    private const val MAX_VARINT_BYTES = 3

    /** Sequence the app used before per-device sequencing existed. */
    const val LEGACY_SEQUENCE = 0xF0

    fun encode(
        command: Int,
        sequence: Int = LEGACY_SEQUENCE,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        require(payload.size <= 0xFFFF) { "payload exceeds the 16-bit length field: ${payload.size}" }

        val encodedLength = CONTROL_SIZE + INNER_HEADER_SIZE + payload.size
        val lengthBytes = encodeVarint(encodedLength)
        val frame = ByteArray(1 + lengthBytes.size + encodedLength)

        frame[0] = OppoFrameStreamDecoder.FRAME_MARKER
        lengthBytes.copyInto(frame, destinationOffset = 1)

        val controlAt = 1 + lengthBytes.size
        frame[controlAt] = 0x00
        frame[controlAt + 1] = 0x00

        val innerAt = controlAt + CONTROL_SIZE
        frame[innerAt] = (command and 0xFF).toByte()
        frame[innerAt + 1] = ((command shr 8) and 0xFF).toByte()
        frame[innerAt + 2] = sequence.toByte()
        frame[innerAt + 3] = (payload.size and 0xFF).toByte()
        frame[innerAt + 4] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(frame, innerAt + INNER_HEADER_SIZE)

        return frame
    }

    /**
     * Decodes one complete link frame, or null when it is not a usable frame.
     *
     * A message whose declared length exceeds the bytes present is still
     * returned, with [OppoMessage.isComplete] false, so callers can tell a
     * truncated frame from an absent one rather than both surfacing as null.
     */
    fun decode(frame: ByteArray): OppoMessage? {
        if (frame.isEmpty() || frame[0] != OppoFrameStreamDecoder.FRAME_MARKER) return null

        var varintSize = 0
        var shift = 0
        var encodedLength = 0
        for (index in 1 until minOf(frame.size, MAX_VARINT_BYTES + 1)) {
            val current = frame[index].toInt() and 0xFF
            encodedLength = encodedLength or ((current and 0x7F) shl shift)
            varintSize = index
            if ((current and 0x80) == 0) break
            shift += 7
            if (index == MAX_VARINT_BYTES) return null
        }
        if (varintSize == 0) return null

        val controlAt = 1 + varintSize
        val innerAt = controlAt + CONTROL_SIZE
        if (frame.size < innerAt + INNER_HEADER_SIZE) return null

        val command = (frame[innerAt].toInt() and 0xFF) or ((frame[innerAt + 1].toInt() and 0xFF) shl 8)
        val sequence = frame[innerAt + 2].toInt() and 0xFF
        val declared = (frame[innerAt + 3].toInt() and 0xFF) or ((frame[innerAt + 4].toInt() and 0xFF) shl 8)

        val payloadAt = innerAt + INNER_HEADER_SIZE
        val available = (frame.size - payloadAt).coerceAtLeast(0)
        val payload = frame.copyOfRange(payloadAt, payloadAt + minOf(declared, available))

        return OppoMessage(
            command = command,
            sequence = sequence,
            payload = payload,
            declaredPayloadLength = declared,
            control = frame.copyOfRange(controlAt, innerAt),
            raw = frame,
        )
    }

    /** Convenience for a whole stream: split into frames, then decode each. */
    fun decodeStream(decoder: OppoFrameStreamDecoder, chunk: ByteArray): List<OppoMessage> =
        decoder.feed(chunk).mapNotNull(::decode)

    private fun encodeVarint(value: Int): ByteArray {
        require(value >= 0)
        var remaining = value
        val out = mutableListOf<Byte>()
        do {
            val group = remaining and 0x7F
            remaining = remaining ushr 7
            out += (if (remaining == 0) group else group or 0x80).toByte()
        } while (remaining != 0)
        return out.toByteArray()
    }
}
