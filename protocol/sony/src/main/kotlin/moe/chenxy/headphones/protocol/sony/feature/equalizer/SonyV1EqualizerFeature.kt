package moe.chenxy.headphones.protocol.sony.feature.equalizer

import moe.chenxy.headphones.core.feature.EqualizerPreset
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
        val preset = SonyEqualizerFeature.toDomainPreset(u8(bytes[2])) ?: return null
        if (preset.id !in capability.presetIds) return null
        val bands = bytes.copyOfRange(4, bytes.size).map(::u8)
        if (bands.any { it !in 0 until capability.levelCount }) return null
        return SonyEqualizerState(preset, bands)
    }

    fun capabilityMatches(message: SonyMdrMessage): Boolean =
        message.payload.getOrNull(1)?.let(::u8) == INQUIRED_TYPE

    fun matches(message: SonyMdrMessage): Boolean = capabilityMatches(message)

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}
