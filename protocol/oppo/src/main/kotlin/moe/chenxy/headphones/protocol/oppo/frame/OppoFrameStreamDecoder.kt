package moe.chenxy.headphones.protocol.oppo.frame

/**
 * Separates OPOv1 link frames out of an arbitrary byte stream.
 *
 * A transport chunk is whatever the link happened to deliver. Captures from a
 * real Enco Air5s show a single response arriving as a 3-byte read followed by a
 * 15-byte read, so treating one read as one packet — which the original
 * implementation did — loses frames outright.
 *
 * Layout: `0xAA | 7-bit varint encoded length | control(2) | inner packet`.
 * The encoded length counts the control bytes and the inner packet but excludes
 * the marker and the varint itself.
 *
 * This type stops at complete link frames. Fragment reassembly across frames and
 * inner-message decoding belong to [OppoMessageCodec].
 */
class OppoFrameStreamDecoder(
    private val maxEncodedLength: Int = DEFAULT_MAX_ENCODED_LENGTH,
) {
    init {
        require(maxEncodedLength in MIN_ENCODED_LENGTH..MAX_SUPPORTED_ENCODED_LENGTH) {
            "maxEncodedLength out of range: $maxEncodedLength"
        }
    }

    private var pending = ByteArray(0)

    /** Feeds one transport chunk and returns whatever frames it completed. */
    fun feed(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        pending += bytes

        val frames = mutableListOf<ByteArray>()
        while (pending.isNotEmpty()) {
            val markerIndex = pending.indexOf(FRAME_MARKER)
            if (markerIndex < 0) {
                // Nothing in the buffer can start a frame any more.
                pending = ByteArray(0)
                break
            }
            if (markerIndex > 0) pending = pending.copyOfRange(markerIndex, pending.size)

            when (val header = readHeader(pending)) {
                HeaderResult.Incomplete -> break
                // Drop one byte and rescan: a 0xAA inside payload data can look
                // like a marker, and refusing to resynchronise would wedge the
                // stream permanently.
                HeaderResult.Invalid -> pending = pending.copyOfRange(1, pending.size)
                is HeaderResult.Complete -> {
                    val frameLength = 1 + header.varintSize + header.encodedLength
                    if (pending.size < frameLength) break
                    frames += pending.copyOfRange(0, frameLength)
                    pending = pending.copyOfRange(frameLength, pending.size)
                }
            }
        }
        return frames
    }

    /** Discards any partial frame, e.g. when a connection generation ends. */
    fun reset() {
        pending = ByteArray(0)
    }

    fun pendingByteCount(): Int = pending.size

    private fun readHeader(data: ByteArray): HeaderResult {
        var value = 0
        var shift = 0
        for (index in 1 until minOf(data.size, MAX_VARINT_BYTES + 1)) {
            val current = data[index].toInt() and 0xFF
            value = value or ((current and 0x7F) shl shift)
            if ((current and 0x80) == 0) {
                return if (value in MIN_ENCODED_LENGTH..maxEncodedLength) {
                    HeaderResult.Complete(value, index)
                } else {
                    HeaderResult.Invalid
                }
            }
            shift += 7
        }
        return if (data.size <= MAX_VARINT_BYTES) HeaderResult.Incomplete else HeaderResult.Invalid
    }

    private sealed interface HeaderResult {
        data object Incomplete : HeaderResult
        data object Invalid : HeaderResult
        data class Complete(val encodedLength: Int, val varintSize: Int) : HeaderResult
    }

    companion object {
        const val FRAME_MARKER: Byte = 0xAA.toByte()
        private const val MIN_ENCODED_LENGTH = 2
        private const val MAX_VARINT_BYTES = 3
        private const val MAX_SUPPORTED_ENCODED_LENGTH = (1 shl (MAX_VARINT_BYTES * 7)) - 1
        const val DEFAULT_MAX_ENCODED_LENGTH = 16 * 1024
    }
}
