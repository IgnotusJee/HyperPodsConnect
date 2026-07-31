package moe.chenxy.oppopods.integration

import moe.chenxy.oppopods.ipc.BatteryPayload
import moe.chenxy.oppopods.ui.state.HeadphoneUiState
import moe.chenxy.oppopods.ui.state.UiConnectionState
import moe.chenxy.oppopods.ui.state.UiFeatureState
import org.junit.Assert.assertEquals
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
