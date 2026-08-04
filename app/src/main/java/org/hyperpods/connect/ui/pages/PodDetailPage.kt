package org.hyperpods.connect.ui.pages

import android.content.res.Configuration
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.hyperpods.connect.R
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import org.hyperpods.connect.pods.WearStatus
import org.hyperpods.connect.ui.components.AncSwitch
import org.hyperpods.connect.ui.components.PodStatus
import org.hyperpods.connect.ui.state.UiFeatureState
import org.hyperpods.connect.ui.state.UiOperation
import org.hyperpods.connect.ui.state.UiOperationStatus
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.io.File

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    batteryParams: HeadphoneBatteryPresentation,
    batteryTopology: String? = null,
    wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onAmbientSoundLevelChange: (Int) -> Unit = {},
    smartAncLevel: NoiseControlMode? = null,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit = {},
    gameMode: Boolean = false,
    onGameModeChange: (Boolean) -> Unit = {},
    spatialAudioMode: SpatialAudioMode = SpatialAudioMode.OFF,
    onSpatialAudioModeChange: (SpatialAudioMode) -> Unit = {},
    spatialSoundSwitch: Boolean = false,
    onSpatialSoundSwitchChange: (Boolean) -> Unit = {},
    dualDeviceConnection: Boolean = false,
    onDualDeviceConnectionChange: (Boolean) -> Unit = {},
    eqPresetId: String? = null,
    onOpenEqualizer: () -> Unit = {},
    features: Map<String, UiFeatureState> = emptyMap(),
    operation: UiOperation? = null,
    boxImagePath: String? = null,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PodHeroArtwork(
                    path = boxImagePath,
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 360.dp)
                        .aspectRatio(1f),
                )
                Text(
                    text = podName,
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                podControlItems(
                    batteryParams = batteryParams,
                    batteryTopology = batteryTopology,
                    wearStatus = wearStatus,
                    ancMode = ancMode,
                    onAncModeChange = onAncModeChange,
                    onAmbientSoundLevelChange = onAmbientSoundLevelChange,
                    smartAncLevel = smartAncLevel,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                    gameMode = gameMode,
                    onGameModeChange = onGameModeChange,
                    spatialAudioMode = spatialAudioMode,
                    onSpatialAudioModeChange = onSpatialAudioModeChange,
                    spatialSoundSwitch = spatialSoundSwitch,
                    onSpatialSoundSwitchChange = onSpatialSoundSwitchChange,
                    dualDeviceConnection = dualDeviceConnection,
                    onDualDeviceConnectionChange = onDualDeviceConnectionChange,
                    eqPresetId = eqPresetId,
                    onOpenEqualizer = onOpenEqualizer,
                    features = features,
                    operation = operation,
                    bottomContentPadding = bottomContentPadding
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            PodHeroArtwork(
                path = boxImagePath,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f)
                    .padding(vertical = 16.dp),
            )
        }

        podControlItems(
            batteryParams = batteryParams,
            batteryTopology = batteryTopology,
            wearStatus = wearStatus,
            ancMode = ancMode,
            onAncModeChange = onAncModeChange,
            onAmbientSoundLevelChange = onAmbientSoundLevelChange,
            smartAncLevel = smartAncLevel,
            transparencyVocalEnhancement = transparencyVocalEnhancement,
            onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
            gameMode = gameMode,
            onGameModeChange = onGameModeChange,
            spatialAudioMode = spatialAudioMode,
            onSpatialAudioModeChange = onSpatialAudioModeChange,
            spatialSoundSwitch = spatialSoundSwitch,
            onSpatialSoundSwitchChange = onSpatialSoundSwitchChange,
            dualDeviceConnection = dualDeviceConnection,
            onDualDeviceConnectionChange = onDualDeviceConnectionChange,
            eqPresetId = eqPresetId,
            onOpenEqualizer = onOpenEqualizer,
            features = features,
            operation = operation,
            bottomContentPadding = bottomContentPadding
        )
    }
}

@Composable
private fun PodHeroArtwork(
    path: String?,
    modifier: Modifier = Modifier,
) {
    val drawable = remember(path) {
        path?.let { filePath ->
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(filePath)))
            }.getOrNull()
        }
    }
    DisposableEffect(drawable) {
        (drawable as? Animatable)?.start()
        onDispose { (drawable as? Animatable)?.stop() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Earphones"
            }
        },
        update = { imageView ->
            if (drawable != null) {
                if (imageView.drawable !== drawable) imageView.setImageDrawable(drawable)
                (drawable as? Animatable)?.start()
            } else {
                imageView.setImageResource(R.drawable.ic_generic_headphones)
            }
        },
    )
}

private fun LazyListScope.podControlItems(
    batteryParams: HeadphoneBatteryPresentation,
    batteryTopology: String?,
    wearStatus: WearStatus,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onAmbientSoundLevelChange: (Int) -> Unit,
    smartAncLevel: NoiseControlMode?,
    transparencyVocalEnhancement: Boolean,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    gameMode: Boolean,
    onGameModeChange: (Boolean) -> Unit,
    spatialAudioMode: SpatialAudioMode,
    onSpatialAudioModeChange: (SpatialAudioMode) -> Unit,
    spatialSoundSwitch: Boolean,
    onSpatialSoundSwitchChange: (Boolean) -> Unit,
    dualDeviceConnection: Boolean,
    onDualDeviceConnectionChange: (Boolean) -> Unit,
    eqPresetId: String?,
    onOpenEqualizer: () -> Unit,
    features: Map<String, UiFeatureState>,
    operation: UiOperation?,
    bottomContentPadding: Dp
) {
    val noiseControl = features["NOISE_CONTROL"]
    val ambientLevel = features["AMBIENT_SOUND_LEVEL"]
    val transparency = features["TRANSPARENCY_VOCAL_ENHANCEMENT"]
    val lowLatency = features["LOW_LATENCY"]
    val spatialAudio = features["SPATIAL_AUDIO"]
    val spatialSound = features["SPATIAL_SOUND_SWITCH"]
    val equalizer = features["EQUALIZER"]
    val dualDevice = features["DUAL_DEVICE_CONNECTION"]

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            PodStatus(
                batteryParams = batteryParams,
                batteryTopology = batteryTopology,
                wearStatus = wearStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }

    if (noiseControl?.visible == true) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                AncSwitch(
                    ancStatus = ancMode,
                    onAncModeChange = onAncModeChange,
                    smartAncLevel = smartAncLevel,
                    availableModes = noiseControl.options.mapNotNull {
                        runCatching { NoiseControlMode.valueOf(it.value) }.getOrNull()
                    }.toSet(),
                    enabled = noiseControl.writable,
                    adaptiveModeEnabled =
                        noiseControl.options.any { it.value == "ADAPTIVE" },
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange =
                        if (transparency?.writable == true) {
                            onTransparencyVocalEnhancementChange
                        } else {
                            null
                        },
                )
                if (
                    ancMode == NoiseControlMode.TRANSPARENCY &&
                    ambientLevel?.visible == true &&
                    ambientLevel.options.isNotEmpty()
                ) {
                    val levels = ambientLevel.options.mapNotNull { option ->
                        option.value.toIntOrNull()
                            ?.let { it to option.label }
                    }
                    val displayedLevel = ambientLevel.displayed?.toIntOrNull()
                    if (levels.isNotEmpty()) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.ambient_sound_level),
                            summary = if (ambientLevel.readOnly) {
                                stringResource(R.string.feature_read_only)
                            } else {
                                stringResource(
                                    R.string.ambient_sound_level_summary,
                                    displayedLevel ?: levels.first().first,
                                )
                            },
                            items = levels.map { it.second },
                            selectedIndex = levels.indexOfFirst {
                                it.first == displayedLevel
                            }.coerceAtLeast(0),
                            onSelectedIndexChange = {
                                onAmbientSoundLevelChange(levels[it].first)
                            },
                            enabled = ambientLevel.writable,
                        )
                    }
                }
                if (noiseControl.readOnly) {
                    Text(
                        text = stringResource(R.string.feature_read_only),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (
        listOf(lowLatency, spatialAudio, spatialSound, equalizer, dualDevice)
            .any { it?.visible == true }
    ) {
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
            if (lowLatency?.visible == true) {
                SwitchPreference(
                    title = stringResource(R.string.game_mode),
                    summary = if (lowLatency.readOnly) {
                        stringResource(R.string.feature_read_only)
                    } else {
                        stringResource(R.string.game_mode_summary)
                    },
                    checked = gameMode,
                    onCheckedChange = onGameModeChange,
                    enabled = lowLatency.writable,
                )
            }
            if (spatialAudio?.visible == true) {
                val spatialAudioValues = spatialAudio.options.mapNotNull {
                    runCatching { SpatialAudioMode.valueOf(it.value) }.getOrNull()
                }.ifEmpty { SpatialAudioMode.entries }
                val spatialAudioOptions = listOf(
                    stringResource(R.string.off),
                    stringResource(R.string.spatial_audio_fixed),
                    stringResource(R.string.spatial_audio_head_tracking),
                ).take(spatialAudioValues.size)
                OverlayDropdownPreference(
                    title = stringResource(R.string.spatial_audio),
                    summary = if (spatialAudio.readOnly) {
                        stringResource(R.string.feature_read_only)
                    } else {
                        stringResource(R.string.spatial_audio_summary)
                    },
                    items = spatialAudioOptions,
                    selectedIndex = spatialAudioValues.indexOf(spatialAudioMode).coerceAtLeast(0),
                    onSelectedIndexChange = { onSpatialAudioModeChange(spatialAudioValues[it]) },
                    enabled = spatialAudio.writable,
                )
            }
            if (spatialSound?.visible == true) {
                SwitchPreference(
                    title = stringResource(R.string.spatial_sound),
                    summary = if (spatialSound.readOnly) {
                        stringResource(R.string.feature_read_only)
                    } else {
                        stringResource(if (spatialSoundSwitch) R.string.enabled else R.string.off)
                    },
                    checked = spatialSoundSwitch,
                    onCheckedChange = onSpatialSoundSwitchChange,
                    enabled = spatialSound.writable,
                )
            }
            if (equalizer?.visible == true && equalizer.options.isNotEmpty()) {
                val selectedLabel = equalizer.options.firstOrNull {
                    it.value == eqPresetId
                }?.label
                BasicComponent(
                    title = stringResource(R.string.eq_preset_title),
                    summary = if (equalizer.readOnly) {
                        stringResource(R.string.feature_read_only)
                    } else {
                        selectedLabel ?: stringResource(R.string.eq_preset_summary)
                    },
                    onClick = onOpenEqualizer,
                )
            }
            if (dualDevice?.visible == true) {
                SwitchPreference(
                    title = stringResource(R.string.dual_device_connection),
                    summary = if (dualDevice.readOnly) {
                        stringResource(R.string.feature_read_only)
                    } else {
                        stringResource(
                            if (dualDeviceConnection) R.string.enabled else R.string.off,
                        )
                    },
                    checked = dualDeviceConnection,
                    onCheckedChange = onDualDeviceConnectionChange,
                    enabled = dualDevice.writable,
                )
            }
            }
        }
    }
    operation?.let { currentOperation ->
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    text = when (currentOperation.status) {
                        UiOperationStatus.PENDING ->
                            stringResource(R.string.operation_pending)
                        UiOperationStatus.CONFIRMED ->
                            stringResource(R.string.operation_confirmed)
                        UiOperationStatus.TIMED_OUT ->
                            stringResource(R.string.operation_timed_out)
                        UiOperationStatus.FAILED ->
                            stringResource(R.string.operation_failed)
                        UiOperationStatus.CANCELLED ->
                            stringResource(R.string.operation_cancelled)
                    },
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    item {
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}
