package org.hyperpods.connect.integration

import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import org.hyperpods.connect.ui.state.HeadphoneUiState
import org.hyperpods.connect.ui.state.UiFeatureState

internal const val MILINK_GENERIC_DEVICE_TYPE = 0
internal const val MILINK_HEADPHONES_DEVICE_TYPE = 6
internal const val MILINK_HEADPHONES_DEVICE_ID = "0A20"

internal const val INTEGRATION_ANC_OFF = 1
internal const val INTEGRATION_ANC_ON = 2
internal const val INTEGRATION_ANC_TRANSPARENCY = 3

internal const val MILINK_RUNTIME_ANC_OFF = 0
internal const val MILINK_RUNTIME_ANC_ON = 1
internal const val MILINK_RUNTIME_ANC_TRANSPARENCY = 2

internal const val MILINK_DISPLAY_ANC_ON = 0
internal const val MILINK_DISPLAY_ANC_TRANSPARENCY = 1
internal const val MILINK_DISPLAY_ANC_OFF = 2

internal data class MiLinkDevicePresentation(
    val isTws: Boolean,
    val deviceId: String,
    val deviceType: Int,
)

internal data class MiLinkCardCapabilities(
    val showNoiseControl: Boolean,
    val noiseControlWritable: Boolean,
    val allowedNoiseControlValues: Set<String>,
    val showSpatialAudio: Boolean,
    val spatialAudioWritable: Boolean,
    val showRingFind: Boolean,
)

/**
 * MiLink has no brand-neutral form-factor API. Its own runtime represents a
 * single-battery over-ear device as type 6 and derives that type from the
 * AirPods Max protocol id. Keep this translation at the HyperOS boundary so
 * the domain model remains vendor and platform independent.
 */
internal fun miLinkDevicePresentation(
    isTws: Boolean,
    configuredTwsDeviceId: String,
): MiLinkDevicePresentation = if (isTws) {
    MiLinkDevicePresentation(
        isTws = true,
        deviceId = configuredTwsDeviceId,
        deviceType = MILINK_GENERIC_DEVICE_TYPE,
    )
} else {
    MiLinkDevicePresentation(
        isTws = false,
        deviceId = MILINK_HEADPHONES_DEVICE_ID,
        deviceType = MILINK_HEADPHONES_DEVICE_TYPE,
    )
}

/**
 * MiLink's type-6 branch assumes AirPods Max and hard-codes its cards. Replace
 * that model-specific assumption with the capabilities reported by our driver.
 */
internal fun miLinkCardCapabilities(state: HeadphoneUiState): MiLinkCardCapabilities =
    state.run {
        val noiseControl = feature(FeatureId.NOISE_CONTROL.name)
        val spatialAudio = feature(FeatureId.SPATIAL_AUDIO.name)
        MiLinkCardCapabilities(
        showNoiseControl = noiseControl?.visible == true,
        noiseControlWritable = noiseControl?.writable == true,
        allowedNoiseControlValues = noiseControl?.options?.mapTo(linkedSetOf()) { it.value }.orEmpty(),
        showSpatialAudio = spatialAudio?.visible == true,
        spatialAudioWritable = spatialAudio?.writable == true,
        // Ring find is intentionally unavailable until it becomes a real core capability.
        showRingFind = false,
    )
    }

/** Raw mode returned by com.miui.headset.api.HeadsetInfo and AncBatteryController. */
internal fun miLinkRuntimeAncState(integrationAnc: Int): Int = when (integrationAnc) {
    2, 5, 6, 7, 8 -> MILINK_RUNTIME_ANC_ON
    INTEGRATION_ANC_TRANSPARENCY -> MILINK_RUNTIME_ANC_TRANSPARENCY
    else -> MILINK_RUNTIME_ANC_OFF
}

/** Mode consumed directly by HeadSetsDetail's three-button presentation. */
internal fun miLinkDisplayAncMode(integrationAnc: Int): Int = when (integrationAnc) {
    2, 5, 6, 7, 8 -> MILINK_DISPLAY_ANC_ON
    INTEGRATION_ANC_TRANSPARENCY -> MILINK_DISPLAY_ANC_TRANSPARENCY
    else -> MILINK_DISPLAY_ANC_OFF
}

/** Runtime command received by AncBatteryController.setAncStateBlock. */
internal fun integrationAncFromMiLinkRuntime(runtimeMode: Int): Int = when (runtimeMode) {
    MILINK_RUNTIME_ANC_ON -> INTEGRATION_ANC_ON
    MILINK_RUNTIME_ANC_TRANSPARENCY -> INTEGRATION_ANC_TRANSPARENCY
    else -> INTEGRATION_ANC_OFF
}

internal fun miLinkIntegrationAncForCommand(
    requestedIntegrationMode: Int,
    feature: UiFeatureState?,
): Int? {
    if (feature?.visible != true || !feature.writable) return null
    val allowed = feature.options.map { it.value }
    val selected = when (requestedIntegrationMode) {
        INTEGRATION_ANC_OFF -> "OFF".takeIf(allowed::contains)
        INTEGRATION_ANC_TRANSPARENCY -> "TRANSPARENCY".takeIf(allowed::contains)
        INTEGRATION_ANC_ON -> feature.confirmed
            ?.takeIf { it.startsWith("NOISE_CANCELLATION") && it in allowed }
            ?: allowed.firstOrNull { it.startsWith("NOISE_CANCELLATION") }
        else -> null
    } ?: return null
    return when (selected) {
        "OFF" -> INTEGRATION_ANC_OFF
        "TRANSPARENCY" -> INTEGRATION_ANC_TRANSPARENCY
        "NOISE_CANCELLATION_SMART" -> 5
        "NOISE_CANCELLATION_LIGHT" -> 6
        "NOISE_CANCELLATION_MEDIUM" -> 7
        "NOISE_CANCELLATION_DEEP" -> 8
        "NOISE_CANCELLATION" -> INTEGRATION_ANC_ON
        else -> null
    }
}

/** Maps MiLink's binary switch to an allowed domain mode without inventing capabilities. */
internal fun miLinkSpatialModeForBinary(
    binaryState: Int,
    feature: UiFeatureState?,
): SpatialAudioMode? {
    if (feature?.visible != true || !feature.writable) return null
    val allowed = feature.options.mapTo(linkedSetOf()) { it.value }
    return when (binaryState) {
        0 -> SpatialAudioMode.OFF.takeIf { it.name in allowed }
        1 -> when {
            SpatialAudioMode.FIXED.name in allowed -> SpatialAudioMode.FIXED
            SpatialAudioMode.HEAD_TRACKING.name in allowed -> SpatialAudioMode.HEAD_TRACKING
            else -> null
        }
        else -> null
    }
}
