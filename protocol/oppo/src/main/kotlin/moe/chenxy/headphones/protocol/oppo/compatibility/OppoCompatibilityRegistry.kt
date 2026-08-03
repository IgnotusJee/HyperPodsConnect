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
    val equalizerValues: Map<String, String>,
    internal val forcedFeatures: Set<FeatureId>,
)

/**
 * Builds the OPPO protocol baseline without inspecting the Bluetooth name.
 * Device responses and the official capability bitmap promote individual
 * features later. Explicit overrides exist only for protocol variants that
 * cannot be safely distinguished from a read-only state report.
 */
object OppoCompatibilityRegistry {

    private val genericEqualizerValues = linkedMapOf(
        "oppo:0" to "Authentic",
        "oppo:1" to "Detail",
        "oppo:2" to "Vocal",
        "oppo:3" to "Bass",
        // The vendor table is intentionally non-contiguous: slots 4..6
        // belong to other products, while Dynaudio is preset 7.
        "oppo:7" to "Dynaudio",
    )

    fun resolve(
        overrides: OppoCompatibilityOverrides = OppoCompatibilityOverrides(),
    ): OppoCompatibilityProfile {
        val forced = buildSet {
            if (overrides.adaptiveSupported != null) add(FeatureId.NOISE_CONTROL)
            if (overrides.spatialAudioSupported != null) add(FeatureId.SPATIAL_AUDIO)
            if (overrides.spatialSoundSwitchSupported != null) add(FeatureId.SPATIAL_SOUND_SWITCH)
        }
        return OppoCompatibilityProfile(
            adaptiveSupported = overrides.adaptiveSupported
                ?: false,
            spatialAudioSupported = overrides.spatialAudioSupported
                ?: false,
            spatialSoundSwitchSupported = overrides.spatialSoundSwitchSupported
                ?: false,
            ancEncoding = overrides.ancEncoding
                ?: OppoAncEncoding.STANDARD,
            lowLatencyStrategy = overrides.lowLatencyStrategy,
            equalizerValues = genericEqualizerValues,
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
            allowedValues: Set<String> = emptySet(),
            valueLabels: Map<String, String> = emptyMap(),
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
                allowedValues = allowedValues,
                valueLabels = valueLabels,
                source = when {
                    forced -> "user-override"
                    advertised -> "compatibility-registry"
                    else -> source
                },
            )
        }

        val features = buildMap {
            put(FeatureId.BATTERY, capability(FeatureId.BATTERY, false, false))
            put(FeatureId.WEAR_DETECTION, capability(FeatureId.WEAR_DETECTION, false, false))
            val noiseControlValues = linkedMapOf(
                "OFF" to "Off",
                "NOISE_CANCELLATION" to "Noise cancellation",
                "TRANSPARENCY" to "Transparency",
            ).apply {
                if (compatibility.adaptiveSupported) put("ADAPTIVE", "Adaptive")
            }
            put(
                FeatureId.NOISE_CONTROL,
                capability(
                    FeatureId.NOISE_CONTROL,
                    false,
                    false,
                    allowedValues = noiseControlValues.keys,
                    valueLabels = noiseControlValues,
                ),
            )
            put(
                FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT,
                capability(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT, false, false),
            )
            val equalizerValues = compatibility.equalizerValues
            put(
                FeatureId.EQUALIZER,
                capability(
                    FeatureId.EQUALIZER,
                    false,
                    false,
                    allowedValues = equalizerValues.keys,
                    valueLabels = equalizerValues,
                ),
            )
            put(FeatureId.LOW_LATENCY, capability(FeatureId.LOW_LATENCY, false, false))
            put(FeatureId.DUAL_DEVICE_CONNECTION, capability(FeatureId.DUAL_DEVICE_CONNECTION, false, false))
            put(
                FeatureId.SPATIAL_AUDIO,
                capability(
                    FeatureId.SPATIAL_AUDIO,
                    compatibility.spatialAudioSupported,
                    true,
                    advertised = compatibility.spatialAudioSupported,
                    allowedValues = setOf("OFF", "FIXED", "HEAD_TRACKING"),
                    valueLabels = mapOf(
                        "OFF" to "Off",
                        "FIXED" to "Fixed",
                        "HEAD_TRACKING" to "Head tracking",
                    ),
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
            put(FeatureId.FIRMWARE_VERSION, capability(FeatureId.FIRMWARE_VERSION, false, false))
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
        writable: Set<FeatureId> = emptySet(),
        refuted: Set<FeatureId> = emptySet(),
        firmware: String? = profile.firmware,
    ): DeviceProfile {
        val features = profile.features.mapValues { (id, capability) ->
            when (id) {
                in verified -> capability.copy(
                    canRead = true,
                    canWrite = id in writable,
                    evidence = EvidenceLevel.VERIFIED,
                    source = "device-response",
                )
                in refuted -> capability.copy(
                    canRead = false,
                    canWrite = false,
                    evidence = EvidenceLevel.REFUTED,
                    source = "device-omission",
                )
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

}
