package org.hyperpods.connect.integration

import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import org.hyperpods.connect.ui.state.HeadphoneUiState

enum class PresentationBatterySlot { SINGLE, LEFT, RIGHT, CASE }

data class BatteryPresentation(
    val slot: PresentationBatterySlot,
    val level: Int,
    val charging: Boolean,
)

data class FeaturePresentation(
    val visible: Boolean = false,
    val writable: Boolean = false,
    val value: String? = null,
    val allowedValues: Set<String> = emptySet(),
    val valueLabels: Map<String, String> = emptyMap(),
)

data class HeadphonePresentationState(
    val deviceId: String? = null,
    val generationId: Long = -1L,
    val emittedAtMillis: Long = -1L,
    val address: String? = null,
    val memberAddresses: Set<String> = emptySet(),
    val title: String = "",
    val connected: Boolean = false,
    val topology: String? = null,
    val batteries: Map<PresentationBatterySlot, BatteryPresentation> = emptyMap(),
    val wearing: Map<PresentationBatterySlot, String> = emptyMap(),
    val features: Map<FeatureId, FeaturePresentation> = emptyMap(),
) {
    fun feature(id: FeatureId): FeaturePresentation = features[id] ?: FeaturePresentation()

    fun supportsAddress(value: String?): Boolean = value != null &&
        (address?.equals(value, ignoreCase = true) == true || memberAddresses.any {
            it.equals(value, ignoreCase = true)
        })

    val noiseControlModes: Set<NoiseControlMode>
        get() = feature(FeatureId.NOISE_CONTROL).allowedValues.mapNotNullTo(linkedSetOf()) {
            runCatching { NoiseControlMode.valueOf(it) }.getOrNull()
        }
}

fun HeadphoneUiState.toPresentationState(): HeadphonePresentationState {
    val projectedFeatures = FeatureId.entries.associateWith { id ->
        val feature = features[id.name]
        FeaturePresentation(
            visible = feature?.visible == true,
            writable = feature?.writable == true,
            value = feature?.displayed,
            allowedValues = feature?.options?.mapTo(linkedSetOf()) { it.value }.orEmpty(),
            valueLabels = feature?.options?.associate { it.value to it.label }.orEmpty(),
        )
    }
    return HeadphonePresentationState(
        deviceId = deviceId,
        generationId = generationId,
        emittedAtMillis = emittedAtMillis,
        address = address,
        memberAddresses = memberAddresses,
        title = title,
        connected = connected,
        topology = topology,
        batteries = batteries.mapNotNull { (component, value) ->
            component.toPresentationBatterySlot()?.let { slot ->
                slot to BatteryPresentation(slot, value.level.coerceIn(0, 100), value.charging)
            }
        }.toMap(),
        wearing = wearing.mapNotNull { (component, state) ->
            component.toPresentationBatterySlot()?.let { it to state }
        }.toMap(),
        features = projectedFeatures,
    )
}

private fun String.toPresentationBatterySlot(): PresentationBatterySlot? =
    runCatching { PresentationBatterySlot.valueOf(this) }.getOrNull()
