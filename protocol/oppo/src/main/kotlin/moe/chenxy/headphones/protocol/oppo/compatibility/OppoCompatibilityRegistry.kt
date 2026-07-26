package moe.chenxy.headphones.protocol.oppo.compatibility

import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceTopology
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.ProtocolDescriptor
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncEncoding

enum class OppoLowLatencyStrategy {
    MAIN_SWITCH,
    MAIN_AND_LOW_LATENCY_SWITCH,
}

data class OppoCompatibilityOverrides(
    val adaptiveSupported: Boolean? = null,
    val spatialAudioSupported: Boolean? = null,
    val spatialSoundSwitchSupported: Boolean? = null,
    val ancEncoding: OppoAncEncoding? = null,
    val lowLatencyStrategy: OppoLowLatencyStrategy = OppoLowLatencyStrategy.MAIN_SWITCH,
)

data class OppoCompatibilityProfile(
    val adaptiveSupported: Boolean,
    val spatialAudioSupported: Boolean,
    val spatialSoundSwitchSupported: Boolean,
    val ancEncoding: OppoAncEncoding,
    val lowLatencyStrategy: OppoLowLatencyStrategy,
    internal val forcedFeatures: Set<FeatureId>,
)

/**
 * The only model-name compatibility table in the OPPO protocol.
 *
 * Generic family features start as [EvidenceLevel.ASSUMED] and are promoted by
 * query replies. Model-table matches and explicit user overrides are
 * [EvidenceLevel.ADVERTISED]: they are strong enough to permit the legacy
 * model-specific controls, but still remain distinguishable from a verified
 * device response.
 */
object OppoCompatibilityRegistry {

    private val adaptiveModels = setOf(
        "OPPO Enco Free4",
        "OPPO Enco Free4 丹拿版",
    )
    private val spatialAudioModels = setOf("OPPO Enco X3")
    private val spatialSwitchModels = setOf(
        "OPPO Enco Free4",
        "OPPO Enco Free4 丹拿版",
        "OPPO Enco Air5",
        // Verified on the Phase 0 device capture; listed explicitly so a future
        // "Air50" cannot inherit Air5 support through substring matching.
        "OPPO Enco Air5s",
    )
    private val compatibleAncModels = setOf("OPPO Enco Air2 Pro")

    fun resolve(
        modelName: String?,
        overrides: OppoCompatibilityOverrides = OppoCompatibilityOverrides(),
    ): OppoCompatibilityProfile {
        val name = modelName.orEmpty()
        val forced = buildSet {
            if (overrides.adaptiveSupported != null) add(FeatureId.NOISE_CONTROL)
            if (overrides.spatialAudioSupported != null) add(FeatureId.SPATIAL_AUDIO)
            if (overrides.spatialSoundSwitchSupported != null) add(FeatureId.SPATIAL_SOUND_SWITCH)
        }
        return OppoCompatibilityProfile(
            adaptiveSupported = overrides.adaptiveSupported
                ?: matches(name, adaptiveModels),
            spatialAudioSupported = overrides.spatialAudioSupported
                ?: matches(name, spatialAudioModels),
            spatialSoundSwitchSupported = overrides.spatialSoundSwitchSupported
                ?: matches(name, spatialSwitchModels),
            ancEncoding = overrides.ancEncoding
                ?: if (matches(name, compatibleAncModels)) {
                    OppoAncEncoding.COMPATIBLE
                } else {
                    OppoAncEncoding.STANDARD
                },
            lowLatencyStrategy = overrides.lowLatencyStrategy,
            forcedFeatures = forced,
        )
    }

    fun initialProfile(
        candidate: DeviceCandidate,
        compatibility: OppoCompatibilityProfile,
    ): DeviceProfile {
        val transports = setOf(TransportKind.CLASSIC_SPP)

        fun capability(
            id: FeatureId,
            supported: Boolean,
            writable: Boolean,
            advertised: Boolean = false,
            source: String = "oppo-family",
        ): FeatureCapability {
            val forced = id in compatibility.forcedFeatures
            return FeatureCapability(
                featureId = id,
                canRead = supported,
                canWrite = supported && writable,
                evidence = if ((forced || advertised) && supported) {
                    EvidenceLevel.ADVERTISED
                } else {
                    EvidenceLevel.ASSUMED
                },
                availableOnTransports = transports,
                requiresReadback = writable,
                source = when {
                    forced -> "user-override"
                    advertised -> "compatibility-registry"
                    else -> source
                },
            )
        }

        val features = buildMap {
            put(FeatureId.BATTERY, capability(FeatureId.BATTERY, true, false))
            put(FeatureId.WEAR_DETECTION, capability(FeatureId.WEAR_DETECTION, true, false))
            put(FeatureId.NOISE_CONTROL, capability(FeatureId.NOISE_CONTROL, true, true))
            put(
                FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT,
                capability(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT, true, true),
            )
            put(FeatureId.EQUALIZER, capability(FeatureId.EQUALIZER, true, true))
            put(FeatureId.LOW_LATENCY, capability(FeatureId.LOW_LATENCY, true, true))
            put(FeatureId.DUAL_DEVICE_CONNECTION, capability(FeatureId.DUAL_DEVICE_CONNECTION, true, true))
            put(
                FeatureId.SPATIAL_AUDIO,
                capability(
                    FeatureId.SPATIAL_AUDIO,
                    compatibility.spatialAudioSupported,
                    true,
                    advertised = compatibility.spatialAudioSupported,
                ),
            )
            put(
                FeatureId.SPATIAL_SOUND_SWITCH,
                capability(
                    FeatureId.SPATIAL_SOUND_SWITCH,
                    compatibility.spatialSoundSwitchSupported,
                    true,
                    advertised = compatibility.spatialSoundSwitchSupported,
                ),
            )
            put(FeatureId.FIRMWARE_VERSION, capability(FeatureId.FIRMWARE_VERSION, true, false))
        }
        return DeviceProfile(
            identity = candidate.identity,
            vendorId = VendorId.OPPO,
            model = candidate.displayName,
            firmware = null,
            topology = DeviceTopology.EARBUDS_WITH_CASE,
            transport = TransportKind.CLASSIC_SPP,
            protocol = ProtocolDescriptor("OPOv1", commandTable = "oppo-encospp-v1"),
            features = features,
            compatibilityLevel = CompatibilityLevel.DETECTED,
        )
    }

    fun withEvidence(
        profile: DeviceProfile,
        verified: Set<FeatureId>,
        refuted: Set<FeatureId> = emptySet(),
        firmware: String? = profile.firmware,
    ): DeviceProfile {
        val features = profile.features.mapValues { (id, capability) ->
            when (id) {
                in verified -> capability.copy(evidence = EvidenceLevel.VERIFIED, source = "device-response")
                in refuted -> capability.copy(evidence = EvidenceLevel.REFUTED, source = "device-omission")
                else -> capability
            }
        }
        val level = when {
            features.values.any { it.isWritable } -> CompatibilityLevel.CONTROLLED
            features.values.any { it.isReadable && it.evidence == EvidenceLevel.VERIFIED } ->
                CompatibilityLevel.READ_ONLY
            else -> CompatibilityLevel.DETECTED
        }
        return profile.copy(
            firmware = firmware,
            features = features,
            compatibilityLevel = level,
        )
    }

    private fun matches(deviceName: String, supportedModels: Set<String>): Boolean {
        val normalized = normalize(deviceName)
        return normalized.isNotEmpty() && supportedModels.any { normalize(it) == normalized }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
