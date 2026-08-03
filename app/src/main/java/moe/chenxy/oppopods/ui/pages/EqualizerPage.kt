package moe.chenxy.oppopods.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.chenxy.headphones.core.feature.EqualizerBandKind
import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.ui.state.EqualizerBrandLayout
import moe.chenxy.oppopods.ui.state.UiEqualizerCurveState
import moe.chenxy.oppopods.ui.state.UiFeatureOption
import moe.chenxy.oppopods.ui.state.UiFeatureState
import moe.chenxy.oppopods.ui.state.buildEqualizerUiModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun EqualizerPage(
    vendorId: String?,
    deviceName: String?,
    feature: UiFeatureState,
    curveState: UiEqualizerCurveState?,
    onPresetChange: (String) -> Unit,
    onCurveChange: (EqualizerCurve) -> Unit,
    onPresetRename: (String, String) -> Unit,
    onPresetDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val model = buildEqualizerUiModel(vendorId, feature, curveState)
    var editingCustom by remember { mutableStateOf<UiFeatureOption?>(null) }
    var deleteTarget by remember { mutableStateOf<UiFeatureOption?>(null) }
    var customName by remember { mutableStateOf("") }
    val editorCurve = curveState?.displayed?.takeIf {
        it.slotId == model.selectedId && model.selectedIsEditable
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (model.layout == EqualizerBrandLayout.OPPO) {
            item { SectionTitle(stringResource(R.string.eq_recommended_title)) }
        }
        item {
            Card {
                model.presets.forEach { option ->
                    EqualizerOptionRow(
                        label = option.label,
                        selected = option.value == model.selectedId,
                        editable = option.value in model.editablePresetIds,
                        enabled = feature.writable,
                        onClick = { onPresetChange(option.value) },
                    )
                }
            }
        }

        if (model.layout == EqualizerBrandLayout.OPPO &&
            (model.customPresets.isNotEmpty() || model.creationSlotId != null)
        ) {
            item { SectionTitle(stringResource(R.string.eq_custom_section_title)) }
            item {
                Card {
                    model.customPresets.forEach { option ->
                        EqualizerOptionRow(
                            label = option.label,
                            selected = option.value == model.selectedId,
                            editable = true,
                            enabled = feature.writable,
                            onClick = { onPresetChange(option.value) },
                            actionLabel = stringResource(R.string.eq_custom_manage),
                            onAction = {
                                customName = option.label
                                editingCustom = option
                            },
                        )
                    }
                    model.creationSlotId?.let { creationSlotId ->
                        TextButton(
                            text = stringResource(R.string.eq_custom_add),
                            onClick = {
                                val spec = curveState?.spec ?: return@TextButton
                                onCurveChange(
                                    EqualizerCurve(
                                        slotId = creationSlotId,
                                        gains = spec.bands.map { band ->
                                            0.coerceIn(band.minGain, band.maxGain)
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = feature.writable && curveState != null && !curveState.stale,
                        )
                    }
                }
            }
        }

        if (editorCurve != null) {
            item {
                Card {
                    EqualizerCurveEditor(
                        state = curveState,
                        curve = editorCurve,
                        useOppoLayout = model.layout == EqualizerBrandLayout.OPPO,
                        useSonyLayout = model.layout == EqualizerBrandLayout.SONY,
                        enabled = feature.writable && !curveState.stale,
                        onCurveChange = onCurveChange,
                    )
                }
            }
        }
    }

    val editTarget = editingCustom
    val normalizedName = customName.trim()
    val validName = normalizedName.isNotEmpty() &&
        normalizedName.toByteArray(Charsets.UTF_8).size <= MAX_CUSTOM_EQ_NAME_BYTES
    OverlayDialog(
        title = stringResource(R.string.eq_custom_edit_title),
        summary = stringResource(R.string.eq_custom_name_hint),
        show = editTarget != null,
        onDismissRequest = { editingCustom = null },
    ) {
        TextField(
            value = customName,
            onValueChange = { value ->
                if (value.toByteArray(Charsets.UTF_8).size <= MAX_CUSTOM_EQ_NAME_BYTES) {
                    customName = value
                }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.eq_custom_delete),
                onClick = {
                    deleteTarget = editTarget
                    editingCustom = null
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { editingCustom = null },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.save),
                onClick = {
                    editTarget?.let { onPresetRename(it.value, normalizedName) }
                    editingCustom = null
                },
                modifier = Modifier.weight(1f),
                enabled = validName && normalizedName != editTarget?.label,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    val deleting = deleteTarget
    OverlayDialog(
        title = stringResource(R.string.eq_custom_delete_title),
        summary = deleting?.let {
            stringResource(R.string.eq_custom_delete_summary, it.label)
        },
        show = deleting != null,
        onDismissRequest = { deleteTarget = null },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { deleteTarget = null },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.eq_custom_delete),
                onClick = {
                    deleting?.let { onPresetDelete(it.value) }
                    deleteTarget = null
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun EqualizerOptionRow(
    label: String,
    selected: Boolean,
    editable: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            if (editable) {
                Text(
                    text = stringResource(R.string.eq_editable_summary),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
        }
        if (selected) {
            Text(
                text = "✓",
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = actionLabel,
                onClick = onAction,
                enabled = enabled,
            )
        }
    }
}

private const val MAX_CUSTOM_EQ_NAME_BYTES = 128

@Composable
private fun EqualizerCurveEditor(
    state: UiEqualizerCurveState,
    curve: EqualizerCurve,
    useOppoLayout: Boolean,
    useSonyLayout: Boolean,
    enabled: Boolean,
    onCurveChange: (EqualizerCurve) -> Unit,
) {
    if (curve.slotId !in state.spec.writableSlotIds) return
    if (curve.gains.size != state.spec.bands.size) return
    val gains = remember(curve) { curve.gains.toMutableStateList() }
    val updateGain = { index: Int, value: Float ->
        val band = state.spec.bands[index]
        val stepIndex = ((value - band.minGain) / band.step).roundToInt()
        gains[index] = (band.minGain + stepIndex * band.step)
            .coerceIn(band.minGain, band.maxGain)
    }
    val commitChanges = {
        val updated = curve.copy(gains = gains.toList())
        if (updated != curve) onCurveChange(updated)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.eq_custom_title),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.eq_custom_summary),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            useOppoLayout -> VerticalEqualizerBands(
                state = state,
                gains = gains,
                indices = state.spec.bands.indices.toList(),
                enabled = enabled,
                onGainChange = updateGain,
                onValueChangeFinished = commitChanges,
            )

            useSonyLayout -> {
                val standardIndices = state.spec.bands.indices.filter { index ->
                    state.spec.bands[index].kind == EqualizerBandKind.STANDARD
                }
                VerticalEqualizerBands(
                    state = state,
                    gains = gains,
                    indices = standardIndices,
                    enabled = enabled,
                    onGainChange = updateGain,
                    onValueChangeFinished = commitChanges,
                )
                state.spec.bands.indexOfFirst { it.kind == EqualizerBandKind.CLEAR_BASS }
                    .takeIf { it >= 0 }
                    ?.let { clearBassIndex ->
                        Spacer(Modifier.height(16.dp))
                        HorizontalEqualizerBand(
                            state = state,
                            gains = gains,
                            index = clearBassIndex,
                            enabled = enabled,
                            onGainChange = updateGain,
                            onValueChangeFinished = commitChanges,
                        )
                    }
            }

            else -> {
                state.spec.bands.forEachIndexed { index, band ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = band.displayName, modifier = Modifier.weight(1f))
                        Text(text = signedGain(gains[index]))
                    }
                    Slider(
                        value = gains[index].toFloat(),
                        onValueChange = { value -> updateGain(index, value) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        valueRange = band.minGain.toFloat()..band.maxGain.toFloat(),
                        steps = ((band.maxGain - band.minGain) / band.step - 1).coerceAtLeast(0),
                        onValueChangeFinished = commitChanges,
                        showKeyPoints = true,
                        keyPoints = listOf(0f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalEqualizerBands(
    state: UiEqualizerCurveState,
    gains: List<Int>,
    indices: List<Int>,
    enabled: Boolean,
    onGainChange: (Int, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        indices.forEach { index ->
            val band = state.spec.bands[index]
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = signedGain(gains[index]),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 12.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Slider(
                        value = gains[index].toFloat(),
                        onValueChange = { value -> onGainChange(index, value) },
                        modifier = Modifier
                            // A regular width is clamped by each narrow band column before
                            // rotation. Preserve enough travel for the official-style control.
                            .requiredWidth(150.dp)
                            .rotate(-90f),
                        enabled = enabled,
                        valueRange = band.minGain.toFloat()..band.maxGain.toFloat(),
                        steps = ((band.maxGain - band.minGain) / band.step - 1)
                            .coerceAtLeast(0),
                        onValueChangeFinished = onValueChangeFinished,
                        showKeyPoints = true,
                        keyPoints = listOf(0f),
                    )
                }
                Text(
                    text = band.displayName,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun HorizontalEqualizerBand(
    state: UiEqualizerCurveState,
    gains: List<Int>,
    index: Int,
    enabled: Boolean,
    onGainChange: (Int, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val band = state.spec.bands[index]
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = band.displayName, modifier = Modifier.weight(1f))
        Text(text = signedGain(gains[index]))
    }
    Slider(
        value = gains[index].toFloat(),
        onValueChange = { value -> onGainChange(index, value) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        valueRange = band.minGain.toFloat()..band.maxGain.toFloat(),
        steps = ((band.maxGain - band.minGain) / band.step - 1).coerceAtLeast(0),
        onValueChangeFinished = onValueChangeFinished,
        showKeyPoints = true,
        keyPoints = listOf(0f),
    )
}

private fun signedGain(gain: Int): String = if (gain > 0) "+$gain" else gain.toString()
