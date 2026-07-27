package moe.chenxy.oppopods.integration

import android.content.Context
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.oppopods.ipc.HeadphoneCommandClient
import moe.chenxy.oppopods.ui.state.HeadphoneUiState
import moe.chenxy.oppopods.ui.state.HeadphoneUiStore

/**
 * Brand-neutral boundary between HyperOS' integer APIs and the domain model.
 *
 * The name fallback is only used before the first engine snapshot so the
 * existing OPPO bootstrap path remains available during migration. Once a
 * profile exists, DeviceId/address and capability data are authoritative.
 */
object HyperOsHeadphoneAdapter {
    val state: HeadphoneUiState get() = HeadphoneUiStore.state.value

    fun supports(address: String?, name: String? = null): Boolean {
        if (state.supportsAddress(address)) return true
        if (state.deviceId != null) return false
        return name?.contains("oppo", ignoreCase = true) == true
    }

    fun execute(context: Context, command: FeatureCommand): String =
        HeadphoneCommandClient.execute(context, command)

    fun setNoiseControl(context: Context, platformMode: Int): String =
        execute(context, FeatureCommand.SetNoiseControl(noiseControlFromPlatform(platformMode)))

    fun setSpatialAudio(context: Context, platformMode: Int): String =
        execute(context, FeatureCommand.SetSpatialAudio(spatialAudioFromPlatform(platformMode)))

    fun noiseControlFromPlatform(mode: Int): NoiseControlMode = when (mode) {
        2 -> NoiseControlMode.NOISE_CANCELLATION
        3 -> NoiseControlMode.TRANSPARENCY
        4 -> NoiseControlMode.ADAPTIVE
        5 -> NoiseControlMode.NOISE_CANCELLATION_SMART
        6 -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
        7 -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
        8 -> NoiseControlMode.NOISE_CANCELLATION_DEEP
        else -> NoiseControlMode.OFF
    }

    fun spatialAudioFromPlatform(mode: Int): SpatialAudioMode = when (mode) {
        1 -> SpatialAudioMode.FIXED
        2, 9, 11 -> SpatialAudioMode.HEAD_TRACKING
        else -> SpatialAudioMode.OFF
    }

    fun miuiRefreshPayload(): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue("LEFT", "SINGLE")
        values[1] = miuiBatteryValue("RIGHT")
        values[2] = miuiBatteryValue("CASE")
        val noise = state.feature(FeatureId.NOISE_CONTROL.name)?.displayed
        val vocalEnhancement = state
            .feature(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT.name)
            ?.displayed?.toBooleanStrictOrNull() == true
        values[7] = when (noise) {
            "NOISE_CANCELLATION_SMART" -> "0103"
            "NOISE_CANCELLATION_LIGHT" -> "0101"
            "NOISE_CANCELLATION_MEDIUM" -> "0100"
            "NOISE_CANCELLATION_DEEP" -> "0102"
            "TRANSPARENCY" -> if (vocalEnhancement) "0201" else "0200"
            else -> "0000"
        }
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(primary: String, fallback: String? = null): String {
        val battery = state.batteries[primary] ?: fallback?.let(state.batteries::get)
            ?: return "255"
        val level = battery.level.coerceIn(0, 100)
        return (if (battery.charging) level or 128 else level).toString()
    }
}
