package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.SystemClock
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.BatteryState
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.feature.WearComponent
import moe.chenxy.headphones.core.feature.NoiseControlMode as CoreNoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode as CoreSpatialAudioMode
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityOverrides
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityRegistry
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoLowLatencyStrategy
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncEncoding
import moe.chenxy.headphones.protocol.oppo.session.OppoSessionEvent
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.hook.Log
import moe.chenxy.oppopods.runtime.bluetoothprocess.BluetoothProcessRuntimeHost
import moe.chenxy.oppopods.utils.MediaControl
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams

/**
 * OPPO/HyperOS compatibility boundary for Android-only side effects.
 *
 * Session authority and protocol work live in [BluetoothProcessRuntimeHost]. This adapter only
 * translates the remaining legacy Android events, notifications and media-route integration while
 * those call sites are migrated to the versioned IPC/state projection.
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object OppoSystemIntegrationAdapter {
    private const val TAG = "HyperPods-OppoIntegration"
    private const val APP_UI_ACTIVE_TIMEOUT_MS = 75_000L

    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc = 1
    private var currentSmartAncLevel = -1
    private var currentGameMode = false
    private var currentTransparencyVocalEnhancement = false
    private var currentSpatialAudioMode = SpatialAudioMode.OFF
    private var currentEqPreset = -1
    private var currentDualDeviceConnection = false
    private var autoGameModeEnabled = false
    private var gameModeImplementation = GameModeImplementation.STANDARD
    private var lastGameModeStatusUpdateMs = 0L
    private var lastKnownCaseBattery = 0
    private var lastKnownCaseCharging = false
    private var cachedDeviceName = ""
    private var receiverRegistered = false
    private var routeScanStarted = false
    private var appUiActive = false
    private var appUiActiveUntilMs = 0L
    private var currentWearStatus = WearStatus()
    private val connectionStateObservable = RfcommConnectionStateObservable()
    private val rawHexSessionGate = RawHexSessionGate(BuildConfig.ALLOW_RAW_PROTOCOL_CONSOLE)

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?,
        val connected: Boolean,
        val connecting: Boolean,
        val reconnectPending: Boolean,
    )

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectPending = false
    private var lastEngineState = HeadphoneState()
    private var readyGeneration = -1L

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::handleUIEvent)
        }
    }

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                markAppUiActive()
                changeUIConnectionState(currentConnectionState())
                if (::currentBatteryParams.isInitialized) changeUIBatteryStatus(currentBatteryParams)
                changeUIWearStatus(currentWearStatus)
                changeUIAncStatus(currentAnc)
                changeUISmartAncLevel(currentSmartAncLevel)
                changeUIGameModeStatus(currentGameMode)
                changeUITransparencyVocalEnhancementStatus(currentTransparencyVocalEnhancement)
                changeUISpatialAudioStatus(currentSpatialAudioMode)
                changeUIEqPreset(currentEqPreset)
                changeUIDualDeviceConnectionStatus(currentDualDeviceConnection)
                if (::mDevice.isInitialized && isConnected) {
                    sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        putExtra("address", mDevice.address)
                        putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                    sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                        putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    }
                }
            }
            OppoPodsAction.ACTION_PODS_UI_CLOSED -> {
                appUiActive = false
                appUiActiveUntilMs = 0L
                rawHexSessionGate.lock()
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> queryStatus(immediateReconnect = true)
            OppoPodsAction.ACTION_AUTO_GAME_MODE_CHANGED ->
                autoGameModeEnabled = intent.getBooleanExtra("enabled", autoGameModeEnabled)
            OppoPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED -> {
                gameModeImplementation = GameModeImplementation.fromPreference(
                    intent.getStringExtra(GameModeImplementation.PREF_KEY),
                )
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> cycleAnc()
            OppoPodsAction.ACTION_CONFIG_CHANGED -> {
                ConfigManager.refreshFromPrefs(mPrefs)
                if (!currentCompatibility().adaptiveSupported && currentAnc == 4) setANCMode(2)
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT -> {
                if (!RfcommLog.isEnabled()) RfcommLog.setEnabled(true, mContext)
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT -> RfcommLog.setEnabled(false)
            OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR -> RfcommLog.clear()
            OppoPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK -> {
                val token = intent.getStringExtra(
                    OppoPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN,
                ).orEmpty()
                if (rawHexSessionGate.unlock(token)) {
                    RfcommLog.w(mContext, "RFCOMM/DEBUG", "raw HEX unlocked for this debug-page session")
                } else {
                    RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX is unavailable in release builds")
                }
            }
            OppoPodsAction.ACTION_RFCOMM_DEBUG_LOCK -> {
                rawHexSessionGate.lock(
                    intent.getStringExtra(OppoPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN),
                )
                RfcommLog.i(mContext, "RFCOMM/DEBUG", "raw HEX locked")
            }
            OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND -> sendDebugHex(
                intent.getStringExtra("hex").orEmpty(),
                intent.getStringExtra(OppoPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN),
            )
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot = StatusSnapshot(
        battery = if (::currentBatteryParams.isInitialized) currentBatteryParams else null,
        anc = currentAnc,
        transparencyVocalEnhancement = currentTransparencyVocalEnhancement,
        address = if (::mDevice.isInitialized) mDevice.address else null,
        deviceName = if (::mDevice.isInitialized) {
            mDevice.name ?: cachedDeviceName
        } else {
            cachedDeviceName.takeIf(String::isNotEmpty)
        },
        connected = isConnected &&
            BluetoothProcessRuntimeHost.snapshot.value?.connection is SessionState.Ready,
        connecting = BluetoothProcessRuntimeHost.snapshot.value?.connection?.isActive == true,
        reconnectPending = reconnectPending,
    )

    private fun currentConnectionState(): String = when {
        isConnected &&
            BluetoothProcessRuntimeHost.snapshot.value?.connection is SessionState.Ready &&
            ::currentBatteryParams.isInitialized -> "connected"
        reconnectPending -> "connecting"
        isConnected && BluetoothProcessRuntimeHost.snapshot.value?.connection?.isActive == true ->
            "connecting"
        else -> "disconnected"
    }

    fun connectPod(
        context: Context,
        device: BluetoothDevice,
        prefs: SharedPreferences,
        appRequested: Boolean = false,
    ) {
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        if (appRequested) markAppUiActive()
        autoGameModeEnabled = mPrefs.getBoolean("auto_game_mode", false)
        gameModeImplementation = GameModeImplementation.fromPreference(
            mPrefs.getString(GameModeImplementation.PREF_KEY, null),
        )
        ConfigManager.refreshFromPrefs(mPrefs)

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                addAction(OppoPodsAction.ACTION_AUTO_GAME_MODE_CHANGED)
                addAction(OppoPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED)
                addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                addAction(OppoPodsAction.ACTION_CONFIG_CHANGED)
                addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT)
                addAction(OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
                addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
                OppoPodsAction.RFCOMM_DEBUG_CONTROL_ACTIONS.forEach(::addAction)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        MediaControl.mContext = context
        mediaRouter = MediaRouter2.getInstance(context)
        startRoutesScan()
        isConnected = true
        reconnectPending = false
        lastEngineState = HeadphoneState()
        changeUIConnectionState("connecting")
        lifecycleScope.launch {
            delay(500)
            if (isConnected) {
                BluetoothProcessRuntimeHost.connect(
                    context,
                    device,
                    sessionOverrides(),
                )
            }
        }
    }

    fun onEngineSnapshot(snapshot: HeadphoneSnapshot) {
        if (!::mDevice.isInitialized || snapshot.deviceId.value !=
            moe.chenxy.headphones.core.device.DeviceId.fromAddress(mDevice.address).value
        ) return
        val previous = lastEngineState
        lastEngineState = snapshot.state
        publishStateChanges(previous, snapshot.state)
        when (val connection = snapshot.connection) {
            is SessionState.Ready -> {
                reconnectPending = false
                changeUIConnectionState(
                    if (::currentBatteryParams.isInitialized) "connected" else "connecting",
                )
                if (readyGeneration != snapshot.generationId) {
                    readyGeneration = snapshot.generationId
                    Log.d(TAG, "engine session ready generation=${snapshot.generationId}")
                    RfcommLog.i(mContext, TAG, "session ready generation=${snapshot.generationId}")
                    if (autoGameModeEnabled) lifecycleScope.launch { enableGameModeOnConnect() }
                }
            }
            is SessionState.Reconnecting -> {
                reconnectPending = true
                changeUIConnectionState("connecting")
            }
            is SessionState.Failed -> {
                reconnectPending = connection.canRetry
                Log.e(TAG, "engine session failed: ${connection.detail.orEmpty()}")
                RfcommLog.e(mContext, TAG, "session failed: ${connection.detail.orEmpty()}")
                changeUIConnectionState("error")
            }
            is SessionState.Idle -> if (!isConnected) changeUIConnectionState("disconnected")
            else -> if (isConnected) changeUIConnectionState("connecting")
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun onOppoSessionEvent(event: OppoSessionEvent) {
        when (event) {
            is OppoSessionEvent.RawChunk ->
                RfcommLog.d(
                    mContext,
                    "RFCOMM/RX",
                    event.bytes.toHexString(HexFormat.UpperCase),
                )
            is OppoSessionEvent.WearReport -> Unit
            is OppoSessionEvent.SmartAncLevel -> {
                val ordinal = legacyNoiseMode(event.mode).ordinal
                if (ordinal != currentSmartAncLevel) {
                    currentSmartAncLevel = ordinal
                    changeUISmartAncLevel(ordinal)
                }
            }
            is OppoSessionEvent.UnknownMessage ->
                Log.d(TAG, "Unknown OPPO message: ${event.message}")
            is OppoSessionEvent.Message -> Unit
        }
    }

    fun onEngineOperation(event: OperationEvent) {
        RfcommLog.d(
            mContext,
            "OPERATION",
            "${event.requestId.value} ${event.command.featureId} ${event.phase}" +
                event.detail?.let { " $it" }.orEmpty(),
        )
    }

    private fun publishStateChanges(previous: HeadphoneState, current: HeadphoneState) {
        if (current.batteries.isNotEmpty() && current.batteries != previous.batteries) {
            handleBatteryChanged(current.batteries)
        }
        if (current.wearing != previous.wearing) {
            val oldWear = currentWearStatus
            currentWearStatus = WearStatus(
                left = current.wearing[WearComponent.LEFT]?.toLegacyWearState(),
                right = current.wearing[WearComponent.RIGHT]?.toLegacyWearState(),
                case = oldWear.case,
            )
            changeUIWearStatus(currentWearStatus)
            showIslandForWearStatusChange(oldWear, currentWearStatus)
        }
        current.noiseControl.confirmed?.let { value ->
            if (value != previous.noiseControl.confirmed) {
                currentAnc = coreNoiseModeToStatus(value)
                changeUIAncStatus(currentAnc)
            }
        }
        current.transparencyVocalEnhancement.confirmed?.let { value ->
            if (value != previous.transparencyVocalEnhancement.confirmed) {
                currentTransparencyVocalEnhancement = value
                changeUITransparencyVocalEnhancementStatus(value)
            }
        }
        current.equalizer.confirmed?.let { value ->
            if (value != previous.equalizer.confirmed) {
                value.id.substringAfter("oppo:").toIntOrNull()?.let { preset ->
                    currentEqPreset = preset
                    changeUIEqPreset(preset)
                }
            }
        }
        current.lowLatency.confirmed?.let { value ->
            if (value != previous.lowLatency.confirmed) {
                currentGameMode = value
                lastGameModeStatusUpdateMs = SystemClock.elapsedRealtime()
                changeUIGameModeStatus(value)
            }
        }
        current.spatialAudio.confirmed?.let { value ->
            if (value != previous.spatialAudio.confirmed) {
                currentSpatialAudioMode = when (value) {
                    CoreSpatialAudioMode.OFF -> SpatialAudioMode.OFF
                    CoreSpatialAudioMode.FIXED -> SpatialAudioMode.FIXED
                    CoreSpatialAudioMode.HEAD_TRACKING -> SpatialAudioMode.HEAD_TRACKING
                }
                changeUISpatialAudioStatus(currentSpatialAudioMode)
            }
        }
        current.dualDeviceConnection.confirmed?.let { value ->
            if (value != previous.dualDeviceConnection.confirmed) {
                currentDualDeviceConnection = value
                changeUIDualDeviceConnectionStatus(value)
            }
        }
    }

    private fun moe.chenxy.headphones.core.feature.WearState.toLegacyWearState(): WearState =
        when (this) {
            moe.chenxy.headphones.core.feature.WearState.WEARING -> WearState.WEARING
            moe.chenxy.headphones.core.feature.WearState.REMOVED -> WearState.REMOVED
            moe.chenxy.headphones.core.feature.WearState.IN_CASE -> WearState.IN_CASE
            moe.chenxy.headphones.core.feature.WearState.UNKNOWN -> WearState.DISCONNECTED
        }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        reconnectPending = false
        BluetoothProcessRuntimeHost.disconnectAsync(DisconnectCause.REQUESTED)

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED) {
                putExtra("address", device.address)
            }
            if (receiverRegistered) {
                it.unregisterReceiver(broadcastReceiver)
                receiverRegistered = false
            }
        }
        mShowedConnectedToast = false
        currentWearStatus = WearStatus()
        currentAnc = 1
        currentSmartAncLevel = -1
        currentGameMode = false
        currentTransparencyVocalEnhancement = false
        currentSpatialAudioMode = SpatialAudioMode.OFF
        currentEqPreset = -1
        currentDualDeviceConnection = false
        lastKnownCaseBattery = 0
        lastKnownCaseCharging = false
        changeUIConnectionState("disconnected")
        cachedDeviceName = ""
        mContext = null
        MediaControl.mContext = null
    }

    private fun launchCommand(command: FeatureCommand, reason: String) {
        lifecycleScope.launch {
            val result = BluetoothProcessRuntimeHost.execute(command)
            if (!result.succeeded) {
                Log.w(TAG, "$reason failed: ${result.failure} ${result.detail.orEmpty()}")
            }
        }
    }

    private fun setGameMode(enabled: Boolean) =
        launchCommand(FeatureCommand.SetLowLatency(enabled), "game mode control")

    private suspend fun enableGameModeOnConnect() {
        delay(500)
        repeat(3) { attempt ->
            if (!isConnected) return
            val started = SystemClock.elapsedRealtime()
            val result = BluetoothProcessRuntimeHost.execute(FeatureCommand.SetLowLatency(true))
            if (result.succeeded) return
            BluetoothProcessRuntimeHost.refresh(setOf(FeatureId.LOW_LATENCY))
            delay(if (attempt == 0) 700 else 1_500)
            if (lastGameModeStatusUpdateMs >= started && currentGameMode) return
        }
    }

    private fun cycleAnc() {
        val cycle = if (currentCompatibility().adaptiveSupported) {
            listOf(2, 4, 3, 1)
        } else {
            listOf(2, 3, 1)
        }
        val currentIndex = cycle.indexOf(if (currentAnc in 5..8) 2 else currentAnc)
        setANCMode(cycle[(currentIndex + 1).floorMod(cycle.size)])
    }

    private fun setANCMode(mode: Int) {
        val domain = when (mode) {
            1 -> CoreNoiseControlMode.OFF
            2 -> CoreNoiseControlMode.NOISE_CANCELLATION
            3 -> CoreNoiseControlMode.TRANSPARENCY
            4 -> CoreNoiseControlMode.ADAPTIVE
            5 -> CoreNoiseControlMode.NOISE_CANCELLATION_SMART
            6 -> CoreNoiseControlMode.NOISE_CANCELLATION_LIGHT
            7 -> CoreNoiseControlMode.NOISE_CANCELLATION_MEDIUM
            8 -> CoreNoiseControlMode.NOISE_CANCELLATION_DEEP
            else -> return
        }
        launchCommand(FeatureCommand.SetNoiseControl(domain), "anc control")
    }

    fun queryBattery() {
        BluetoothProcessRuntimeHost.refreshAsync(setOf(FeatureId.BATTERY))
    }

    private fun queryStatus(immediateReconnect: Boolean = true) {
        BluetoothProcessRuntimeHost.refreshAsync()
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendDebugHex(hex: String, sessionToken: String? = null) {
        if (!rawHexSessionGate.canSend(sessionToken)) {
            RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX denied: unlock it first")
            return
        }
        val normalized = hex.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (normalized.isEmpty() || normalized.length % 2 != 0 ||
            !normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            RfcommLog.e(mContext, "RFCOMM/DEBUG", "invalid HEX: $hex")
            return
        }
        val bytes = normalized.hexToByteArray()
        lifecycleScope.launch {
            RfcommLog.i(mContext, "RFCOMM/DEBUG", "send ${bytes.size} bytes")
            if (!BluetoothProcessRuntimeHost.sendDebugFrame(bytes)) {
                RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX denied: no active OPPO session")
            }
        }
    }

    private fun sessionOverrides(): OppoCompatibilityOverrides = OppoCompatibilityOverrides(
        adaptiveSupported = ConfigManager.adaptiveCapabilityOverride().asBooleanOverride(),
        spatialAudioSupported = ConfigManager.spatialAudioCapabilityOverride().asBooleanOverride(),
        spatialSoundSwitchSupported =
            ConfigManager.spatialSoundSwitchCapabilityOverride().asBooleanOverride(),
        ancEncoding = when (ConfigManager.ancImplementationCapabilityOverride()) {
            DeviceCapabilityOverride.FORCE_ENABLED -> OppoAncEncoding.COMPATIBLE
            DeviceCapabilityOverride.FORCE_DISABLED -> OppoAncEncoding.STANDARD
            else -> null
        },
        lowLatencyStrategy = if (gameModeImplementation == GameModeImplementation.COMPATIBLE) {
            OppoLowLatencyStrategy.MAIN_AND_LOW_LATENCY_SWITCH
        } else {
            OppoLowLatencyStrategy.MAIN_SWITCH
        },
    )

    private fun currentCompatibility() = OppoCompatibilityRegistry.resolve(
        if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName,
        sessionOverrides(),
    )

    private fun Int.asBooleanOverride(): Boolean? = when (this) {
        DeviceCapabilityOverride.FORCE_ENABLED -> true
        DeviceCapabilityOverride.FORCE_DISABLED -> false
        else -> null
    }

    private fun coreNoiseModeToStatus(mode: CoreNoiseControlMode): Int = when (mode) {
        CoreNoiseControlMode.OFF -> 1
        CoreNoiseControlMode.NOISE_CANCELLATION -> 2
        CoreNoiseControlMode.TRANSPARENCY -> 3
        CoreNoiseControlMode.ADAPTIVE -> 4
        CoreNoiseControlMode.NOISE_CANCELLATION_SMART -> 5
        CoreNoiseControlMode.NOISE_CANCELLATION_LIGHT -> 6
        CoreNoiseControlMode.NOISE_CANCELLATION_MEDIUM -> 7
        CoreNoiseControlMode.NOISE_CANCELLATION_DEEP -> 8
    }

    private fun legacyNoiseMode(mode: CoreNoiseControlMode): NoiseControlMode = when (mode) {
        CoreNoiseControlMode.OFF -> NoiseControlMode.OFF
        CoreNoiseControlMode.NOISE_CANCELLATION -> NoiseControlMode.NOISE_CANCELLATION
        CoreNoiseControlMode.NOISE_CANCELLATION_SMART -> NoiseControlMode.NOISE_CANCELLATION_SMART
        CoreNoiseControlMode.NOISE_CANCELLATION_LIGHT -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
        CoreNoiseControlMode.NOISE_CANCELLATION_MEDIUM -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
        CoreNoiseControlMode.NOISE_CANCELLATION_DEEP -> NoiseControlMode.NOISE_CANCELLATION_DEEP
        CoreNoiseControlMode.ADAPTIVE -> NoiseControlMode.ADAPTIVE
        CoreNoiseControlMode.TRANSPARENCY -> NoiseControlMode.TRANSPARENCY
    }

    fun handleBatteryChanged(result: Map<BatteryComponent, BatteryState>) {
        val leftState = result[BatteryComponent.LEFT] ?: result[BatteryComponent.SINGLE]
        val rightState = result[BatteryComponent.RIGHT]
        val caseState = result[BatteryComponent.CASE]
        val left = PodParams(leftState?.level ?: 0, leftState?.charging == true, leftState != null, 0)
        val right = PodParams(rightState?.level ?: 0, rightState?.charging == true, rightState != null, 0)
        val case = if (caseState != null) {
            lastKnownCaseBattery = caseState.level
            lastKnownCaseCharging = caseState.charging
            PodParams(caseState.level, caseState.charging, true, 0)
        } else {
            PodParams(lastKnownCaseBattery, lastKnownCaseCharging, false, 0)
        }
        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            val valid = (left.isConnected && left.battery > 0) ||
                (right.isConnected && right.battery > 0)
            if (!valid) return
        }
        val battery = BatteryParams(left, right, case)
        currentBatteryParams = battery
        if (shouldShowToast) {
            changeUIConnectionState("connected")
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                putExtra("address", mDevice.address)
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            if (shouldShowIsland(ConfigManager.ISLAND_SHOW_TIMING_CONNECTED)) {
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, battery, mDevice)
            }
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, battery, mDevice)
        changeUIBatteryStatus(battery)
        lastTempBatt = when {
            left.isConnected && right.isConnected -> minOf(left.battery, right.battery)
            left.isConnected -> left.battery
            right.isConnected -> right.battery
            else -> SystemApisUtils.BATTERY_LEVEL_UNKNOWN
        }
        setRegularBatteryLevel(lastTempBatt)
    }

    private fun changeUIAncStatus(status: Int) {
        if (status !in 1..8) return
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            if (::mDevice.isInitialized) putExtra("address", mDevice.address)
            putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            if (::mDevice.isInitialized) putExtra("address", mDevice.address)
            putExtra("status", status)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", status)
            putBatteryExtras(status)
        }
    }

    private fun changeUIWearStatus(status: WearStatus) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            if (::mDevice.isInitialized) putExtra("address", mDevice.address)
            putWearStatusExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            putWearStatusExtras(status)
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) =
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            putExtra("enabled", enabled)
        }

    private fun changeUITransparencyVocalEnhancementStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            putExtra("enabled", enabled)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) =
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            putExtra("mode", mode)
        }

    private fun changeUIEqPreset(presetId: Int) =
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            putExtra("preset", presetId)
        }

    private fun changeUISmartAncLevel(ordinal: Int) =
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED) {
            putExtra("ordinal", ordinal)
        }

    private fun changeUIDualDeviceConnectionStatus(enabled: Boolean) =
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED) {
            putExtra("enabled", enabled)
        }

    private fun changeUIConnectionState(state: String) {
        connectionStateObservable.publish(RfcommConnectionState.fromWireValue(state))
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            if (::mDevice.isInitialized) {
                putExtra("address", mDevice.address)
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            putExtra("state", state)
        }
    }

    private fun sendAppStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val context = mContext ?: return
        if (!isAppUiActive()) return
        Intent(action).apply {
            fill()
            setPackage(BuildConfig.APPLICATION_ID)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val context = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { target ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(target)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }
    }

    private fun isAppUiActive(): Boolean {
        if (!appUiActive) return false
        if (SystemClock.elapsedRealtime() <= appUiActiveUntilMs) return true
        appUiActive = false
        appUiActiveUntilMs = 0L
        return false
    }

    private fun markAppUiActive() {
        appUiActive = true
        appUiActiveUntilMs = SystemClock.elapsedRealtime() + APP_UI_ACTIVE_TIMEOUT_MS
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun Intent.putWearStatusExtras(status: WearStatus) {
        putExtra("left_wear_status", status.left?.value ?: -1)
        putExtra("right_wear_status", status.right?.value ?: -1)
        putExtra("case_wear_status", status.case?.value ?: -1)
    }

    private fun mergeWearStatus(current: WearStatus, update: WearStatus) = WearStatus(
        left = update.left ?: current.left,
        right = update.right ?: current.right,
        case = update.case ?: current.case,
    )

    private fun showIslandForWearStatusChange(previous: WearStatus, current: WearStatus) {
        if (!::currentBatteryParams.isInitialized) return
        val changes = setOfNotNull(
            islandShowTimingForChange(previous.left, current.left),
            islandShowTimingForChange(previous.right, current.right),
            islandShowTimingForChange(previous.case, current.case),
        )
        if (changes.any(::shouldShowIsland)) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(
                mContext ?: return,
                currentBatteryParams,
            )
        }
    }

    private fun shouldShowIsland(timing: Int) =
        ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_MODULE &&
            timing in ConfigManager.islandShowTimings()

    private fun islandShowTimingForChange(previous: WearState?, current: WearState?): Int? {
        if (previous == current) return null
        return when (current) {
            WearState.WEARING -> ConfigManager.ISLAND_SHOW_TIMING_WEARING
            WearState.REMOVED -> ConfigManager.ISLAND_SHOW_TIMING_REMOVED
            WearState.IN_CASE -> ConfigManager.ISLAND_SHOW_TIMING_IN_CASE
            else -> null
        }
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            this@OppoSystemIntegrationAdapter.routes = routes
        }
    }

    private fun startRoutesScan() {
        if (routeScanStarted) return
        val executor = Executor { runnable -> lifecycleScope.launch { runnable?.run() } }
        val features = listOf(
            MediaRoute2Info.FEATURE_LIVE_AUDIO,
            MediaRoute2Info.FEATURE_LIVE_VIDEO,
        )
        mediaRouter.registerRouteCallback(
            executor,
            routeCallback,
            RouteDiscoveryPreference.Builder(features, true).build(),
        )
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
        routeScanStarted = true
    }

    private fun stopRoutesScan() {
        scanToken?.let(mediaRouter::cancelScanRequest)
        if (routeScanStarted) {
            mediaRouter.unregisterRouteCallback(routeCallback)
            routeScanStarted = false
        }
    }

    fun miuiRefreshPayload(
        battery: BatteryParams?,
        anc: Int,
        transparencyVocalEnhancement: Boolean = false,
    ): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = when (anc) {
            5 -> "0103"
            6 -> "0101"
            7 -> "0100"
            8 -> "0102"
            3 -> if (transparencyVocalEnhancement) "0201" else "0200"
            else -> "0000"
        }
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    fun addConnectionStateObserver(
        observer: RfcommConnectionStateObserver,
        emitCurrent: Boolean = true,
    ) = connectionStateObservable.addObserver(observer, emitCurrent)

    fun removeConnectionStateObserver(observer: RfcommConnectionStateObserver) =
        connectionStateObservable.removeObserver(observer)

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        MediaControl.sendPause()
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HEADSET) return
                try {
                    proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        .invoke(proxy, device)
                } finally {
                    adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                }
            }
            override fun onServiceDisconnected(profile: Int) = Unit
        }, BluetoothProfile.HEADSET)
        lifecycleScope.launch {
            delay(500)
            routes.firstOrNull { it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER }
                ?.let(mediaRouter::transferTo)
        }
        setRegularBatteryLevel(lastTempBatt)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HEADSET) return
                try {
                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        .invoke(proxy, device)
                } finally {
                    adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                }
            }
            override fun onServiceDisconnected(profile: Int) = Unit
        }, BluetoothProfile.HEADSET)
        routes.firstOrNull {
            it.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && it.name == device?.name
        }?.let(mediaRouter::transferTo)
        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
        statusBarManager.setIconVisibility("wireless_headset", true)
        setRegularBatteryLevel(lastTempBatt)
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            val service = getObjectField(mContext, "mAdapterService")
            callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (error: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", error)
        }
    }

    private fun getObjectField(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching {
                return type.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            }
            type = type.superclass
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == args.size
            }?.let {
                it.isAccessible = true
                return it.invoke(instance, *args)
            }
            type = type.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
