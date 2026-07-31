package moe.chenxy.headphones.protocol.sony.feature.equalizer

import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

data class SonyEqualizerState(
    val preset: EqualizerPreset,
    val bandValues: List<Int>,
)

/**
 * LinkBuds S EQEBB preset control.
 *
 * Only the twelve presets observed in a complete Sony Sound Connect 13.2.0
 * carousel capture are writable. The six encoded band values are retained for
 * strict layout validation, but this slice never exposes custom-band writes.
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

    private const val INQUIRED_TYPE = 0x00
    private const val BAND_COUNT = 0x06
    private const val WRITE_TRAILER = 0x00
    private const val ENCODED_BAND_MIN = 0x00
    private const val ENCODED_BAND_MAX = 0x14

    fun query(): ByteArray =
        byteArrayOf(SonyCommand.EQEBB_GET_PARAM.toByte(), INQUIRED_TYPE.toByte())

    fun set(preset: EqualizerPreset): ByteArray? {
        val vendorPreset = toVendorPreset(preset) ?: return null
        return byteArrayOf(
            SonyCommand.EQEBB_SET_PARAM.toByte(),
            INQUIRED_TYPE.toByte(),
            vendorPreset.toByte(),
            WRITE_TRAILER.toByte(),
        )
    }

    fun parse(message: SonyMdrMessage): SonyEqualizerState? {
        if (
            message.table != SonyCommandTable.TABLE1 ||
            message.command !in setOf(
                SonyCommand.EQEBB_RET_PARAM,
                SonyCommand.EQEBB_NTFY_PARAM,
            )
        ) return null
        val bytes = message.payload
        if (
            bytes.size != 10 ||
            (bytes[1].toInt() and 0xFF) != INQUIRED_TYPE ||
            (bytes[3].toInt() and 0xFF) != BAND_COUNT
        ) return null
        val preset = toDomainPreset(bytes[2].toInt() and 0xFF) ?: return null
        val bands = bytes.copyOfRange(4, 10).map { it.toInt() and 0xFF }
        if (bands.any { it !in ENCODED_BAND_MIN..ENCODED_BAND_MAX }) return null
        return SonyEqualizerState(preset, bands)
    }

    fun matches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.toInt()?.and(0xFF) == INQUIRED_TYPE

    private fun toVendorPreset(preset: EqualizerPreset): Int? = vendorPresetById[preset.id]

    private fun toDomainPreset(value: Int): EqualizerPreset? =
        presetIdByVendor[value]?.let(::EqualizerPreset)
}
