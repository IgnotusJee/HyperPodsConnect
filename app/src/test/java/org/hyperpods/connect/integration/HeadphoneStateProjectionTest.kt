package org.hyperpods.connect.integration

import org.hyperpods.connect.ipc.BatteryPayload
import org.hyperpods.connect.ui.state.HeadphoneUiState
import org.hyperpods.connect.ui.state.UiConnectionState
import org.hyperpods.connect.ui.state.UiFeatureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphoneStateProjectionTest {
    @Test
    fun `versioned state projects to HyperOS DTOs without a legacy broadcast`() {
        val state = HeadphoneUiState(
            deviceId = "device:test",
            generationId = 7,
            address = "11:22:33:44:55:66",
            title = "LinkBuds S",
            connection = UiConnectionState.CONNECTED,
            batteries = mapOf(
                "LEFT" to BatteryPayload("LEFT", 81, false),
                "RIGHT" to BatteryPayload("RIGHT", 79, true),
                "CASE" to BatteryPayload("CASE", 64, false),
            ),
            features = mapOf(
                "NOISE_CONTROL" to feature("NOISE_CONTROL", "TRANSPARENCY"),
                "TRANSPARENCY_VOCAL_ENHANCEMENT" to
                    feature("TRANSPARENCY_VOCAL_ENHANCEMENT", "true"),
                "SPATIAL_AUDIO" to feature("SPATIAL_AUDIO", "HEAD_TRACKING"),
            ),
        )

        val projected = state.toIntegrationState()

        assertTrue(projected.connected)
        assertEquals(81, projected.battery?.left?.battery)
        assertEquals(79, projected.battery?.right?.battery)
        assertEquals(64, projected.battery?.case?.battery)
        assertEquals(3, projected.anc)
        assertEquals(true, projected.transparencyVocalEnhancement)
        assertEquals(2, projected.spatialAudioMode)
    }

    @Test
    fun `single battery projects as a non TWS device`() {
        val state = HeadphoneUiState(
            deviceId = "sony:wh",
            topology = "UNKNOWN",
            batteries = mapOf("SINGLE" to BatteryPayload("SINGLE", 90, false)),
        )

        assertFalse(state.isTwsForHyperOs())
    }

    @Test
    fun `earbud components project as TWS without model name checks`() {
        val state = HeadphoneUiState(
            deviceId = "sony:buds",
            topology = "UNKNOWN",
            batteries = mapOf(
                "LEFT" to BatteryPayload("LEFT", 80, false),
                "RIGHT" to BatteryPayload("RIGHT", 70, false),
            ),
        )

        assertTrue(state.isTwsForHyperOs())
    }

    @Test
    fun `basic ANC maps to an enabled MIUI ANC level`() {
        assertEquals("0100", miuiAncLevel("NOISE_CANCELLATION", false))
        assertEquals("0200", miuiAncLevel("TRANSPARENCY", false))
        assertEquals("0000", miuiAncLevel("OFF", false))
    }

    private fun feature(id: String, value: String) = UiFeatureState(
        id = id,
        readable = true,
        writable = true,
        confirmed = value,
        pending = null,
        stale = false,
        options = emptyList(),
        operation = null,
    )
}
