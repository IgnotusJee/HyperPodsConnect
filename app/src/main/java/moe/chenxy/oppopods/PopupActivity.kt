package moe.chenxy.oppopods

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
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.ipc.HeadphoneCommandClient
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotReceiver
import moe.chenxy.oppopods.ui.AppLocale
import moe.chenxy.oppopods.ui.AppTheme
import moe.chenxy.oppopods.ui.components.AncSwitch
import moe.chenxy.oppopods.ui.components.PodStatus
import moe.chenxy.oppopods.ui.state.HeadphoneUiStore
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
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
        if (appConfig.notificationClickAction != ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
            openNotificationTarget(appConfig.notificationClickAction, bluetoothDevice)
            finish()
            return
        }

        setContent {
            val colorSchemeMode = when (prefs.getInt("theme_mode", 0)) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            AppTheme(colorSchemeMode = colorSchemeMode, accentMode = prefs.getInt("accent_mode", 0)) {
                PopupContent(
                    onMore = {
                        val latestConfig = ConfigManager.refreshFromPrefs(prefs)
                        openMoreTarget(latestConfig.moreClickAction, bluetoothDevice)
                        finish()
                    },
                    onDone = { finish() }
                )
            }
        }
    }

    private fun openNotificationTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            ConfigManager.NOTIFICATION_CLICK_HEYTAP -> openHeyTapOrModule()
            else -> openModule()
        }
    }

    private fun openMoreTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.MORE_CLICK_HEYTAP -> openHeyTapOrModule()
            ConfigManager.MORE_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            else -> openModule()
        }
    }

    private fun openModule() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openHeyTapOrModule() {
        val intent = packageManager.getLaunchIntentForPackage("com.heytap.headset")
        if (intent != null) {
            startActivity(intent)
        } else {
            openModule()
        }
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
            putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
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
private fun PopupContent(onMore: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val showDialog = remember { mutableStateOf(false) }
    val headphoneUiState = HeadphoneUiStore.state.collectAsState().value

    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val themeMode = remember { prefs.getInt("theme_mode", 0) }
    val systemDark = isSystemInDarkTheme()
    val isDarkMode = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val deviceName = remember { mutableStateOf("") }
    val adaptiveModeEnabled =
        headphoneUiState.feature(FeatureId.NOISE_CONTROL.name)
            ?.options?.any { it.value == "ADAPTIVE" } == true
    LaunchedEffect(headphoneUiState) {
        val state = headphoneUiState
        deviceName.value = state.title
        showDialog.value = state.connected || showDialog.value
        fun battery(component: String): PodParams? = state.batteries[component]?.let {
            PodParams(it.level, it.charging, true, 0)
        }
        batteryParams.value = BatteryParams(
            left = battery("LEFT") ?: battery("SINGLE"),
            right = battery("RIGHT"),
            case = battery("CASE"),
        )
        state.feature(FeatureId.NOISE_CONTROL.name)?.displayed?.let {
            runCatching { NoiseControlMode.valueOf(it) }.getOrNull()
                ?.let { mode -> ancMode.value = mode }
        }
        state.feature(FeatureId.LOW_LATENCY.name)?.displayed?.toBooleanStrictOrNull()
            ?.let { gameMode.value = it }
        state.feature(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT.name)
            ?.displayed?.toBooleanStrictOrNull()
            ?.let { transparencyVocalEnhancement.value = it }
    }

    LaunchedEffect(Unit) {
        HeadphoneSnapshotReceiver.requestSnapshot(context)
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    // Timeout fallback: show dialog even if no response within 500ms
    // Periodic refresh: poll earbuds every 15s while popup is open
    LaunchedEffect(Unit) {
        delay(500)
        if (!showDialog.value) showDialog.value = true

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
                    adaptiveModeEnabled = adaptiveModeEnabled
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
                    adaptiveModeEnabled = adaptiveModeEnabled
                )
            }
        }
    }
}

@Composable
private fun PortraitPopupBody(
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    adaptiveModeEnabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            PodStatus(
                batteryParams,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            AncSwitch(
                ancStatus = ancMode,
                onAncModeChange = onAncModeChange,
                adaptiveModeEnabled = adaptiveModeEnabled,
                transparencyVocalEnhancement = transparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = stringResource(R.string.game_mode),
                summary = stringResource(R.string.game_mode_summary),
                checked = gameMode,
                onCheckedChange = onGameModeChange
            )
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
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    adaptiveModeEnabled: Boolean = true
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
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                AncSwitch(
                    ancMode,
                    onAncModeChange = onAncModeChange,
                    compact = true,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            val gameModeCardColor = if (gameMode) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
            val gameModeTextColor = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = gameModeCardColor,
                    contentColor = gameModeTextColor
                ),
                pressFeedbackType = PressFeedbackType.Sink,
                showIndication = true,
                onClick = { onGameModeChange(!gameMode) },
                onLongPress = {}
            ) {
                Text(
                    text = stringResource(R.string.game_mode),
                    color = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
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
