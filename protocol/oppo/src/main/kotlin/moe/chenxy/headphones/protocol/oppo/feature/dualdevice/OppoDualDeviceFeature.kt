package moe.chenxy.headphones.protocol.oppo.feature.dualdevice

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatchStatusParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoSwitchSetParser
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec

object OppoDualDeviceFeature {
    fun set(enabled: Boolean): ByteArray = OppoMessageCodec.encode(
        OppoCommand.SET_SWITCH_FEATURE,
        payload = OppoSwitchSetParser.setPayload(OppoFeature.DUAL_DEVICE, enabled),
    )

    fun parse(message: OppoMessage): DeviceReport.DualDeviceConnection? =
        OppoBatchStatusParser.parse(message)
            ?.get(OppoFeature.DUAL_DEVICE)
            ?.let { DeviceReport.DualDeviceConnection(it == 0x01) }
}
