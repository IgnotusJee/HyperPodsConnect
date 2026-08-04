package org.hyperpods.connect.integration

import org.hyperpods.connect.ipc.BatteryPayload
import org.hyperpods.connect.ui.state.HeadphoneUiState
import org.hyperpods.connect.ui.state.UiConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialHeadsetIslandProjectionTest {
    @Test
    fun `TWS payload preserves batteries and Xiaomi wear constants`() {
        val both = readyState(
            batteries = mapOf(
                "LEFT" to BatteryPayload("LEFT", 88, false),
                "RIGHT" to BatteryPayload("RIGHT", 76, false),
            ),
            wearing = mapOf("LEFT" to "WEARING", "RIGHT" to "WEARING"),
        ).toOfficialHeadsetIslandPayload()

        assertEquals(88, both?.leftBattery)
        assertEquals(76, both?.rightBattery)
        assertEquals(OfficialHeadsetIslandPayload.WEAR_BOTH, both?.wearState)

        val left = readyState(wearing = mapOf("LEFT" to "WEARING", "RIGHT" to "REMOVED"))
        val right = readyState(wearing = mapOf("LEFT" to "REMOVED", "RIGHT" to "WEARING"))
        val none = readyState(wearing = mapOf("LEFT" to "REMOVED", "RIGHT" to "IN_CASE"))
        assertEquals(OfficialHeadsetIslandPayload.WEAR_LEFT, left.toOfficialHeadsetIslandPayload()?.wearState)
        assertEquals(OfficialHeadsetIslandPayload.WEAR_RIGHT, right.toOfficialHeadsetIslandPayload()?.wearState)
        assertEquals(OfficialHeadsetIslandPayload.WEAR_NONE, none.toOfficialHeadsetIslandPayload()?.wearState)
    }

    @Test
    fun `single battery device uses one official slot without fake right battery`() {
        val payload = readyState(
            batteries = mapOf("SINGLE" to BatteryPayload("SINGLE", 103, false)),
            wearing = mapOf("SINGLE" to "WEARING"),
        ).toOfficialHeadsetIslandPayload()

        assertEquals(100, payload?.leftBattery)
        assertEquals(OfficialHeadsetIslandPayload.BATTERY_UNAVAILABLE, payload?.rightBattery)
        assertEquals(OfficialHeadsetIslandPayload.WEAR_LEFT, payload?.wearState)
    }

    @Test
    fun `missing wear evidence explicitly reports unsupported`() {
        assertEquals(
            OfficialHeadsetIslandPayload.WEAR_NOT_SUPPORTED,
            readyState().toOfficialHeadsetIslandPayload()?.wearState,
        )
    }

    @Test
    fun `projection requires an exactly identified Ready device with battery evidence`() {
        assertNull(readyState().copy(deviceId = null).toOfficialHeadsetIslandPayload())
        assertNull(readyState().copy(address = null).toOfficialHeadsetIslandPayload())
        assertNull(readyState().copy(connection = UiConnectionState.CONNECTING).toOfficialHeadsetIslandPayload())
        assertNull(readyState(batteries = emptyMap()).toOfficialHeadsetIslandPayload())
    }

    @Test
    fun `trigger fires for Ready and wear transitions but not battery refresh`() {
        val initial = readyState()
        assertTrue(shouldTriggerOfficialHeadsetIsland(HeadphoneUiState(), initial))
        assertTrue(
            shouldTriggerOfficialHeadsetIsland(
                initial,
                initial.copy(wearing = mapOf("LEFT" to "WEARING")),
            ),
        )
        assertFalse(
            shouldTriggerOfficialHeadsetIsland(
                initial,
                initial.copy(batteries = mapOf("LEFT" to BatteryPayload("LEFT", 42, false))),
            ),
        )
    }

    @Test
    fun `raw wear changes that project to the same effective state do not retrigger`() {
        val previous = readyState(
            wearing = mapOf("LEFT" to "REMOVED", "RIGHT" to "REMOVED"),
        )
        val current = previous.copy(
            wearing = mapOf("LEFT" to "IN_CASE", "RIGHT" to "REMOVED"),
        )

        assertFalse(shouldTriggerOfficialHeadsetIsland(previous, current))
        assertEquals(previous.officialIslandEmissionToken(), current.officialIslandEmissionToken())
    }

    @Test
    fun `emission token changes for generation address and effective wear only`() {
        val state = readyState(wearing = mapOf("LEFT" to "REMOVED", "RIGHT" to "REMOVED"))

        assertEquals(
            state.officialIslandEmissionToken(),
            state.copy(title = "Settled title", emittedAtMillis = 99).officialIslandEmissionToken(),
        )
        assertTrue(state.officialIslandEmissionToken() != state.copy(generationId = 6).officialIslandEmissionToken())
        assertTrue(
            state.officialIslandEmissionToken() !=
                state.copy(wearing = mapOf("LEFT" to "WEARING", "RIGHT" to "REMOVED"))
                    .officialIslandEmissionToken(),
        )
    }

    private fun readyState(
        batteries: Map<String, BatteryPayload> = mapOf(
            "LEFT" to BatteryPayload("LEFT", 80, false),
            "RIGHT" to BatteryPayload("RIGHT", 70, false),
        ),
        wearing: Map<String, String> = emptyMap(),
    ) = HeadphoneUiState(
        deviceId = "sony:linkbuds-s",
        generationId = 5,
        address = "AA:BB:CC:DD:EE:FF",
        title = "LinkBuds S",
        connection = UiConnectionState.CONNECTED,
        topology = "EARBUDS_WITH_CASE",
        batteries = batteries,
        wearing = wearing,
    )
}
