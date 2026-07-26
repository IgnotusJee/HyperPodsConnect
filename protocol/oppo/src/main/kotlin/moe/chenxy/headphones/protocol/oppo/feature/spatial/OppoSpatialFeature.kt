package moe.chenxy.headphones.protocol.oppo.feature.spatial

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatchStatusParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoSwitchSetParser
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec

object OppoSpatialFeature {
    fun set(mode: SpatialAudioMode, useSwitch: Boolean): ByteArray =
        if (useSwitch) {
            OppoMessageCodec.encode(
                OppoCommand.SET_SWITCH_FEATURE,
                payload = OppoSwitchSetParser.setPayload(
                    OppoFeature.SPATIAL_SOUND_SWITCH,
                    mode != SpatialAudioMode.OFF,
                ),
            )
        } else {
            OppoMessageCodec.encode(
                OppoCommand.SET_SPATIAL_AUDIO,
                payload = byteArrayOf(mode.toVendorValue().toByte()),
            )
        }

    fun parse(message: OppoMessage): List<DeviceReport> {
        val reports = mutableListOf<DeviceReport>()
        if (
            message.isComplete &&
            message.command == OppoCommand.SPATIAL_AUDIO_NOTIFICATION &&
            message.payload.isNotEmpty()
        ) {
            fromVendorValue(message.payload[0].toInt() and 0xFF)
                ?.let { reports += DeviceReport.SpatialAudio(it) }
        }
        OppoBatchStatusParser.parse(message)?.get(OppoFeature.SPATIAL_SOUND_SWITCH)?.let { value ->
            val enabled = value == 0x01
            reports += DeviceReport.SpatialSoundSwitch(enabled)
            reports += DeviceReport.SpatialAudio(
                if (enabled) SpatialAudioMode.FIXED else SpatialAudioMode.OFF,
            )
        }
        return reports
    }

    private fun SpatialAudioMode.toVendorValue(): Int = when (this) {
        SpatialAudioMode.OFF -> 0
        SpatialAudioMode.FIXED -> 1
        SpatialAudioMode.HEAD_TRACKING -> 2
    }

    private fun fromVendorValue(value: Int): SpatialAudioMode? = when (value) {
        0 -> SpatialAudioMode.OFF
        1 -> SpatialAudioMode.FIXED
        2 -> SpatialAudioMode.HEAD_TRACKING
        else -> null
    }
}
