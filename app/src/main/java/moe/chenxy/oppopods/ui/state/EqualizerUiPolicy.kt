package moe.chenxy.oppopods.ui.state

internal const val OPPO_CUSTOM_EQ_PREFIX = "oppo:eq:custom:"
internal const val OPPO_CUSTOM_EQ_CREATION_SLOT = "oppo:eq:custom:new"

internal enum class EqualizerBrandLayout {
    OPPO,
    SONY,
    GENERIC,
}

internal data class EqualizerUiModel(
    val layout: EqualizerBrandLayout,
    val presets: List<UiFeatureOption>,
    val customPresets: List<UiFeatureOption>,
    val selectedId: String?,
    val editablePresetIds: Set<String>,
    val creationSlotId: String?,
) {
    val selectedOption: UiFeatureOption?
        get() = (presets + customPresets).firstOrNull { it.value == selectedId }

    val selectedIsEditable: Boolean
        get() = selectedId != null && selectedId in editablePresetIds
}

internal fun buildEqualizerUiModel(
    vendorId: String?,
    feature: UiFeatureState,
    curveState: UiEqualizerCurveState?,
): EqualizerUiModel {
    val layout = when (vendorId?.lowercase()) {
        "oppo" -> EqualizerBrandLayout.OPPO
        "sony" -> EqualizerBrandLayout.SONY
        else -> EqualizerBrandLayout.GENERIC
    }
    val writableSlots = curveState?.spec?.writableSlotIds.orEmpty()
    val creationSlot = OPPO_CUSTOM_EQ_CREATION_SLOT.takeIf(writableSlots::contains)
    val customOptions = when (layout) {
        EqualizerBrandLayout.OPPO -> feature.options.filter {
            it.value.startsWith(OPPO_CUSTOM_EQ_PREFIX) && it.value != OPPO_CUSTOM_EQ_CREATION_SLOT
        }
        else -> emptyList()
    }
    val presetOptions = if (layout == EqualizerBrandLayout.OPPO) {
        feature.options - customOptions.toSet()
    } else {
        feature.options
    }
    return EqualizerUiModel(
        layout = layout,
        presets = presetOptions,
        customPresets = customOptions,
        selectedId = feature.displayed,
        editablePresetIds = writableSlots - OPPO_CUSTOM_EQ_CREATION_SLOT,
        creationSlotId = creationSlot,
    )
}
