package moe.chenxy.headphones.protocol.sony.frame

object TandemCodec {
    const val SOF = 0x3E
    const val EOF = 0x3C
    const val ESCAPE = 0x3D
    const val MAX_PAYLOAD_LENGTH = 64 * 1024
    private const val HEADER_LENGTH = 6
    private const val CHECKSUM_LENGTH = 1

    fun encode(frame: TandemFrame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD_LENGTH) {
            "payload exceeds $MAX_PAYLOAD_LENGTH bytes"
        }
        val body = ByteArray(HEADER_LENGTH + frame.payload.size + CHECKSUM_LENGTH)
        body[0] = frame.dataType.toByte()
        body[1] = frame.sequence.toByte()
        writeU32Be(frame.payload.size, body, 2)
        frame.payload.copyInto(body, HEADER_LENGTH)
        body[body.lastIndex] = checksum(body, body.lastIndex).toByte()

        val output = ArrayList<Byte>(body.size + 2)
        output += SOF.toByte()
        body.forEach { byte ->
            when (val value = byte.toInt() and 0xFF) {
                EOF, ESCAPE, SOF -> {
                    output += ESCAPE.toByte()
                    output += (value - 0x10).toByte()
                }
                else -> output += byte
            }
        }
        output += EOF.toByte()
        return output.toByteArray()
    }

    fun decodeUnescapedBody(body: ByteArray): TandemDecodeResult {
        if (body.size < HEADER_LENGTH + CHECKSUM_LENGTH) {
            return TandemDecodeResult.Rejected("body shorter than Tandem header")
        }
        val payloadLength = readU32Be(body, 2)
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            return TandemDecodeResult.Rejected("payload length $payloadLength is outside safety limit")
        }
        val expectedLength = HEADER_LENGTH + payloadLength + CHECKSUM_LENGTH
        if (body.size != expectedLength) {
            return TandemDecodeResult.Rejected(
                "length mismatch: header=$payloadLength body=${body.size}",
            )
        }
        val expectedChecksum = checksum(body, body.lastIndex)
        val actualChecksum = body.last().toInt() and 0xFF
        if (actualChecksum != expectedChecksum) {
            return TandemDecodeResult.Rejected(
                "checksum mismatch: expected=$expectedChecksum actual=$actualChecksum",
            )
        }
        return TandemDecodeResult.Frame(
            TandemFrame(
                dataType = body[0].toInt() and 0xFF,
                sequence = body[1].toInt() and 0xFF,
                payload = body.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + payloadLength),
            ),
        )
    }

    private fun checksum(bytes: ByteArray, endExclusive: Int): Int {
        var sum = 0
        for (index in 0 until endExclusive) {
            sum = (sum + (bytes[index].toInt() and 0xFF)) and 0xFF
        }
        return sum
    }

    private fun writeU32Be(value: Int, target: ByteArray, offset: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int {
        val high = source[offset].toInt() and 0xFF
        if (high and 0x80 != 0) return -1
        return (high shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
    }
}
