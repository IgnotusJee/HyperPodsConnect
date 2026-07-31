package moe.chenxy.headphones.protocol.sony.feature.noisecontrol

import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

data class SonyNoiseControlState(
    val mode: NoiseControlMode,
    val ambientLevel: Int,
    val changeStatus: SonyValueChangeStatus,
    val ambientSoundMode: SonyAmbientSoundMode,
    internal val ambientSelected: Boolean,
)

enum class SonyValueChangeStatus(val code: Int) {
    UNDER_CHANGING(0x00),
    CHANGED(0x01),
}

enum class SonyAmbientSoundMode(val code: Int) {
    NORMAL(0x00),
    VOICE(0x01),
}

/**
 * LinkBuds S NC/ASM parameter 0x17.
 *
 * The layout is intentionally exact. It comes from repeated Sony Headphones
 * Connect 13.0.8 and Sound Connect 13.2.0 GATT captures on LinkBuds S 4.2.1;
 * malformed or extended variants are rejected instead of being guessed.
 */
object SonyNoiseControlFeature {
    const val FUNCTION_ID = 0x17FF
    const val AMBIENT_LEVEL_MIN = 1
    const val AMBIENT_LEVEL_MAX = 20
    private const val PARAMETER_ID = 0x17

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
            SonyValueChangeStatus.CHANGED.code.toByte(),
            enabled.toByte(),
            ambient.toByte(),
            current.ambientSoundMode.code.toByte(),
            current.ambientLevel.toByte(),
        )
    }

    fun setAmbientLevel(level: Int, current: SonyNoiseControlState): ByteArray? {
        if (
            current.mode != NoiseControlMode.TRANSPARENCY ||
            level !in AMBIENT_LEVEL_MIN..AMBIENT_LEVEL_MAX
        ) return null
        return byteArrayOf(
            SonyCommand.NCASM_SET_PARAM.toByte(),
            PARAMETER_ID.toByte(),
            SonyValueChangeStatus.CHANGED.code.toByte(),
            0x01,
            0x01,
            current.ambientSoundMode.code.toByte(),
            level.toByte(),
        )
    }

    fun setAmbientSoundMode(
        mode: SonyAmbientSoundMode,
        current: SonyNoiseControlState,
    ): ByteArray? {
        if (current.mode != NoiseControlMode.TRANSPARENCY) return null
        return byteArrayOf(
            SonyCommand.NCASM_SET_PARAM.toByte(),
            PARAMETER_ID.toByte(),
            SonyValueChangeStatus.CHANGED.code.toByte(),
            0x01,
            0x01,
            mode.code.toByte(),
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
            (bytes[1].toInt() and 0xFF) != PARAMETER_ID
        ) return null
        val changeStatus = SonyValueChangeStatus.entries.firstOrNull {
            it.code == (bytes[2].toInt() and 0xFF)
        } ?: return null
        // Only a committed device report may update confirmed state.
        if (changeStatus != SonyValueChangeStatus.CHANGED) return null
        val enabled = bytes[3].toInt() and 0xFF
        val ambient = bytes[4].toInt() and 0xFF
        val ambientSoundMode = SonyAmbientSoundMode.entries.firstOrNull {
            it.code == (bytes[5].toInt() and 0xFF)
        } ?: return null
        val level = bytes[6].toInt() and 0xFF
        if (enabled !in 0..1 || ambient !in 0..1 || level !in 0..20) return null
        if (ambient == 1 && level !in AMBIENT_LEVEL_MIN..AMBIENT_LEVEL_MAX) return null
        val mode = when {
            enabled == 0 -> NoiseControlMode.OFF
            ambient == 0 -> NoiseControlMode.NOISE_CANCELLATION
            else -> NoiseControlMode.TRANSPARENCY
        }
        return SonyNoiseControlState(
            mode = mode,
            ambientLevel = level,
            changeStatus = changeStatus,
            ambientSoundMode = ambientSoundMode,
            ambientSelected = ambient == 1,
        )
    }

    fun matches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.toInt()?.and(0xFF) == PARAMETER_ID
}
