package org.hyperpods.connect.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the behavior of features exposed by the current UI/controller.
 *
 * These are current-implementation regression vectors. They do not raise the
 * evidence level of any model or firmware to device-capture verified.
 */
class CurrentOppoBehaviorRegressionTest {
    private val hex = OppoTestFixtures::hex

    @Test
    fun `all current ANC builders retain their wire values`() {
        val expected = listOf(
            "AA 0A 00 00 04 04 F0 03 00 01 01 01" to Enums.ANC_OFF,
            "AA 0A 00 00 04 04 F0 03 00 01 01 02" to Enums.ANC_NOISE_CANCEL,
            "AA 0A 00 00 04 04 F0 03 00 01 01 80" to Enums.ANC_NOISE_CANCEL_SMART,
            "AA 0A 00 00 04 04 F0 03 00 01 01 40" to Enums.ANC_NOISE_CANCEL_LIGHT,
            "AA 0A 00 00 04 04 F0 03 00 01 01 20" to Enums.ANC_NOISE_CANCEL_MEDIUM,
            "AA 0A 00 00 04 04 F0 03 00 01 01 10" to Enums.ANC_NOISE_CANCEL_DEEP,
            "AA 0A 00 00 04 04 F0 03 00 01 01 04" to Enums.ANC_TRANSPARENCY,
            "AA 0B 00 00 04 04 F0 04 00 01 01 00 08" to Enums.ANC_ADAPTIVE,
        )

        expected.forEach { (wire, packet) -> assertArrayEquals(hex(wire), packet) }
    }

    @Test
    fun `game EQ spatial and switch builders retain current packets`() {
        assertArrayEquals(hex("AA 09 00 00 03 04 F0 02 00 28 01"), Enums.GAME_MODE_ON)
        assertArrayEquals(hex("AA 09 00 00 03 04 F0 02 00 28 00"), Enums.GAME_MODE_OFF)
        assertArrayEquals(hex("AA 09 00 00 03 04 F0 02 00 06 01"), Enums.GAME_LOW_LATENCY_ON)
        assertArrayEquals(hex("AA 09 00 00 03 04 F0 02 00 06 00"), Enums.GAME_LOW_LATENCY_OFF)
        assertArrayEquals(
            hex("AA 0B 00 00 04 04 57 04 00 01 01 00 02"),
            Enums.TRANSPARENCY_VOCAL_ENHANCEMENT_ON,
        )
        assertArrayEquals(
            hex("AA 0B 00 00 04 04 57 04 00 01 01 00 01"),
            Enums.TRANSPARENCY_VOCAL_ENHANCEMENT_OFF,
        )
        assertArrayEquals(hex("AA 08 00 00 22 04 F0 01 00 02"), Enums.spatialAudioPacket(2))
        assertArrayEquals(hex("AA 08 00 00 06 04 F0 01 00 07"), Enums.eqPresetPacket(EqPreset.DYNAUDIO))
        assertArrayEquals(hex("AA 09 00 00 03 04 F0 02 00 1B 01"), Enums.spatialSoundSwitchPacket(true))
        assertArrayEquals(hex("AA 09 00 00 03 04 F0 02 00 11 00"), Enums.dualDeviceConnectionPacket(false))
        assertArrayEquals(
            hex("AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28"),
            Enums.QUERY_STATUS,
        )

        val compatibleOn = Enums.gameModePackets(true, GameModeImplementation.COMPATIBLE)
        assertArrayEquals(Enums.GAME_MODE_ON, compatibleOn[0])
        assertArrayEquals(Enums.GAME_LOW_LATENCY_ON, compatibleOn[1])
        val compatibleOff = Enums.gameModePackets(false, GameModeImplementation.COMPATIBLE)
        assertArrayEquals(Enums.GAME_LOW_LATENCY_OFF, compatibleOff[0])
        assertArrayEquals(Enums.GAME_MODE_OFF, compatibleOff[1])
    }

    @Test
    fun `battery and wear active reports preserve component semantics`() {
        val battery = BatteryParser.parseActiveReport(
            hex("AA 0F 00 00 04 02 31 08 00 01 03 01 D8 02 57 03 E4"),
        )!!
        assertEquals(88, battery.left?.level)
        assertTrue(battery.left?.isCharging == true)
        assertEquals(87, battery.right?.level)
        assertFalse(battery.right?.isCharging == true)
        assertEquals(100, battery.case?.level)

        val wear = WearStatusParser.parse(
            hex("AA 0F 00 00 04 02 32 08 00 02 03 01 07 02 05 03 04"),
        )!!
        assertEquals(WearState.WEARING, wear.left)
        assertEquals(WearState.REMOVED, wear.right)
        assertEquals(WearState.IN_CASE, wear.case)
    }

    @Test
    fun `ANC parser retains standard compatible and extended mappings`() {
        assertEquals(
            NoiseControlMode.OFF,
            AncModeParser.parse(hex("AA 0B 00 00 0C 81 40 04 00 00 01 01 08")),
        )
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            AncModeParser.parse(
                hex("AA 0B 00 00 0C 81 41 04 00 00 01 01 01"),
                AncImplementation.COMPATIBLE,
            ),
        )
        assertEquals(
            NoiseControlMode.ADAPTIVE,
            AncModeParser.parse(hex("AA 0C 00 00 0C 81 42 05 00 00 01 01 00 08")),
        )
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION_DEEP,
            AncModeParser.parse(hex("AA 0B 00 00 0C 81 43 04 00 00 01 01 10")),
        )
    }

    @Test
    fun `current feature response parsers preserve exposed UI state`() {
        assertTrue(
            TransparencyVocalEnhancementParser.parse(
                hex("AA 0B 00 00 04 04 57 04 00 01 01 00 02"),
            ) == true,
        )

        val game = GameModeParser.parseStatus(
            hex("AA 0F 00 00 0D 81 33 08 00 00 03 28 01 06 01 11 00"),
        )!!
        assertTrue(game.mainEnabled == true)
        assertTrue(game.lowLatencyEnabled == true)
        assertFalse(game.dualDeviceConnectionEnabled == true)

        val dual = SwitchFeatureSetParser.parse(
            hex("AA 0A 00 00 03 84 34 03 00 00 11 01"),
        )!!
        assertEquals(0, dual.status)
        assertEquals(GameModeFeature.DUAL_DEVICE_CONNECTION, dual.featureId)
        assertEquals(1, dual.value)

        assertEquals(
            SpatialAudioMode.HEAD_TRACKING,
            SpatialAudioParser.parseModeNotify(hex("AA 08 00 00 10 05 35 01 00 02")),
        )
        assertEquals(
            0,
            SpatialAudioParser.parseSetResponseStatus(hex("AA 08 00 00 22 84 36 01 00 00")),
        )
        assertTrue(
            SpatialAudioParser.parseSpatialSoundSwitchSetResponse(
                hex("AA 09 00 00 03 84 37 02 00 1B 01"),
            ) == true,
        )
        assertEquals(
            EqPreset.DYNAUDIO,
            EqPresetParser.parse(hex("AA 08 00 00 04 05 38 01 00 07")),
        )
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
            SmartAncLevelParser.parse(hex("AA 0B 00 00 04 02 39 04 00 03 04 01 20")),
        )
    }

    @Test
    fun `feature parsers reject unrelated or incomplete packets`() {
        assertNull(BatteryParser.parseActiveReport(hex("AA 08 00 00 04 02 31 01 00 02")))
        assertNull(WearStatusParser.parse(hex("AA 09 00 00 04 02 32 02 00 02 01")))
        assertNull(SpatialAudioParser.parseModeNotify(hex("AA 08 00 00 10 05 35 01 00 03")))
        assertNull(EqPresetParser.parse(hex("AA 08 00 00 04 05 38 02 00 07")))
    }
}
