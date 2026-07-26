package moe.chenxy.oppopods.pods

/**
 * Incrementally separates OPOv1 link frames from an RFCOMM/GATT byte stream.
 *
 * The encoded length starts immediately after 0xAA and uses little-endian
 * 7-bit varint groups. It counts the control/reserved bytes and inner packet,
 * but excludes the 0xAA marker and the varint itself.
 *
 * This Phase 0 decoder deliberately stops at complete link frames. Fragment
 * reassembly and inner-message decoding belong to the OPPO protocol module.
 */
class OppoFrameStreamDecoder(
    private val maxEncodedLength: Int = DEFAULT_MAX_ENCODED_LENGTH,
) {
    init {
        require(maxEncodedLength in MIN_ENCODED_LENGTH..MAX_SUPPORTED_ENCODED_LENGTH)
    }

    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        pending += bytes

        val frames = mutableListOf<ByteArray>()
        while (pending.isNotEmpty()) {
            val markerIndex = pending.indexOf(FRAME_MARKER)
            if (markerIndex < 0) {
                pending = ByteArray(0)
                break
            }
            if (markerIndex > 0) pending = pending.copyOfRange(markerIndex, pending.size)

            when (val header = readHeader(pending)) {
                HeaderResult.Incomplete -> break
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

    fun reset() {
        pending = ByteArray(0)
    }

    internal fun pendingByteCount(): Int = pending.size

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
        private const val FRAME_MARKER: Byte = 0xAA.toByte()
        private const val MIN_ENCODED_LENGTH = 2
        private const val MAX_VARINT_BYTES = 3
        private const val MAX_SUPPORTED_ENCODED_LENGTH = (1 shl (MAX_VARINT_BYTES * 7)) - 1
        const val DEFAULT_MAX_ENCODED_LENGTH = 16 * 1024
    }
}
