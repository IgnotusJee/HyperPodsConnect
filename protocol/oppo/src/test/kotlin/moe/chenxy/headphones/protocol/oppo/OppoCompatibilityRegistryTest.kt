package moe.chenxy.headphones.protocol.oppo

import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.DetectionConfidence
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityRegistry
import moe.chenxy.headphones.protocol.oppo.session.OppoDriverProvider
import moe.chenxy.headphones.protocol.oppo.session.OppoSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OppoCompatibilityRegistryTest {

    @Test
    fun `model-specific support is advertised while family defaults stay assumed`() {
        val candidate = candidate("OPPO Enco Air5s")
        val compatibility = OppoCompatibilityRegistry.resolve(candidate.displayName)

        val profile = OppoCompatibilityRegistry.initialProfile(candidate, compatibility)

        assertEquals(
            EvidenceLevel.ADVERTISED,
            profile.capability(FeatureId.SPATIAL_SOUND_SWITCH)?.evidence,
        )
        assertEquals(
            EvidenceLevel.ASSUMED,
            profile.capability(FeatureId.NOISE_CONTROL)?.evidence,
        )
        assertFalse(profile.canWrite(FeatureId.NOISE_CONTROL))
        assertTrue(profile.canWrite(FeatureId.SPATIAL_SOUND_SWITCH))
        assertFalse(
            OppoCompatibilityRegistry.resolve("OPPO Enco Air50").spatialSoundSwitchSupported,
        )
    }

    @Test
    fun `device evidence promotes or refutes each feature independently`() {
        val candidate = candidate("OPPO Enco Air5s")
        val initial = OppoCompatibilityRegistry.initialProfile(
            candidate,
            OppoCompatibilityRegistry.resolve(candidate.displayName),
        )

        val resolved = OppoCompatibilityRegistry.withEvidence(
            initial,
            verified = setOf(FeatureId.NOISE_CONTROL),
            refuted = setOf(FeatureId.DUAL_DEVICE_CONNECTION),
        )

        assertTrue(resolved.canWrite(FeatureId.NOISE_CONTROL))
        assertFalse(resolved.canRead(FeatureId.DUAL_DEVICE_CONNECTION))
        assertEquals(
            EvidenceLevel.REFUTED,
            resolved.capability(FeatureId.DUAL_DEVICE_CONNECTION)?.evidence,
        )
    }

    @Test
    fun `provider prefers UUID evidence over a model-name hint`() {
        val provider = OppoDriverProvider()
        val byUuid = candidate("Unknown Buds", setOf(OppoSession.OPPO_SPP_UUID))
        val byName = candidate("OPPO Enco Buds 3")

        assertEquals(DetectionConfidence.TRANSPORT_EVIDENCE, provider.inspect(byUuid)?.confidence)
        assertEquals(DetectionConfidence.HINT, provider.inspect(byName)?.confidence)
    }

    private fun candidate(name: String, uuids: Set<String> = emptySet()): DeviceCandidate {
        val address = "11:22:33:44:55:66"
        return DeviceCandidate(
            identity = DeviceIdentity(
                DeviceId.fromAddress(address),
                VendorId.OPPO,
                address,
            ),
            displayName = name,
            bonded = true,
            advertisedUuids = uuids,
            availableTransports = setOf(TransportKind.CLASSIC_SPP),
        )
    }
}
