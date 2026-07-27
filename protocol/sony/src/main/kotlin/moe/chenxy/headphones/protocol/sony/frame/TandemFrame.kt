package moe.chenxy.headphones.protocol.sony.frame

/**
 * One decoded Sony Tandem frame.
 *
 * The payload is copied at the boundary so transport buffers cannot mutate a
 * frame after checksum validation.
 */
class TandemFrame(
    val dataType: Int,
    val sequence: Int,
    payload: ByteArray,
) {
    val payload: ByteArray = payload.copyOf()

    init {
        require(dataType in 0..0xFF)
        require(sequence in 0..0xFF)
    }

    override fun equals(other: Any?): Boolean =
        other is TandemFrame &&
            dataType == other.dataType &&
            sequence == other.sequence &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * dataType + sequence) + payload.contentHashCode()

    override fun toString(): String =
        "TandemFrame(dataType=0x${dataType.toString(16)}, sequence=$sequence, payload=${payload.size} bytes)"
}

sealed interface TandemDecodeResult {
    data class Frame(val value: TandemFrame) : TandemDecodeResult
    data class Rejected(val reason: String) : TandemDecodeResult
}
