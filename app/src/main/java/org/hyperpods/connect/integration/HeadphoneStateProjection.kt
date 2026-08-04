package org.hyperpods.connect.integration

import org.hyperpods.connect.ui.state.HeadphoneUiState
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation
import org.hyperpods.connect.integration.BatterySlotPresentation

/** Vendor-neutral state projected into the DTOs still required by HyperOS APIs. */
data class HeadphoneIntegrationState(
    val address: String?,
    val name: String?,
    val connected: Boolean,
    val battery: HeadphoneBatteryPresentation?,
    val anc: Int?,
    val transparencyVocalEnhancement: Boolean?,
    val spatialAudioMode: Int?,
)

fun HeadphoneUiState.toIntegrationState() = HeadphoneIntegrationState(
    address = address,
    name = title.takeIf(String::isNotBlank),
    connected = connected,
    battery = batteries.takeIf(Map<*, *>::isNotEmpty)?.let { values ->
        HeadphoneBatteryPresentation(
            left = (values["LEFT"] ?: values["SINGLE"])?.let {
                BatterySlotPresentation(it.level, it.charging, true, 0)
            },
            right = values["RIGHT"]?.let {
                BatterySlotPresentation(it.level, it.charging, true, 0)
            },
            case = values["CASE"]?.let {
                BatterySlotPresentation(it.level, it.charging, true, 0)
            },
            single = values["SINGLE"]?.let {
                BatterySlotPresentation(it.level, it.charging, true, 0)
            },
            deviceName = title.takeIf(String::isNotBlank),
            topology = topology,
            canCycleNoiseControl = features["NOISE_CONTROL"]?.writable == true,
        )
    },
    anc = features["NOISE_CONTROL"]?.confirmed?.let(::noiseControlValue),
    transparencyVocalEnhancement = features["TRANSPARENCY_VOCAL_ENHANCEMENT"]
        ?.confirmed?.toBooleanStrictOrNull(),
    spatialAudioMode = features["SPATIAL_AUDIO"]?.confirmed?.let {
        when (it) {
            "FIXED" -> 1
            "HEAD_TRACKING" -> 2
            else -> 0
        }
    },
)

private fun noiseControlValue(value: String): Int = when (value) {
    "NOISE_CANCELLATION" -> 2
    "TRANSPARENCY" -> 3
    "ADAPTIVE" -> 4
    "NOISE_CANCELLATION_SMART" -> 5
    "NOISE_CANCELLATION_LIGHT" -> 6
    "NOISE_CANCELLATION_MEDIUM" -> 7
    "NOISE_CANCELLATION_DEEP" -> 8
    else -> 1
}
