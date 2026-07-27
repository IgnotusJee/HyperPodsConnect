package moe.chenxy.headphones.protocol.sony.frame

/**
 * Incremental, noise-tolerant Tandem decoder.
 *
 * A new SOF always starts a fresh candidate. Invalid escapes and oversized
 * bodies are rejected without retaining attacker-controlled buffers.
 */
class TandemStreamDecoder(
    private val maxBodyLength: Int = TandemCodec.MAX_PAYLOAD_LENGTH + 7,
) {
    private val body = ArrayList<Byte>()
    private var insideFrame = false
    private var escaped = false

    fun feed(chunk: ByteArray): List<TandemDecodeResult> {
        val results = mutableListOf<TandemDecodeResult>()
        chunk.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (!insideFrame) {
                if (value == TandemCodec.SOF) startFrame()
                return@forEach
            }
            if (escaped) {
                escaped = false
                val decoded = when (value) {
                    TandemCodec.EOF - 0x10,
                    TandemCodec.ESCAPE - 0x10,
                    TandemCodec.SOF - 0x10,
                    -> value + 0x10
                    else -> {
                        results += TandemDecodeResult.Rejected("invalid escape 0x${value.toString(16)}")
                        reset()
                        return@forEach
                    }
                }
                body += decoded.toByte()
                rejectIfOversized(results)
                return@forEach
            }
            when (value) {
                TandemCodec.SOF -> startFrame()
                TandemCodec.ESCAPE -> escaped = true
                TandemCodec.EOF -> {
                    results += TandemCodec.decodeUnescapedBody(body.toByteArray())
                    reset()
                }
                else -> {
                    body += byte
                    rejectIfOversized(results)
                }
            }
        }
        return results
    }

    fun reset() {
        body.clear()
        insideFrame = false
        escaped = false
    }

    private fun startFrame() {
        body.clear()
        insideFrame = true
        escaped = false
    }

    private fun rejectIfOversized(results: MutableList<TandemDecodeResult>) {
        if (body.size <= maxBodyLength) return
        results += TandemDecodeResult.Rejected("frame body exceeds $maxBodyLength bytes")
        reset()
    }
}
