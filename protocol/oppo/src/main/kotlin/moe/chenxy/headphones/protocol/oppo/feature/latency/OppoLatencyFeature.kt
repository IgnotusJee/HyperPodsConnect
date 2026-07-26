package moe.chenxy.headphones.protocol.oppo.feature.latency

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoLowLatencyStrategy
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatchStatusParser
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec
import moe.chenxy.headphones.protocol.oppo.feature.OppoSwitchSetParser

object OppoLatencyFeature {
    fun set(enabled: Boolean, strategy: OppoLowLatencyStrategy): List<ByteArray> {
        fun packet(feature: Int, value: Boolean) = OppoMessageCodec.encode(
            OppoCommand.SET_SWITCH_FEATURE,
            payload = OppoSwitchSetParser.setPayload(feature, value),
        )
        return when (strategy) {
            OppoLowLatencyStrategy.MAIN_SWITCH ->
                listOf(packet(OppoFeature.GAME_MODE, enabled))
            OppoLowLatencyStrategy.MAIN_AND_LOW_LATENCY_SWITCH ->
                if (enabled) {
                    listOf(
                        packet(OppoFeature.GAME_MODE, true),
                        packet(OppoFeature.LOW_LATENCY, true),
                    )
                } else {
                    listOf(
                        packet(OppoFeature.LOW_LATENCY, false),
                        packet(OppoFeature.GAME_MODE, false),
                    )
                }
        }
    }

    fun parse(
        message: OppoMessage,
        strategy: OppoLowLatencyStrategy,
    ): DeviceReport.LowLatency? {
        val values = OppoBatchStatusParser.parse(message) ?: return null
        val value = when (strategy) {
            OppoLowLatencyStrategy.MAIN_SWITCH -> values[OppoFeature.GAME_MODE]
            OppoLowLatencyStrategy.MAIN_AND_LOW_LATENCY_SWITCH ->
                values[OppoFeature.LOW_LATENCY] ?: values[OppoFeature.GAME_MODE]
        } ?: return null
        return DeviceReport.LowLatency(value == 0x01)
    }
}
