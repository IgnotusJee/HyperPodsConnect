package org.hyperpods.connect.integration

import moe.chenxy.headphones.core.feature.FeatureId
import org.hyperpods.connect.ui.state.HeadphoneUiState
import org.hyperpods.connect.ui.state.UiFeatureState
import org.hyperpods.connect.ui.state.UiFeatureOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiLinkDevicePresentationTest {
    @Test
    fun `TWS keeps configured Xiaomi presentation`() {
        val presentation = miLinkDevicePresentation(
            isTws = true,
            configuredTwsDeviceId = "01010607",
        )

        assertTrue(presentation.isTws)
        assertEquals("01010607", presentation.deviceId)
        assertEquals(0, presentation.deviceType)
    }

    @Test
    fun `single device uses MiLink headphones presentation`() {
        val presentation = miLinkDevicePresentation(
            isTws = false,
            configuredTwsDeviceId = "01010607",
        )

        assertFalse(presentation.isTws)
        assertEquals("0A20", presentation.deviceId)
        assertEquals(6, presentation.deviceType)
    }

    @Test
    fun `writable noise control shows ANC and unsupported ring find stays hidden`() {
        val state = HeadphoneUiState(
            features = mapOf(
                FeatureId.NOISE_CONTROL.name to feature(
                    id = FeatureId.NOISE_CONTROL.name,
                    readable = true,
                    writable = true,
                ),
            ),
        )

        val capabilities = miLinkCardCapabilities(state)

        assertTrue(capabilities.showNoiseControl)
        assertTrue(capabilities.noiseControlWritable)
        assertFalse(capabilities.showRingFind)
    }

    @Test
    fun `read-only noise control remains visible but disabled and absent stays hidden`() {
        val readOnly = HeadphoneUiState(
            features = mapOf(
                FeatureId.NOISE_CONTROL.name to feature(
                    id = FeatureId.NOISE_CONTROL.name,
                    readable = true,
                    writable = false,
                ),
            ),
        )

        assertTrue(miLinkCardCapabilities(readOnly).showNoiseControl)
        assertFalse(miLinkCardCapabilities(readOnly).noiseControlWritable)
        assertFalse(miLinkCardCapabilities(HeadphoneUiState()).showNoiseControl)
    }

    @Test
    fun `MiLink binary spatial on prefers fixed then head tracking and never invents a mode`() {
        val fixedAndTracking = feature(
            id = FeatureId.SPATIAL_AUDIO.name,
            readable = true,
            writable = true,
            options = listOf("OFF", "HEAD_TRACKING", "FIXED"),
        )
        val trackingOnly = fixedAndTracking.copy(
            options = listOf("OFF", "HEAD_TRACKING").map { UiFeatureOption(it, it) },
        )
        val offOnly = fixedAndTracking.copy(
            options = listOf(UiFeatureOption("OFF", "OFF")),
        )

        assertEquals(
            moe.chenxy.headphones.core.feature.SpatialAudioMode.FIXED,
            miLinkSpatialModeForBinary(1, fixedAndTracking),
        )
        assertEquals(
            moe.chenxy.headphones.core.feature.SpatialAudioMode.HEAD_TRACKING,
            miLinkSpatialModeForBinary(1, trackingOnly),
        )
        assertEquals(
            moe.chenxy.headphones.core.feature.SpatialAudioMode.OFF,
            miLinkSpatialModeForBinary(0, fixedAndTracking),
        )
        assertEquals(null, miLinkSpatialModeForBinary(1, offOnly))
        assertEquals(null, miLinkSpatialModeForBinary(1, fixedAndTracking.copy(writable = false)))
        assertEquals(null, miLinkSpatialModeForBinary(2, fixedAndTracking))
    }

    @Test
    fun `ANC mappings keep runtime and display domains separate`() {
        assertEquals(MILINK_RUNTIME_ANC_ON, miLinkRuntimeAncState(INTEGRATION_ANC_ON))
        assertEquals(
            MILINK_RUNTIME_ANC_TRANSPARENCY,
            miLinkRuntimeAncState(INTEGRATION_ANC_TRANSPARENCY),
        )
        assertEquals(MILINK_RUNTIME_ANC_OFF, miLinkRuntimeAncState(INTEGRATION_ANC_OFF))

        assertEquals(MILINK_DISPLAY_ANC_ON, miLinkDisplayAncMode(INTEGRATION_ANC_ON))
        assertEquals(
            MILINK_DISPLAY_ANC_TRANSPARENCY,
            miLinkDisplayAncMode(INTEGRATION_ANC_TRANSPARENCY),
        )
        assertEquals(MILINK_DISPLAY_ANC_OFF, miLinkDisplayAncMode(INTEGRATION_ANC_OFF))
    }

    @Test
    fun `MiLink runtime commands map back to integration modes`() {
        assertEquals(INTEGRATION_ANC_ON, integrationAncFromMiLinkRuntime(MILINK_RUNTIME_ANC_ON))
        assertEquals(
            INTEGRATION_ANC_TRANSPARENCY,
            integrationAncFromMiLinkRuntime(MILINK_RUNTIME_ANC_TRANSPARENCY),
        )
        assertEquals(INTEGRATION_ANC_OFF, integrationAncFromMiLinkRuntime(MILINK_RUNTIME_ANC_OFF))
    }

    @Test
    fun `MiLink ANC command selects only driver allowed modes`() {
        val feature = feature(
            id = FeatureId.NOISE_CONTROL.name,
            readable = true,
            writable = true,
            options = listOf("OFF", "TRANSPARENCY", "NOISE_CANCELLATION_DEEP"),
        )

        assertEquals(8, miLinkIntegrationAncForCommand(INTEGRATION_ANC_ON, feature))
        assertEquals(3, miLinkIntegrationAncForCommand(INTEGRATION_ANC_TRANSPARENCY, feature))
        assertEquals(1, miLinkIntegrationAncForCommand(INTEGRATION_ANC_OFF, feature))
        assertEquals(
            null,
            miLinkIntegrationAncForCommand(INTEGRATION_ANC_ON, feature.copy(writable = false)),
        )
        assertEquals(
            null,
            miLinkIntegrationAncForCommand(
                INTEGRATION_ANC_TRANSPARENCY,
                feature.copy(options = listOf(UiFeatureOption("OFF", "OFF"))),
            ),
        )
    }

    private fun feature(
        id: String,
        readable: Boolean,
        writable: Boolean,
        options: List<String> = emptyList(),
    ) = UiFeatureState(
        id = id,
        readable = readable,
        writable = writable,
        confirmed = null,
        pending = null,
        stale = false,
        options = options.map { UiFeatureOption(it, it) },
        operation = null,
    )
}
