package moe.chenxy.oppopods.core

import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.oppopods.pods.DeviceCapabilities
import moe.chenxy.oppopods.pods.EqPreset
import moe.chenxy.oppopods.pods.detectDeviceCapabilities
import moe.chenxy.oppopods.pods.NoiseControlMode as OppoNoiseControlMode
import moe.chenxy.oppopods.pods.WearState as OppoWearState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OppoCoreAdapterTest {

    @Test
    fun `every noise control mode survives a round trip`() {
        OppoNoiseControlMode.entries.forEach { mode ->
            val core = OppoCoreAdapter.toCoreNoiseControl(mode)
            assertEquals(mode, OppoCoreAdapter.toOppoNoiseControl(core))
        }
    }

    @Test
    fun `every wear state maps and disconnected becomes unknown`() {
        OppoWearState.entries.forEach { assertNotNull(OppoCoreAdapter.toCoreWear(it)) }
        assertEquals(
            moe.chenxy.headphones.core.feature.WearState.UNKNOWN,
            OppoCoreAdapter.toCoreWear(OppoWearState.DISCONNECTED),
        )
    }

    @Test
    fun `every supported EQ preset survives a round trip`() {
        EqPreset.ALL.forEach { presetId ->
            val core = OppoCoreAdapter.toCoreEqPreset(presetId)
            assertEquals(presetId, OppoCoreAdapter.toOppoEqPreset(core))
        }
    }

    @Test
    fun `an unknown preset id does not map back to a vendor value`() {
        val foreign = moe.chenxy.headphones.core.feature.EqualizerPreset(id = "sony:3")
        assertNull(OppoCoreAdapter.toOppoEqPreset(foreign))
        // A vendor id outside the supported set is rejected too.
        assertNull(OppoCoreAdapter.toOppoEqPreset(OppoCoreAdapter.toCoreEqPreset(99)))
    }

    @Test
    fun `spatial audio maps both ways and rejects out of range values`() {
        (0..2).forEach { raw ->
            val core = OppoCoreAdapter.toCoreSpatialAudio(raw)!!
            assertEquals(raw, OppoCoreAdapter.toOppoSpatialAudio(core))
        }
        assertNull(OppoCoreAdapter.toCoreSpatialAudio(3))
    }

    @Test
    fun `device id is stable across address formatting differences`() {
        val a = OppoCoreAdapter.identityOf("02:00:00:00:00:01", "OPPO Enco Air5s")
        val b = OppoCoreAdapter.identityOf("020000000 001".replace(" ", ""), "OPPO Enco Air5s")
        assertEquals(a.id, b.id)
    }

    /**
     * Legacy override data stays assumed; only the protocol session may promote
     * a feature to device-verified and writable.
     */
    @Test
    fun `legacy override capabilities are assumed and therefore not writable`() {
        val capabilities = detectDeviceCapabilities()

        val mapped = OppoCoreAdapter.toCoreCapabilities(capabilities)

        mapped.values.forEach { capability ->
            assertEquals(EvidenceLevel.ASSUMED, capability.evidence)
            assertFalse("${capability.featureId} must not be writable before protocol evidence", capability.isWritable)
        }
    }

    @Test
    fun `explicit override is independent of the device name`() {
        val enabled = OppoCoreAdapter.toCoreCapabilities(
            detectDeviceCapabilities(
                spatialSoundSwitchOverride =
                    moe.chenxy.oppopods.pods.DeviceCapabilityOverride.FORCE_ENABLED,
            ),
        )
        val automatic = OppoCoreAdapter.toCoreCapabilities(detectDeviceCapabilities())

        assertTrue(enabled.getValue(FeatureId.SPATIAL_SOUND_SWITCH).canRead)
        assertFalse(automatic.getValue(FeatureId.SPATIAL_SOUND_SWITCH).canRead)
    }

    @Test
    fun `read only features are never marked writable`() {
        val mapped = OppoCoreAdapter.toCoreCapabilities(DeviceCapabilities(
            adaptiveSupported = true,
            spatialAudioSupported = true,
            spatialSoundSwitchSupported = true,
            ancImplementation = moe.chenxy.oppopods.pods.AncImplementation.STANDARD,
        ))

        assertFalse(mapped.getValue(FeatureId.BATTERY).canWrite)
        assertFalse(mapped.getValue(FeatureId.WEAR_DETECTION).canWrite)
    }
}
