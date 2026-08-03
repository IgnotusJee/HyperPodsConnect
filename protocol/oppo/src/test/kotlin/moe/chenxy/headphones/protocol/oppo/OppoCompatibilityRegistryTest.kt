package moe.chenxy.headphones.protocol.oppo

import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.DetectionConfidence
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.CompatibilityLevel
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
    fun `Bluetooth model name does not change protocol capabilities`() {
        val air5s = candidate("OPPO Enco Air5s")
        val unknown = candidate("OPPO Enco Future")
        val knownProfile = OppoCompatibilityRegistry.initialProfile(
            air5s,
            OppoCompatibilityRegistry.resolve(),
        )
        val unknownProfile = OppoCompatibilityRegistry.initialProfile(
            unknown,
            OppoCompatibilityRegistry.resolve(),
        )

        assertEquals(
            knownProfile.features.mapValues { it.value.copy(source = "") },
            unknownProfile.features.mapValues { it.value.copy(source = "") },
        )
        assertFalse(knownProfile.canWrite(FeatureId.NOISE_CONTROL))
        assertFalse(knownProfile.canWrite(FeatureId.SPATIAL_SOUND_SWITCH))
    }

    @Test
    fun `equalizer baseline is protocol generic rather than model named`() {
        val candidate = candidate("OPPO Enco Air5s")
        val profile = OppoCompatibilityRegistry.initialProfile(
            candidate,
            OppoCompatibilityRegistry.resolve(),
        )
        val equalizer = profile.capability(FeatureId.EQUALIZER)!!

        assertEquals(setOf("oppo:0", "oppo:1", "oppo:2", "oppo:3", "oppo:7"), equalizer.allowedValues)
        assertEquals("Authentic", equalizer.valueLabels["oppo:0"])
        assertEquals("Vocal", equalizer.valueLabels["oppo:2"])
        assertEquals("Detail", equalizer.valueLabels["oppo:1"])
    }

    @Test
    fun `generic equalizer capability retains the non-contiguous Dynaudio protocol id`() {
        val candidate = candidate("OPPO Enco Unknown")
        val profile = OppoCompatibilityRegistry.initialProfile(
            candidate,
            OppoCompatibilityRegistry.resolve(),
        )
        val equalizer = profile.capability(FeatureId.EQUALIZER)!!

        assertTrue("oppo:7" in equalizer.allowedValues)
        assertEquals("Dynaudio", equalizer.valueLabels["oppo:7"])
        assertFalse("oppo:4" in equalizer.allowedValues)
    }

    @Test
    fun `device evidence promotes or refutes each feature independently`() {
        val candidate = candidate("OPPO Enco Air5s")
        val initial = OppoCompatibilityRegistry.initialProfile(
            candidate,
            OppoCompatibilityRegistry.resolve(),
        )

        val resolved = OppoCompatibilityRegistry.withEvidence(
            initial,
            verified = setOf(FeatureId.NOISE_CONTROL),
            writable = setOf(FeatureId.NOISE_CONTROL),
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
    fun `firmware strings do not change compatibility level`() {
        val candidate = candidate("OPPO Enco Air5s")
        val initial = OppoCompatibilityRegistry.initialProfile(
            candidate,
            OppoCompatibilityRegistry.resolve(),
        )

        val stable = OppoCompatibilityRegistry.withEvidence(
            initial,
            verified = setOf(FeatureId.NOISE_CONTROL),
            writable = setOf(FeatureId.NOISE_CONTROL),
            firmware = "163.163.102",
        )
        val otherFirmware = OppoCompatibilityRegistry.withEvidence(
            initial,
            verified = setOf(FeatureId.NOISE_CONTROL),
            writable = setOf(FeatureId.NOISE_CONTROL),
            firmware = "163.163.103",
        )

        assertEquals(CompatibilityLevel.CONTROLLED, stable.compatibilityLevel)
        assertEquals(CompatibilityLevel.CONTROLLED, otherFirmware.compatibilityLevel)
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
