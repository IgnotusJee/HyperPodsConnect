package moe.chenxy.oppopods.core

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.WearComponent
import moe.chenxy.oppopods.pods.DeviceCapabilities
import moe.chenxy.oppopods.pods.EqPreset
import moe.chenxy.oppopods.pods.SpatialAudioMode as OppoSpatialAudioMode
import moe.chenxy.oppopods.pods.NoiseControlMode as OppoNoiseControlMode
import moe.chenxy.oppopods.pods.WearState as OppoWearState
import moe.chenxy.headphones.core.feature.NoiseControlMode as CoreNoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode as CoreSpatialAudioMode
import moe.chenxy.headphones.core.feature.WearState as CoreWearState

/**
 * Maps the existing OPPO-specific model onto the vendor-neutral core model.
 *
 * Phase 1 only introduces the mapping; the running path still goes through
 * `RfcommController`, so nothing here changes app behaviour yet. Having it
 * compiled and tested first means the later switch-over is a wiring change
 * rather than a translation exercise.
 */
object OppoCoreAdapter {

    private const val OPPO_SPP_UUID = "0000079A-D102-11E1-9B23-00025B00A5A5"

    fun identityOf(address: String, deviceName: String?): DeviceIdentity = DeviceIdentity(
        id = DeviceId.fromAddress(address),
        vendorId = VendorId.OPPO,
        primaryAddress = address,
    ).let { identity ->
        if (deviceName.isNullOrBlank()) identity else identity
    }

    fun sppUuid(): String = OPPO_SPP_UUID

    fun toCoreNoiseControl(mode: OppoNoiseControlMode): CoreNoiseControlMode = when (mode) {
        OppoNoiseControlMode.OFF -> CoreNoiseControlMode.OFF
        OppoNoiseControlMode.NOISE_CANCELLATION -> CoreNoiseControlMode.NOISE_CANCELLATION
        OppoNoiseControlMode.NOISE_CANCELLATION_SMART -> CoreNoiseControlMode.NOISE_CANCELLATION_SMART
        OppoNoiseControlMode.NOISE_CANCELLATION_LIGHT -> CoreNoiseControlMode.NOISE_CANCELLATION_LIGHT
        OppoNoiseControlMode.NOISE_CANCELLATION_MEDIUM -> CoreNoiseControlMode.NOISE_CANCELLATION_MEDIUM
        OppoNoiseControlMode.NOISE_CANCELLATION_DEEP -> CoreNoiseControlMode.NOISE_CANCELLATION_DEEP
        OppoNoiseControlMode.ADAPTIVE -> CoreNoiseControlMode.ADAPTIVE
        OppoNoiseControlMode.TRANSPARENCY -> CoreNoiseControlMode.TRANSPARENCY
    }

    fun toOppoNoiseControl(mode: CoreNoiseControlMode): OppoNoiseControlMode? = when (mode) {
        CoreNoiseControlMode.OFF -> OppoNoiseControlMode.OFF
        CoreNoiseControlMode.NOISE_CANCELLATION -> OppoNoiseControlMode.NOISE_CANCELLATION
        CoreNoiseControlMode.NOISE_CANCELLATION_SMART -> OppoNoiseControlMode.NOISE_CANCELLATION_SMART
        CoreNoiseControlMode.NOISE_CANCELLATION_LIGHT -> OppoNoiseControlMode.NOISE_CANCELLATION_LIGHT
        CoreNoiseControlMode.NOISE_CANCELLATION_MEDIUM -> OppoNoiseControlMode.NOISE_CANCELLATION_MEDIUM
        CoreNoiseControlMode.NOISE_CANCELLATION_DEEP -> OppoNoiseControlMode.NOISE_CANCELLATION_DEEP
        CoreNoiseControlMode.ADAPTIVE -> OppoNoiseControlMode.ADAPTIVE
        CoreNoiseControlMode.TRANSPARENCY -> OppoNoiseControlMode.TRANSPARENCY
    }

    fun toCoreWear(state: OppoWearState): CoreWearState = when (state) {
        OppoWearState.WEARING -> CoreWearState.WEARING
        OppoWearState.REMOVED -> CoreWearState.REMOVED
        OppoWearState.IN_CASE -> CoreWearState.IN_CASE
        OppoWearState.DISCONNECTED -> CoreWearState.UNKNOWN
    }

    fun toCoreSpatialAudio(mode: Int): CoreSpatialAudioMode? = when (mode) {
        OppoSpatialAudioMode.OFF -> CoreSpatialAudioMode.OFF
        OppoSpatialAudioMode.FIXED -> CoreSpatialAudioMode.FIXED
        OppoSpatialAudioMode.HEAD_TRACKING -> CoreSpatialAudioMode.HEAD_TRACKING
        else -> null
    }

    fun toOppoSpatialAudio(mode: CoreSpatialAudioMode): Int = when (mode) {
        CoreSpatialAudioMode.OFF -> OppoSpatialAudioMode.OFF
        CoreSpatialAudioMode.FIXED -> OppoSpatialAudioMode.FIXED
        CoreSpatialAudioMode.HEAD_TRACKING -> OppoSpatialAudioMode.HEAD_TRACKING
    }

    /** Vendor preset ids stay vendor-side; the domain only sees an opaque id. */
    fun toCoreEqPreset(presetId: Int): EqualizerPreset =
        EqualizerPreset(id = "oppo:$presetId", displayName = eqDisplayName(presetId))

    fun toOppoEqPreset(preset: EqualizerPreset): Int? =
        preset.id.removePrefix("oppo:").toIntOrNull()?.takeIf { it in EqPreset.ALL }

    private fun eqDisplayName(presetId: Int): String? = when (presetId) {
        EqPreset.AUTHENTIC -> "Authentic"
        EqPreset.DETAIL -> "Detail"
        EqPreset.VOCAL -> "Vocal"
        EqPreset.BASS -> "Bass"
        EqPreset.DYNAUDIO -> "Dynaudio"
        else -> null
    }

    fun wearComponentOf(isLeft: Boolean): WearComponent =
        if (isLeft) WearComponent.LEFT else WearComponent.RIGHT

    /**
     * Converts the current name-based capability guess into core capabilities.
     *
     * Everything derived this way is [EvidenceLevel.ASSUMED], which makes it
     * non-writable by design. The matcher decides support by testing whether a
     * whitelisted model name is a substring of the device name, so a device
     * whose name merely extends a listed one inherits its capabilities — an Enco
     * Air5s picks up the Enco Air5 entry that way. A capture proved that
     * particular inheritance happens to be right, which is exactly why it cannot
     * be trusted in general: the rule got the right answer for the wrong reason.
     * Real evidence has to come from a capability handshake, and until it does
     * these entries only decide what to show, never what to send.
     */
    fun toCoreCapabilities(
        capabilities: DeviceCapabilities,
        transport: TransportKind = TransportKind.CLASSIC_SPP,
    ): Map<FeatureId, FeatureCapability> {
        val transports = setOf(transport)

        fun assumed(featureId: FeatureId, supported: Boolean, writable: Boolean = true) =
            FeatureCapability(
                featureId = featureId,
                canRead = supported,
                canWrite = supported && writable,
                evidence = EvidenceLevel.ASSUMED,
                availableOnTransports = transports,
                requiresReadback = true,
                source = "name-whitelist",
            )

        return buildMap {
            // Always present on this protocol family, still only assumed until a
            // handshake says so.
            put(FeatureId.BATTERY, assumed(FeatureId.BATTERY, supported = true, writable = false))
            put(FeatureId.WEAR_DETECTION, assumed(FeatureId.WEAR_DETECTION, supported = true, writable = false))
            put(FeatureId.NOISE_CONTROL, assumed(FeatureId.NOISE_CONTROL, supported = true))
            put(FeatureId.EQUALIZER, assumed(FeatureId.EQUALIZER, supported = true))
            put(FeatureId.LOW_LATENCY, assumed(FeatureId.LOW_LATENCY, supported = true))
            put(FeatureId.DUAL_DEVICE_CONNECTION, assumed(FeatureId.DUAL_DEVICE_CONNECTION, supported = true))

            put(
                FeatureId.SPATIAL_AUDIO,
                assumed(FeatureId.SPATIAL_AUDIO, supported = capabilities.spatialAudioSupported),
            )
            put(
                FeatureId.SPATIAL_SOUND_SWITCH,
                assumed(FeatureId.SPATIAL_SOUND_SWITCH, supported = capabilities.spatialSoundSwitchSupported),
            )
        }
    }
}
