package moe.chenxy.headphones.protocol.sony

import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.protocol.sony.feature.SonyHandshake
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolGeneration
import moe.chenxy.headphones.protocol.sony.feature.battery.SonyBatteryFeature
import moe.chenxy.headphones.protocol.sony.message.SonyDataType
import moe.chenxy.headphones.protocol.sony.message.SonyDeviceInfoType
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage
import moe.chenxy.headphones.protocol.sony.message.SonyMdrRouter
import moe.chenxy.headphones.protocol.sony.message.SonyRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyParsersTest {
    @Test
    fun `parses v2 protocol tables and device strings strictly`() {
        val protocol = SonyHandshake.parseProtocolInfo(
            message(byteArrayOf(0x01, 0, 0, 0, 0, 2, 1, 1)),
        )
        val modelBytes = "LinkBuds S".toByteArray()
        val model = SonyHandshake.parseDeviceInfo(
            message(byteArrayOf(0x05, 0x01, modelBytes.size.toByte()) + modelBytes),
            SonyDeviceInfoType.MODEL_NAME,
        )

        assertEquals(SonyProtocolGeneration.V2, protocol?.generation)
        assertEquals(2L, protocol?.version)
        assertTrue(protocol!!.table1Enabled)
        assertTrue(protocol.table2Enabled)
        assertEquals("LinkBuds S", model)
    }

    @Test
    fun `rejects malformed support count and device length`() {
        assertNull(
            SonyHandshake.parseSupportFunction(
                message(byteArrayOf(0x07, 0, 3, 0, 1, 2)),
            ),
        )
        assertNull(
            SonyHandshake.parseDeviceInfo(
                message(byteArrayOf(0x05, 0x01, 3, 'W'.code.toByte())),
                SonyDeviceInfoType.MODEL_NAME,
            ),
        )
    }

    @Test
    fun `v1 support length is bytes and yields eleven two-byte functions`() {
        val support = SonyHandshake.parseSupportFunction(
            message(
                byteArrayOf(
                    0x07, 0x00, 0x16,
                    0x71, 0x62, 0xF5.toByte(), 0x81.toByte(),
                    0x51, 0xA1.toByte(), 0xE1.toByte(), 0xE2.toByte(),
                    0xD2.toByte(), 0xF6.toByte(), 0xD1.toByte(), 0xF4.toByte(),
                    0xF3.toByte(), 0x39, 0x12, 0x13, 0x11, 0x30,
                    0xC1.toByte(), 0x14, 0x22, 0x21,
                ),
            ),
        )

        assertEquals(11, support?.functions?.size)
        assertEquals(0x7162, support?.functions?.first())
        assertEquals(0x2221, support?.functions?.last())
    }

    @Test
    fun `maps left right and cradle battery without treating charged as charging`() {
        val pair = SonyBatteryFeature.parse(
            SonyProtocolGeneration.V2,
            message(byteArrayOf(0x23, 1, 81, 1, 75, 3)),
        )!!
        val cradle = SonyBatteryFeature.parse(
            SonyProtocolGeneration.V2,
            message(byteArrayOf(0x25, 2, 66, 0)),
        )!!

        assertEquals(81, pair.values[BatteryComponent.LEFT]?.level)
        assertTrue(pair.values[BatteryComponent.LEFT]?.charging == true)
        assertFalse(pair.values[BatteryComponent.RIGHT]?.charging == true)
        assertEquals(66, cradle.values[BatteryComponent.CASE]?.level)
    }

    @Test
    fun `router distinguishes command tables and preserves unknown payload`() {
        val table2 = SonyMdrMessage(
            SonyDataType.DATA_MDR_NO2,
            1,
            0x7F,
            byteArrayOf(0x7F, 0x3C),
        )
        val routed = SonyMdrRouter.route(table2)

        assertTrue(routed is SonyRoute.Unknown)
        assertEquals(table2, routed.message)
        assertEquals(0x3C, routed.message.payload[1].toInt() and 0xFF)
    }

    private fun message(payload: ByteArray) = SonyMdrMessage(
        SonyDataType.DATA_MDR,
        sequence = 0,
        command = payload.first().toInt() and 0xFF,
        payload = payload,
    )
}
