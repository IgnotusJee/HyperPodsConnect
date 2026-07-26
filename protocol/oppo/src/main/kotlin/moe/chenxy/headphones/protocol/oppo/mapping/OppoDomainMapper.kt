package moe.chenxy.headphones.protocol.oppo.mapping

import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.BatteryState
import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.WearComponent
import moe.chenxy.headphones.core.feature.WearState
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncMode
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatteryLevel
import moe.chenxy.headphones.protocol.oppo.feature.OppoComponent
import moe.chenxy.headphones.protocol.oppo.feature.OppoWearState

/**
 * The single crossing point from vendor encoding to domain values.
 *
 * Keeping it in one object is what lets the rest of the app avoid OPPO command
 * codes and preset numbers entirely. Nothing above this layer should ever see an
 * [OppoAncMode] or a raw preset id.
 */
object OppoDomainMapper {

    /** Vendor preset ids stay namespaced so two vendors cannot collide. */
    private const val PRESET_NAMESPACE = "oppo"

    fun toBatteryReport(levels: Map<OppoComponent, OppoBatteryLevel>): DeviceReport.Batteries =
        DeviceReport.Batteries(
            levels.entries.associate { (component, value) ->
                toBatteryComponent(component) to BatteryState(value.level, value.charging)
            },
        )

    fun toWearReport(states: Map<OppoComponent, OppoWearState>): DeviceReport.Wearing =
        DeviceReport.Wearing(
            states.entries.mapNotNull { (component, state) ->
                // The case has no wear state of its own in the domain model; it
                // is reported by the device but describes storage, not wearing.
                toWearComponent(component)?.let { it to toWearState(state) }
            }.toMap(),
        )

    fun toNoiseControl(mode: OppoAncMode): NoiseControlMode = when (mode) {
        OppoAncMode.OFF -> NoiseControlMode.OFF
        OppoAncMode.NOISE_CANCELLATION -> NoiseControlMode.NOISE_CANCELLATION
        OppoAncMode.NOISE_CANCELLATION_SMART -> NoiseControlMode.NOISE_CANCELLATION_SMART
        OppoAncMode.NOISE_CANCELLATION_LIGHT -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
        OppoAncMode.NOISE_CANCELLATION_MEDIUM -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
        OppoAncMode.NOISE_CANCELLATION_DEEP -> NoiseControlMode.NOISE_CANCELLATION_DEEP
        OppoAncMode.TRANSPARENCY -> NoiseControlMode.TRANSPARENCY
        OppoAncMode.ADAPTIVE -> NoiseControlMode.ADAPTIVE
    }

    fun toVendorAnc(mode: NoiseControlMode): OppoAncMode? = when (mode) {
        NoiseControlMode.OFF -> OppoAncMode.OFF
        NoiseControlMode.NOISE_CANCELLATION -> OppoAncMode.NOISE_CANCELLATION
        NoiseControlMode.NOISE_CANCELLATION_SMART -> OppoAncMode.NOISE_CANCELLATION_SMART
        NoiseControlMode.NOISE_CANCELLATION_LIGHT -> OppoAncMode.NOISE_CANCELLATION_LIGHT
        NoiseControlMode.NOISE_CANCELLATION_MEDIUM -> OppoAncMode.NOISE_CANCELLATION_MEDIUM
        NoiseControlMode.NOISE_CANCELLATION_DEEP -> OppoAncMode.NOISE_CANCELLATION_DEEP
        NoiseControlMode.TRANSPARENCY -> OppoAncMode.TRANSPARENCY
        NoiseControlMode.ADAPTIVE -> OppoAncMode.ADAPTIVE
    }

    fun toEqualizerPreset(presetId: Int, displayName: String? = null): EqualizerPreset =
        EqualizerPreset(id = "$PRESET_NAMESPACE:$presetId", displayName = displayName)

    fun toVendorPreset(preset: EqualizerPreset): Int? =
        preset.id.substringAfter("$PRESET_NAMESPACE:", missingDelimiterValue = "").toIntOrNull()

    private fun toBatteryComponent(component: OppoComponent): BatteryComponent = when (component) {
        OppoComponent.LEFT -> BatteryComponent.LEFT
        OppoComponent.RIGHT -> BatteryComponent.RIGHT
        OppoComponent.CASE -> BatteryComponent.CASE
    }

    private fun toWearComponent(component: OppoComponent): WearComponent? = when (component) {
        OppoComponent.LEFT -> WearComponent.LEFT
        OppoComponent.RIGHT -> WearComponent.RIGHT
        OppoComponent.CASE -> null
    }

    private fun toWearState(state: OppoWearState): WearState = when (state) {
        OppoWearState.WEARING -> WearState.WEARING
        OppoWearState.REMOVED -> WearState.REMOVED
        OppoWearState.IN_CASE -> WearState.IN_CASE
        OppoWearState.DISCONNECTED -> WearState.UNKNOWN
    }
}
