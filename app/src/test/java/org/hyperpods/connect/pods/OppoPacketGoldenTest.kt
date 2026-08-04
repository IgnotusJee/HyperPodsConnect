package org.hyperpods.connect.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static protocol vectors derived from the official-app implementation.
 * These are not labelled as device-capture fixtures.
 */
class OppoPacketGoldenTest {
    private val hex = OppoTestFixtures::hex

    @Test
    fun `short command builders match official OPOv1 layout`() {
        assertArrayEquals(
            hex("AA 07 00 00 06 01 F0 00 00"),
            OppoPackets.buildPacket(Cmd.QUERY_BATTERY),
        )
        assertArrayEquals(
            hex("AA 09 00 00 0C 01 F0 02 00 01 01"),
            OppoPackets.buildPacket(Cmd.QUERY_ANC_MODE, payload = hex("01 01")),
        )
        assertArrayEquals(
            hex("AA 07 00 00 0F 01 F0 00 00"),
            OppoPackets.buildPacket(Cmd.QUERY_EQ_PRESET),
        )
        assertArrayEquals(
            hex("AA 0A 00 00 05 02 F0 03 00 02 01 03"),
            Enums.registerMultiNotification(hex("01 03")),
        )
    }

    @Test
    fun `builder uses official seven bit varint for long frames`() {
        val packet = OppoPackets.buildPacket(0x1234, seq = 0x56, payload = ByteArray(130) { it.toByte() })

        // Encoded length = 7 + 130 = 137 = 0x89 0x01 as a 7-bit varint.
        assertArrayEquals(hex("AA 89 01 00 00 34 12 56 82 00"), packet.copyOfRange(0, 10))
        assertEquals(140, packet.size)
    }

    @Test
    fun `source derived battery response parses charging bit and components`() {
        val packet = OppoTestFixtures.officialSource("battery-response.hex")

        val result = BatteryParser.parse(packet)!!

        assertEquals(88, result.left?.level)
        assertTrue(result.left?.isCharging == true)
        assertEquals(87, result.right?.level)
        assertFalse(result.right?.isCharging == true)
        assertEquals(100, result.case?.level)
        assertTrue(result.case?.isCharging == true)
    }

    @Test
    fun `notification handshake response preserves advertised ids`() {
        val response = OppoTestFixtures.officialSource("notification-support-response.hex")

        assertArrayEquals(hex("01 03 F1"), NotificationSupportParser.parse(response)!!)
    }

    @Test
    fun `ANC and EQ parsers reject truncated payload and accept complete source vectors`() {
        val anc = OppoTestFixtures.officialSource("anc-response.hex")
        val eq = OppoTestFixtures.officialSource("eq-response.hex")

        assertEquals(NoiseControlMode.NOISE_CANCELLATION, AncModeParser.parse(anc))
        assertEquals(EqPreset.DYNAUDIO, EqPresetParser.parse(eq))
        assertNull(AncModeParser.parse(anc.copyOf(anc.size - 1)))
        assertNull(EqPresetParser.parse(eq.copyOf(eq.size - 1)))
    }

    @Test
    fun `failed and incomplete handshake or EQ responses are rejected`() {
        assertNull(NotificationSupportParser.parse(hex("AA 0A 00 00 00 82 20 03 00 01 01 03")))
        assertNull(NotificationSupportParser.parse(hex("AA 0A 00 00 00 82 20 03 00 00 02 03")))
        assertNull(EqPresetParser.parse(hex("AA 09 00 00 0F 81 21 02 00 01 07")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder rejects payloads that do not fit the inner unsigned short length`() {
        OppoPackets.buildPacket(0x1234, payload = ByteArray(0x10000))
    }
}
