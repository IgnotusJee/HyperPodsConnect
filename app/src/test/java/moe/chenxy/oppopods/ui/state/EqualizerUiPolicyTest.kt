package moe.chenxy.oppopods.ui.state

import moe.chenxy.headphones.core.feature.EqualizerBandSpec
import moe.chenxy.headphones.core.feature.EqualizerCurveSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerUiPolicyTest {
    @Test
    fun `OPPO layout separates official presets from user-created slots`() {
        val feature = feature(
            selected = "oppo:eq:custom:5",
            options = listOf(
                "oppo:0" to "Ultimate sound",
                "oppo:2" to "Pure vocals",
                "oppo:1" to "Powerful bass",
                "oppo:eq:custom:5" to "Custom 1",
            ),
        )
        val model = buildEqualizerUiModel(
            vendorId = "OPPO",
            feature = feature,
            curveState = curveState(
                setOf("oppo:eq:custom:5", OPPO_CUSTOM_EQ_CREATION_SLOT),
            ),
        )

        assertEquals(EqualizerBrandLayout.OPPO, model.layout)
        assertEquals(listOf("oppo:0", "oppo:2", "oppo:1"), model.presets.map { it.value })
        assertEquals(listOf("oppo:eq:custom:5"), model.customPresets.map { it.value })
        assertEquals(OPPO_CUSTOM_EQ_CREATION_SLOT, model.creationSlotId)
        assertTrue(model.selectedIsEditable)
    }

    @Test
    fun `Sony layout keeps one ordered list and only tail custom slots editable`() {
        val options = listOf(
            "sony:eq:00" to "Off",
            "sony:eq:10" to "Bright",
            "sony:eq:a0" to "Manual",
            "sony:eq:a1" to "Custom 1",
            "sony:eq:a2" to "Custom 2",
        )
        val model = buildEqualizerUiModel(
            vendorId = "SONY",
            feature = feature("sony:eq:a1", options),
            curveState = curveState(setOf("sony:eq:a0", "sony:eq:a1", "sony:eq:a2")),
        )

        assertEquals(EqualizerBrandLayout.SONY, model.layout)
        assertEquals(options.map { it.first }, model.presets.map { it.value })
        assertTrue(model.customPresets.isEmpty())
        assertEquals(setOf("sony:eq:a0", "sony:eq:a1", "sony:eq:a2"), model.editablePresetIds)
        assertTrue(model.selectedIsEditable)
    }

    private fun feature(
        selected: String,
        options: List<Pair<String, String>>,
    ) = UiFeatureState(
        id = "EQUALIZER",
        readable = true,
        writable = true,
        confirmed = selected,
        pending = null,
        stale = false,
        options = options.map { (value, label) -> UiFeatureOption(value, label) },
        operation = null,
    )

    private fun curveState(writableSlots: Set<String>) = UiEqualizerCurveState(
        confirmed = null,
        pending = null,
        spec = EqualizerCurveSpec(
            bands = listOf(EqualizerBandSpec("band", "1 kHz", -6, 6)),
            writableSlotIds = writableSlots,
        ),
        stale = false,
    )
}
