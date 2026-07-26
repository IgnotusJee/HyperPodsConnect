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
        val a = OppoCoreAdapter.identityOf("B0:38:E2:E6:93:B4", "OPPO Enco Air5s")
        val b = OppoCoreAdapter.identityOf("b038e2e69 3b4".replace(" ", ""), "OPPO Enco Air5s")
        assertEquals(a.id, b.id)
    }

    /**
     * The whole point of routing the name whitelist through the core model: a
     * guess stays a guess. These capabilities can decide what a UI shows, but
     * `isWritable` stays false so nothing derived from a model name can put a
     * command on the wire before a handshake confirms it.
     */
    @Test
    fun `name derived capabilities are assumed and therefore not writable`() {
        val capabilities = detectDeviceCapabilities("OPPO Enco Air5s")

        val mapped = OppoCoreAdapter.toCoreCapabilities(capabilities)

        mapped.values.forEach { capability ->
            assertEquals(EvidenceLevel.ASSUMED, capability.evidence)
            assertFalse("${capability.featureId} must not be writable on a name guess", capability.isWritable)
        }
    }

    @Test
    fun `the substring inheritance the whitelist performs is visible in the mapping`() {
        // "OPPO Enco Air5" is a substring of the Air5s name, so the switch is
        // inherited rather than listed. A capture showed the inherited answer is
        // correct for this model; the mapping still records it as assumed.
        val air5s = OppoCoreAdapter.toCoreCapabilities(detectDeviceCapabilities("OPPO Enco Air5s"))
        val unknown = OppoCoreAdapter.toCoreCapabilities(detectDeviceCapabilities("Some Other Buds"))

        assertTrue(air5s.getValue(FeatureId.SPATIAL_SOUND_SWITCH).canRead)
        assertFalse(unknown.getValue(FeatureId.SPATIAL_SOUND_SWITCH).canRead)
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
