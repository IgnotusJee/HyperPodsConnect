package moe.chenxy.headphones.protocol.oppo.feature.equalizer

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.protocol.oppo.feature.OppoEqParser
import moe.chenxy.headphones.protocol.oppo.mapping.OppoDomainMapper
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec

object OppoEqualizerFeature {
    val allowedPresets: Set<Int> = setOf(0, 1, 2, 3, 7)

    fun query(): ByteArray = OppoMessageCodec.encode(OppoCommand.QUERY_EQ)

    fun set(preset: EqualizerPreset): ByteArray? =
        OppoDomainMapper.toVendorPreset(preset)
            ?.takeIf { it in allowedPresets }
            ?.let { OppoMessageCodec.encode(OppoCommand.SET_EQ, payload = byteArrayOf(it.toByte())) }

    fun parse(message: OppoMessage): DeviceReport.Equalizer? =
        OppoEqParser.parse(message, allowedPresets)
            ?.let(OppoDomainMapper::toEqualizerPreset)
            ?.let(DeviceReport::Equalizer)
}
