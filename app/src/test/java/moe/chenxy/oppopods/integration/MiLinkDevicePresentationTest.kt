package moe.chenxy.oppopods.integration

import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.oppopods.ui.state.HeadphoneUiState
import moe.chenxy.oppopods.ui.state.UiFeatureState
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
        assertFalse(capabilities.showRingFind)
    }

    @Test
    fun `read-only or absent noise control does not expose MiLink toggle`() {
        val readOnly = HeadphoneUiState(
            features = mapOf(
                FeatureId.NOISE_CONTROL.name to feature(
                    id = FeatureId.NOISE_CONTROL.name,
                    readable = true,
                    writable = false,
                ),
            ),
        )

        assertFalse(miLinkCardCapabilities(readOnly).showNoiseControl)
        assertFalse(miLinkCardCapabilities(HeadphoneUiState()).showNoiseControl)
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

    private fun feature(
        id: String,
        readable: Boolean,
        writable: Boolean,
    ) = UiFeatureState(
        id = id,
        readable = readable,
        writable = writable,
        confirmed = null,
        pending = null,
        stale = false,
        options = emptyList(),
        operation = null,
    )
}
