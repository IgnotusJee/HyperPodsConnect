package moe.chenxy.headphones.protocol.sony.message

import moe.chenxy.headphones.protocol.sony.frame.TandemFrame

enum class SonyDataType(
    val code: Int,
    val table: SonyCommandTable?,
    val requiresAcknowledgement: Boolean,
) {
    DATA(0x00, null, true),
    ACK(0x01, null, false),
    DATA_MDR(0x0C, SonyCommandTable.TABLE1, true),
    DATA_MDR_NO2(0x0E, SonyCommandTable.TABLE2, true),
    SHOT_MDR(0x1C, SonyCommandTable.TABLE1, false),
    SHOT_MDR_NO2(0x1E, SonyCommandTable.TABLE2, false);

    companion object {
        fun fromCode(code: Int): SonyDataType? = entries.firstOrNull { it.code == code }
    }
}

enum class SonyCommandTable { TABLE1, TABLE2 }

data class SonyMdrMessage(
    val dataType: SonyDataType,
    val sequence: Int,
    val command: Int,
    val payload: ByteArray,
) {
    val table: SonyCommandTable get() = requireNotNull(dataType.table)

    companion object {
        fun from(frame: TandemFrame): SonyMdrMessage? {
            val type = SonyDataType.fromCode(frame.dataType) ?: return null
            if (type.table == null || frame.payload.isEmpty()) return null
            return SonyMdrMessage(
                dataType = type,
                sequence = frame.sequence,
                command = frame.payload[0].toInt() and 0xFF,
                payload = frame.payload.copyOf(),
            )
        }
    }
}

object SonyCommand {
    const val CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CONNECT_RET_PROTOCOL_INFO = 0x01
    const val CONNECT_GET_CAPABILITY_INFO = 0x02
    const val CONNECT_RET_CAPABILITY_INFO = 0x03
    const val CONNECT_GET_DEVICE_INFO = 0x04
    const val CONNECT_RET_DEVICE_INFO = 0x05
    const val CONNECT_GET_SUPPORT_FUNCTION = 0x06
    const val CONNECT_RET_SUPPORT_FUNCTION = 0x07

    const val COMMON_GET_BATTERY_LEVEL = 0x10
    const val COMMON_RET_BATTERY_LEVEL = 0x11
    const val COMMON_NTFY_BATTERY_LEVEL = 0x13

    const val POWER_GET_STATUS = 0x22
    const val POWER_RET_STATUS = 0x23
    const val POWER_NTFY_STATUS = 0x25
}

enum class SonyDeviceInfoType(val code: Int) {
    MODEL_NAME(0x01),
    FIRMWARE_VERSION(0x02),
    SERIES_AND_COLOR(0x03),
    INSTRUCTION_GUIDE(0x04),
}
