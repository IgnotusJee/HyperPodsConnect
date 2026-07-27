package moe.chenxy.headphones.core.feature

import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId

/** Vendor-neutral name for something a headset can report or be told. */
enum class FeatureId {
    BATTERY,
    WEAR_DETECTION,
    NOISE_CONTROL,
    TRANSPARENCY_VOCAL_ENHANCEMENT,
    EQUALIZER,
    LOW_LATENCY,
    SPATIAL_AUDIO,
    SPATIAL_SOUND_SWITCH,
    DUAL_DEVICE_CONNECTION,
    FIRMWARE_VERSION,
}

/**
 * How well a capability is known.
 *
 * The distinction between [ADVERTISED] and [VERIFIED] exists because of a real
 * device behaviour: when asked for a batch of features, an OPPO Enco Air5s
 * answers only for the ones it supports and **silently omits the rest** rather
 * than reporting an error. A successful response therefore says nothing about
 * the features missing from it, and support can only be established per feature
 * by comparing what was asked against what came back.
 */
enum class EvidenceLevel {
    /** Guessed from a model name or a remembered profile. Weakest possible. */
    ASSUMED,

    /** The device listed it in a capability bitmap or a supported-feature reply. */
    ADVERTISED,

    /** We asked for this exact feature and the device answered for it. */
    VERIFIED,

    /** We asked and the device omitted it from an otherwise successful reply. */
    REFUTED,
}

/**
 * What can be done with one feature on one device.
 *
 * [requiresReadback] is not a style preference. Every set response observed on
 * real hardware carries a status byte and no echo of the new value, so an
 * accepted write does not prove the value changed; only a subsequent read or
 * notification does.
 */
data class FeatureCapability(
    val featureId: FeatureId,
    val canRead: Boolean,
    val canWrite: Boolean,
    val evidence: EvidenceLevel,
    val availableOnTransports: Set<TransportKind>,
    val requiresReadback: Boolean = true,
    /** Allowed values for enumerated features; empty when not enumerable. */
    val allowedValues: Set<String> = emptySet(),
    /** Driver-provided, presentation-safe labels keyed by opaque domain value. */
    val valueLabels: Map<String, String> = emptyMap(),
    val source: String? = null,
) {
    /**
     * Writes are gated on verification, not on the vendor's word. A feature the
     * device merely advertised may still be written once the user asks, but a
     * refuted or assumed one must not be, which keeps a name-list guess from
     * turning into a command on the wire.
     */
    val isWritable: Boolean
        get() = canWrite && (evidence == EvidenceLevel.VERIFIED || evidence == EvidenceLevel.ADVERTISED)

    val isReadable: Boolean
        get() = canRead && evidence != EvidenceLevel.REFUTED
}

enum class DeviceTopology { EARBUDS_WITH_CASE, EARBUDS_NO_CASE, HEADBAND, NECKBAND, UNKNOWN }

enum class CompatibilityLevel {
    /** Recognised, nothing confirmed. Show nothing actionable. */
    DETECTED,

    /** Protocol answers reads. Show state, no controls. */
    READ_ONLY,

    /** At least one write verified end to end. Controls may be shown. */
    CONTROLLED,
}

data class ProtocolDescriptor(
    val name: String,
    val version: String? = null,
    val commandTable: String? = null,
)

data class DeviceProfile(
    val identity: DeviceIdentity,
    val vendorId: VendorId,
    val model: String?,
    val firmware: String?,
    val topology: DeviceTopology,
    val transport: TransportKind,
    val protocol: ProtocolDescriptor,
    val features: Map<FeatureId, FeatureCapability>,
    val compatibilityLevel: CompatibilityLevel,
) {
    fun capability(featureId: FeatureId): FeatureCapability? = features[featureId]

    fun canWrite(featureId: FeatureId): Boolean = features[featureId]?.isWritable == true

    fun canRead(featureId: FeatureId): Boolean = features[featureId]?.isReadable == true
}

/**
 * Turns a batch capability query into per-feature evidence.
 *
 * Anything requested but absent from the reply is [EvidenceLevel.REFUTED], which
 * is the only way to notice a silently dropped feature.
 */
fun resolveBatchEvidence(
    requested: Set<FeatureId>,
    answered: Set<FeatureId>,
): Map<FeatureId, EvidenceLevel> = requested.associateWith { featureId ->
    if (featureId in answered) EvidenceLevel.VERIFIED else EvidenceLevel.REFUTED
}
