package moe.chenxy.headphones.protocol.sony.feature.noisecontrol

import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

data class SonyNoiseControlState(
    val mode: NoiseControlMode,
    val ambientLevel: Int,
    internal val ambientSelected: Boolean,
)

/**
 * LinkBuds S NC/ASM parameter 0x17.
 *
 * The layout is intentionally exact. It comes from repeated Sony Headphones
 * Connect 13.0.8 GATT captures on LinkBuds S 4.2.1; malformed or extended
 * variants are rejected instead of being guessed.
 */
object SonyNoiseControlFeature {
    const val FUNCTION_ID = 0x17FF
    private const val PARAMETER_ID = 0x17
    private const val PARAMETER_VERSION = 0x01
    private const val RESERVED = 0x00

    fun query(): ByteArray =
        byteArrayOf(SonyCommand.NCASM_GET_PARAM.toByte(), PARAMETER_ID.toByte())

    fun set(mode: NoiseControlMode, current: SonyNoiseControlState): ByteArray? {
        val enabled: Int
        val ambient: Int
        when (mode) {
            NoiseControlMode.OFF -> {
                enabled = 0
                ambient = if (current.ambientSelected) 1 else 0
            }
            NoiseControlMode.NOISE_CANCELLATION -> {
                enabled = 1
                ambient = 0
            }
            NoiseControlMode.TRANSPARENCY -> {
                enabled = 1
                ambient = 1
            }
            else -> return null
        }
        return byteArrayOf(
            SonyCommand.NCASM_SET_PARAM.toByte(),
            PARAMETER_ID.toByte(),
            PARAMETER_VERSION.toByte(),
            enabled.toByte(),
            ambient.toByte(),
            RESERVED.toByte(),
            current.ambientLevel.toByte(),
        )
    }

    fun parse(message: SonyMdrMessage): SonyNoiseControlState? {
        if (
            message.table != SonyCommandTable.TABLE1 ||
            message.command !in setOf(
                SonyCommand.NCASM_RET_PARAM,
                SonyCommand.NCASM_NTFY_PARAM,
            )
        ) return null
        val bytes = message.payload
        if (
            bytes.size != 7 ||
            (bytes[1].toInt() and 0xFF) != PARAMETER_ID ||
            (bytes[2].toInt() and 0xFF) != PARAMETER_VERSION ||
            (bytes[5].toInt() and 0xFF) != RESERVED
        ) return null
        val enabled = bytes[3].toInt() and 0xFF
        val ambient = bytes[4].toInt() and 0xFF
        val level = bytes[6].toInt() and 0xFF
        if (enabled !in 0..1 || ambient !in 0..1 || level !in 0..20) return null
        val mode = when {
            enabled == 0 -> NoiseControlMode.OFF
            ambient == 0 -> NoiseControlMode.NOISE_CANCELLATION
            else -> NoiseControlMode.TRANSPARENCY
        }
        return SonyNoiseControlState(
            mode = mode,
            ambientLevel = level,
            ambientSelected = ambient == 1,
        )
    }

    fun matches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.toInt()?.and(0xFF) == PARAMETER_ID
}
