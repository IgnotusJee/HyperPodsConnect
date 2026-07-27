package moe.chenxy.headphones.protocol.sony.feature.battery

import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.BatteryState
import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolGeneration
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

enum class SonyBatteryType(val code: Int) {
    SINGLE(0x00),
    LEFT_RIGHT(0x01),
    CRADLE(0x02),
}

object SonyBatteryFeature {
    fun query(generation: SonyProtocolGeneration, type: SonyBatteryType): ByteArray = byteArrayOf(
        (
            if (generation == SonyProtocolGeneration.V2) {
                SonyCommand.POWER_GET_STATUS
            } else {
                SonyCommand.COMMON_GET_BATTERY_LEVEL
            }
            ).toByte(),
        type.code.toByte(),
    )

    fun parse(
        generation: SonyProtocolGeneration,
        message: SonyMdrMessage,
    ): DeviceReport.Batteries? {
        val expectedCommands = if (generation == SonyProtocolGeneration.V2) {
            setOf(SonyCommand.POWER_RET_STATUS, SonyCommand.POWER_NTFY_STATUS)
        } else {
            setOf(SonyCommand.COMMON_RET_BATTERY_LEVEL, SonyCommand.COMMON_NTFY_BATTERY_LEVEL)
        }
        if (message.command !in expectedCommands || message.payload.size < 4) return null
        return when (message.payload[1].toInt() and 0xFF) {
            SonyBatteryType.SINGLE.code -> parseSingle(message.payload, BatteryComponent.SINGLE)
            SonyBatteryType.CRADLE.code -> parseSingle(message.payload, BatteryComponent.CASE)
            SonyBatteryType.LEFT_RIGHT.code -> parsePair(message.payload)
            else -> null
        }?.let(DeviceReport::Batteries)
    }

    private fun parseSingle(
        bytes: ByteArray,
        component: BatteryComponent,
    ): Map<BatteryComponent, BatteryState>? {
        if (bytes.size !in setOf(4, 5)) return null
        val state = batteryState(bytes[2], bytes[3]) ?: return null
        return mapOf(component to state)
    }

    private fun parsePair(bytes: ByteArray): Map<BatteryComponent, BatteryState>? {
        if (bytes.size !in setOf(6, 8)) return null
        val left = batteryState(bytes[2], bytes[3]) ?: return null
        val right = batteryState(bytes[4], bytes[5]) ?: return null
        return mapOf(BatteryComponent.LEFT to left, BatteryComponent.RIGHT to right)
    }

    private fun batteryState(levelByte: Byte, chargingByte: Byte): BatteryState? {
        val level = levelByte.toInt() and 0xFF
        val charging = chargingByte.toInt() and 0xFF
        if (level !in 0..100 || charging !in 0..3) return null
        return BatteryState(level, charging == 1)
    }
}
