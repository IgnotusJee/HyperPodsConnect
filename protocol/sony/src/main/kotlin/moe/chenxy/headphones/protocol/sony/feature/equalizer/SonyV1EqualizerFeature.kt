package moe.chenxy.headphones.protocol.sony.feature.equalizer

import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.EqualizerBandSpec
import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.headphones.core.feature.EqualizerCurveSpec
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

data class SonyV1EqualizerCapability(
    val bandCount: Int,
    val levelCount: Int,
    val presetIds: Set<String>,
)

/** Sound Connect 13.2.1 MDR table-set 1 PRESET_EQ capability and state codec. */
object SonyV1EqualizerFeature {
    private const val INQUIRED_TYPE = 0x01
    private const val UNDEFINED_LANGUAGE = 0x00
    private const val UNSPECIFIED_PRESET = 0xFF

    private val customizablePresetIds = setOf(
        SonyEqualizerFeature.MANUAL_ID,
        SonyEqualizerFeature.CUSTOM_1_ID,
        SonyEqualizerFeature.CUSTOM_2_ID,
    )

    fun queryCapability(): ByteArray = byteArrayOf(
        SonyCommand.EQEBB_GET_CAPABILITY.toByte(),
        INQUIRED_TYPE.toByte(),
        UNDEFINED_LANGUAGE.toByte(),
    )

    fun query(): ByteArray =
        byteArrayOf(SonyCommand.EQEBB_GET_PARAM.toByte(), INQUIRED_TYPE.toByte())

    fun set(
        preset: EqualizerPreset,
        capability: SonyV1EqualizerCapability,
    ): ByteArray? {
        if (preset.id !in capability.presetIds) return null
        val vendorPreset = SonyEqualizerFeature.toVendorPreset(preset) ?: return null
        return byteArrayOf(
            SonyCommand.EQEBB_SET_PARAM.toByte(),
            INQUIRED_TYPE.toByte(),
            vendorPreset.toByte(),
            0x00,
        )
    }

    /**
     * Sound Connect sends PRESET_EQ + UNSPECIFIED + the complete encoded band
     * array, which edits the currently active custom slot atomically.
     */
    fun setCurve(
        curve: EqualizerCurve,
        activePreset: EqualizerPreset,
        capability: SonyV1EqualizerCapability,
    ): ByteArray? {
        if (curve.slotId != activePreset.id) return null
        val spec = curveSpec(capability) ?: return null
        if (!spec.accepts(curve)) return null
        val neutral = neutralLevel(capability) ?: return null
        return byteArrayOf(
            SonyCommand.EQEBB_SET_PARAM.toByte(),
            INQUIRED_TYPE.toByte(),
            UNSPECIFIED_PRESET.toByte(),
            capability.bandCount.toByte(),
            *curve.gains.map { (it + neutral).toByte() }.toByteArray(),
        )
    }

    fun curveSpec(capability: SonyV1EqualizerCapability): EqualizerCurveSpec? {
        val neutral = neutralLevel(capability) ?: return null
        val writableSlots = capability.presetIds.intersect(customizablePresetIds)
        if (writableSlots.isEmpty()) return null
        return EqualizerCurveSpec(
            bands = List(capability.bandCount) { index ->
                EqualizerBandSpec(
                    id = "sony:eq:band:$index",
                    displayName = "Band ${index + 1}",
                    minGain = -neutral,
                    maxGain = neutral,
                )
            },
            writableSlotIds = writableSlots,
        )
    }

    fun toDomainCurve(
        state: SonyEqualizerState,
        capability: SonyV1EqualizerCapability,
    ): EqualizerCurve? {
        val neutral = neutralLevel(capability) ?: return null
        if (state.bandValues.size != capability.bandCount) return null
        return EqualizerCurve(
            slotId = state.preset.id,
            gains = state.bandValues.map { it - neutral },
        )
    }

    fun parseCapability(message: SonyMdrMessage): SonyV1EqualizerCapability? {
        if (
            message.table != SonyCommandTable.TABLE1 ||
            message.command != SonyCommand.EQEBB_RET_CAPABILITY
        ) return null
        val bytes = message.payload
        if (bytes.size < 5 || u8(bytes[1]) != INQUIRED_TYPE) return null
        val bandCount = u8(bytes[2])
        val levelCount = u8(bytes[3])
        val presetCount = u8(bytes[4])
        if (bandCount == 0 || levelCount == 0 || presetCount == 0) return null

        var offset = 5
        val presetIds = linkedSetOf<String>()
        repeat(presetCount) {
            if (offset + 2 > bytes.size) return null
            val preset = SonyEqualizerFeature.toDomainPreset(u8(bytes[offset])) ?: return null
            val labelLength = u8(bytes[offset + 1])
            offset += 2
            if (labelLength > 0x80 || offset + labelLength > bytes.size) return null
            offset += labelLength
            if (!presetIds.add(preset.id)) return null
        }
        if (offset != bytes.size) return null
        return SonyV1EqualizerCapability(bandCount, levelCount, presetIds)
    }

    fun parse(
        message: SonyMdrMessage,
        capability: SonyV1EqualizerCapability,
        fallbackPreset: EqualizerPreset? = null,
    ): SonyEqualizerState? {
        if (
            message.table != SonyCommandTable.TABLE1 ||
            message.command !in setOf(SonyCommand.EQEBB_RET_PARAM, SonyCommand.EQEBB_NTFY_PARAM)
        ) return null
        val bytes = message.payload
        if (
            bytes.size != 4 + capability.bandCount ||
            u8(bytes[1]) != INQUIRED_TYPE ||
            u8(bytes[3]) != capability.bandCount
        ) return null
        val presetValue = u8(bytes[2])
        val preset = if (presetValue == UNSPECIFIED_PRESET) {
            fallbackPreset
        } else {
            SonyEqualizerFeature.toDomainPreset(presetValue)
        } ?: return null
        if (preset.id !in capability.presetIds) return null
        val bands = bytes.copyOfRange(4, bytes.size).map(::u8)
        if (bands.any { it !in 0 until capability.levelCount }) return null
        return SonyEqualizerState(preset, bands)
    }

    fun capabilityMatches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.let(::u8) == INQUIRED_TYPE

    fun matches(message: SonyMdrMessage): Boolean = capabilityMatches(message)

    private fun neutralLevel(capability: SonyV1EqualizerCapability): Int? =
        capability.levelCount.takeIf { it > 0 && it % 2 == 1 }?.let { (it - 1) / 2 }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}
