package moe.chenxy.oppopods.pods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test
    fun `exact model name is recognized`() {
        assertTrue(isAdaptiveSupportedByName("OPPO Enco Free4"))
        assertTrue(isSpatialAudioSupportedByName("OPPO Enco X3"))
        assertTrue(isSpatialSoundSwitchSupportedByName("OPPO Enco Air5"))
    }

    @Test
    fun `model suffix is recognized`() {
        assertTrue(isAdaptiveSupportedByName("OPPO Enco Free4（丹拿版）"))
    }

    @Test
    fun `blank or punctuation-only name is not recognized`() {
        assertFalse(isAdaptiveSupportedByName(""))
        assertFalse(isSpatialAudioSupportedByName("   "))
        assertFalse(isSpatialSoundSwitchSupportedByName("---"))
    }

    @Test
    fun `shorter model prefix is not treated as a supported model`() {
        assertFalse(isAdaptiveSupportedByName("OPPO Enco Free"))
    }

    @Test
    fun `matching is case and punctuation insensitive for every current model rule`() {
        assertTrue(isAdaptiveSupportedByName("oppo-enco_free4"))
        assertTrue(isSpatialAudioSupportedByName("oppo enco x3"))
        assertTrue(isSpatialSoundSwitchSupportedByName("OPPO.ENCO.AIR5"))
        assertTrue(isLegacyAncDeviceByName("oppo enco air2 pro"))
    }

    @Test
    fun `unknown complete-looking OPPO model does not inherit a named capability`() {
        assertFalse(isAdaptiveSupportedByName("OPPO Enco Free5"))
        assertFalse(isSpatialAudioSupportedByName("OPPO Enco X4"))
        assertFalse(isSpatialSoundSwitchSupportedByName("OPPO Enco Air4"))
        assertFalse(isLegacyAncDeviceByName("OPPO Enco Air3 Pro"))
    }

    @Test
    fun `user overrides remain authoritative over name detection`() {
        val forcedOff = detectDeviceCapabilities(
            deviceName = "OPPO Enco Free4",
            adaptiveOverride = DeviceCapabilityOverride.FORCE_DISABLED,
            spatialSoundSwitchOverride = DeviceCapabilityOverride.FORCE_DISABLED,
        )
        assertFalse(forcedOff.adaptiveSupported)
        assertFalse(forcedOff.spatialSoundSwitchSupported)

        val forcedOn = detectDeviceCapabilities(
            deviceName = "Unknown Headphones",
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
