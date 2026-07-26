package moe.chenxy.headphones.protocol.oppo.feature.noisecontrol

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncEncoding
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncMode
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncParser
import moe.chenxy.headphones.protocol.oppo.mapping.OppoDomainMapper
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec

object OppoNoiseControlFeature {
    private const val TRANSPARENCY_SEQUENCE = 0x57

    fun query(): ByteArray = OppoMessageCodec.encode(
        OppoCommand.QUERY_ANC,
        payload = OppoAncParser.CURRENT_MODE_SELECTOR,
    )

    fun set(mode: NoiseControlMode, encoding: OppoAncEncoding): ByteArray? {
        val vendor = OppoDomainMapper.toVendorAnc(mode) ?: return null
        val payload = when (vendor) {
            OppoAncMode.OFF -> byteArrayOf(0x01, 0x01, offValue(encoding))
            OppoAncMode.NOISE_CANCELLATION ->
                byteArrayOf(0x01, 0x01, noiseCancellationValue(encoding))
            OppoAncMode.NOISE_CANCELLATION_SMART -> byteArrayOf(0x01, 0x01, 0x80.toByte())
            OppoAncMode.NOISE_CANCELLATION_LIGHT -> byteArrayOf(0x01, 0x01, 0x40)
            OppoAncMode.NOISE_CANCELLATION_MEDIUM -> byteArrayOf(0x01, 0x01, 0x20)
            OppoAncMode.NOISE_CANCELLATION_DEEP -> byteArrayOf(0x01, 0x01, 0x10)
            OppoAncMode.TRANSPARENCY -> byteArrayOf(0x01, 0x01, 0x04)
            OppoAncMode.ADAPTIVE -> byteArrayOf(0x01, 0x01, 0x00, 0x08)
        }
        return OppoMessageCodec.encode(OppoCommand.SET_ANC, sequence = 0, payload = payload)
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean): ByteArray =
        OppoMessageCodec.encode(
            OppoCommand.SET_ANC,
            sequence = TRANSPARENCY_SEQUENCE,
            payload = byteArrayOf(
                0x01,
                0x01,
                0x00,
                (if (enabled) 0x02 else 0x01).toByte(),
            ),
        )

    fun parseNoiseControl(
        message: OppoMessage,
        encoding: OppoAncEncoding,
    ): DeviceReport.NoiseControl? =
        OppoAncParser.parse(message, encoding)
            ?.let(OppoDomainMapper::toNoiseControl)
            ?.let(DeviceReport::NoiseControl)

    fun parseTransparencyVocalEnhancement(
        message: OppoMessage,
    ): DeviceReport.TransparencyVocalEnhancement? {
        if (!message.isComplete) return null
        if (
            message.command != OppoCommand.SET_ANC &&
            message.command != OppoCommand.responseOf(OppoCommand.SET_ANC) &&
            message.command != OppoCommand.NOTIFICATION_EVENT
        ) {
            return null
        }
        val payload = message.payload
        for (index in 0 until payload.size - 3) {
            if (
                payload[index] == 0x01.toByte() &&
                payload[index + 1] == 0x01.toByte() &&
                payload[index + 2] == 0x00.toByte()
            ) {
                return when (payload[index + 3].toInt() and 0xFF) {
                    0x01 -> DeviceReport.TransparencyVocalEnhancement(false)
                    0x02 -> DeviceReport.TransparencyVocalEnhancement(true)
                    else -> null
                }
            }
        }
        return null
    }

    private fun offValue(encoding: OppoAncEncoding): Byte =
        (if (encoding == OppoAncEncoding.COMPATIBLE) 0x02 else 0x01).toByte()

    private fun noiseCancellationValue(encoding: OppoAncEncoding): Byte =
        (if (encoding == OppoAncEncoding.COMPATIBLE) 0x01 else 0x02).toByte()
}
