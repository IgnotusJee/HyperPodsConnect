package moe.chenxy.headphones.protocol.sony.feature.equalizer

import moe.chenxy.headphones.core.feature.EqualizerBandKind
import moe.chenxy.headphones.core.feature.EqualizerBandSpec
import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.headphones.core.feature.EqualizerCurveSpec
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

data class SonyEqualizerState(
    val preset: EqualizerPreset,
    val bandValues: List<Int>,
)

data class SonyV2EqualizerCapability(
    val bandCount: Int,
    val levelCount: Int,
    val presetIds: Set<String>,
    val valueLabels: Map<String, String>,
)

/**
 * Sony MDR table-set 2 PRESET_EQ control.
 *
 * Only the twelve presets observed in a complete Sony Sound Connect 13.2.0
 * LinkBuds S carousel capture are writable. Sound Connect 13.2.1's table-set 2
 * controller also proves that customizable devices write the active Manual /
 * Custom slot id followed by the complete encoded band array.
 */
object SonyEqualizerFeature {
    const val OFF_ID = "sony:eq:00"
    const val BRIGHT_ID = "sony:eq:10"
    const val EXCITED_ID = "sony:eq:11"
    const val MELLOW_ID = "sony:eq:12"
    const val RELAXED_ID = "sony:eq:13"
    const val VOCAL_ID = "sony:eq:14"
    const val TREBLE_BOOST_ID = "sony:eq:15"
    const val BASS_BOOST_ID = "sony:eq:16"
    const val SPEECH_ID = "sony:eq:17"
    const val MANUAL_ID = "sony:eq:a0"
    const val CUSTOM_1_ID = "sony:eq:a1"
    const val CUSTOM_2_ID = "sony:eq:a2"
    const val CUSTOM_3_ID = "sony:eq:a3"
    const val CUSTOM_4_ID = "sony:eq:a4"
    const val CUSTOM_5_ID = "sony:eq:a5"

    private data class PresetDefinition(
        val id: String,
        val vendorValue: Int,
        val label: String,
    )

    private val presets = listOf(
        PresetDefinition(OFF_ID, 0x00, "Off"),
        PresetDefinition(BRIGHT_ID, 0x10, "Bright"),
        PresetDefinition(EXCITED_ID, 0x11, "Excited"),
        PresetDefinition(MELLOW_ID, 0x12, "Mellow"),
        PresetDefinition(RELAXED_ID, 0x13, "Relaxed"),
        PresetDefinition(VOCAL_ID, 0x14, "Vocal"),
        PresetDefinition(TREBLE_BOOST_ID, 0x15, "Treble Boost"),
        PresetDefinition(BASS_BOOST_ID, 0x16, "Bass Boost"),
        PresetDefinition(SPEECH_ID, 0x17, "Speech"),
        PresetDefinition(MANUAL_ID, 0xA0, "Manual"),
        PresetDefinition(CUSTOM_1_ID, 0xA1, "Custom 1"),
        PresetDefinition(CUSTOM_2_ID, 0xA2, "Custom 2"),
    )

    val allowedPresetIds: Set<String> = presets.mapTo(linkedSetOf()) { it.id }
    val valueLabels: Map<String, String> = presets.associateTo(linkedMapOf()) {
        it.id to it.label
    }

    private val vendorPresetById = presets.associate { it.id to it.vendorValue }
    private val presetIdByVendor = presets.associate { it.vendorValue to it.id }
    private val customizablePresetIds = setOf(
        MANUAL_ID,
        CUSTOM_1_ID,
        CUSTOM_2_ID,
        CUSTOM_3_ID,
        CUSTOM_4_ID,
        CUSTOM_5_ID,
    )

    private val soundConnectSixBandLayout = listOf(
        SonyBandMetadata("CLEAR BASS", kind = EqualizerBandKind.CLEAR_BASS),
        SonyBandMetadata("400", centerFrequencyHz = 400),
        SonyBandMetadata("1k", centerFrequencyHz = 1_000),
        SonyBandMetadata("2.5k", centerFrequencyHz = 2_500),
        SonyBandMetadata("6.3k", centerFrequencyHz = 6_300),
        SonyBandMetadata("16k", centerFrequencyHz = 16_000),
    )

    private const val INQUIRED_TYPE = 0x00
    private const val WRITE_TRAILER = 0x00

    fun queryCapability(): ByteArray = byteArrayOf(
        SonyCommand.EQEBB_GET_CAPABILITY.toByte(),
        INQUIRED_TYPE.toByte(),
    )

    fun query(): ByteArray =
        byteArrayOf(SonyCommand.EQEBB_GET_PARAM.toByte(), INQUIRED_TYPE.toByte())

    fun set(
        preset: EqualizerPreset,
        capability: SonyV2EqualizerCapability,
    ): ByteArray? {
        if (preset.id !in capability.presetIds) return null
        val vendorPreset = toVendorPreset(preset) ?: return null
        return byteArrayOf(
            SonyCommand.EQEBB_SET_PARAM.toByte(),
            INQUIRED_TYPE.toByte(),
            vendorPreset.toByte(),
            WRITE_TRAILER.toByte(),
        )
    }

    /**
     * Table-set 2 sends the real active custom preset id (not v1's 0xFF)
     * followed by all six encoded values.
     */
    fun setCurve(
        curve: EqualizerCurve,
        activePreset: EqualizerPreset,
        capability: SonyV2EqualizerCapability,
    ): ByteArray? {
        if (curve.slotId != activePreset.id || activePreset.id !in customizablePresetIds) {
            return null
        }
        val spec = curveSpec(capability) ?: return null
        if (!spec.accepts(curve)) return null
        val neutral = neutralLevel(capability) ?: return null
        val vendorPreset = toVendorPreset(activePreset) ?: return null
        return byteArrayOf(
            SonyCommand.EQEBB_SET_PARAM.toByte(),
            INQUIRED_TYPE.toByte(),
            vendorPreset.toByte(),
            capability.bandCount.toByte(),
            *curve.gains.map { (it + neutral).toByte() }.toByteArray(),
        )
    }

    fun curveSpec(capability: SonyV2EqualizerCapability): EqualizerCurveSpec? {
        val neutral = neutralLevel(capability) ?: return null
        val writableSlots = capability.presetIds.intersect(customizablePresetIds)
        if (writableSlots.isEmpty()) return null
        return EqualizerCurveSpec(
            bands = List(capability.bandCount) { index ->
                val metadata = soundConnectSixBandLayout
                    .takeIf { capability.bandCount == it.size }
                    ?.get(index)
                EqualizerBandSpec(
                    id = "sony:eq:band:$index",
                    displayName = metadata?.displayName ?: "Band ${index + 1}",
                    minGain = -neutral,
                    maxGain = neutral,
                    centerFrequencyHz = metadata?.centerFrequencyHz,
                    kind = metadata?.kind ?: EqualizerBandKind.STANDARD,
                )
            },
            writableSlotIds = writableSlots,
        )
    }

    fun toDomainCurve(
        state: SonyEqualizerState,
        capability: SonyV2EqualizerCapability,
    ): EqualizerCurve? {
        val neutral = neutralLevel(capability) ?: return null
        if (state.bandValues.size != capability.bandCount) return null
        return EqualizerCurve(
            slotId = state.preset.id,
            gains = state.bandValues.map { it - neutral },
        )
    }

    fun parseCapability(message: SonyMdrMessage): SonyV2EqualizerCapability? {
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
        val labels = linkedMapOf<String, String>()
        repeat(presetCount) {
            if (offset + 2 > bytes.size) return null
            val preset = toDomainPreset(u8(bytes[offset]))
            val labelLength = u8(bytes[offset + 1])
            offset += 2
            if (labelLength > 0x80 || offset + labelLength > bytes.size) return null
            val label = bytes.copyOfRange(offset, offset + labelLength)
                .toString(Charsets.UTF_8)
                .takeIf(String::isNotBlank)
                ?: valueLabels[preset.id]
                ?: preset.id
            offset += labelLength
            if (!presetIds.add(preset.id)) return null
            labels[preset.id] = label
        }
        if (offset != bytes.size) return null
        return SonyV2EqualizerCapability(bandCount, levelCount, presetIds, labels)
    }

    fun parse(
        message: SonyMdrMessage,
        capability: SonyV2EqualizerCapability,
    ): SonyEqualizerState? {
        if (
            message.table != SonyCommandTable.TABLE1 ||
            message.command !in setOf(
                SonyCommand.EQEBB_RET_PARAM,
                SonyCommand.EQEBB_NTFY_PARAM,
            )
        ) return null
        val bytes = message.payload
        if (
            bytes.size != 4 + capability.bandCount ||
            (bytes[1].toInt() and 0xFF) != INQUIRED_TYPE ||
            (bytes[3].toInt() and 0xFF) != capability.bandCount
        ) return null
        val preset = toDomainPreset(bytes[2].toInt() and 0xFF)
        if (preset.id !in capability.presetIds) return null
        val bands = bytes.copyOfRange(4, bytes.size).map { it.toInt() and 0xFF }
        if (bands.any { it !in 0 until capability.levelCount }) return null
        return SonyEqualizerState(preset, bands)
    }

    fun capabilityMatches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.toInt()?.and(0xFF) == INQUIRED_TYPE

    fun matches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.toInt()?.and(0xFF) == INQUIRED_TYPE

    internal fun toVendorPreset(preset: EqualizerPreset): Int? =
        vendorPresetById[preset.id] ?: preset.id
            .takeIf { it.startsWith(PRESET_ID_PREFIX) && it.length == PRESET_ID_PREFIX.length + 2 }
            ?.removePrefix(PRESET_ID_PREFIX)
            ?.toIntOrNull(16)

    internal fun toDomainPreset(value: Int): EqualizerPreset = EqualizerPreset(
        presetIdByVendor[value] ?: PRESET_ID_PREFIX + value.toString(16).padStart(2, '0'),
    )

    private fun neutralLevel(capability: SonyV2EqualizerCapability): Int? =
        capability.levelCount.takeIf { it > 0 && it % 2 == 1 }?.let { (it - 1) / 2 }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF

    private const val PRESET_ID_PREFIX = "sony:eq:"

    private data class SonyBandMetadata(
        val displayName: String,
        val centerFrequencyHz: Int? = null,
        val kind: EqualizerBandKind = EqualizerBandKind.STANDARD,
    )
}
