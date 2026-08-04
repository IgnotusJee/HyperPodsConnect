package moe.chenxy.headphones.protocol.sony.feature.wearing

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.WearComponent
import moe.chenxy.headphones.core.feature.WearState
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

enum class SonyWearingStatus(val code: Int) {
    NORMAL(0x00),
    ILLEGAL(0x01),
    LEFT_SIDE_NOT_WEAR(0x02),
    RIGHT_SIDE_NOT_WEAR(0x03),
    BOTH_NOT_WEAR(0x04),
    ;

    companion object {
        fun fromCode(code: Int): SonyWearingStatus? = entries.firstOrNull { it.code == code }
    }
}

/** Sony table2 System wearing-status checker (function 0xF0). */
object SonyWearingFeature {
    const val FUNCTION_ID = 0xF0
    private const val INQUIRED_TYPE = 0x00

    fun query(): ByteArray = byteArrayOf(
        SonyCommand.SYSTEM_GET_STATUS.toByte(),
        INQUIRED_TYPE.toByte(),
    )

    fun matches(message: SonyMdrMessage): Boolean =
        message.table == SonyCommandTable.TABLE2 &&
            message.command in setOf(
                SonyCommand.SYSTEM_RET_STATUS,
                SonyCommand.SYSTEM_NTFY_STATUS,
            ) &&
            message.payload.size == 3 &&
            (message.payload[1].toInt() and 0xFF) == INQUIRED_TYPE &&
            SonyWearingStatus.fromCode(message.payload[2].toInt() and 0xFF) != null

    fun parse(message: SonyMdrMessage): DeviceReport.Wearing? {
        if (!matches(message)) return null
        return when (SonyWearingStatus.fromCode(message.payload[2].toInt() and 0xFF)) {
            SonyWearingStatus.NORMAL -> report(WearState.WEARING, WearState.WEARING)
            SonyWearingStatus.ILLEGAL -> report(WearState.UNKNOWN, WearState.UNKNOWN)
            SonyWearingStatus.LEFT_SIDE_NOT_WEAR ->
                report(WearState.REMOVED, WearState.WEARING)
            SonyWearingStatus.RIGHT_SIDE_NOT_WEAR ->
                report(WearState.WEARING, WearState.REMOVED)
            SonyWearingStatus.BOTH_NOT_WEAR -> report(WearState.REMOVED, WearState.REMOVED)
            null -> null
        }
    }

    private fun report(left: WearState, right: WearState) = DeviceReport.Wearing(
        mapOf(
            WearComponent.LEFT to left,
            WearComponent.RIGHT to right,
        ),
    )
}
