package moe.chenxy.headphones.protocol.oppo.feature.battery

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatteryParser
import moe.chenxy.headphones.protocol.oppo.mapping.OppoDomainMapper
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec

object OppoBatteryFeature {
    fun query(): ByteArray = OppoMessageCodec.encode(OppoCommand.QUERY_BATTERY)

    fun parse(message: OppoMessage): DeviceReport.Batteries? =
        OppoBatteryParser.parse(message)?.let(OppoDomainMapper::toBatteryReport)
}
