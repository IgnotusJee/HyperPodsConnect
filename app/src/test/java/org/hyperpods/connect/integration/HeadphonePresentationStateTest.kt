package org.hyperpods.connect.integration

import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import org.hyperpods.connect.ipc.BatteryPayload
import org.hyperpods.connect.ui.state.HeadphoneUiState
import org.hyperpods.connect.ui.state.UiConnectionState
import org.hyperpods.connect.ui.state.UiFeatureOption
import org.hyperpods.connect.ui.state.UiFeatureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphonePresentationStateTest {
    @Test
    fun `unsupported capabilities remain hidden`() {
        val presentation = readyState().toPresentationState()

        FeatureId.entries.forEach { id ->
            assertFalse("$id must be hidden without capability evidence", presentation.feature(id).visible)
            assertFalse("$id must not be writable without capability evidence", presentation.feature(id).writable)
        }
    }

    @Test
    fun `read only and writable capabilities remain distinct`() {
        val presentation = readyState(
            features = mapOf(
                FeatureId.NOISE_CONTROL.name to feature(
                    FeatureId.NOISE_CONTROL,
                    value = NoiseControlMode.TRANSPARENCY.name,
                    writable = false,
                    options = listOf(
                        NoiseControlMode.OFF.name,
                        NoiseControlMode.TRANSPARENCY.name,
                    ),
                ),
                FeatureId.LOW_LATENCY.name to feature(
                    FeatureId.LOW_LATENCY,
                    value = "true",
                    writable = true,
                    options = listOf("false", "true"),
                ),
            ),
        ).toPresentationState()

        val noise = presentation.feature(FeatureId.NOISE_CONTROL)
        assertTrue(noise.visible)
        assertFalse(noise.writable)
        assertEquals(NoiseControlMode.TRANSPARENCY.name, noise.value)
        assertEquals(
            setOf(NoiseControlMode.OFF, NoiseControlMode.TRANSPARENCY),
            presentation.noiseControlModes,
        )
        assertTrue(presentation.feature(FeatureId.LOW_LATENCY).writable)
        assertFalse(presentation.feature(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT).visible)
    }

    @Test
    fun `battery topology projects single TWS and single side with case`() {
        val single = readyState(
            topology = "HEADBAND",
            batteries = batteries("SINGLE" to 90),
        ).toPresentationState()
        assertEquals(setOf(PresentationBatterySlot.SINGLE), single.batteries.keys)

        val tws = readyState(
            topology = "EARBUDS_WITH_CASE",
            batteries = batteries("LEFT" to 81, "RIGHT" to 79, "CASE" to 64),
        ).toPresentationState()
        assertEquals(
            setOf(PresentationBatterySlot.LEFT, PresentationBatterySlot.RIGHT, PresentationBatterySlot.CASE),
            tws.batteries.keys,
        )

        val singleSide = readyState(
            topology = "EARBUDS_WITH_CASE",
            batteries = batteries("LEFT" to 81, "CASE" to 64),
        ).toPresentationState()
        assertEquals(
            listOf(NotificationBatteryComponent.LEFT, NotificationBatteryComponent.CASE),
            singleSide.toNotificationProjection().islandSlots.map(NotificationBatterySlot::component),
        )
    }

    @Test
    fun `vendor identity cannot change popup notification or official island projection`() {
        val capabilities = mapOf(
            FeatureId.NOISE_CONTROL.name to feature(
                FeatureId.NOISE_CONTROL,
                NoiseControlMode.OFF.name,
                writable = true,
                options = listOf(NoiseControlMode.OFF.name, NoiseControlMode.TRANSPARENCY.name),
            ),
        )
        val baseline = readyState(
            vendorId = "vendor-a",
            topology = "EARBUDS_WITH_CASE",
            batteries = batteries("LEFT" to 70, "RIGHT" to 71, "CASE" to 80),
            features = capabilities,
        )
        val futureVendor = baseline.copy(vendorId = "future-third-party")

        val baselinePresentation = baseline.toPresentationState()
        val futurePresentation = futureVendor.toPresentationState()
        assertEquals(baselinePresentation, futurePresentation)
        assertEquals(
            baselinePresentation.toNotificationProjection(),
            futurePresentation.toNotificationProjection(),
        )
        assertEquals(
            baselinePresentation.toOfficialHeadsetIslandPayload(),
            futurePresentation.toOfficialHeadsetIslandPayload(),
        )
    }

    private fun readyState(
        vendorId: String? = null,
        topology: String? = null,
        batteries: Map<String, BatteryPayload> = emptyMap(),
        features: Map<String, UiFeatureState> = emptyMap(),
    ) = HeadphoneUiState(
        deviceId = "device:ready",
        generationId = 9,
        emittedAtMillis = 10,
        vendorId = vendorId,
        address = "11:22:33:44:55:66",
        title = "Test Headset",
        connection = UiConnectionState.CONNECTED,
        topology = topology,
        batteries = batteries,
        features = features,
    )

    private fun feature(
        id: FeatureId,
        value: String,
        writable: Boolean,
        options: List<String>,
    ) = UiFeatureState(
        id = id.name,
        readable = true,
        writable = writable,
        confirmed = value,
        pending = null,
        stale = false,
        options = options.map { UiFeatureOption(it, it) },
        operation = null,
    )

    private fun batteries(vararg values: Pair<String, Int>): Map<String, BatteryPayload> =
        values.associate { (component, level) ->
            component to BatteryPayload(component, level, charging = false)
        }
}
