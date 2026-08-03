package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
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
import moe.chenxy.oppopods.ipc.IpcSenderPolicy
import moe.chenxy.oppopods.ipc.isSentFrom
import moe.chenxy.oppopods.runtime.bluetoothprocess.BluetoothProcessRuntimeHost
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction
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

    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    private lateinit var currentBatteryParams: BatteryParams
    private var currentAnc = 1
    private var currentGameMode = false
    private var autoGameModeEnabled = false
    private var gameModeImplementation = GameModeImplementation.STANDARD
    private var lastGameModeStatusUpdateMs = 0L
    private var lastKnownCaseBattery = 0
    private var lastKnownCaseCharging = false
    private var cachedDeviceName = ""
    private var receiverRegistered = false
    private var currentWearStatus = WearStatus()
    private val rawHexSessionGate = RawHexSessionGate(BuildConfig.ALLOW_RAW_PROTOCOL_CONSOLE)

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastEngineState = HeadphoneState()
    private var readyGeneration = -1L

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isSentFrom(IpcSenderPolicy.allowedBluetoothLegacySenders(intent?.action))) return
            intent?.let(::handleUIEvent)
        }
    }

    private fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            LegacyPodsAction.ACTION_PODS_UI_CLOSED -> {
                rawHexSessionGate.lock()
            }
            LegacyPodsAction.ACTION_AUTO_GAME_MODE_CHANGED ->
                autoGameModeEnabled = intent.getBooleanExtra("enabled", autoGameModeEnabled)
            LegacyPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED -> {
                gameModeImplementation = GameModeImplementation.fromPreference(
                    intent.getStringExtra(GameModeImplementation.PREF_KEY),
                )
            }
            LegacyPodsAction.ACTION_CYCLE_ANC -> cycleAnc()
            LegacyPodsAction.ACTION_CONFIG_CHANGED -> {
                ConfigManager.refreshFromPrefs(mPrefs)
                if (!currentCompatibility().adaptiveSupported && currentAnc == 4) setANCMode(2)
            }
            LegacyPodsAction.ACTION_RFCOMM_LOG_CONNECT -> {
                if (!RfcommLog.isEnabled()) RfcommLog.setEnabled(true, mContext)
            }
            LegacyPodsAction.ACTION_RFCOMM_LOG_DISCONNECT -> RfcommLog.setEnabled(false)
            LegacyPodsAction.ACTION_RFCOMM_LOG_CLEAR -> RfcommLog.clear()
            LegacyPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK -> {
                val token = intent.getStringExtra(
                    LegacyPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN,
                ).orEmpty()
                if (rawHexSessionGate.unlock(token)) {
                    RfcommLog.w(mContext, "RFCOMM/DEBUG", "raw HEX unlocked for this debug-page session")
                } else {
                    RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX is unavailable in release builds")
                }
            }
            LegacyPodsAction.ACTION_RFCOMM_DEBUG_LOCK -> {
                rawHexSessionGate.lock(
                    intent.getStringExtra(LegacyPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN),
                )
                RfcommLog.i(mContext, "RFCOMM/DEBUG", "raw HEX locked")
            }
            LegacyPodsAction.ACTION_RFCOMM_DEBUG_SEND -> sendDebugHex(
                intent.getStringExtra("hex").orEmpty(),
                intent.getStringExtra(LegacyPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN),
            )
        }
    }

    fun connectPod(
        context: Context,
        device: BluetoothDevice,
        prefs: SharedPreferences,
    ) = connectPod(context, device, prefs, automatic = false)

    fun connectPodAutomatically(
        context: Context,
        device: BluetoothDevice,
        prefs: SharedPreferences,
    ) = connectPod(context, device, prefs, automatic = true)

    private fun connectPod(
        context: Context,
        device: BluetoothDevice,
        prefs: SharedPreferences,
        automatic: Boolean,
    ) {
        mPrefs = prefs
        autoGameModeEnabled = mPrefs.getBoolean("auto_game_mode", false)
        gameModeImplementation = GameModeImplementation.fromPreference(
            mPrefs.getString(GameModeImplementation.PREF_KEY, null),
        )
        ConfigManager.refreshFromPrefs(mPrefs)
        if (
            automatic &&
            !BluetoothProcessRuntimeHost.canAutoConnect(device, sessionOverrides())
        ) return

        mContext = context
        mDevice = device
        cachedDeviceName = device.name ?: ""

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                addAction(LegacyPodsAction.ACTION_PODS_UI_CLOSED)
                addAction(LegacyPodsAction.ACTION_AUTO_GAME_MODE_CHANGED)
                addAction(LegacyPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED)
                addAction(LegacyPodsAction.ACTION_CYCLE_ANC)
                addAction(LegacyPodsAction.ACTION_CONFIG_CHANGED)
                addAction(LegacyPodsAction.ACTION_RFCOMM_LOG_CONNECT)
                addAction(LegacyPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
                addAction(LegacyPodsAction.ACTION_RFCOMM_LOG_CLEAR)
                LegacyPodsAction.RFCOMM_DEBUG_CONTROL_ACTIONS.forEach(::addAction)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        isConnected = true
        lastEngineState = HeadphoneState()
        lifecycleScope.launch {
            delay(500)
            if (isConnected) {
                if (automatic) {
                    BluetoothProcessRuntimeHost.connectAutomatically(
                        context,
                        device,
                        sessionOverrides(),
                    )
                } else {
                    BluetoothProcessRuntimeHost.connect(
                        context,
                        device,
                        sessionOverrides(),
                    )
                }
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
                if (readyGeneration != snapshot.generationId) {
                    readyGeneration = snapshot.generationId
                    Log.d(TAG, "engine session ready generation=${snapshot.generationId}")
                    RfcommLog.i(mContext, TAG, "session ready generation=${snapshot.generationId}")
                    if (autoGameModeEnabled) lifecycleScope.launch { enableGameModeOnConnect() }
                }
            }
            is SessionState.Reconnecting -> Unit
            is SessionState.Failed -> {
                Log.e(TAG, "engine session failed: ${connection.detail.orEmpty()}")
                RfcommLog.e(mContext, TAG, "session failed: ${connection.detail.orEmpty()}")
            }
            else -> Unit
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun onOppoSessionEvent(event: OppoSessionEvent) {
        when (event) {
            is OppoSessionEvent.TxFrame ->
                RfcommLog.d(
                    mContext,
                    "RFCOMM/TX",
                    event.bytes.toHexString(HexFormat.UpperCase),
                )
            is OppoSessionEvent.RawChunk ->
                RfcommLog.d(
                    mContext,
                    "RFCOMM/RX",
                    event.bytes.toHexString(HexFormat.UpperCase),
                )
            is OppoSessionEvent.WearReport -> Unit
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
            showIslandForWearStatusChange(oldWear, currentWearStatus)
        }
        current.noiseControl.confirmed?.let { value ->
            if (value != previous.noiseControl.confirmed) {
                currentAnc = coreNoiseModeToStatus(value)
            }
        }
        current.lowLatency.confirmed?.let { value ->
            if (value != previous.lowLatency.confirmed) {
                currentGameMode = value
                lastGameModeStatusUpdateMs = SystemClock.elapsedRealtime()
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
        BluetoothProcessRuntimeHost.disconnectAsync(DisconnectCause.REQUESTED)

        mContext?.let {
            cancelPodsNotificationByMiuiBt(context, device)
            if (receiverRegistered) {
                it.unregisterReceiver(broadcastReceiver)
                receiverRegistered = false
            }
        }
        mShowedConnectedToast = false
        currentWearStatus = WearStatus()
        currentAnc = 1
        currentGameMode = false
        lastKnownCaseBattery = 0
        lastKnownCaseCharging = false
        cachedDeviceName = ""
        mContext = null
    }

    fun isCurrentDevice(device: BluetoothDevice): Boolean =
        ::mDevice.isInitialized && mDevice.address == device.address && isConnected

    private fun launchCommand(command: FeatureCommand, reason: String) {
        lifecycleScope.launch {
            val result = BluetoothProcessRuntimeHost.execute(command)
            if (!result.succeeded) {
                Log.w(TAG, "$reason failed: ${result.failure} ${result.detail.orEmpty()}")
            }
        }
    }

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
            if (shouldShowIsland(ConfigManager.ISLAND_SHOW_TIMING_CONNECTED)) {
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, battery, mDevice)
            }
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, battery, mDevice)
        lastTempBatt = when {
            left.isConnected && right.isConnected -> minOf(left.battery, right.battery)
            left.isConnected -> left.battery
            right.isConnected -> right.battery
            else -> SystemApisUtils.BATTERY_LEVEL_UNKNOWN
        }
        setRegularBatteryLevel(lastTempBatt)
    }

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
