package moe.chenxy.oppopods.hook.milink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotReceiver
import moe.chenxy.oppopods.ipc.IpcSenderPolicy
import moe.chenxy.oppopods.ipc.isSentFrom
import moe.chenxy.oppopods.ipc.sendIdentitySharedBroadcast
import moe.chenxy.oppopods.integration.HyperOsHeadphoneAdapter
import moe.chenxy.oppopods.integration.integrationAncFromMiLinkRuntime
import moe.chenxy.oppopods.integration.isTwsForHyperOs
import moe.chenxy.oppopods.integration.miLinkCardCapabilities
import moe.chenxy.oppopods.integration.miLinkDevicePresentation
import moe.chenxy.oppopods.integration.miLinkDisplayAncMode
import moe.chenxy.oppopods.integration.miLinkRuntimeAncState
import moe.chenxy.oppopods.integration.toIntegrationState
import moe.chenxy.oppopods.ui.state.HeadphoneUiStore
import moe.chenxy.oppopods.hook.HookContext
import moe.chenxy.oppopods.hook.Log
import moe.chenxy.oppopods.hook.callMethod
import moe.chenxy.oppopods.hook.getObjectField
import moe.chenxy.oppopods.hook.setObjectField
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams

@SuppressLint("MissingPermission")
object MiLinkServiceHook : HookContext() {
    internal const val TAG = "OppoPods-MiLink"
    private const val PREFS_NAME = "oppopods_milink_state"
    private const val HEADSET_OPERATION_SUCCESS = 100
    internal var context: Context? = null
    private var receiverRegistered = false
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentIsTws = true
    private var currentAnc = 1
    internal var currentSpatialAudioMode = ConfigManager.SPATIAL_AUDIO_OFF
    internal var lastAncBatteryController: Any? = null
    internal var lastProfileContext: Any? = null
    private val spatialAudioHook = MiLinkSpatialAudioHook(this)

    override fun onHook() {
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetRuntimeDisplay()
        spatialAudioHook.hookCirculateHeadsetServiceInfo()
    }

    private fun hookContextEntry() {
        runCatching {
            hookAfter(findMethod("com.milink.ui.MiLinkApplication", "onCreate")) {
                registerStatusReceiver(instance as? Context)
            }
        }.onFailure { Log.w(TAG, "hook MiLinkApplication.onCreate skipped", it) }
        listOf(
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"
        ).forEach { className ->
            runCatching {
                hookBefore(findMethod(className, "getInstanceForIsMiTWS", Context::class.java)) {
                    registerStatusReceiver(args[0] as? Context)
                }
            }.onFailure { Log.w(TAG, "hook $className.getInstanceForIsMiTWS skipped", it) }
        }
    }

    private fun hookMxBluetoothRuntime() {
        val classes = listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
        )
        classes.forEach { className ->
            hookBluetoothDeviceResult(className, "checkIsMiTWS") { if (miLinkIsTws()) 1 else 0 }
            hookBluetoothDeviceResult(className, "getDeviceId") { miLinkDeviceId() }
            hookBluetoothDeviceResult(className, "getBatteryLevel") { 1 }
            hookBluetoothDeviceResult(className, "getAncState") { miLinkRuntimeAncState(currentAnc) }
            hookBluetoothDeviceResult(className, "getDeviceRunInfo") { 0 }
            hookBluetoothDeviceResult(className, "getWearStatus") { "0,0" }
            hookBluetoothDeviceResult(className, "isLeAudio") { false }
            hookAncCommand(className, "openAnc", 2, 1)
            hookAncCommand(className, "closeAnc", 1, 0)
            hookAncCommand(className, "openTransparent", 3, 2)
        }
        classes.forEach { className ->
            hookStringAddressResult(className, "isMiTWS") { miLinkIsTws() }
            hookStringAddressResult(className, "isSupportAudioSwitch") { miLinkSwitchState() }
            hookStringAddressResult(className, "getRingFindState") { false }
        }
        spatialAudioHook.hookMxBluetoothRuntime(classes)
    }

    private fun hookHeadsetRuntimeDisplay() {
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getDeviceId") { miLinkDeviceId() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getDeviceType") { miLinkDeviceType() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getBatteryLevel") { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getDeviceId") { miLinkDeviceId() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getAncState") {
            miLinkRuntimeAncState(currentAnc)
        }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getBatteryLevelCache") { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getHeadsetPropertyBlock") { batteryPercentForMiLink() }
        hookStringAddressResult("com.miui.headset.runtime.AncBatteryController", "getSwitchState") { miLinkSwitchState() }
        hookAncStateBlock()
        spatialAudioHook.hookHeadsetRuntimeDisplay()
        hookHeadsetInfoNoArg("getDeviceId") { miLinkDeviceId() }
        hookHeadsetInfoNoArg("component3") { miLinkDeviceId() }
        hookHeadsetInfoNoArg("getPowers") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("component4") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("getMode") { miLinkRuntimeAncState(currentAnc) }
        hookHeadsetInfoNoArg("component5") { miLinkRuntimeAncState(currentAnc) }
        hookHeadsetInfoNoArg("getType") { miLinkDeviceType() }
        hookHeadsetInfoNoArg("component7") { miLinkDeviceType() }
        hookHeadsetInfoNoArg("getSwitchState") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("component8") { miLinkSwitchState() }
        hookFusionHeadsetCardCapabilities()
        hookFusionHeadsetOverviewIcon()
    }

    private fun hookFusionHeadsetCardCapabilities() {
        runCatching {
            hookAfter(findMethodByParamCount(
                "com.miui.circulateplus.world.headset.HeadSetsDetail",
                "A",
                0,
            )) {
                val detail = instance ?: return@hookAfter
                val headset = runCatching { callMethod(detail, "getHeadsetDeviceInfo") }.getOrNull()
                    ?: runCatching { getObjectField(detail, "N") }.getOrNull()
                    ?: return@hookAfter
                val address = runCatching { getObjectField(headset, "mac") as? String }.getOrNull()
                val name = runCatching { getObjectField(headset, "name") as? String }.getOrNull()
                if (!HyperOsHeadphoneAdapter.supports(address, name)) return@hookAfter

                val cards = miLinkCardCapabilities(HyperOsHeadphoneAdapter.state)
                runCatching {
                    val ancSection = getObjectField(detail, "A")
                    if (cards.showNoiseControl) {
                        // MiLink's type-6 branch skips updateMode, leaving all three items clear.
                        callMethod(ancSection, "y", miLinkDisplayAncMode(currentAnc))
                    } else {
                        callMethod(ancSection, "x", false)
                    }
                    callMethod(detail, "setModeVisible", cards.showNoiseControl)
                    callMethod(getObjectField(detail, "D"), "h", cards.showRingFind)
                    callMethod(detail, "setRingVisible", cards.showRingFind)
                    applyMiLinkHeadbandCategoryIcon(getObjectField(detail, "J"))
                    callMethod(detail, "y")
                }.onFailure { Log.w(TAG, "apply fusion headset card capabilities failed", it) }
            }
        }.onFailure { Log.w(TAG, "hook HeadSetsDetail.A card capabilities skipped", it) }
    }

    private fun hookFusionHeadsetOverviewIcon() {
        runCatching {
            hookAfter(findMethod(
                "com.miui.circulate.world.headset.ui.HeadsetInfoView",
                "setHeadsetInfo",
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )) {
                val state = HyperOsHeadphoneAdapter.state
                val type = args[1] as? Int ?: return@hookAfter
                if (type != 6 || state.deviceId == null || state.isTwsForHyperOs()) return@hookAfter
                applyMiLinkHeadbandCategoryIcon(
                    runCatching { getObjectField(instance, "A") }.getOrNull(),
                )
            }
        }.onFailure { Log.w(TAG, "hook HeadsetInfoView category icon skipped", it) }
    }

    private fun applyMiLinkHeadbandCategoryIcon(target: Any?) {
        val imageView = target as? ImageView ?: return
        val moduleBitmap = runCatching {
            val moduleContext = imageView.context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            BitmapFactory.decodeResource(
                moduleContext.resources,
                R.drawable.ic_milink_generic_headset,
            )
        }.onFailure {
            Log.w(TAG, "load module headband category icon failed", it)
        }.getOrNull()
        if (moduleBitmap != null) {
            imageView.clearColorFilter()
            imageView.setImageBitmap(moduleBitmap)
            return
        }

        // Keep MiLink's built-in vector as a last-resort fallback if the module resources cannot
        // be opened in the scoped process.
        val iconId = imageView.resources.getIdentifier(
            "ic_miplay_headset",
            "drawable",
            imageView.context.packageName,
        )
        if (iconId == 0) {
            Log.w(TAG, "MiLink generic headband category icon not found")
            return
        }
        imageView.setImageResource(iconId)
        imageView.setColorFilter(Color.WHITE)
    }

    internal fun hookBluetoothDeviceResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                cacheRuntimeOwner(className, instance)
                captureRuntimeContext(instance)
                this.result = result()
                if (className == "com.miui.headset.runtime.AncBatteryController" && methodName == "getHeadsetPropertyBlock") {
                    notifyHeadsetPropertyChanged(instance, device, 4)
                }
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(BluetoothDevice) skipped", it) }
    }

    internal fun hookStringAddressResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookAfter
                if (!isOppoAddress(address)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookAncCommand(className: String, methodName: String, integrationAnc: Int, result: Int) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                cacheRuntimeOwner(className, instance)
                captureRuntimeContext(instance)
                currentAnc = integrationAnc
                sendOppoAnc(integrationAnc)
                this.result = result
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName command skipped", it) }
    }

    private fun hookAncStateBlock() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setAncStateBlock", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                lastAncBatteryController = instance
                captureRuntimeContext(instance)
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val integrationAnc = integrationAncFromMiLinkRuntime(miLinkMode)
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                currentAnc = integrationAnc
                sendOppoAnc(integrationAnc, instanceContext)
                notifyHeadsetPropertyChanged(instance, device, 8)
                notifyHeadsetPropertyChanged(instance, device, 4)
                this.result = HEADSET_OPERATION_SUCCESS
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setAncStateBlock skipped", it) }
    }

    internal fun hookHeadsetInfoNoArg(methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethodByParamCount("com.miui.headset.api.HeadsetInfo", methodName, 0)) {
                if (!isTargetHeadsetInfo(instance)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.w(TAG, "hook HeadsetInfo.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        HeadphoneSnapshotReceiver.register(context ?: ctx)
        val filter = IntentFilter().apply {
            addAction(LegacyPodsAction.ACTION_CONFIG_CHANGED)
        }
        context?.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isSentFrom(IpcSenderPolicy.moduleOnly)) return
                if (intent?.action == LegacyPodsAction.ACTION_CONFIG_CHANGED) refreshConfig()
            }
        }, filter, Context.RECEIVER_EXPORTED)
        loadState()
        stateScope.launch {
            HeadphoneUiStore.state.collect { state ->
                val projected = state.toIntegrationState()
                val batteryChanged = projected.battery != null && projected.battery != currentBattery
                val ancChanged = projected.anc != null && projected.anc != currentAnc
                val spatialChanged = projected.spatialAudioMode != null &&
                    projected.spatialAudioMode != currentSpatialAudioMode
                currentAddress = projected.address ?: currentAddress
                currentName = projected.name ?: currentName
                projected.battery?.let { currentBattery = it }
                projected.anc?.let { currentAnc = it }
                projected.spatialAudioMode?.let { currentSpatialAudioMode = it }
                if (state.deviceId != null) currentIsTws = state.isTwsForHyperOs()
                saveState(context)
                notifyRuntimeSnapshotChanged(
                    batteryChanged = batteryChanged,
                    ancChanged = ancChanged,
                    spatialChanged = spatialChanged,
                )
            }
        }
        receiverRegistered = true
        context?.let(HeadphoneSnapshotReceiver::requestSnapshot)
        context?.sendIdentitySharedBroadcast(Intent(LegacyPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    internal fun isOppoPod(device: BluetoothDevice): Boolean {
        val address = runCatching { device.address }.getOrNull()
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        val result = HyperOsHeadphoneAdapter.supports(address, name)
        if (result && address != null) {
            currentAddress = address
            currentName = name
        }
        return result
    }

    internal fun isOppoAddress(address: String): Boolean {
        return HyperOsHeadphoneAdapter.supports(address)
    }

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { method ->
            val address = runCatching { callMethod(info, method) as? String }.getOrNull()
            if (address != null && isOppoAddress(address)) return true
        }
        return false
    }

    private fun miLinkIsTws(): Boolean {
        loadState()
        return currentIsTws
    }

    private fun miLinkDeviceId(): String {
        return miLinkDevicePresentation(miLinkIsTws(), fakeDeviceId()).deviceId
    }

    private fun miLinkDeviceType(): Int {
        return miLinkDevicePresentation(miLinkIsTws(), fakeDeviceId()).deviceType
    }

    private fun miLinkBatteryLevels(): List<Int> {
        loadState()
        val left = batteryValue(currentBattery.left)
        val right = batteryValue(currentBattery.right)
        val box = batteryValue(currentBattery.case)
        return listOf(
            box,
            left,
            right,
            chargingValue(currentBattery.case),
            chargingValue(currentBattery.left),
            chargingValue(currentBattery.right)
        )
    }

    private fun batteryPercentForMiLink(): Int {
        loadState()
        val values = listOfNotNull(currentBattery.left, currentBattery.right)
            .filter { it.isConnected }
            .map { it.battery.coerceIn(0, 100) }
        return values.minOrNull() ?: 0
    }

    private fun batteryValue(params: moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams?): Int {
        if (params?.isConnected != true) return -1
        return params.battery.coerceIn(0, 100)
    }

    private fun chargingValue(params: moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams?): Int {
        return if (params?.isConnected == true && params.isCharging) 1 else 0
    }

    private fun sendOppoAnc(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoAnc skipped: context is null mode=$mode")
            return
        }
        HyperOsHeadphoneAdapter.setNoiseControl(ctx, mode)
    }

    internal fun sendOppoSpatialAudio(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoSpatialAudio skipped: context is null mode=$mode")
            return
        }
        HyperOsHeadphoneAdapter.setSpatialAudio(ctx, mode)
    }

    internal fun miLinkSpatialMode(): Int {
        loadState()
        if (!spatialAudioPanelEnabled()) return -1
        return when (currentAudioEffectState()) {
            ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING -> if (miLinkDeviceSpatialType() == 1) 11 else 9
            ConfigManager.SPATIAL_AUDIO_FIXED -> 1
            else -> 0
        }
    }

    internal fun oppoSpatialFromMiLink(mode: Int): Int {
        return when (mode) {
            9, 11 -> ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING
            1 -> ConfigManager.SPATIAL_AUDIO_FIXED
            else -> ConfigManager.SPATIAL_AUDIO_OFF
        }
    }

    internal fun miLinkSpatialModeFromMode(mode: Int): Int {
        return when (mode) {
            ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING -> if (miLinkDeviceSpatialType() == 1) 11 else 9
            ConfigManager.SPATIAL_AUDIO_FIXED -> 1
            ConfigManager.SPATIAL_AUDIO_OFF -> 0
            else -> -1
        }
    }

    internal fun miLinkAudioEffectState(): Int {
        loadState()
        return currentAudioEffectState()
    }

    internal fun miLinkDeviceSpatialType(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    internal fun miLinkSwitchState(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    internal fun updateSpatialAudioMode(mode: Int) {
        currentSpatialAudioMode = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        saveState(context)
    }

    private fun currentAudioEffectState(): Int {
        return if (spatialAudioPanelEnabled()) {
            currentSpatialAudioMode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        } else {
            -1
        }
    }

    internal fun spatialAudioPanelEnabled(): Boolean {
        return HyperOsHeadphoneAdapter.state
            .feature(FeatureId.SPATIAL_AUDIO.name)
            ?.visible == true
    }

    internal fun isTargetAncBatteryModel(model: Any?): Boolean {
        val device = runCatching { callMethod(model, "getBluetoothDevice") as? BluetoothDevice }.getOrNull()
        return device?.let { isOppoPod(it) } == true
    }

    internal fun cacheRuntimeOwner(className: String, owner: Any?) {
        when (className) {
            "com.miui.headset.runtime.AncBatteryController" -> lastAncBatteryController = owner
            "com.miui.headset.runtime.ProfileContext" -> lastProfileContext = owner
        }
    }

    internal fun captureRuntimeContext(owner: Any?) {
        val ownerContext = runCatching { getObjectField(owner, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastProfileContext, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastAncBatteryController, "context") as? Context }.getOrNull()
            ?: return
        context = ownerContext.applicationContext ?: ownerContext
    }

    internal fun notifySpatialUiChanged(owner: Any?, device: BluetoothDevice, mode: Int) {
        val spatialMode = miLinkSpatialModeFromMode(mode)
        val audioEffectState = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        syncSpatialModel(owner, device, spatialMode)
        syncSpatialModel(lastAncBatteryController, device, spatialMode)
        listOf(owner, lastAncBatteryController, lastProfileContext).distinctBy { it?.javaClass?.name }.forEach { target ->
            notifyHeadsetPropertyChanged(target, device, 9)
            notifyHeadsetPropertyChanged(target, device, 4)
            notifyProfileAudioEffectListeners(target, audioEffectState)
        }
    }

    private fun syncSpatialModel(owner: Any?, device: BluetoothDevice, spatialMode: Int) {
        val model = runCatching { getObjectField(owner, "ancBatteryModel") }.getOrNull() ?: return
        if (!isTargetAncBatteryModel(model)) return
        runCatching { setObjectField(model, "spatialState", spatialMode) }
            .onFailure { }
        runCatching { setObjectField(model, "deviceSpatialType", miLinkDeviceSpatialType()) }
            .onFailure { }
    }

    private fun notifyProfileAudioEffectListeners(owner: Any?, audioEffectState: Int) {
        runCatching {
            val listener = getObjectField(owner, "audioEffectListener")
            callMethod(listener, "invoke", audioEffectState)
        }.onFailure { }
    }

    private fun notifyHeadsetPropertyChanged(controller: Any?, device: BluetoothDevice, updateType: Int) {
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }.getOrNull() ?: return
        runCatching {
            callMethod(listener, "invoke", device, updateType)
        }.onFailure { }
    }

    private fun notifyRuntimeSnapshotChanged(
        batteryChanged: Boolean,
        ancChanged: Boolean,
        spatialChanged: Boolean,
    ) {
        if (!batteryChanged && !ancChanged && !spatialChanged) return
        val address = currentAddress ?: return
        val device = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        }.getOrNull() ?: return
        val owners = listOf(lastAncBatteryController, lastProfileContext)
            .distinctBy { it?.javaClass?.name }
        owners.forEach { owner ->
            if (batteryChanged) notifyHeadsetPropertyChanged(owner, device, 4)
            if (ancChanged) notifyHeadsetPropertyChanged(owner, device, 8)
            if (spatialChanged) notifyHeadsetPropertyChanged(owner, device, 9)
        }
    }

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putInt("spatial_audio_mode", currentSpatialAudioMode)
            .putBoolean("is_tws", currentIsTws)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .apply()
    }

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        currentAnc = prefs.getInt("anc", currentAnc)
        currentSpatialAudioMode = prefs.getInt("spatial_audio_mode", currentSpatialAudioMode)
            .coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        currentIsTws = prefs.getBoolean("is_tws", currentIsTws)
        currentBattery = BatteryParams(
            left = PodParams(
                prefs.getInt("left_battery", currentBattery.left?.battery ?: 0),
                prefs.getBoolean("left_charging", currentBattery.left?.isCharging == true),
                prefs.getBoolean("left_connected", currentBattery.left?.isConnected == true),
                0
            ),
            right = PodParams(
                prefs.getInt("right_battery", currentBattery.right?.battery ?: 0),
                prefs.getBoolean("right_charging", currentBattery.right?.isCharging == true),
                prefs.getBoolean("right_connected", currentBattery.right?.isConnected == true),
                0
            ),
            case = PodParams(
                prefs.getInt("case_battery", currentBattery.case?.battery ?: 0),
                prefs.getBoolean("case_charging", currentBattery.case?.isCharging == true),
                prefs.getBoolean("case_connected", currentBattery.case?.isConnected == true),
                0
            )
        )
    }
}
