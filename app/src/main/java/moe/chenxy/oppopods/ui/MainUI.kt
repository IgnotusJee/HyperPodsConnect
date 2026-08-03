package moe.chenxy.oppopods.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import moe.chenxy.oppopods.OppoPodsApp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.config.DeviceArtworkSelector
import moe.chenxy.oppopods.config.PodImagePrefs
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotReceiver
import moe.chenxy.oppopods.ipc.HeadphoneCommandClient
import moe.chenxy.oppopods.ipc.IpcSenderPolicy
import moe.chenxy.oppopods.ipc.isSentFrom
import moe.chenxy.oppopods.ipc.sendIdentitySharedBroadcast
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.oppopods.pods.GameModeImplementation
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.oppopods.pods.WearState
import moe.chenxy.oppopods.pods.WearStatus
import moe.chenxy.oppopods.ui.pages.AboutPage
import moe.chenxy.oppopods.ui.pages.DeviceCapabilitiesPage
import moe.chenxy.oppopods.ui.pages.EqualizerPage
import moe.chenxy.oppopods.ui.pages.RfcommDebugPage
import moe.chenxy.oppopods.ui.pages.ThemeSettingsPage
import moe.chenxy.oppopods.ui.state.HeadphoneUiStore
import moe.chenxy.oppopods.ui.state.UiConnectionState
import moe.chenxy.oppopods.utils.RootManager
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

sealed interface Screen : NavKey {
    data object Main : Screen
    data object About : Screen
    data object Theme : Screen
    data object DeviceCapabilities : Screen
    data object Equalizer : Screen
    data object RfcommDebug : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    backStack: SnapshotStateList<Screen>,
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: MutableState<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    blurBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val headphoneUiState by HeadphoneUiStore.state.collectAsState()

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val wearStatus = remember { mutableStateOf(WearStatus()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    /** Smart-mode current auto-applied NC level (LIGHT/MEDIUM/DEEP), or null. */
    val smartAncLevel = remember { mutableStateOf<NoiseControlMode?>(null) }
    val hookConnected = remember { mutableStateOf(false) }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val dualDeviceConnection = remember { mutableStateOf(false) }
    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var xposedService by remember { mutableStateOf(OppoPodsApp.xposedService) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    var restartingScopes by remember { mutableStateOf(false) }
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }
    var connectedDeviceAddress by remember { mutableStateOf("") }
    var showConnectErrorDialog by remember { mutableStateOf(false) }
    var hookConnectionState by remember { mutableStateOf("disconnected") }
    var pendingOpenEarphonesAfterPickerLoaded by remember { mutableStateOf(false) }
    var lastBluetoothServiceAliveMs by remember { mutableStateOf(0L) }
    var bluetoothServiceResponsive by remember { mutableStateOf(false) }
    val backgroundColor = appBackground()
    val overlayBottomBar = floatingBottomBar.value || blurBottomBar.value
    val pageBottomContentPadding = if (overlayBottomBar) 104.dp else 28.dp
    val backdrop = if (blurBottomBar.value) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    } else {
        null
    }

    // Auto game mode preference (persisted)
    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val appConfig = remember { ConfigManager.refreshFromPrefs(prefs) }
    val autoGameMode = remember { mutableStateOf(prefs.getBoolean("auto_game_mode", false)) }
    val gameModeImplementation = remember {
        mutableStateOf(
            GameModeImplementation.fromPreference(
                prefs.getString(GameModeImplementation.PREF_KEY, null)
            )
        )
    }
    val notificationClickAction = remember { mutableStateOf(appConfig.notificationClickAction) }
    val moreClickAction = remember { mutableStateOf(appConfig.moreClickAction) }
    val desktopIconHidden = remember { mutableStateOf(isLauncherIconHidden(context)) }
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val fakeDeviceId = remember { mutableStateOf(appConfig.fakeDeviceId) }
    val islandMode = remember { mutableStateOf(appConfig.islandMode) }
    val islandShowTimings = remember { mutableStateOf(appConfig.islandShowTimings) }
    val spatialAudioMode = remember { mutableStateOf(SpatialAudioMode.OFF) }
    val spatialSoundSwitch = remember { mutableStateOf(false) }
    val eqPresetId = remember { mutableStateOf<String?>(null) }
    val earphonePrefs = remember { mutableStateOf(PodImagePrefs.load(prefs)) }
    val adaptiveCapabilityOverride = remember { mutableStateOf(appConfig.adaptiveCapabilityOverride) }
    val spatialAudioCapabilityOverride = remember { mutableStateOf(appConfig.spatialAudioCapabilityOverride) }
    val spatialSoundSwitchCapabilityOverride = remember { mutableStateOf(appConfig.spatialSoundSwitchCapabilityOverride) }
    val ancImplementationCapabilityOverride = remember { mutableStateOf(appConfig.ancImplementationCapabilityOverride) }

    val canShowDetailPage = hookConnected.value
    val showEarphoneDetail = canShowDetailPage && !showDevicePicker
    val displayBattery = batteryParams.value
    val displayWearStatus = wearStatus.value
    val displayAnc = ancMode.value
    val displayGameMode = gameMode.value
    val displayTransparencyVocalEnhancement = transparencyVocalEnhancement.value
    val displayDualDeviceConnection = dualDeviceConnection.value
    val displayTitle = mainTitle.value.takeIf { it.isNotBlank() && hookConnected.value } ?: mainTitle.value
    val displayFeatures = headphoneUiState.features

    LaunchedEffect(headphoneUiState) {
        val state = headphoneUiState
        val wasConnected = hookConnected.value
        if (state.connection == UiConnectionState.DISCONNECTED) {
            connectedDeviceAddress = ""
        } else {
            state.address?.let { connectedDeviceAddress = it }
        }
        if (state.title.isNotBlank()) mainTitle.value = state.title
        hookConnectionState = when (state.connection) {
            UiConnectionState.CONNECTED -> "connected"
            UiConnectionState.CONNECTING -> "connecting"
            UiConnectionState.ERROR -> "error"
            UiConnectionState.DISCONNECTED -> "disconnected"
        }
        hookConnected.value = state.connected

        fun battery(component: String): PodParams? = state.batteries[component]?.let {
            PodParams(it.level, it.charging, true, 0)
        }
        batteryParams.value = BatteryParams(
            left = battery("LEFT") ?: battery("SINGLE"),
            right = battery("RIGHT"),
            case = battery("CASE"),
        )
        fun wear(component: String): WearState? = when (state.wearing[component]) {
            "WEARING" -> WearState.WEARING
            "REMOVED" -> WearState.REMOVED
            "IN_CASE" -> WearState.IN_CASE
            else -> null
        }
        wearStatus.value = WearStatus(
            left = wear("LEFT"),
            right = wear("RIGHT"),
        )
        state.feature(FeatureId.NOISE_CONTROL.name)?.displayed?.let {
            runCatching { NoiseControlMode.valueOf(it) }.getOrNull()
                ?.let { value -> ancMode.value = value }
        }
        smartAncLevel.value = state.noiseControlActiveMode?.let {
            runCatching { NoiseControlMode.valueOf(it) }.getOrNull()
        }
        state.feature(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT.name)
            ?.displayed?.toBooleanStrictOrNull()
            ?.let { transparencyVocalEnhancement.value = it }
        state.feature(FeatureId.LOW_LATENCY.name)?.displayed?.toBooleanStrictOrNull()
            ?.let { gameMode.value = it }
        state.feature(FeatureId.SPATIAL_AUDIO.name)?.displayed?.let {
            runCatching { SpatialAudioMode.valueOf(it) }.getOrNull()
                ?.let { value -> spatialAudioMode.value = value }
        }
        state.feature(FeatureId.SPATIAL_SOUND_SWITCH.name)
            ?.displayed?.toBooleanStrictOrNull()
            ?.let { spatialSoundSwitch.value = it }
        state.feature(FeatureId.EQUALIZER.name)?.displayed?.let { eqPresetId.value = it }
        state.feature(FeatureId.DUAL_DEVICE_CONNECTION.name)
            ?.displayed?.toBooleanStrictOrNull()
            ?.let { dualDeviceConnection.value = it }

        if (state.connected) {
            connectingDeviceAddress = null
            earphonePrefs.value = PodImagePrefs.upsertConnected(
                prefs = prefs,
                service = xposedService,
                address = connectedDeviceAddress,
                name = state.title,
            )
            if (!wasConnected) {
                pendingOpenEarphonesAfterPickerLoaded = true
            }
        } else if (state.connection == UiConnectionState.DISCONNECTED) {
            connectedDeviceAddress = ""
        }
    }

    LaunchedEffect(displayTitle) {
        if (displayTitle.isNotEmpty()) {
            mainTitle.value = displayTitle
        }
    }

    LaunchedEffect(canShowDetailPage) {
        if (!hasAppliedDefaultTab) {
            selectedTab = if (canShowDetailPage) MainTab.Earphones else MainTab.Module
            hasAppliedDefaultTab = true
        }
    }

    LaunchedEffect(hookConnectionState) {
        if (hookConnectionState == "error") {
            connectingDeviceAddress = null
            pendingOpenEarphonesAfterPickerLoaded = false
            showConnectErrorDialog = true
            showDevicePicker = true
        }
    }

    LaunchedEffect(pendingOpenEarphonesAfterPickerLoaded, connectingDeviceAddress, hookConnected.value) {
        if (pendingOpenEarphonesAfterPickerLoaded && connectingDeviceAddress == null && hookConnected.value) {
            withFrameNanos { }
            pendingOpenEarphonesAfterPickerLoaded = false
            showDevicePicker = false
        }
    }

    val serviceAliveReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                if (!isSentFrom(IpcSenderPolicy.bluetoothOnly)) return
                if (p1?.action != LegacyPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE) return
                lastBluetoothServiceAliveMs = SystemClock.elapsedRealtime()
                bluetoothServiceResponsive = true
            }
        }
    }
    val bluetoothStateReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                if (
                    p1?.action == BluetoothAdapter.ACTION_STATE_CHANGED ||
                    p1?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED
                ) bluetoothState = readBluetoothState(context)
            }
        }
    }

    DisposableEffect(Unit) {
        val serviceListener: (io.github.libxposed.service.XposedService?) -> Unit = { service ->
            xposedService = service
        }
        OppoPodsApp.addServiceListener(serviceListener)

        context.registerReceiver(
            serviceAliveReceiver,
            IntentFilter(LegacyPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE),
            Context.RECEIVER_EXPORTED,
        )
        context.registerReceiver(bluetoothStateReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        HeadphoneSnapshotReceiver.requestSnapshot(context)
        sendBluetoothModuleBroadcast(context, LegacyPodsAction.ACTION_PODS_UI_INIT)

        onDispose {
            sendBluetoothModuleBroadcast(context, LegacyPodsAction.ACTION_PODS_UI_CLOSED)
            try {
                context.unregisterReceiver(serviceAliveReceiver)
                context.unregisterReceiver(bluetoothStateReceiver)
            } catch (_: Exception) {}
            OppoPodsApp.removeServiceListener(serviceListener)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            sendBluetoothModuleBroadcast(context, LegacyPodsAction.ACTION_PODS_UI_INIT)
            HeadphoneCommandClient.execute(context, FeatureCommand.RefreshAll)
            delay(30_000L)
        }
    }

    LaunchedEffect(selectedTab, hookConnected.value) {
        sendBluetoothModuleBroadcast(context, LegacyPodsAction.ACTION_PODS_UI_INIT)
        if (selectedTab == MainTab.Module || hookConnected.value) {
            HeadphoneCommandClient.execute(context, FeatureCommand.RefreshAll)
        }
    }

    LaunchedEffect(lastBluetoothServiceAliveMs) {
        while (true) {
            bluetoothServiceResponsive = lastBluetoothServiceAliveMs > 0L &&
                    SystemClock.elapsedRealtime() - lastBluetoothServiceAliveMs <= 75_000L
            delay(5_000L)
        }
    }

    fun setAncMode(mode: NoiseControlMode) {
        HeadphoneCommandClient.execute(context, FeatureCommand.SetNoiseControl(mode))
    }

    fun setAmbientSoundLevel(level: Int) {
        HeadphoneCommandClient.execute(context, FeatureCommand.SetAmbientSoundLevel(level))
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

    fun clearPodConnectionState() {
        connectingDeviceAddress = null
        pendingOpenEarphonesAfterPickerLoaded = false
        connectedDeviceAddress = ""
        mainTitle.value = ""
        batteryParams.value = BatteryParams()
        wearStatus.value = WearStatus()
        ancMode.value = NoiseControlMode.OFF
        hookConnected.value = false
        hookConnectionState = "disconnected"
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    fun requestControlSession(context: Context, device: BluetoothDevice) {
        Intent(LegacyPodsAction.ACTION_CONNECT_POD_REQUEST).apply {
            putExtra("device", device)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendIdentitySharedBroadcast(this)
        }
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        connectingDeviceAddress = device.address
        pendingOpenEarphonesAfterPickerLoaded = false
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
        hookConnectionState = "connecting"
        requestControlSession(context, device)
    }

    fun setSpatialAudioMode(mode: SpatialAudioMode) {
        HeadphoneCommandClient.execute(context, FeatureCommand.SetSpatialAudio(mode))
    }

    fun setSpatialSoundSwitch(enabled: Boolean) {
        HeadphoneCommandClient.execute(
            context,
            FeatureCommand.SetSpatialSoundSwitch(enabled),
        )
    }

    fun setEqPreset(presetId: String) {
        HeadphoneCommandClient.execute(
            context,
            FeatureCommand.SetEqualizerPreset(EqualizerPreset(presetId)),
        )
    }

    fun setEqCurve(curve: EqualizerCurve) {
        HeadphoneCommandClient.execute(
            context,
            FeatureCommand.SetEqualizerCurve(curve),
        )
    }

    fun renameEqPreset(presetId: String, name: String) {
        HeadphoneCommandClient.execute(
            context,
            FeatureCommand.RenameEqualizerPreset(EqualizerPreset(presetId, name)),
        )
    }

    fun deleteEqPreset(presetId: String) {
        HeadphoneCommandClient.execute(
            context,
            FeatureCommand.DeleteEqualizerPreset(presetId),
        )
    }

    fun setDualDeviceConnection(enabled: Boolean) {
        HeadphoneCommandClient.execute(
            context,
            FeatureCommand.SetDualDeviceConnection(enabled),
        )
    }

    fun onDeviceDisconnect(device: BluetoothDevice) {
        connectingDeviceAddress = null
        pendingOpenEarphonesAfterPickerLoaded = false
        if (device.address == connectedDeviceAddress) {
            hookConnected.value = false
            hookConnectionState = "disconnected"
            connectedDeviceAddress = ""
            mainTitle.value = ""
        }
        Intent(LegacyPodsAction.ACTION_DISCONNECT_POD_REQUEST).apply {
            putExtra("device", device)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendIdentitySharedBroadcast(this)
        }
    }

    fun onConnectedDeviceClick() {
        if (connectedDeviceAddress.isBlank() && mainTitle.value.isBlank()) return
        pendingOpenEarphonesAfterPickerLoaded = false
        hookConnected.value = true
        hookConnectionState = "connected"
        showDevicePicker = false
        selectedTab = MainTab.Earphones
        runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(connectedDeviceAddress)
        }.getOrNull()?.let { requestControlSession(context, it) }
    }

    fun backToDevicePicker() {
        showDevicePicker = true
    }

    fun openBluetoothSettings() {
        val action = if (bluetoothState.enabled) Settings.ACTION_BLUETOOTH_SETTINGS else BluetoothAdapter.ACTION_REQUEST_ENABLE
        Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun openDevicePicker() {
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    @SuppressLint("MissingPermission")
    fun openSystemHeadsetSettings() {
        val address = connectedDeviceAddress
        if (address.isBlank()) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val device = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        }.getOrNull()
        if (device == null) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Intent().apply {
            setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
            putExtra("android.bluetooth.device.extra.DEVICE", device)
            putExtra("bluetoothaddress", device.address)
            putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun refreshStatus() {
        if (hookConnected.value) {
            HeadphoneCommandClient.execute(context, FeatureCommand.RefreshAll)
        }
    }

    fun savePodImages(
        address: String,
        name: String,
        images: Map<PodImageResource, Uri?>,
        clearedImages: Set<PodImageResource>,
    ) {
        earphonePrefs.value = PodImagePrefs.saveImages(context, prefs, xposedService, address, name, images, clearedImages)
    }

    fun saveOfficialPodImages(
        address: String,
        name: String,
        selector: DeviceArtworkSelector,
        images: Map<PodImageResource, ByteArray>,
    ) {
        earphonePrefs.value = PodImagePrefs.saveOfficialImages(
            context = context,
            prefs = prefs,
            service = xposedService,
            address = address,
            name = name,
            selector = selector.copy(firmware = headphoneUiState.firmware),
            images = images,
        )
    }

    LaunchedEffect(
        headphoneUiState.connected,
        headphoneUiState.vendorId,
        headphoneUiState.address,
        headphoneUiState.title,
        headphoneUiState.firmware,
    ) {
        val state = headphoneUiState
        val address = state.address.orEmpty()
        if (!state.connected || address.isBlank()) {
            return@LaunchedEffect
        }
        val resolved = withContext(Dispatchers.IO) {
            when {
                state.vendorId.equals("oppo", ignoreCase = true) -> {
                    val candidate = RootManager.resolveMelodyImageCandidate(state.title, address)
                        ?: return@withContext null
                    val images = RootManager.readMelodyImages(candidate) ?: return@withContext null
                    DeviceArtworkSelector(
                        vendorId = "oppo",
                        productId = candidate.productId,
                        colorId = candidate.colorId,
                        model = candidate.model,
                    ) to images
                }
                state.vendorId.equals("sony", ignoreCase = true) -> {
                    val candidate = RootManager.resolveSonyImageCandidate(state.title, address)
                        ?: return@withContext null
                    val images = RootManager.readSonyImages(candidate) ?: return@withContext null
                    candidate.selector to images
                }
                else -> null
            }
        } ?: return@LaunchedEffect
        saveOfficialPodImages(address, state.title, resolved.first, resolved.second)
    }

    fun restartScopes(packages: List<String>) {
        if (packages.isEmpty() || restartingScopes) return
        restartingScopes = true
        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) {
                RootManager.restartPackages(packages)
            }
            restartingScopes = false
            showRestartScopeDialog = false
            if (success && "com.android.bluetooth" in packages) {
                clearPodConnectionState()
            }
            Toast.makeText(
                context,
                if (success) R.string.restart_scope_success else R.string.restart_scope_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val entryProvider = entryProvider<Screen> {
        entry<Screen.Main> {
            MainTabsScaffold(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                floatingBottomBar = floatingBottomBar.value,
                blurBottomBar = blurBottomBar.value,
                backdrop = backdrop,
                backgroundColor = backgroundColor,
                overlayBottomBar = overlayBottomBar,
                pageBottomContentPadding = pageBottomContentPadding,
                xposedService = xposedService,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothEnabled = bluetoothState.enabled,
                bondedDeviceCount = bluetoothState.bondedCount,
                onBluetoothStatusClick = { openBluetoothSettings() },
                onPairedBluetoothClick = { openDevicePicker() },
                showEarphoneDetail = showEarphoneDetail,
                mainTitle = mainTitle.value,
                displayTitle = displayTitle,
                displayBattery = displayBattery,
                displayTopology = headphoneUiState.topology,
                displayWearStatus = displayWearStatus,
                displayAnc = displayAnc,
                onAncModeChange = { setAncMode(it) },
                onAmbientSoundLevelChange = { setAmbientSoundLevel(it) },
                smartAncLevel = smartAncLevel.value,
                displayTransparencyVocalEnhancement = displayTransparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = { setTransparencyVocalEnhancement(it) },
                displayGameMode = displayGameMode,
                onGameModeChange = { setGameMode(it) },
                spatialAudioMode = spatialAudioMode.value,
                onSpatialAudioModeChange = { setSpatialAudioMode(it) },
                spatialSoundSwitch = spatialSoundSwitch.value,
                onSpatialSoundSwitchChange = { setSpatialSoundSwitch(it) },
                eqPresetId = eqPresetId.value,
                onOpenEqualizer = { backStack.add(Screen.Equalizer) },
                displayDualDeviceConnection = displayDualDeviceConnection,
                onDualDeviceConnectionChange = { setDualDeviceConnection(it) },
                features = displayFeatures,
                operation = headphoneUiState.lastOperation,
                earphonePrefs = earphonePrefs.value,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectErrorDialog = showConnectErrorDialog,
                onDeviceSelected = { onDeviceSelected(it) },
                onConnectedDeviceClick = { onConnectedDeviceClick() },
                onDeviceDisconnect = { onDeviceDisconnect(it) },
                onDismissConnectError = { showConnectErrorDialog = false },
                desktopIconHidden = desktopIconHidden,
                onDesktopIconHiddenChange = {
                    desktopIconHidden.value = it
                    setLauncherIconHidden(context, it)
                },
                logLevel = logLevel,
                onLogLevelChange = {
                    logLevel.value = it
                    ConfigManager.updateLogLevel(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.milink.service")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandMode = islandMode,
                onIslandModeChange = {
                    islandMode.value = it
                    ConfigManager.updateIslandMode(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandShowTimings = islandShowTimings,
                onIslandShowTimingsChange = {
                    islandShowTimings.value = it
                    ConfigManager.updateIslandShowTimings(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    onAppLanguageChange(it)
                },
                autoGameMode = autoGameMode,
                onAutoGameModeChange = {
                    autoGameMode.value = it
                    prefs.edit().putBoolean("auto_game_mode", it).apply()
                    Intent(LegacyPodsAction.ACTION_AUTO_GAME_MODE_CHANGED).apply {
                        setPackage("com.android.bluetooth")
                        putExtra("enabled", it)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        context.sendIdentitySharedBroadcast(this)
                    }
                },
                gameModeImplementation = gameModeImplementation,
                onGameModeImplementationChange = {
                    gameModeImplementation.value = it
                    prefs.edit()
                        .putString(GameModeImplementation.PREF_KEY, it.preferenceValue)
                        .apply()
                    Intent(LegacyPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED).apply {
                        setPackage("com.android.bluetooth")
                        putExtra(GameModeImplementation.PREF_KEY, it.preferenceValue)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        context.sendIdentitySharedBroadcast(this)
                    }
                },
                notificationClickAction = notificationClickAction,
                onNotificationClickActionChange = {
                    notificationClickAction.value = it
                    ConfigManager.updateNotificationClickAction(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                moreClickAction = moreClickAction,
                onMoreClickActionChange = {
                    moreClickAction.value = it
                    ConfigManager.updateMoreClickAction(prefs, xposedService, it)
                },
                adaptiveCapabilityOverride = adaptiveCapabilityOverride,
                spatialAudioCapabilityOverride = spatialAudioCapabilityOverride,
                spatialSoundSwitchCapabilityOverride = spatialSoundSwitchCapabilityOverride,
                onOpenDeviceCapabilities = { backStack.add(Screen.DeviceCapabilities) },
                onOpenRfcommDebug = { backStack.add(Screen.RfcommDebug) },
                fakeDeviceId = fakeDeviceId,
                onFakeDeviceIdChange = {
                    fakeDeviceId.value = it
                    ConfigManager.updateFakeDeviceId(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.android.settings")
                    broadcastConfigChanged(context, "com.milink.service")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                onOpenTheme = { backStack.add(Screen.Theme) },
                onOpenAbout = { backStack.add(Screen.About) },
                showRestartScopeDialog = showRestartScopeDialog,
                restartingScopes = restartingScopes,
                onShowRestartScopeDialog = { showRestartScopeDialog = true },
                onDismissRestartScopeDialog = { showRestartScopeDialog = false },
                onRestartScopes = { restartScopes(it) },
                onBackToDevicePicker = { backToDevicePicker() },
                onOpenSystemHeadsetSettings = { openSystemHeadsetSettings() },
                onSavePodImages = { address, name, images, clearedImages ->
                    savePodImages(address, name, images, clearedImages)
                },
                onSavePodImageBytes = { address, name, candidate, images ->
                    saveOfficialPodImages(
                        address = address,
                        name = name,
                        selector = DeviceArtworkSelector(
                            vendorId = "oppo",
                            productId = candidate.productId,
                            colorId = candidate.colorId,
                            model = candidate.model,
                        ),
                        images = images,
                    )
                },
            )
        }
        entry<Screen.Equalizer> {
            val equalizerScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            val equalizer = displayFeatures[FeatureId.EQUALIZER.name]

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.eq_page_title),
                        largeTitle = stringResource(R.string.eq_page_title),
                        scrollBehavior = equalizerScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    if (equalizer != null) {
                        EqualizerPage(
                            vendorId = headphoneUiState.vendorId,
                            deviceName = headphoneUiState.title,
                            feature = equalizer,
                            curveState = headphoneUiState.equalizerCurve,
                            onPresetChange = { setEqPreset(it) },
                            onCurveChange = { setEqCurve(it) },
                            onPresetRename = { presetId, name -> renameEqPreset(presetId, name) },
                            onPresetDelete = { deleteEqPreset(it) },
                            modifier = Modifier
                                .overScrollVertical()
                                .nestedScroll(equalizerScrollBehavior.nestedScrollConnection),
                        )
                    }
                }
            }
        }
        entry<Screen.About> {
            val aboutScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.about),
                        largeTitle = stringResource(R.string.about),
                        scrollBehavior = aboutScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    AboutPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(aboutScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                    )
                }
            }
        }
        entry<Screen.Theme> {
            val themeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.theme_title),
                        largeTitle = stringResource(R.string.theme_title),
                        scrollBehavior = themeScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    ThemeSettingsPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(themeScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        accentMode = accentMode,
                        onAccentModeChange = onAccentModeChange,
                        floatingBottomBar = floatingBottomBar,
                        onFloatingBottomBarChange = onFloatingBottomBarChange,
                        blurBottomBar = blurBottomBar,
                        onBlurBottomBarChange = onBlurBottomBarChange,
                    )
                }
            }
        }
        entry<Screen.DeviceCapabilities> {
            val capabilitiesScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.device_capabilities),
                        largeTitle = stringResource(R.string.device_capabilities),
                        scrollBehavior = capabilitiesScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    DeviceCapabilitiesPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(capabilitiesScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                        adaptiveCapabilityOverride = adaptiveCapabilityOverride,
                        onAdaptiveCapabilityOverrideChange = {
                            adaptiveCapabilityOverride.value = it
                            ConfigManager.updateAdaptiveCapabilityOverride(prefs, xposedService, it)
                            broadcastConfigChanged(context, "com.android.bluetooth")
                            if (it == ConfigManager.CAPABILITY_OVERRIDE_FORCE_DISABLED &&
                                displayAnc == NoiseControlMode.ADAPTIVE
                            ) {
                                setAncMode(NoiseControlMode.NOISE_CANCELLATION)
                            }
                        },
                        spatialAudioCapabilityOverride = spatialAudioCapabilityOverride,
                        onSpatialAudioCapabilityOverrideChange = {
                            spatialAudioCapabilityOverride.value = it
                            ConfigManager.updateSpatialAudioCapabilityOverride(prefs, xposedService, it)
                            broadcastConfigChanged(context, "com.android.bluetooth")
                        },
                        spatialSoundSwitchCapabilityOverride = spatialSoundSwitchCapabilityOverride,
                        onSpatialSoundSwitchCapabilityOverrideChange = {
                            spatialSoundSwitchCapabilityOverride.value = it
                            ConfigManager.updateSpatialSoundSwitchCapabilityOverride(prefs, xposedService, it)
                            broadcastConfigChanged(context, "com.android.bluetooth")
                        },
                        ancImplementationCapabilityOverride = ancImplementationCapabilityOverride,
                        onAncImplementationCapabilityOverrideChange = {
                            ancImplementationCapabilityOverride.value = it
                            ConfigManager.updateAncImplementationCapabilityOverride(prefs, xposedService, it)
                            broadcastConfigChanged(context, "com.android.bluetooth")
                        },
                    )
                }
            }
        }
        entry<Screen.RfcommDebug> {
            val rfcommScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            var clearRfcommLogsRequest by remember { mutableIntStateOf(0) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = "RFCOMM 调试",
                        largeTitle = "RFCOMM 调试",
                        scrollBehavior = rfcommScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { clearRfcommLogsRequest++ }) {
                                Icon(imageVector = MiuixIcons.Delete, contentDescription = "Clear logs")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    RfcommDebugPage(
                        modifier = Modifier.nestedScroll(rfcommScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(0.dp),
                        clearRequest = clearRfcommLogsRequest,
                    )
                }
            }
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider
    )

    NavDisplay(
        entries = entries,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        }
    )
}

@Composable
fun appBackground(): Color = MiuixTheme.colorScheme.surface

private data class BluetoothSummary(
    val enabled: Boolean,
    val bondedCount: Int,
)

@android.annotation.SuppressLint("MissingPermission")
private fun readBluetoothState(context: Context): BluetoothSummary {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return runCatching {
        BluetoothSummary(
            enabled = adapter?.isEnabled == true,
            bondedCount = adapter?.bondedDevices?.size ?: 0,
        )
    }.getOrDefault(BluetoothSummary(enabled = false, bondedCount = 0))
}

private fun sendBluetoothModuleBroadcast(context: Context, action: String) {
    listOf("com.android.bluetooth", "com.xiaomi.bluetooth").forEach { packageName ->
        Intent(action).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendIdentitySharedBroadcast(this)
        }
    }
}

private fun isLauncherIconHidden(context: Context): Boolean {
    val component = ComponentName(context, "moe.chenxy.oppopods.LauncherActivity")
    val state = context.packageManager.getComponentEnabledSetting(component)
    return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

private fun setLauncherIconHidden(context: Context, hidden: Boolean) {
    val component = ComponentName(context, "moe.chenxy.oppopods.LauncherActivity")
    val state = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}

private fun broadcastConfigChanged(context: Context, packageName: String) {
    Intent(LegacyPodsAction.ACTION_CONFIG_CHANGED).apply {
        setPackage(packageName)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        context.sendIdentitySharedBroadcast(this)
    }
}
