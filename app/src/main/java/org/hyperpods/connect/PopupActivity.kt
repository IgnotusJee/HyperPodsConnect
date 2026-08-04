package org.hyperpods.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.operation.FeatureCommand
import org.hyperpods.connect.config.ConfigManager
import org.hyperpods.connect.ipc.HeadphoneCommandClient
import org.hyperpods.connect.ipc.HeadphoneSnapshotReceiver
import org.hyperpods.connect.ipc.sendIdentitySharedBroadcast
import org.hyperpods.connect.integration.PresentationBatterySlot
import org.hyperpods.connect.integration.toPresentationState
import org.hyperpods.connect.integration.HyperOsOfficialIslandConfig
import org.hyperpods.connect.pods.ConnectedPopupContract
import org.hyperpods.connect.pods.ConnectionPopupSnapshotDecision
import org.hyperpods.connect.pods.connectionPopupSnapshotDecision
import org.hyperpods.connect.ui.AppLocale
import org.hyperpods.connect.ui.AppTheme
import org.hyperpods.connect.ui.components.AncSwitch
import org.hyperpods.connect.ui.components.PodStatus
import org.hyperpods.connect.ui.state.HeadphoneUiStore
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation
import org.hyperpods.connect.ipc.HeadphoneActionContract
import org.hyperpods.connect.integration.BatterySlotPresentation
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

class PopupActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.SYSTEM))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val appConfig = ConfigManager.refreshFromPrefs(prefs)
        val bluetoothDevice = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
        val forceModulePopup = intent.getBooleanExtra(ConnectedPopupContract.EXTRA_FORCE_MODULE_POPUP, false)
        val connectionEdgePopup = intent.getBooleanExtra(ConnectedPopupContract.EXTRA_CONNECTION_EDGE_POPUP, false)
        val expectedGeneration = intent.getLongExtra(ConnectedPopupContract.EXTRA_EXPECTED_GENERATION, -1L)
        val expectedEmittedAtMillis = intent.getLongExtra(ConnectedPopupContract.EXTRA_EXPECTED_EMITTED_AT, -1L)
        val expectedDeviceId = intent.getStringExtra(ConnectedPopupContract.EXTRA_EXPECTED_DEVICE_ID)
        val expectedAddress = intent.getStringExtra(ConnectedPopupContract.EXTRA_EXPECTED_ADDRESS)
        if (!forceModulePopup && appConfig.notificationClickAction != ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
            openNotificationTarget(appConfig.notificationClickAction, bluetoothDevice)
            finish()
            return
        }
        if (connectionEdgePopup) setShowWhenLocked(true)

        setContent {
            val colorSchemeMode = when (prefs.getInt("theme_mode", 0)) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            AppTheme(colorSchemeMode = colorSchemeMode, accentMode = prefs.getInt("accent_mode", 0)) {
                PopupContent(
                    connectionEdgePopup = connectionEdgePopup,
                    expectedGeneration = expectedGeneration,
                    expectedEmittedAtMillis = expectedEmittedAtMillis,
                    expectedDeviceId = expectedDeviceId,
                    expectedAddress = expectedAddress,
                    onMore = {
                        val latestConfig = ConfigManager.refreshFromPrefs(prefs)
                        openMoreTarget(latestConfig.moreClickAction, bluetoothDevice)
                        finish()
                    },
                    onDone = { finish() },
                    onUnavailable = { finish() },
                )
            }
        }
    }

    private fun openNotificationTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            else -> openModule()
        }
    }

    private fun openMoreTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.MORE_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            else -> openModule()
        }
    }

    private fun openModule() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    @SuppressLint("MissingPermission")
    private fun openSystemSettings(bluetoothDevice: BluetoothDevice?) {
        if (bluetoothDevice == null) {
            openModule()
            return
        }
        val intent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
            putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            putExtra("bluetoothaddress", bluetoothDevice.address)
            putExtra("MIUI_HEADSET_SUPPORT", HyperOsOfficialIslandConfig.supportDescriptor)
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", HyperOsOfficialIslandConfig.presentationTypeId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure { openModule() }
    }

    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }
}

@Composable
private fun PopupContent(
    connectionEdgePopup: Boolean,
    expectedGeneration: Long,
    expectedEmittedAtMillis: Long,
    expectedDeviceId: String?,
    expectedAddress: String?,
    onMore: () -> Unit,
    onDone: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val showDialog = remember { mutableStateOf(false) }
    val headphoneUiState = HeadphoneUiStore.state.collectAsState().value
    val presentation = headphoneUiState.toPresentationState()

    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val themeMode = remember { prefs.getInt("theme_mode", 0) }
    val systemDark = isSystemInDarkTheme()
    val isDarkMode = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    val batteryParams = remember { mutableStateOf(HeadphoneBatteryPresentation()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val deviceName = remember { mutableStateOf("") }
    val noiseControl = presentation.feature(FeatureId.NOISE_CONTROL)
    val lowLatency = presentation.feature(FeatureId.LOW_LATENCY)
    val vocalEnhancement = presentation.feature(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT)
    LaunchedEffect(headphoneUiState) {
        val state = headphoneUiState.toPresentationState()
        when (
            connectionPopupSnapshotDecision(
                connectionEdgePopup = connectionEdgePopup,
                currentlyShown = showDialog.value,
                expectedGeneration = expectedGeneration,
                expectedEmittedAtMillis = expectedEmittedAtMillis,
                expectedDeviceId = expectedDeviceId,
                expectedAddress = expectedAddress,
                state = state,
            )
        ) {
            ConnectionPopupSnapshotDecision.WAIT -> return@LaunchedEffect
            ConnectionPopupSnapshotDecision.DISMISS -> {
                showDialog.value = false
                return@LaunchedEffect
            }
            ConnectionPopupSnapshotDecision.UPDATE -> Unit
        }
        deviceName.value = state.title
        showDialog.value = if (connectionEdgePopup) true else state.connected || showDialog.value
        fun battery(component: PresentationBatterySlot): BatterySlotPresentation? = state.batteries[component]?.let {
            BatterySlotPresentation(it.level, it.charging, true, 0)
        }
        batteryParams.value = HeadphoneBatteryPresentation(
            left = battery(PresentationBatterySlot.LEFT) ?: battery(PresentationBatterySlot.SINGLE),
            right = battery(PresentationBatterySlot.RIGHT),
            case = battery(PresentationBatterySlot.CASE),
        )
        state.feature(FeatureId.NOISE_CONTROL).value?.let {
            runCatching { NoiseControlMode.valueOf(it) }.getOrNull()
                ?.let { mode -> ancMode.value = mode }
        }
        state.feature(FeatureId.LOW_LATENCY).value?.toBooleanStrictOrNull()
            ?.let { gameMode.value = it }
        state.feature(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT)
            .value?.toBooleanStrictOrNull()
            ?.let { transparencyVocalEnhancement.value = it }
    }

    LaunchedEffect(Unit) {
        HeadphoneSnapshotReceiver.requestSnapshot(context)
        context.sendIdentitySharedBroadcast(Intent(HeadphoneActionContract.ACTION_HEADPHONE_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    // Manual opens retain the historical timeout fallback. An automatic connection popup must
    // match the exact generation/address snapshot or close without presenting stale state.
    // Periodic refresh: poll earbuds every 15s while popup is open
    LaunchedEffect(
        connectionEdgePopup,
        expectedGeneration,
        expectedEmittedAtMillis,
        expectedDeviceId,
        expectedAddress,
    ) {
        delay(if (connectionEdgePopup) 2_000 else 500)
        if (!showDialog.value) {
            if (connectionEdgePopup) {
                onUnavailable()
                return@LaunchedEffect
            }
            showDialog.value = true
        }

        while (true) {
            delay(15_000)
            HeadphoneCommandClient.execute(context, FeatureCommand.RefreshAll)
        }
    }

    fun setAncMode(mode: NoiseControlMode) {
        HeadphoneCommandClient.execute(context, FeatureCommand.SetNoiseControl(mode))
    }

    fun setGameMode(enabled: Boolean) {
        HeadphoneCommandClient.execute(context, FeatureCommand.SetLowLatency(enabled))
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        HeadphoneCommandClient.execute(
            context,
            FeatureCommand.SetTransparencyVocalEnhancement(enabled),
        )
    }

    val dialogBgColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(containerColor = Color.Transparent) { _ ->
        OverlayDialog(
            title = deviceName.value.ifEmpty { stringResource(R.string.app_name) },
            show = showDialog.value,
            backgroundColor = dialogBgColor,
            onDismissRequest = {
                showDialog.value = false
            },
            onDismissFinished = {
                onDone()
            }
        ) {
            if (isLandscape) {
                LandscapePopupBody(
                    batteryParams = batteryParams.value,
                    ancMode = ancMode.value,
                    gameMode = gameMode.value,
                    transparencyVocalEnhancement = transparencyVocalEnhancement.value,
                    onAncModeChange = ::setAncMode,
                    onGameModeChange = ::setGameMode,
                    onTransparencyVocalEnhancementChange = ::setTransparencyVocalEnhancement,
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                    noiseControlVisible = noiseControl.visible,
                    noiseControlWritable = noiseControl.writable,
                    availableNoiseControlModes = presentation.noiseControlModes,
                    lowLatencyVisible = lowLatency.visible,
                    lowLatencyWritable = lowLatency.writable,
                    vocalEnhancementVisible = vocalEnhancement.visible,
                    vocalEnhancementWritable = vocalEnhancement.writable,
                )
            } else {
                PortraitPopupBody(
                    batteryParams = batteryParams.value,
                    ancMode = ancMode.value,
                    gameMode = gameMode.value,
                    transparencyVocalEnhancement = transparencyVocalEnhancement.value,
                    onAncModeChange = ::setAncMode,
                    onGameModeChange = ::setGameMode,
                    onTransparencyVocalEnhancementChange = ::setTransparencyVocalEnhancement,
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                    noiseControlVisible = noiseControl.visible,
                    noiseControlWritable = noiseControl.writable,
                    availableNoiseControlModes = presentation.noiseControlModes,
                    lowLatencyVisible = lowLatency.visible,
                    lowLatencyWritable = lowLatency.writable,
                    vocalEnhancementVisible = vocalEnhancement.visible,
                    vocalEnhancementWritable = vocalEnhancement.writable,
                )
            }
        }
    }
}

@Composable
private fun PortraitPopupBody(
    batteryParams: HeadphoneBatteryPresentation,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    noiseControlVisible: Boolean,
    noiseControlWritable: Boolean,
    availableNoiseControlModes: Set<NoiseControlMode>,
    lowLatencyVisible: Boolean,
    lowLatencyWritable: Boolean,
    vocalEnhancementVisible: Boolean,
    vocalEnhancementWritable: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            PodStatus(
                batteryParams,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
        if (noiseControlVisible) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                AncSwitch(
                    ancStatus = ancMode,
                    onAncModeChange = onAncModeChange,
                    availableModes = availableNoiseControlModes,
                    enabled = noiseControlWritable,
                    adaptiveModeEnabled = NoiseControlMode.ADAPTIVE in availableNoiseControlModes,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    transparencyVocalEnhancementVisible = vocalEnhancementVisible,
                    transparencyVocalEnhancementWritable = vocalEnhancementWritable,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                )
            }
        }
        if (lowLatencyVisible) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = stringResource(R.string.game_mode),
                    summary = if (lowLatencyWritable) {
                        stringResource(R.string.game_mode_summary)
                    } else {
                        stringResource(R.string.feature_read_only)
                    },
                    checked = gameMode,
                    onCheckedChange = onGameModeChange,
                    enabled = lowLatencyWritable,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LandscapePopupBody(
    batteryParams: HeadphoneBatteryPresentation,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    noiseControlVisible: Boolean,
    noiseControlWritable: Boolean,
    availableNoiseControlModes: Set<NoiseControlMode>,
    lowLatencyVisible: Boolean,
    lowLatencyWritable: Boolean,
    vocalEnhancementVisible: Boolean,
    vocalEnhancementWritable: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 560.dp)
            .height(240.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                PodStatus(
                    batteryParams,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    compact = true
                )
            }
            if (noiseControlVisible) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    AncSwitch(
                        ancMode,
                        onAncModeChange = onAncModeChange,
                        availableModes = availableNoiseControlModes,
                        compact = true,
                        enabled = noiseControlWritable,
                        adaptiveModeEnabled = NoiseControlMode.ADAPTIVE in availableNoiseControlModes,
                        transparencyVocalEnhancement = transparencyVocalEnhancement,
                        transparencyVocalEnhancementVisible = vocalEnhancementVisible,
                        transparencyVocalEnhancementWritable = vocalEnhancementWritable,
                        onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            if (lowLatencyVisible) {
                val gameModeCardColor = if (gameMode) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
                val gameModeTextColor = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(
                        color = gameModeCardColor,
                        contentColor = gameModeTextColor,
                    ),
                    pressFeedbackType = PressFeedbackType.Sink,
                    showIndication = lowLatencyWritable,
                    onClick = { if (lowLatencyWritable) onGameModeChange(!gameMode) },
                    onLongPress = {},
                ) {
                    Text(
                        text = stringResource(R.string.game_mode),
                        color = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
