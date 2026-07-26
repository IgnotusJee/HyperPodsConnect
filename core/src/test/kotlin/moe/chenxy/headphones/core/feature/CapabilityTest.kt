package moe.chenxy.headphones.core.feature

import moe.chenxy.headphones.core.device.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {

    private fun capability(
        featureId: FeatureId = FeatureId.NOISE_CONTROL,
        canWrite: Boolean = true,
        evidence: EvidenceLevel,
    ) = FeatureCapability(
        featureId = featureId,
        canRead = true,
        canWrite = canWrite,
        evidence = evidence,
        availableOnTransports = setOf(TransportKind.CLASSIC_SPP),
    )

    /**
     * A batch query answers only for supported features and omits the rest with
     * no error, so absence from the reply is the only signal that a feature is
     * unsupported. This mirrors an Enco Air5s answering 10 of 11 requested
     * features with an overall status of success.
     */
    @Test
    fun `features omitted from a successful batch reply are refuted`() {
        val requested = setOf(
            FeatureId.NOISE_CONTROL,
            FeatureId.EQUALIZER,
            FeatureId.SPATIAL_SOUND_SWITCH,
            FeatureId.DUAL_DEVICE_CONNECTION,
        )
        val answered = setOf(
            FeatureId.NOISE_CONTROL,
            FeatureId.EQUALIZER,
            FeatureId.SPATIAL_SOUND_SWITCH,
        )

        val evidence = resolveBatchEvidence(requested, answered)

        assertEquals(EvidenceLevel.VERIFIED, evidence[FeatureId.NOISE_CONTROL])
        assertEquals(EvidenceLevel.REFUTED, evidence[FeatureId.DUAL_DEVICE_CONNECTION])
    }

    @Test
    fun `a feature never asked about gets no evidence at all`() {
        val evidence = resolveBatchEvidence(
            requested = setOf(FeatureId.NOISE_CONTROL),
            answered = setOf(FeatureId.NOISE_CONTROL, FeatureId.LOW_LATENCY),
        )

        // Answering about something we did not request says nothing we can rely on.
        assertFalse(evidence.containsKey(FeatureId.LOW_LATENCY))
    }

    @Test
    fun `writes need at least advertised evidence`() {
        assertTrue(capability(evidence = EvidenceLevel.VERIFIED).isWritable)
        assertTrue(capability(evidence = EvidenceLevel.ADVERTISED).isWritable)
        // A model-name guess must not put a command on the wire.
        assertFalse(capability(evidence = EvidenceLevel.ASSUMED).isWritable)
        assertFalse(capability(evidence = EvidenceLevel.REFUTED).isWritable)
    }

    @Test
    fun `a refuted feature is not even readable`() {
        assertFalse(capability(evidence = EvidenceLevel.REFUTED).isReadable)
        assertTrue(capability(evidence = EvidenceLevel.ASSUMED).isReadable)
    }

    /**
     * Set responses carry a status byte and no value, so a capability that can
     * be written must default to needing a readback before its state is trusted.
     */
    @Test
    fun `writable capabilities require readback by default`() {
        assertTrue(capability(evidence = EvidenceLevel.VERIFIED).requiresReadback)
    }
}
