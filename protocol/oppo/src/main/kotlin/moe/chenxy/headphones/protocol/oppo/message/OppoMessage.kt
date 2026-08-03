package moe.chenxy.headphones.protocol.oppo.message

/**
 * OPPO command codes.
 *
 * Values verified against captures from an Enco Air5s unless marked otherwise.
 * A response code is always `request or 0x8000`.
 */
object OppoCommand {
    const val QUERY_CAPABILITY = 0x0100
    const val QUERY_FIRMWARE = 0x0105
    const val QUERY_BATTERY = 0x0106
    const val QUERY_ANC = 0x010C
    const val QUERY_BATCH_STATUS = 0x010D
    const val QUERY_EQ = 0x010F
    const val QUERY_CUSTOM_EQ = 0x0122

    const val QUERY_NOTIFICATION_SUPPORT = 0x0200
    const val SUBSCRIBE_NOTIFICATION_SINGLE = 0x0201
    /** Multiplexed event channel: battery, wear and ANC all arrive here. */
    const val NOTIFICATION_EVENT = 0x0204
    const val SUBSCRIBE_NOTIFICATION_BATCH = 0x0205

    const val SET_SWITCH_FEATURE = 0x0403
    const val SET_ANC = 0x0404
    const val SET_EQ = 0x0406
    const val SET_CUSTOM_EQ = 0x0418
    const val SET_SPATIAL_AUDIO = 0x0422
    const val EQ_PRESET_NOTIFICATION = 0x0504
    const val SPATIAL_AUDIO_NOTIFICATION = 0x0510

    const val RESPONSE_FLAG = 0x8000

    fun responseOf(command: Int): Int = command or RESPONSE_FLAG

    fun requestOf(command: Int): Int = command and RESPONSE_FLAG.inv()

    fun isResponse(command: Int): Boolean = (command and RESPONSE_FLAG) != 0
}

/** Feature ids used by the switch-set command and the batch status query. */
object OppoFeature {
    const val GAME_MODE = 0x28
    const val LOW_LATENCY = 0x06
    const val DUAL_DEVICE = 0x11
    const val SPATIAL_SOUND_SWITCH = 0x1B
}

/** Payload type byte of a [OppoCommand.NOTIFICATION_EVENT]. */
object OppoNotificationType {
    const val BATTERY = 0x01
    const val WEAR = 0x02
    const val ANC = 0x03
}

/**
 * One decoded inner packet.
 *
 * Every parser takes this instead of a raw frame, so header layout is understood
 * in exactly one place. The old implementation re-derived the command, sequence
 * and payload offsets inside each parser, which is how two of them ended up
 * disagreeing about whether a truncated payload was valid.
 */
data class OppoMessage(
    val command: Int,
    val sequence: Int,
    val payload: ByteArray,
    /** Length the frame claimed, which may exceed what actually arrived. */
    val declaredPayloadLength: Int,
    val control: ByteArray,
    val raw: ByteArray,
) {
    val isResponse: Boolean get() = OppoCommand.isResponse(command)

    val requestCommand: Int get() = OppoCommand.requestOf(command)

    /** False when the frame promised more payload than it carried. */
    val isComplete: Boolean get() = declaredPayloadLength == payload.size

    /**
     * First payload byte for command families that lead with a status, or null
     * when there is no payload. Zero means success.
     */
    val status: Int? get() = payload.firstOrNull()?.toInt()?.and(0xFF)

    val isSuccess: Boolean get() = status == 0x00

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OppoMessage) return false
        return command == other.command &&
            sequence == other.sequence &&
            declaredPayloadLength == other.declaredPayloadLength &&
            payload.contentEquals(other.payload) &&
            control.contentEquals(other.control)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + sequence
        result = 31 * result + declaredPayloadLength
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + control.contentHashCode()
        return result
    }

    override fun toString(): String {
        val kind = if (isResponse) "RET" else "CMD"
        return "$kind 0x%04X seq=0x%02X payload=%s".format(command, sequence, payload.toHexString())
    }
}

internal fun ByteArray.toHexString(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
