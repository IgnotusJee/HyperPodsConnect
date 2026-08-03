package moe.chenxy.oppopods.integration

import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.oppopods.ui.state.HeadphoneUiState

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
    MiLinkCardCapabilities(
        showNoiseControl = state.feature(FeatureId.NOISE_CONTROL.name)?.writable == true,
        // Ring find is intentionally unavailable until it becomes a real core capability.
        showRingFind = false,
    )

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
