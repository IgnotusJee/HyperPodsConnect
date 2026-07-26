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
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.SystemClock
import moe.chenxy.oppopods.hook.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.TransportWriteResult
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.core.RfcommTransportBridge
import moe.chenxy.oppopods.utils.MediaControl
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import android.content.SharedPreferences
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "OppoPods-RfcommController"
    private const val AUTO_RECONNECT_DELAY_MS = 120_000L
    private const val APP_UI_ACTIVE_TIMEOUT_MS = 75_000L

    // Basic Objects
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    /** -1 = unknown / not in smart mode; otherwise a [NoiseControlMode].ordinal of the
     *  level smart mode is currently auto-applying (light/medium/deep). */
    private var currentSmartAncLevel: Int = -1
    private var currentGameMode: Boolean = false
    private var currentTransparencyVocalEnhancement: Boolean = false
    private var currentSpatialAudioMode: Int = SpatialAudioMode.OFF
    /** -1 = unknown; otherwise one of [EqPreset.ALL]. */
    private var currentEqPreset: Int = -1
    private var currentDualDeviceConnection: Boolean = false
    private var autoGameModeEnabled: Boolean = false
    private var gameModeImplementation: GameModeImplementation = GameModeImplementation.STANDARD
    private var lastGameModeStatusUpdateMs: Long = 0L
    private var lastKnownCaseBattery: Int = 0
    private var lastKnownCaseCharging: Boolean = false
    private var cachedDeviceName: String = ""
    private var receiverRegistered = false
    private var routeScanStarted = false
    private var appUiActive = false
    private var appUiActiveUntilMs = 0L
    private val connectionStateObservable = RfcommConnectionStateObservable()
    private val rawHexSessionGate = RawHexSessionGate(BuildConfig.DEBUG)

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

    // RFCOMM jobs
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var transportCloseJob: kotlinx.coroutines.Job? = null
    private val transportLifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectPending = false
    private val OPPO_RFCOMM_UUID: UUID = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
    private val transportBridge by lazy {
        RfcommTransportBridge(
            serviceUuid = OPPO_RFCOMM_UUID,
            onRawChunk = { chunk ->
                RfcommLog.d(mContext, "RFCOMM/RX", chunk.toHexString(HexFormat.UpperCase))
            },
            onFrame = ::handleOppoPacket,
            onDisconnected = { cause, detail ->
                if (isConnected) {
                    Log.e(TAG, "RFCOMM disconnected: cause=$cause detail=${detail.orEmpty()}")
                    RfcommLog.e(mContext, TAG, "disconnected cause=$cause detail=${detail.orEmpty()}")
                    changeUIConnectionState("error")
                    scheduleReconnect("transport failure: $cause")
                }
            },
        )
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            handleUIEvent(p1!!)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 8) return
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            this.putExtra("status", status)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", status)
            putBatteryExtras(status)
        }
    }

    private var currentWearStatus = WearStatus()

    private fun changeUIWearStatus(status: WearStatus) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            if (::mDevice.isInitialized) this.putExtra("address", mDevice.address)
            putWearStatusExtras(status)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            putWearStatusExtras(status)
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            this.putExtra("enabled", enabled)
        }
    }

    private fun changeUITransparencyVocalEnhancementStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            this.putExtra("enabled", enabled)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            this.putExtra("mode", mode)
        }
    }

    private fun changeUIEqPreset(presetId: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            this.putExtra("preset", presetId)
        }
    }

    private fun changeUISmartAncLevel(ordinal: Int) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED) {
            this.putExtra("ordinal", ordinal)
        }
    }

    private fun changeUIDualDeviceConnectionStatus(enabled: Boolean) {
        sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED) {
            this.putExtra("enabled", enabled)
        }
    }

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                markAppUiActive()
                Log.i(TAG, "UI Init")
                changeUIConnectionState(currentConnectionState())
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
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
                        this.putExtra("address", mDevice.address)
                        this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
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
                Log.i(TAG, "UI Closed")
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                queryStatus(immediateReconnect = true)
            }
            OppoPodsAction.ACTION_GAME_MODE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setGameMode(enabled)
            }
            OppoPodsAction.ACTION_AUTO_GAME_MODE_CHANGED -> {
                autoGameModeEnabled = intent.getBooleanExtra("enabled", autoGameModeEnabled)
                Log.d(TAG, "Auto game mode synced: $autoGameModeEnabled")
            }
            OppoPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED -> {
                gameModeImplementation = GameModeImplementation.fromPreference(
                    intent.getStringExtra(GameModeImplementation.PREF_KEY)
                )
                Log.d(TAG, "Game mode implementation synced: ${gameModeImplementation.preferenceValue}")
            }
            OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setTransparencyVocalEnhancement(enabled)
            }
            OppoPodsAction.ACTION_SPATIAL_AUDIO_SET -> {
                val mode = intent.getIntExtra("mode", SpatialAudioMode.OFF)
                setSpatialAudioMode(mode)
            }
            OppoPodsAction.ACTION_EQ_PRESET_SET -> {
                val preset = intent.getIntExtra("preset", -1)
                if (preset in EqPreset.ALL) setEqPreset(preset)
            }
            OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setDualDeviceConnection(enabled)
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_CONFIG_CHANGED -> {
                ConfigManager.refreshFromPrefs(mPrefs)
                Log.d(TAG, "Config synced")
                if (!currentCapabilities().adaptiveSupported && currentAnc == 4) {
                    setANCMode(2)
                }
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT -> {
                if (!RfcommLog.isEnabled()) {
                    RfcommLog.setEnabled(true, mContext)
                }
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT -> {
                RfcommLog.setEnabled(false)
            }
            OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR -> {
                RfcommLog.clear()
            }
            OppoPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK -> {
                val sessionToken = intent.getStringExtra(OppoPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN).orEmpty()
                if (rawHexSessionGate.unlock(sessionToken)) {
                    RfcommLog.w(mContext, "RFCOMM/DEBUG", "raw HEX unlocked for this debug-page session")
                } else {
                    RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX is unavailable in release builds")
                }
            }
            OppoPodsAction.ACTION_RFCOMM_DEBUG_LOCK -> {
                rawHexSessionGate.lock(intent.getStringExtra(OppoPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN))
                RfcommLog.i(mContext, "RFCOMM/DEBUG", "raw HEX locked")
            }
            OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND -> {
                val hex = intent.getStringExtra("hex").orEmpty()
                val sessionToken = intent.getStringExtra(OppoPodsAction.EXTRA_RFCOMM_DEBUG_SESSION_TOKEN)
                sendDebugHex(hex, sessionToken)
            }
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = if (::currentBatteryParams.isInitialized) currentBatteryParams else null,
            anc = currentAnc,
            transparencyVocalEnhancement = currentTransparencyVocalEnhancement,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName.takeIf { it.isNotEmpty() },
            connected = isConnected && transportBridge.isOpen,
            connecting = connectionJob?.isActive == true,
            reconnectPending = reconnectPending,
        )
    }

    private fun currentConnectionState(): String = when {
        isConnected && transportBridge.isOpen && ::currentBatteryParams.isInitialized -> "connected"
        connectionJob?.isActive == true || reconnectPending -> "connecting"
        isConnected && transportBridge.isOpen -> "connecting"
        else -> "disconnected"
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
        val ctx = mContext ?: return
        if (!isAppUiActive()) return
        Intent(action).apply {
            fill()
            this.`package` = BuildConfig.APPLICATION_ID
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun isAppUiActive(): Boolean {
        if (!appUiActive) return false
        if (SystemClock.elapsedRealtime() <= appUiActiveUntilMs) return true
        appUiActive = false
        appUiActiveUntilMs = 0L
        Log.d(TAG, "app UI active timeout, stop app status broadcasts")
        return false
    }

    private fun markAppUiActive() {
        appUiActive = true
        appUiActiveUntilMs = SystemClock.elapsedRealtime() + APP_UI_ACTIVE_TIMEOUT_MS
    }


    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = miuiAncLevel(anc, transparencyVocalEnhancement)
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

    private fun miuiAncLevel(anc: Int, transparencyVocalEnhancement: Boolean): String {
        // MIUI level codes are not ordered like OPPO payloads:
        // 0103=Smart, 0101=Light, 0100=Medium, 0102=Deep, 0201=Transparency vocal enhancement.
        return when (anc) {
            5 -> "0103"
            6 -> "0101"
            7 -> "0100"
            8 -> "0102"
            3 -> if (transparencyVocalEnhancement) "0201" else "0200"
            else -> "0000"
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        val left = PodParams(
            result.left?.level ?: 0,
            result.left?.isCharging == true,
            result.left != null,
            0
        )
        val right = PodParams(
            result.right?.level ?: 0,
            result.right?.isCharging == true,
            result.right != null,
            0
        )
        val case = if (result.case != null) {
            lastKnownCaseBattery = result.case.level
            lastKnownCaseCharging = result.case.isCharging
            PodParams(
                result.case.level,
                result.case.isCharging,
                true,
                0
            )
        } else {
            PodParams(
                lastKnownCaseBattery,
                lastKnownCaseCharging,
                false,
                0
            )
        }

        Log.v(TAG, "batt left ${left.battery} right ${right.battery} case ${case.battery}")

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            // Wait until at least one connected ear has valid battery data
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (!hasValidData) return
        }

        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            changeUIConnectionState("connected")
            sendAppStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                this.putExtra("address", mDevice.address)
                this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                putExtra("device_name", mDevice.name ?: cachedDeviceName)
            }
            if (shouldShowIsland(ConfigManager.ISLAND_SHOW_TIMING_CONNECTED)) {
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, batteryParams, mDevice)
            }
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, batteryParams, mDevice)
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
            minOf(left.battery, right.battery)
        else if (left.isConnected)
            left.battery
        else if (right.isConnected)
            right.battery
        else SystemApisUtils.BATTERY_LEVEL_UNKNOWN

        setRegularBatteryLevel(lastTempBatt)
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            Log.v(TAG, "routes updated: $routes")
            this@RfcommController.routes = routes
        }
    }

    private fun startRoutesScan() {
        if (routeScanStarted) return
        val executor = Executor { p0 ->
            CoroutineScope(Dispatchers.IO).launch { p0?.run() }
        }
        val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
        mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
        routeScanStarted = true
    }

    private fun stopRoutesScan() {
        scanToken?.let { mediaRouter.cancelScanRequest(it) }
        if (routeScanStarted) {
            mediaRouter.unregisterRouteCallback(routeCallback)
            routeScanStarted = false
        }
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences, appRequested: Boolean = false) {
        connectionJob?.cancel()
        reconnectJob?.cancel()
        closeTransportOnly()
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        if (appRequested) {
            markAppUiActive()
        }
        autoGameModeEnabled = mPrefs.getBoolean("auto_game_mode", false)
        gameModeImplementation = GameModeImplementation.fromPreference(
            mPrefs.getString(GameModeImplementation.PREF_KEY, null)
        )
        ConfigManager.refreshFromPrefs(mPrefs)
        Log.d(TAG, "Adaptive support initial: ${currentCapabilities().adaptiveSupported}")
        Log.d(TAG, "Auto game mode initial: $autoGameModeEnabled")
        Log.d(TAG, "Game mode implementation initial: ${gameModeImplementation.preferenceValue}")
        Log.d(TAG, "RFCOMM UUID initial: $OPPO_RFCOMM_UUID")

        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
                this.addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
                this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
                this.addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
                this.addAction(OppoPodsAction.ACTION_AUTO_GAME_MODE_CHANGED)
                this.addAction(OppoPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED)
                this.addAction(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET)
                this.addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
                this.addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
                this.addAction(OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET)
                this.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                this.addAction(OppoPodsAction.ACTION_CONFIG_CHANGED)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
                this.addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
                OppoPodsAction.RFCOMM_DEBUG_CONTROL_ACTIONS.forEach(this::addAction)
            }, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isConnected = true
        changeUIConnectionState("connecting")

        connectRfcomm(initialDelayMs = 500L)

    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
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

    private fun mergeWearStatus(current: WearStatus, update: WearStatus): WearStatus {
        return WearStatus(
            left = update.left ?: current.left,
            right = update.right ?: current.right,
            case = update.case ?: current.case
        )
    }

    private fun showIslandForWearStatusChange(previous: WearStatus, current: WearStatus) {
        if (!::currentBatteryParams.isInitialized) return
        val changedTimings = setOfNotNull(
            islandShowTimingForChange(previous.left, current.left),
            islandShowTimingForChange(previous.right, current.right),
            islandShowTimingForChange(previous.case, current.case),
        )
        if (changedTimings.any { shouldShowIsland(it) }) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext ?: return, currentBatteryParams)
        }
    }

    private fun shouldShowIsland(timing: Int): Boolean {
        return ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_MODULE &&
                timing in ConfigManager.islandShowTimings()
    }

    private fun islandShowTimingForChange(previous: WearState?, current: WearState?): Int? {
        if (previous == current) return null
        return when (current) {
            WearState.WEARING -> ConfigManager.ISLAND_SHOW_TIMING_WEARING
            WearState.REMOVED -> ConfigManager.ISLAND_SHOW_TIMING_REMOVED
            WearState.IN_CASE -> ConfigManager.ISLAND_SHOW_TIMING_IN_CASE
            else -> null
        }
    }

    private fun connectRfcomm(initialDelayMs: Long = 0L) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            transportCloseJob?.join()
            if (initialDelayMs > 0) delay(initialDelayMs)
            if (!isConnected || !::mDevice.isInitialized) return@launch
            reconnectPending = false
            val failure = transportBridge.connect(mDevice)
            if (failure == null) {
                reconnectAttempts.set(0)
                reconnectPending = false
                Log.d(TAG, "RFCOMM connected! uuid=$OPPO_RFCOMM_UUID")
                RfcommLog.i(mContext, TAG, "connected uuid=$OPPO_RFCOMM_UUID")
                changeUIConnectionState("connecting")

                delay(300)
                // Ask the bud which notifications it can push; we subscribe to the
                // advertised list (minus 0xFx debug channels) in handleOppoPacket.
                sendPacketSafe(Enums.QUERY_NOTIFICATION_SUPPORT)
                delay(50)
                sendStatusQueryPackets(immediateReconnect = false)

                if (autoGameModeEnabled) {
                    enableGameModeOnConnect()
                }
            } else {
                Log.e(TAG, "RFCOMM connect failed: ${failure.detail.orEmpty()}")
                RfcommLog.e(mContext, TAG, "connect failed: ${failure.detail.orEmpty()}")
                changeUIConnectionState("error")
                scheduleReconnect("connect failed")
            }
        }
    }

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (!isConnected || !::mDevice.isInitialized || mContext == null) return
        if (!immediate && reconnectPending) {
            Log.d(TAG, "RFCOMM reconnect already pending reason=$reason")
            return
        }
        RfcommLog.w(mContext, TAG, "schedule reconnect reason=$reason immediate=$immediate")
        closeTransportOnly()
        reconnectPending = true
        if (immediate) {
            if (connectionJob?.isActive == true) {
                Log.d(TAG, "immediate RFCOMM reconnect skipped: connecting reason=$reason")
                return
            }
            Log.d(TAG, "immediate RFCOMM reconnect reason=$reason")
            reconnectJob?.cancel()
            reconnectJob = null
            connectRfcomm()
            return
        }
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "RFCOMM reconnect already scheduled reason=$reason")
            return
        }
        val attempt = reconnectAttempts.incrementAndGet()
        Log.d(TAG, "schedule RFCOMM reconnect reason=$reason attempt=$attempt delay=${AUTO_RECONNECT_DELAY_MS}ms")
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            reconnectJob = null
            connectRfcomm()
        }
    }

    private fun closeTransportOnly(cause: DisconnectCause =
        DisconnectCause.SUPERSEDED
    ) {
        transportCloseJob = transportLifecycleScope.launch {
            transportBridge.close(cause)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleOppoPacket(packet: ByteArray) {
        Log.v(TAG, "Received: ${packet.toHexString(HexFormat.UpperCase)}")

        // Subscribe handshake: the bud replies to QUERY_NOTIFICATION_SUPPORT with the
        // notification IDs it can push. Subscribe to all of them except the 0xFx debug
        // channels (f1/f2/f3 push high-rate diagnostic frames that only add latency);
        // id 0x03 in this list carries the smart-mode current-strength notify.
        NotificationSupportParser.parse(packet)?.let { ids ->
            val wanted = ids.filter { (it.toInt() and 0xFF) < 0xF0 }.toByteArray()
            Log.d(TAG, "Notification ids advertised=${ids.toHexString(HexFormat.UpperCase)} subscribing=${wanted.toHexString(HexFormat.UpperCase)}")
            CoroutineScope(Dispatchers.IO).launch {
                sendPacketSafe(Enums.registerMultiNotification(wanted))
            }
            return
        }

        // Smart-mode current noise-reduction level (cmd 0x0204 type 03 key 04).
        val smartLevel = SmartAncLevelParser.parse(packet)
        if (smartLevel != null) {
            Log.d(TAG, "Smart ANC current level: $smartLevel")
            val ord = smartLevel.ordinal
            if (ord != currentSmartAncLevel) {
                currentSmartAncLevel = ord
                changeUISmartAncLevel(ord)
            }
            return
        }

        // Try parse as battery response (query response, Cmd=0x8106)
        val batteryResult = BatteryParser.parse(packet)
        if (batteryResult != null) {
            handleBatteryChanged(batteryResult)
            return
        }

        // Try parse as active battery report (unsolicited, Cmd=0x0204, type=0x01)
        val activeResult = BatteryParser.parseActiveReport(packet)
        if (activeResult != null) {
            handleBatteryChanged(activeResult)
            return
        }

        val wearResult = WearStatusParser.parse(packet)
        if (wearResult != null) {
            Log.d(TAG, "Wear status received: $wearResult")
            val previousWearStatus = currentWearStatus
            currentWearStatus = mergeWearStatus(currentWearStatus, wearResult)
            changeUIWearStatus(currentWearStatus)
            showIslandForWearStatusChange(previousWearStatus, currentWearStatus)
            return
        }

        val spatialAudioResult = SpatialAudioParser.parseModeNotify(packet)
        if (spatialAudioResult != null) {
            Log.i(TAG, "Spatial audio mode notify: packet=${packet.toHexString(HexFormat.UpperCase)}, mode=$spatialAudioResult")
            currentSpatialAudioMode = spatialAudioResult
            changeUISpatialAudioStatus(spatialAudioResult)
            return
        }

        val spatialAudioSetStatus = SpatialAudioParser.parseSetResponseStatus(packet)
        if (spatialAudioSetStatus != null) {
            Log.i(TAG, "Spatial audio set response: packet=${packet.toHexString(HexFormat.UpperCase)}, status=$spatialAudioSetStatus")
            return
        }

        val spatialSoundSwitchEnabled = SpatialAudioParser.parseSpatialSoundSwitchSetResponse(packet)
        if (spatialSoundSwitchEnabled != null) {
            Log.i(TAG, "Spatial sound switch response: packet=${packet.toHexString(HexFormat.UpperCase)}, enabled=$spatialSoundSwitchEnabled")
            currentSpatialAudioMode = if (spatialSoundSwitchEnabled) SpatialAudioMode.FIXED else SpatialAudioMode.OFF
            changeUISpatialAudioStatus(currentSpatialAudioMode)
            return
        }

        // EQ preset (handles both 0x0504 push notify and 0x810F query response)
        val eqPresetResult = EqPresetParser.parse(packet)
        if (eqPresetResult != null) {
            Log.d(TAG, "EQ preset received: $eqPresetResult")
            currentEqPreset = eqPresetResult
            changeUIEqPreset(eqPresetResult)
            return
        }

        // Try parse as ANC mode response
        val ancResult = AncModeParser.parse(packet, currentCapabilities().ancImplementation)
        if (ancResult != null) {
            Log.d(TAG, "ANC mode received: $ancResult")
            currentAnc = when (ancResult) {
                NoiseControlMode.OFF -> 1
                NoiseControlMode.NOISE_CANCELLATION -> 2
                NoiseControlMode.NOISE_CANCELLATION_SMART -> 5
                NoiseControlMode.NOISE_CANCELLATION_LIGHT -> 6
                NoiseControlMode.NOISE_CANCELLATION_MEDIUM -> 7
                NoiseControlMode.NOISE_CANCELLATION_DEEP -> 8
                NoiseControlMode.TRANSPARENCY -> 3
                NoiseControlMode.ADAPTIVE -> 4
            }
            changeUIAncStatus(currentAnc)

            val transparencyVocalEnhancementResult = TransparencyVocalEnhancementParser.parse(packet)
            if (transparencyVocalEnhancementResult != null) {
                Log.d(TAG, "Transparency vocal enhancement received: $transparencyVocalEnhancementResult")
                currentTransparencyVocalEnhancement = transparencyVocalEnhancementResult
                changeUITransparencyVocalEnhancementStatus(transparencyVocalEnhancementResult)
            }
            return
        }

        val transparencyVocalEnhancementResult = TransparencyVocalEnhancementParser.parse(packet)
        if (transparencyVocalEnhancementResult != null) {
            Log.d(TAG, "Transparency vocal enhancement received: $transparencyVocalEnhancementResult")
            currentTransparencyVocalEnhancement = transparencyVocalEnhancementResult
            changeUITransparencyVocalEnhancementStatus(transparencyVocalEnhancementResult)
            return
        }

        // Try parse as batch query response for switch features (Cmd=0x810D).
        val switchFeatureStatus = GameModeParser.parseStatus(packet)
        if (switchFeatureStatus != null) {
            val gameModeResult = switchFeatureStatus.enabledFor(gameModeImplementation)
            if (gameModeResult != null) {
                Log.d(TAG, "Game mode received: $gameModeResult")
                lastGameModeStatusUpdateMs = SystemClock.elapsedRealtime()
                currentGameMode = gameModeResult
                changeUIGameModeStatus(gameModeResult)
            }
            val dualDeviceConnectionResult = switchFeatureStatus.dualDeviceConnectionEnabled
            if (dualDeviceConnectionResult != null) {
                Log.d(TAG, "Dual-device connection received: $dualDeviceConnectionResult")
                currentDualDeviceConnection = dualDeviceConnectionResult
                changeUIDualDeviceConnectionStatus(dualDeviceConnectionResult)
            }
            return
        }

        val setFeatureResult = SwitchFeatureSetParser.parse(packet)
        if (setFeatureResult != null) {
            Log.d(TAG, "Switch feature response: status=${setFeatureResult.status}, value=${setFeatureResult.value}")
            if (setFeatureResult.featureId == GameModeFeature.DUAL_DEVICE_CONNECTION && setFeatureResult.value != null) {
                currentDualDeviceConnection = setFeatureResult.value == 0x01
                changeUIDualDeviceConnectionStatus(currentDualDeviceConnection)
            }
            return
        }

        // Unknown packet - log in debug
        Log.d(TAG, "Unknown OPPO packet: ${packet.toHexString(HexFormat.UpperCase)}")
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        connectionJob?.cancel()
        reconnectJob?.cancel()
        reconnectAttempts.set(0)
        reconnectPending = false

        closeTransportOnly(DisconnectCause.REQUESTED)

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

    private suspend fun sendPacketSafe(packet: ByteArray, requestReason: String? = null) {
        if (!transportBridge.isOpen) {
            RfcommLog.w(mContext, "RFCOMM/TX", "transport closed: ${packet.toHexString(HexFormat.UpperCase)}")
            scheduleReconnect("transport closed before send", immediate = requestReason != null)
            return
        }
        RfcommLog.d(mContext, "RFCOMM/TX", packet.toHexString(HexFormat.UpperCase))
        val result = transportBridge.send(packet)
        if (result is TransportWriteResult.Rejected) {
            val detail = result.failure.detail.orEmpty()
            Log.e(TAG, "Send packet failed: $detail")
            RfcommLog.e(mContext, TAG, "send failed: $detail")
            scheduleReconnect("send error", immediate = requestReason != null)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendDebugHex(hex: String, sessionToken: String? = null) {
        if (!rawHexSessionGate.canSend(sessionToken)) {
            RfcommLog.e(mContext, "RFCOMM/DEBUG", "raw HEX denied: unlock it in a debug-page session first")
            return
        }
        val normalized = hex.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (normalized.isEmpty() || normalized.length % 2 != 0 || !normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            RfcommLog.e(mContext, "RFCOMM/DEBUG", "invalid HEX: $hex")
            return
        }
        val packet = normalized.hexToByteArray()
        RfcommLog.i(mContext, "RFCOMM/DEBUG", "send ${packet.size} bytes")
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "rfcomm debug send")
        }
    }

    fun addConnectionStateObserver(observer: RfcommConnectionStateObserver, emitCurrent: Boolean = true) {
        connectionStateObservable.addObserver(observer, emitCurrent)
    }

    fun removeConnectionStateObserver(observer: RfcommConnectionStateObserver) {
        connectionStateObservable.removeObserver(observer)
    }

    fun setGameMode(enabled: Boolean) {
        Log.d(TAG, "setGameMode: $enabled")
        currentGameMode = enabled
        CoroutineScope(Dispatchers.IO).launch {
            sendGameModePackets(enabled, "game mode control")
        }
    }

    private suspend fun enableGameModeOnConnect() {
        delay(500)
        repeat(3) { attempt ->
            if (!isConnected || mContext == null) return

            val attemptStartedMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "Auto game mode: enabling after connect, attempt=${attempt + 1}, implementation=$gameModeImplementation")
            currentGameMode = true
            changeUIGameModeStatus(true)
            sendGameModePackets(true, "auto game mode")

            delay(300)
            if (!isConnected) return
            sendPacketSafe(Enums.QUERY_STATUS)

            delay(if (attempt == 0) 700 else 1_500)
            if (lastGameModeStatusUpdateMs >= attemptStartedMs && currentGameMode) {
                return
            }
            Log.d(TAG, "Auto game mode: attempt ${attempt + 1} did not verify, retrying")
        }
    }

    private suspend fun sendGameModePackets(enabled: Boolean, requestReason: String? = null) {
        Enums.gameModePackets(enabled, gameModeImplementation).forEachIndexed { index, packet ->
            if (index > 0) delay(120)
            sendPacketSafe(packet, if (index == 0) requestReason else null)
        }
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        Log.d(TAG, "setTransparencyVocalEnhancement: $enabled")
        currentTransparencyVocalEnhancement = enabled
        changeUITransparencyVocalEnhancementStatus(enabled)
        val packet = if (enabled) {
            Enums.TRANSPARENCY_VOCAL_ENHANCEMENT_ON
        } else {
            Enums.TRANSPARENCY_VOCAL_ENHANCEMENT_OFF
        }
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "transparency vocal enhancement control")
            delay(350)
            sendStatusQueryPackets(immediateReconnect = false)
        }
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        val packet = if (currentCapabilities().spatialSoundSwitchSupported) {
            Enums.spatialSoundSwitchPacket(normalizedMode != SpatialAudioMode.OFF)
        } else {
            Enums.spatialAudioPacket(normalizedMode)
        }
        Log.i(TAG, "setSpatialAudioMode: $normalizedMode, packet=${packet.toHexString(HexFormat.UpperCase)}")
        currentSpatialAudioMode = normalizedMode
        changeUISpatialAudioStatus(normalizedMode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "spatial audio control")
        }
    }

    fun setEqPreset(presetId: Int) {
        if (presetId !in EqPreset.ALL) {
            Log.w(TAG, "setEqPreset ignored: invalid preset $presetId")
            return
        }
        val packet = Enums.eqPresetPacket(presetId)
        Log.i(TAG, "setEqPreset: $presetId, packet=${packet.toHexString(HexFormat.UpperCase)}")
        currentEqPreset = presetId
        changeUIEqPreset(presetId)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "eq preset control")
        }
    }

    fun setDualDeviceConnection(enabled: Boolean) {
        val packet = Enums.dualDeviceConnectionPacket(enabled)
        Log.i(TAG, "setDualDeviceConnection: $enabled, packet=${packet.toHexString(HexFormat.UpperCase)}")
        currentDualDeviceConnection = enabled
        changeUIDualDeviceConnectionStatus(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "dual-device connection control")
        }
    }

    fun cycleAnc() {
        val cycle = if (currentCapabilities().adaptiveSupported) {
            listOf(2, 4, 3, 1)
        } else {
            listOf(2, 3, 1)
        }
        val currentIndex = cycle.indexOf(if (currentAnc in 5..8) 2 else currentAnc)
        val next = cycle[(currentIndex + 1).floorMod(cycle.size)]
        setANCMode(next)
    }

    private fun currentCapabilities(): DeviceCapabilities {
        return detectDeviceCapabilities(
            deviceName = if (::mDevice.isInitialized) mDevice.name ?: cachedDeviceName else cachedDeviceName,
            adaptiveOverride = ConfigManager.adaptiveCapabilityOverride(),
            spatialAudioOverride = ConfigManager.spatialAudioCapabilityOverride(),
            spatialSoundSwitchOverride = ConfigManager.spatialSoundSwitchCapabilityOverride(),
            ancImplementationOverride = ConfigManager.ancImplementationCapabilityOverride(),
        )
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        currentAnc = mode
        var packet = when (mode) {
            1 -> Enums.ANC_OFF
            2 -> Enums.ANC_NOISE_CANCEL
            3 -> Enums.ANC_TRANSPARENCY
            4 -> Enums.ANC_ADAPTIVE
            5 -> Enums.ANC_NOISE_CANCEL_SMART
            6 -> Enums.ANC_NOISE_CANCEL_LIGHT
            7 -> Enums.ANC_NOISE_CANCEL_MEDIUM
            8 -> Enums.ANC_NOISE_CANCEL_DEEP
            else -> return
        }
        if (currentCapabilities().ancImplementation == AncImplementation.COMPATIBLE) {
            packet = when (mode) {
                1 -> Enums.ANC_NOISE_CANCEL
                2 -> Enums.ANC_OFF
                else -> packet
            }
        }
        changeUIAncStatus(mode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "anc control")
            delay(350)
            sendStatusQueryPackets(immediateReconnect = false)
        }
    }

    fun queryBattery() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(Enums.QUERY_BATTERY, "battery query")
        }
    }

    /**
     * Combo query strategy: send batch query (wake + game mode), then battery, then ANC.
     */
    fun queryStatus(immediateReconnect: Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            sendStatusQueryPackets(immediateReconnect)
        }
    }

    private suspend fun sendStatusQueryPackets(immediateReconnect: Boolean = true) {
        val reason = if (immediateReconnect) "status query" else null
        sendPacketSafe(Enums.QUERY_STATUS, reason)
        delay(50)
        sendPacketSafe(Enums.QUERY_BATTERY, reason)
        delay(50)
        sendPacketSafe(Enums.QUERY_ANC, reason)
        delay(50)
        sendPacketSafe(Enums.QUERY_EQ, reason)
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        MediaControl.sendPause()

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            for (route in routes) {
                if (route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) {
                    Log.d(TAG, "found speaker route $route")
                    mediaRouter.transferTo(route)
                }
            }
        }

        setRegularBatteryLevel(lastTempBatt)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        for (route in routes) {
            if (route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && route.name == device!!.name) {
                Log.d(TAG, "found bt route $route")
                mediaRouter.transferTo(route)
            }
        }

        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
        statusBarManager.setIconVisibility("wireless_headset", true)
        setRegularBatteryLevel(lastTempBatt)
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            val service = getObjectField(mContext, "mAdapterService")
            callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (e: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", e)
        }
    }

    private fun getObjectField(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            }
            cls = cls.superclass
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
                it.isAccessible = true
                return it.invoke(instance, *args)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException(methodName)
    }
}
