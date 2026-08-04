package org.hyperpods.connect.ui.state

internal enum class EqualizerEditorLayout {
    CUSTOM_SECTIONS,
    CLEAR_BASS,
    GENERIC,
}

internal data class EqualizerUiModel(
    val layout: EqualizerEditorLayout,
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
    feature: UiFeatureState,
    curveState: UiEqualizerCurveState?,
): EqualizerUiModel {
    val writableSlots = curveState?.spec?.writableSlotIds.orEmpty()
    val optionIds = feature.options.mapTo(linkedSetOf(), UiFeatureOption::value)
    val creationSlot = (writableSlots - optionIds).singleOrNull()
    val hasClearBass = curveState?.spec?.bands?.any {
        it.kind == moe.chenxy.headphones.core.feature.EqualizerBandKind.CLEAR_BASS
    } == true
    val layout = when {
        creationSlot != null -> EqualizerEditorLayout.CUSTOM_SECTIONS
        hasClearBass -> EqualizerEditorLayout.CLEAR_BASS
        else -> EqualizerEditorLayout.GENERIC
    }
    val customOptions = if (layout == EqualizerEditorLayout.CUSTOM_SECTIONS) {
        feature.options.filter { it.value in writableSlots }
    } else {
        emptyList()
    }
    val presetOptions = if (layout == EqualizerEditorLayout.CUSTOM_SECTIONS) {
        feature.options - customOptions.toSet()
    } else {
        feature.options
    }
    return EqualizerUiModel(
        layout = layout,
        presets = presetOptions,
        customPresets = customOptions,
        selectedId = feature.displayed,
        editablePresetIds = writableSlots - setOfNotNull(creationSlot),
        creationSlotId = creationSlot,
    )
}
