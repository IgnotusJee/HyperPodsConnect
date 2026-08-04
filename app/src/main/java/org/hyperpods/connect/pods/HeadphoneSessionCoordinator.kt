package org.hyperpods.connect.pods

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.PowerManager
import android.os.UserManager
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
import org.hyperpods.connect.BuildConfig
import org.hyperpods.connect.config.ConfigManager
import org.hyperpods.connect.hook.Log
import org.hyperpods.connect.integration.HeadphonePresentationController
import org.hyperpods.connect.integration.HeadphonePresentationEffects
import org.hyperpods.connect.integration.HyperOsBluetoothBatteryBridge
import org.hyperpods.connect.ipc.IpcSenderPolicy
import org.hyperpods.connect.ipc.isSentFrom
import org.hyperpods.connect.runtime.bluetoothprocess.BluetoothProcessRuntimeHost
import org.hyperpods.connect.utils.SystemApisUtils
import org.hyperpods.connect.utils.SystemApisUtils.setIconVisibility
import org.hyperpods.connect.utils.miuiStrongToast.MiuiStrongToastUtil
import org.hyperpods.connect.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation
import org.hyperpods.connect.ipc.HeadphoneActionContract
import org.hyperpods.connect.integration.BatterySlotPresentation

/**
 * Brand-neutral owner of the active Android headset session.
 *
 * Session authority and protocol work live in [BluetoothProcessRuntimeHost]. This coordinator
 * translates Android events and renders the effects selected by [HeadphonePresentationController].
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object HeadphoneSessionCoordinator : HeadphonePresentationEffects {
    private const val TAG = "HyperPods-Session"

    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    private lateinit var currentHeadphoneBatteryPresentation: HeadphoneBatteryPresentation
    private var currentAnc = 1
    private var currentGameMode = false
    private var autoGameModeEnabled = false
    private var lastGameModeStatusUpdateMs = 0L
    private var lastKnownCaseBattery = 0
    private var lastKnownCaseCharging = false
    private var cachedDeviceName = ""
    private var currentTopology: String? = null
    private var canCycleNoiseControl = false
    private var availableNoiseControlModes: Set<String> = emptySet()
    private var currentProfileGroupId: Int? = null
    private var receiverRegistered = false
    private var currentWearStatus = WearStatus()
    private val rawHexSessionGate = RawHexSessionGate(BuildConfig.ALLOW_RAW_PROTOCOL_CONSOLE)

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readyGeneration = -1L
    private val connectedPopupCoordinator = ConnectedPopupCoordinator()
    private val presentationController = HeadphonePresentationController(this)

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isSentFrom(IpcSenderPolicy.allowedControlSenders(intent?.action))) return
            intent?.let(::handleUIEvent)
        }
    }

    private fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            HeadphoneActionContract.ACTION_HEADPHONE_UI_CLOSED -> {
                rawHexSessionGate.lock()
            }
            HeadphoneActionContract.ACTION_AUTO_GAME_MODE_CHANGED ->
                autoGameModeEnabled = intent.getBooleanExtra("enabled", autoGameModeEnabled)
            HeadphoneActionContract.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED -> Unit
            HeadphoneActionContract.ACTION_CYCLE_ANC -> cycleAnc()
            HeadphoneActionContract.ACTION_CONFIG_CHANGED -> {
                ConfigManager.refreshFromPrefs(mPrefs)
                if (
                    "ADAPTIVE" !in availableNoiseControlModes &&
                    currentAnc == 4
                ) setANCMode(2)
            }
            HeadphoneActionContract.ACTION_RFCOMM_LOG_CONNECT -> {
                if (!RfcommLog.isEnabled()) RfcommLog.setEnabled(true, mContext)
            }
            HeadphoneActionContract.ACTION_RFCOMM_LOG_DISCONNECT -> RfcommLog.setEnabled(false)
            HeadphoneActionContract.ACTION_RFCOMM_LOG_CLEAR -> RfcommLog.clear()
            HeadphoneActionContract.ACTION_RFCOMM_DEBUG_UNLOCK -> {
                val token = intent.getStringExtra(
                    HeadphoneActionContract.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN,
                ).orEmpty()
                if (rawHexSessionGate.unlock(token)) {
                    RfcommLog.w(mContext, "RFCOMM/DEBUG", "raw HEX unlocked for this debug-page session")
                } else {
                    RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX is unavailable in release builds")
                }
            }
            HeadphoneActionContract.ACTION_RFCOMM_DEBUG_LOCK -> {
                rawHexSessionGate.lock(
                    intent.getStringExtra(HeadphoneActionContract.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN),
                )
                RfcommLog.i(mContext, "RFCOMM/DEBUG", "raw HEX locked")
            }
            HeadphoneActionContract.ACTION_RFCOMM_DEBUG_SEND -> sendDebugHex(
                intent.getStringExtra("hex").orEmpty(),
                intent.getStringExtra(HeadphoneActionContract.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN),
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

    fun connectPodFromProfile(
        context: Context,
        device: BluetoothDevice,
        prefs: SharedPreferences,
        profileGroupId: Int?,
    ) = connectPod(
        context,
        device,
        prefs,
        automatic = false,
        profileConnected = true,
        profileGroupId = profileGroupId,
    )

    private fun connectPod(
        context: Context,
        device: BluetoothDevice,
        prefs: SharedPreferences,
        automatic: Boolean,
        profileConnected: Boolean = false,
        profileGroupId: Int? = null,
    ) {
        mPrefs = prefs
        autoGameModeEnabled = mPrefs.getBoolean("auto_game_mode", false)
        ConfigManager.refreshFromPrefs(mPrefs)
        if (
            automatic &&
            !BluetoothProcessRuntimeHost.canAutoConnect(context, device)
        ) return
        if (
            profileConnected &&
            !BluetoothProcessRuntimeHost.canConnectFromProfile(context, device)
        ) return
        if (profileConnected && isCurrentProfileDevice(device, profileGroupId)) {
            if (!isCurrentDevice(device)) {
                // A previous build may have published a focus notification for another
                // member address. The group session owns only the selected member now.
                cancelPodsNotificationByMiuiBt(context, device)
            }
            Log.i(TAG, "ignore duplicate connected profile member group=$profileGroupId")
            return
        }

        mContext = context
        mDevice = device
        currentProfileGroupId = profileGroupId
        cachedDeviceName = device.name ?: ""

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                addAction(HeadphoneActionContract.ACTION_HEADPHONE_UI_CLOSED)
                addAction(HeadphoneActionContract.ACTION_AUTO_GAME_MODE_CHANGED)
                addAction(HeadphoneActionContract.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED)
                addAction(HeadphoneActionContract.ACTION_CYCLE_ANC)
                addAction(HeadphoneActionContract.ACTION_CONFIG_CHANGED)
                addAction(HeadphoneActionContract.ACTION_RFCOMM_LOG_CONNECT)
                addAction(HeadphoneActionContract.ACTION_RFCOMM_LOG_DISCONNECT)
                addAction(HeadphoneActionContract.ACTION_RFCOMM_LOG_CLEAR)
                HeadphoneActionContract.RFCOMM_DEBUG_CONTROL_ACTIONS.forEach(::addAction)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        isConnected = true
        lifecycleScope.launch {
            if (!profileConnected) delay(500)
            if (isConnected) {
                if (profileConnected) {
                    BluetoothProcessRuntimeHost.connectFromProfile(
                        context,
                        device,
                    )
                } else if (automatic) {
                    BluetoothProcessRuntimeHost.connectAutomatically(
                        context,
                        device,
                    )
                } else {
                    BluetoothProcessRuntimeHost.connect(
                        context,
                        device,
                    )
                }
            }
        }
    }

    fun onEngineSnapshot(snapshot: HeadphoneSnapshot) {
        if (!::mDevice.isInitialized || snapshot.deviceId.value !=
            moe.chenxy.headphones.core.device.DeviceId.fromAddress(mDevice.address).value
        ) return
        val nextTopology = snapshot.profile?.topology?.name
        val nextCanCycleNoiseControl = snapshot.profile?.canWrite(FeatureId.NOISE_CONTROL) == true
        val nextNoiseControlModes = snapshot.profile
            ?.capability(FeatureId.NOISE_CONTROL)
            ?.allowedValues
            .orEmpty()
        val nextDeviceName = snapshot.profile?.model?.takeIf(String::isNotBlank) ?: cachedDeviceName
        currentTopology = nextTopology
        canCycleNoiseControl = nextCanCycleNoiseControl
        availableNoiseControlModes = nextNoiseControlModes
        cachedDeviceName = nextDeviceName
        val notificationReady = snapshot.connection is SessionState.Ready
        if (notificationReady) {
            (mContext?.getSystemService("statusbar") as? StatusBarManager)
                ?.setIconVisibility("wireless_headset", true)
        }
        snapshot.state.noiseControl.confirmed?.let { currentAnc = coreNoiseModeToStatus(it) }
        snapshot.state.lowLatency.confirmed?.let {
            if (currentGameMode != it) lastGameModeStatusUpdateMs = SystemClock.elapsedRealtime()
            currentGameMode = it
        }
        (snapshot.connection as? SessionState.Failed)?.let { connection ->
            Log.e(TAG, "engine session failed: ${connection.detail.orEmpty()}")
            RfcommLog.e(mContext, TAG, "session failed: ${connection.detail.orEmpty()}")
        }
        presentationController.onSnapshot(snapshot, currentProfileGroupId, mDevice.address)
    }

    fun onEngineOperation(event: OperationEvent) {
        RfcommLog.d(
            mContext,
            "OPERATION",
            "${event.requestId.value} ${event.command.featureId} ${event.phase}" +
                event.detail?.let { " $it" }.orEmpty(),
        )
    }

    override fun onNotificationInvalidated(connection: SessionState) {
        clearConnectedNotification(connection)
    }

    override fun onBatteryChanged(snapshot: HeadphoneSnapshot, allowConnectedPresentation: Boolean) {
        handleBatteryChanged(snapshot.state.batteries, allowConnectedPresentation)
    }

    override fun onWearingChanged(previous: HeadphoneState, current: HeadphoneState) {
        val oldWear = currentWearStatus
        currentWearStatus = WearStatus(
            left = current.wearing[WearComponent.LEFT]?.toPlatformWearState(),
            right = current.wearing[WearComponent.RIGHT]?.toPlatformWearState(),
            case = oldWear.case,
        )
        showIslandForWearStatusChange(oldWear, currentWearStatus)
    }

    override fun onReady(snapshot: HeadphoneSnapshot, enteringReady: Boolean) {
        if (readyGeneration != snapshot.generationId) {
            readyGeneration = snapshot.generationId
            Log.d(TAG, "engine session ready generation=${snapshot.generationId}")
            RfcommLog.i(mContext, TAG, "session ready generation=${snapshot.generationId}")
            if (autoGameModeEnabled) lifecycleScope.launch { enableGameModeOnConnect() }
        }
        maybeShowConnectedPopup(snapshot, enteringReady)
    }

    private fun moe.chenxy.headphones.core.feature.WearState.toPlatformWearState(): WearState =
        when (this) {
            moe.chenxy.headphones.core.feature.WearState.WEARING -> WearState.WEARING
            moe.chenxy.headphones.core.feature.WearState.REMOVED -> WearState.REMOVED
            moe.chenxy.headphones.core.feature.WearState.IN_CASE -> WearState.IN_CASE
            moe.chenxy.headphones.core.feature.WearState.UNKNOWN -> WearState.DISCONNECTED
        }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        BluetoothProcessRuntimeHost.disconnectAsync(DisconnectCause.REQUESTED)
        (context.getSystemService("statusbar") as? StatusBarManager)
            ?.setIconVisibility("wireless_headset", false)

        cancelPodsNotificationByMiuiBt(context, device)
        mContext?.let {
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
        currentTopology = null
        canCycleNoiseControl = false
        availableNoiseControlModes = emptySet()
        currentProfileGroupId = null
        readyGeneration = -1L
        presentationController.reset()
        mContext = null
    }

    fun onBluetoothProfileDisconnected(
        context: Context,
        device: BluetoothDevice,
        profileGroupId: Int? = null,
    ) {
        if (isCurrentDevice(device)) {
            disconnectedPod(context, device)
        } else if (isCurrentProfileDevice(device, profileGroupId)) {
            // The LE Audio group can report each member separately. Keep the active group
            // session, but clear any per-address notification left by an older session.
            cancelPodsNotificationByMiuiBt(context, device)
            Log.d(TAG, "ignore disconnect of non-session profile member group=$profileGroupId")
        } else {
            // A process restart loses the in-memory session, but its system notification may
            // survive. Cancelling by device address also reconciles that stale state.
            cancelPodsNotificationByMiuiBt(context, device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isCurrentProfileDevice(device: BluetoothDevice, profileGroupId: Int?): Boolean {
        if (!isConnected || !::mDevice.isInitialized) return false
        if (mDevice.address.equals(device.address, ignoreCase = true)) return true
        if (
            currentProfileGroupId != null &&
            profileGroupId != null &&
            currentProfileGroupId == profileGroupId
        ) return true
        val currentName = runCatching { mDevice.name ?: mDevice.alias }.getOrNull()?.trim()
        val incomingName = runCatching { device.name ?: device.alias }.getOrNull()?.trim()
        return !currentName.isNullOrEmpty() && currentName.equals(incomingName, ignoreCase = true)
    }

    private fun clearConnectedNotification(connection: SessionState) {
        val generation = readyGeneration
        readyGeneration = -1L
        mShowedConnectedToast = false
        mContext?.let { cancelPodsNotificationByMiuiBt(it, mDevice) }
        Log.d(TAG, "cancel connected notification: generation=$generation state=$connection")
        RfcommLog.i(
            mContext,
            TAG,
            "cancel connected notification: generation=$generation state=$connection",
        )
    }

    private fun maybeShowConnectedPopup(snapshot: HeadphoneSnapshot, enteringReady: Boolean) {
        val context = mContext ?: return
        val candidate = connectedPopupCoordinator.claim(
            enabled = ConfigManager.connectionPopupEnabled(),
            transportConnected = isConnected,
            protocolReady = snapshot.connection is SessionState.Ready,
            readyEdge = enteringReady,
            generationId = snapshot.generationId,
            address = mDevice.address,
            deviceId = snapshot.deviceId.value,
            batteryLevels = snapshot.state.batteries.values.map(BatteryState::level),
            nowMs = SystemClock.elapsedRealtime(),
        ) ?: return

        if (!isConnectedPopupEnvironmentEligible(context)) {
            Log.d(TAG, "connected popup skipped by foreground environment gate")
            return
        }

        val intent = Intent().apply {
            setClassName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.PopupActivity")
            putExtra("android.bluetooth.device.extra.DEVICE", mDevice)
            putExtra(ConnectedPopupContract.EXTRA_FORCE_MODULE_POPUP, true)
            putExtra(ConnectedPopupContract.EXTRA_CONNECTION_EDGE_POPUP, true)
            putExtra(ConnectedPopupContract.EXTRA_EXPECTED_GENERATION, candidate.generationId)
            putExtra(ConnectedPopupContract.EXTRA_EXPECTED_EMITTED_AT, snapshot.emittedAtMillis)
            putExtra(ConnectedPopupContract.EXTRA_EXPECTED_DEVICE_ID, candidate.deviceId)
            putExtra(ConnectedPopupContract.EXTRA_EXPECTED_ADDRESS, candidate.address)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onSuccess {
                Log.d(TAG, "connected popup requested generation=${candidate.generationId}")
                RfcommLog.i(context, TAG, "connected popup requested generation=${candidate.generationId}")
            }
            .onFailure { error ->
                Log.w(TAG, "connected popup launch failed; notification remains available: ${error.message}")
                RfcommLog.w(context, TAG, "connected popup launch failed: ${error.message}")
            }
    }

    private fun isConnectedPopupEnvironmentEligible(context: Context): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        val audioMode = context.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        val inCall = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
        return interactive && unlocked && !inCall
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
        val cycle = if ("ADAPTIVE" in availableNoiseControlModes) {
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
                RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX denied: no active debug session")
            }
        }
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

    fun handleBatteryChanged(
        result: Map<BatteryComponent, BatteryState>,
        allowConnectedPresentation: Boolean = true,
    ) {
        val leftState = result[BatteryComponent.LEFT] ?: result[BatteryComponent.SINGLE]
        val singleState = result[BatteryComponent.SINGLE]
        val rightState = result[BatteryComponent.RIGHT]
        val caseState = result[BatteryComponent.CASE]
        val left = BatterySlotPresentation(leftState?.level ?: 0, leftState?.charging == true, leftState != null, 0)
        val right = BatterySlotPresentation(rightState?.level ?: 0, rightState?.charging == true, rightState != null, 0)
        val case = if (caseState != null) {
            lastKnownCaseBattery = caseState.level
            lastKnownCaseCharging = caseState.charging
            BatterySlotPresentation(caseState.level, caseState.charging, true, 0)
        } else {
            BatterySlotPresentation(lastKnownCaseBattery, lastKnownCaseCharging, false, 0)
        }
        val shouldShowToast = allowConnectedPresentation && !mShowedConnectedToast
        if (!allowConnectedPresentation) mShowedConnectedToast = true
        if (shouldShowToast) {
            val valid = (left.isConnected && left.battery > 0) ||
                (right.isConnected && right.battery > 0)
            if (!valid) return
        }
        val battery = HeadphoneBatteryPresentation(
            left = left,
            right = right,
            case = case,
            single = singleState?.let {
                BatterySlotPresentation(it.level, it.charging, true, 0)
            },
            deviceName = cachedDeviceName,
            topology = currentTopology,
            canCycleNoiseControl = canCycleNoiseControl,
        )
        currentHeadphoneBatteryPresentation = battery
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
        if (!::currentHeadphoneBatteryPresentation.isInitialized) return
        val changes = setOfNotNull(
            islandShowTimingForChange(previous.left, current.left),
            islandShowTimingForChange(previous.right, current.right),
            islandShowTimingForChange(previous.case, current.case),
        )
        if (changes.any(::shouldShowIsland)) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(
                mContext ?: return,
                currentHeadphoneBatteryPresentation,
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
        val context = mContext ?: return
        HyperOsBluetoothBatteryBridge.setBatteryLevel(context, mDevice, level)
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
