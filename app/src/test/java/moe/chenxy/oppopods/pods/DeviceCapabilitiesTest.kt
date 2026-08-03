package moe.chenxy.oppopods.pods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test
    fun `automatic mode starts disabled until the live session provides evidence`() {
        val automatic = detectDeviceCapabilities()
        assertFalse(automatic.adaptiveSupported)
        assertFalse(automatic.spatialAudioSupported)
        assertFalse(automatic.spatialSoundSwitchSupported)
        assertEquals(AncImplementation.STANDARD, automatic.ancImplementation)
    }

    @Test
    fun `user overrides remain authoritative`() {
        val forcedOff = detectDeviceCapabilities(
            adaptiveOverride = DeviceCapabilityOverride.FORCE_DISABLED,
            spatialSoundSwitchOverride = DeviceCapabilityOverride.FORCE_DISABLED,
        )
        assertFalse(forcedOff.adaptiveSupported)
        assertFalse(forcedOff.spatialSoundSwitchSupported)

        val forcedOn = detectDeviceCapabilities(
            adaptiveOverride = DeviceCapabilityOverride.FORCE_ENABLED,
            spatialAudioOverride = DeviceCapabilityOverride.FORCE_ENABLED,
            spatialSoundSwitchOverride = DeviceCapabilityOverride.FORCE_ENABLED,
            ancImplementationOverride = DeviceCapabilityOverride.FORCE_ENABLED,
        )
        assertTrue(forcedOn.adaptiveSupported)
        assertTrue(forcedOn.spatialAudioSupported)
        assertTrue(forcedOn.spatialSoundSwitchSupported)
        assertEquals(AncImplementation.COMPATIBLE, forcedOn.ancImplementation)
    }
}
