package moe.chenxy.headphones.protocol.sony.feature.noisecontrol

import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

enum class SonyV1NoiseSettingType(val code: Int) {
    ON_OFF(0x00),
    LEVEL_ADJUSTMENT(0x01),
    DUAL_SINGLE_OFF(0x02),
}

enum class SonyV1AmbientSettingType(val code: Int) {
    ON_OFF(0x00),
    LEVEL_ADJUSTMENT(0x01),
}

data class SonyV1NoiseControlCapability(
    val noiseSettingType: SonyV1NoiseSettingType,
    val noiseStepCount: Int,
    val ambientSettingType: SonyV1AmbientSettingType,
    val ambientSteps: Map<SonyAmbientSoundMode, Int>,
)

/**
 * Sony MDR table-set 1 (protocol v1) combined NC/ASM parameter.
 *
 * The selector and layouts mirror Sound Connect 13.2.1's
 * `NcAsmInquiredType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE` implementation.
 * Capability parsing is deliberately required before parameter parsing so a
 * model-specific layout is never inferred from a newer table-set 2 device.
 */
object SonyV1NoiseControlFeature {
    private const val INQUIRED_TYPE = 0x02
    private const val EFFECT_OFF = 0x00
    private const val EFFECT_ON = 0x01
    private const val EFFECT_UNDER_CHANGE = 0x02
    private const val EFFECT_CHANGED = 0x03

    const val AMBIENT_LEVEL_MIN = 1

    fun queryCapability(): ByteArray =
        byteArrayOf(SonyCommand.NCASM_GET_CAPABILITY.toByte(), INQUIRED_TYPE.toByte())

    fun query(): ByteArray =
        byteArrayOf(SonyCommand.NCASM_GET_PARAM.toByte(), INQUIRED_TYPE.toByte())

    /** Encodes the exact table-set 1 combined NC/ASM payload used by Sound Connect. */
    fun set(
        mode: NoiseControlMode,
        current: SonyNoiseControlState,
        capability: SonyV1NoiseControlCapability,
    ): ByteArray? = when (mode) {
        NoiseControlMode.NOISE_CANCELLATION -> encode(
            capability = capability,
            effect = EFFECT_CHANGED,
            noiseValue = preferredNoiseValue(current, capability),
            ambientMode = current.ambientSoundMode,
            ambientLevel = 0,
        )
        NoiseControlMode.TRANSPARENCY -> {
            val ambientMax = capability.ambientSteps[current.ambientSoundMode] ?: return null
            val level = current.ambientLevel.takeIf { it in AMBIENT_LEVEL_MIN..ambientMax }
                ?: ambientMax
            encode(
                capability = capability,
                effect = EFFECT_CHANGED,
                noiseValue = 0,
                ambientMode = current.ambientSoundMode,
                ambientLevel = level,
            )
        }
        NoiseControlMode.OFF -> {
            // Sound Connect changes only NcAsmEffect to OFF and preserves the
            // current capability-backed NC/ASM fields for a later restoration.
            val noiseValue = current.vendorNoiseValue ?: return null
            encode(
                capability = capability,
                effect = EFFECT_OFF,
                noiseValue = noiseValue,
                ambientMode = current.ambientSoundMode,
                ambientLevel = current.ambientLevel,
            )
        }
        else -> null
    }

    fun setAmbientLevel(
        level: Int,
        current: SonyNoiseControlState,
        capability: SonyV1NoiseControlCapability,
    ): ByteArray? {
        if (current.mode != NoiseControlMode.TRANSPARENCY) return null
        val ambientMax = capability.ambientSteps[current.ambientSoundMode] ?: return null
        if (level !in AMBIENT_LEVEL_MIN..ambientMax) return null
        return encode(
            capability = capability,
            effect = EFFECT_CHANGED,
            noiseValue = 0,
            ambientMode = current.ambientSoundMode,
            ambientLevel = level,
        )
    }

    fun setAmbientSoundMode(
        mode: SonyAmbientSoundMode,
        current: SonyNoiseControlState,
        capability: SonyV1NoiseControlCapability,
    ): ByteArray? {
        if (current.mode != NoiseControlMode.TRANSPARENCY) return null
        val ambientMax = capability.ambientSteps[mode] ?: return null
        val level = current.ambientLevel.takeIf { it in AMBIENT_LEVEL_MIN..ambientMax }
            ?: ambientMax
        return encode(
            capability = capability,
            effect = EFFECT_CHANGED,
            noiseValue = 0,
            ambientMode = mode,
            ambientLevel = level,
        )
    }

    fun parseCapability(message: SonyMdrMessage): SonyV1NoiseControlCapability? {
        if (
            message.table != SonyCommandTable.TABLE1 ||
            message.command != SonyCommand.NCASM_RET_CAPABILITY
        ) return null
        val bytes = message.payload
        if (bytes.size < 8 || u8(bytes[1]) != INQUIRED_TYPE) return null
        val noiseSettingType = SonyV1NoiseSettingType.entries.firstOrNull {
            it.code == u8(bytes[2])
        } ?: return null
        val noiseStepCount = u8(bytes[3])
        val ambientSettingType = SonyV1AmbientSettingType.entries.firstOrNull {
            it.code == u8(bytes[4])
        } ?: return null
        val ambientModeCount = u8(bytes[5])
        if (ambientModeCount !in 1..SonyAmbientSoundMode.entries.size) return null
        if (bytes.size != 6 + ambientModeCount * 2) return null

        val ambientSteps = linkedMapOf<SonyAmbientSoundMode, Int>()
        repeat(ambientModeCount) { index ->
            val offset = 6 + index * 2
            val mode = SonyAmbientSoundMode.entries.firstOrNull {
                it.code == u8(bytes[offset])
            } ?: return null
            val steps = u8(bytes[offset + 1])
            if (
                mode in ambientSteps ||
                (ambientSettingType == SonyV1AmbientSettingType.LEVEL_ADJUSTMENT && steps == 0)
            ) return null
            ambientSteps[mode] = steps
        }
        return SonyV1NoiseControlCapability(
            noiseSettingType = noiseSettingType,
            noiseStepCount = noiseStepCount,
            ambientSettingType = ambientSettingType,
            ambientSteps = ambientSteps,
        )
    }

    fun parse(
        message: SonyMdrMessage,
        capability: SonyV1NoiseControlCapability,
    ): SonyNoiseControlState? {
        if (
            message.table != SonyCommandTable.TABLE1 ||
            message.command !in setOf(SonyCommand.NCASM_RET_PARAM, SonyCommand.NCASM_NTFY_PARAM)
        ) return null
        val bytes = message.payload
        if (bytes.size != 8 || u8(bytes[1]) != INQUIRED_TYPE) return null
        val effect = u8(bytes[2])
        if (effect !in setOf(EFFECT_OFF, EFFECT_ON, EFFECT_UNDER_CHANGE, EFFECT_CHANGED)) {
            return null
        }
        if (effect == EFFECT_UNDER_CHANGE) return null
        if (
            u8(bytes[3]) != capability.noiseSettingType.code ||
            u8(bytes[5]) != capability.ambientSettingType.code
        ) return null

        val noiseValue = u8(bytes[4])
        val noiseValueRange = when (capability.noiseSettingType) {
            SonyV1NoiseSettingType.ON_OFF -> 0..1
            SonyV1NoiseSettingType.LEVEL_ADJUSTMENT -> 0..capability.noiseStepCount
            SonyV1NoiseSettingType.DUAL_SINGLE_OFF -> 0..2
        }
        if (noiseValue !in noiseValueRange) return null
        val ambientMode = SonyAmbientSoundMode.entries.firstOrNull {
            it.code == u8(bytes[6])
        } ?: return null
        val ambientMax = capability.ambientSteps[ambientMode] ?: return null
        val ambientLevel = u8(bytes[7])
        val ambientRange = when (capability.ambientSettingType) {
            SonyV1AmbientSettingType.ON_OFF -> 0..1
            SonyV1AmbientSettingType.LEVEL_ADJUSTMENT -> 0..ambientMax
        }
        if (ambientLevel !in ambientRange) return null

        val mode = when {
            effect == EFFECT_OFF -> NoiseControlMode.OFF
            noiseValue != 0 -> NoiseControlMode.NOISE_CANCELLATION
            ambientLevel != 0 -> NoiseControlMode.TRANSPARENCY
            else -> return null
        }
        return SonyNoiseControlState(
            mode = mode,
            ambientLevel = ambientLevel,
            changeStatus = SonyValueChangeStatus.CHANGED,
            ambientSoundMode = ambientMode,
            ambientSelected = noiseValue == 0,
            vendorNoiseValue = noiseValue,
        )
    }

    fun capabilityMatches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.let(::u8) == INQUIRED_TYPE

    fun matches(message: SonyMdrMessage): Boolean = capabilityMatches(message)

    private fun encode(
        capability: SonyV1NoiseControlCapability,
        effect: Int,
        noiseValue: Int,
        ambientMode: SonyAmbientSoundMode,
        ambientLevel: Int,
    ): ByteArray? {
        val noiseRange = when (capability.noiseSettingType) {
            SonyV1NoiseSettingType.ON_OFF -> 0..1
            SonyV1NoiseSettingType.LEVEL_ADJUSTMENT -> 0..capability.noiseStepCount
            SonyV1NoiseSettingType.DUAL_SINGLE_OFF -> 0..2
        }
        val ambientMax = capability.ambientSteps[ambientMode] ?: return null
        val ambientRange = when (capability.ambientSettingType) {
            SonyV1AmbientSettingType.ON_OFF -> 0..1
            SonyV1AmbientSettingType.LEVEL_ADJUSTMENT -> 0..ambientMax
        }
        if (noiseValue !in noiseRange || ambientLevel !in ambientRange) return null
        return byteArrayOf(
            SonyCommand.NCASM_SET_PARAM.toByte(),
            INQUIRED_TYPE.toByte(),
            effect.toByte(),
            capability.noiseSettingType.code.toByte(),
            noiseValue.toByte(),
            capability.ambientSettingType.code.toByte(),
            ambientMode.code.toByte(),
            ambientLevel.toByte(),
        )
    }

    private fun preferredNoiseValue(
        current: SonyNoiseControlState,
        capability: SonyV1NoiseControlCapability,
    ): Int = current.vendorNoiseValue?.takeIf { it > 0 } ?: when (capability.noiseSettingType) {
        SonyV1NoiseSettingType.ON_OFF -> 1
        SonyV1NoiseSettingType.LEVEL_ADJUSTMENT -> capability.noiseStepCount
        SonyV1NoiseSettingType.DUAL_SINGLE_OFF -> 2
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}
