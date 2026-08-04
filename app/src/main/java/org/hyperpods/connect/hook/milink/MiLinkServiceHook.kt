package org.hyperpods.connect.hook.milink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.hyperpods.connect.BuildConfig
import org.hyperpods.connect.R
import org.hyperpods.connect.config.ConfigManager
import org.hyperpods.connect.ipc.HeadphoneSnapshotReceiver
import org.hyperpods.connect.ipc.IpcSenderPolicy
import org.hyperpods.connect.ipc.isSentFrom
import org.hyperpods.connect.ipc.sendIdentitySharedBroadcast
import org.hyperpods.connect.integration.HyperOsHeadphoneAdapter
import org.hyperpods.connect.integration.integrationAncFromMiLinkRuntime
import org.hyperpods.connect.integration.isTwsForHyperOs
import org.hyperpods.connect.integration.miLinkCardCapabilities
import org.hyperpods.connect.integration.miLinkDevicePresentation
import org.hyperpods.connect.integration.miLinkDisplayAncMode
import org.hyperpods.connect.integration.miLinkIntegrationAncForCommand
import org.hyperpods.connect.integration.miLinkRuntimeAncState
import org.hyperpods.connect.integration.miLinkSpatialModeForBinary
import org.hyperpods.connect.integration.toIntegrationState
import org.hyperpods.connect.ui.state.HeadphoneUiStore
import org.hyperpods.connect.hook.HookContext
import org.hyperpods.connect.hook.HyperOsHookContractResolver
import org.hyperpods.connect.hook.HyperOsHookGroup
import org.hyperpods.connect.hook.HyperOsMethodContract
import org.hyperpods.connect.hook.Log
import org.hyperpods.connect.hook.callMethod
import org.hyperpods.connect.hook.getObjectField
import org.hyperpods.connect.hook.setObjectField
import moe.chenxy.headphones.core.feature.FeatureId
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation
import org.hyperpods.connect.ipc.HeadphoneActionContract
import org.hyperpods.connect.integration.BatterySlotPresentation

@SuppressLint("MissingPermission")
object MiLinkServiceHook : HookContext() {
    internal const val TAG = "HyperPodsConnect-MiLink"
    private const val HEADSET_OPERATION_SUCCESS = 100
    internal var context: Context? = null
    private var receiverRegistered = false
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: HeadphoneBatteryPresentation = HeadphoneBatteryPresentation()
    private var currentIsTws = true
    private var currentAnc = 1
    internal var currentSpatialAudioMode = ConfigManager.SPATIAL_AUDIO_OFF
    internal var lastAncBatteryController: Any? = null
    internal var lastProfileContext: Any? = null
    private var attachedHeadsetDetail: WeakReference<View>? = null
    private val spatialAudioHook = MiLinkSpatialAudioHook(this)

    override fun onHook() {
        hookContextEntry()
        val resolver = HyperOsHookContractResolver(this)
        when (processName) {
            "com.milink.service:core" -> {
                resolver.install(
                    HyperOsHookGroup.MILINK_CORE,
                    listOf(
                        HyperOsMethodContract(
                            "com.miui.headset.runtime.ProfileContext",
                            "getDeviceId",
                            listOf(BluetoothDevice::class.java),
                        ),
                        HyperOsMethodContract(
                            "com.miui.headset.runtime.ProfileContext",
                            "switchToHeadsetActivity",
                            listOf(BluetoothDevice::class.java),
                        ),
                    ),
                ) {
                    hookMxBluetoothRuntime()
                    hookHeadsetRuntimeDisplay()
                    hookSwitchToHeadsetActivity()
                }
                resolver.install(
                    HyperOsHookGroup.MILINK_SPATIAL,
                    listOf(
                        HyperOsMethodContract(
                            "com.miui.headset.runtime.ProfileContext",
                            "getAudioSpatialEffectState",
                            listOf(BluetoothDevice::class.java),
                        ),
                        HyperOsMethodContract(
                            "com.miui.headset.runtime.ProfileContext",
                            "setAudioEffectState",
                            listOf(String::class.java, Int::class.javaPrimitiveType!!),
                        ),
                    ),
                ) {
                    spatialAudioHook.hookProfileSpatialAudio()
                }
            }
            "com.milink.service:ui" -> resolver.install(
                HyperOsHookGroup.MILINK_UI,
                listOf(
                    HyperOsMethodContract(
                        "com.miui.circulateplus.world.headset.HeadSetsDetail",
                        "onAttachedToWindow",
                        emptyList(),
                    ),
                ),
            ) {
                hookFusionHeadsetCardCapabilities()
                hookFusionHeadsetOverviewIcon()
                spatialAudioHook.hookCirculateHeadsetServiceInfo()
            }
        }
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
        ).takeIf { processName == "com.milink.service:core" }.orEmpty().forEach { className ->
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
    }

    private fun hookHeadsetRuntimeDisplay() {
        hookHeadsetInfoConstruction()
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
    }

    /**
     * Patch the value object itself in addition to its getters. MiLink sends HeadsetInfo through
     * Parcelable/JSON paths that read the backing fields directly, so getter-only hooks leave
     * remote hosts with stale Xiaomi SDK values. A constructor hook keeps the original address,
     * name, volume and wired state while projecting the snapshot-owned identity and controls.
     */
    private fun hookHeadsetInfoConstruction() {
        runCatching {
            hookConstructorAfter(findConstructorByParamCount(
                "com.miui.headset.api.HeadsetInfo",
                10,
            )) {
                val address = args[0] as? String ?: return@hookConstructorAfter
                val name = args[1] as? String
                val state = HyperOsHeadphoneAdapter.state
                if (!state.connected || state.deviceId == null || !state.supportsAddress(address)) {
                    return@hookConstructorAfter
                }
                currentAddress = address
                currentName = name ?: currentName
                runCatching {
                    setObjectField(instance, "deviceId", miLinkDeviceId())
                    setObjectField(instance, "powers", miLinkBatteryLevels())
                    setObjectField(instance, "mode", miLinkRuntimeAncState(currentAnc))
                    setObjectField(instance, "type", miLinkDeviceType())
                    setObjectField(instance, "switchState", miLinkSwitchState())
                    setObjectField(instance, "audioEffectState", miLinkAudioEffectState())
                }.onFailure {
                    Log.w(TAG, "project HeadsetInfo backing fields failed", it)
                }
            }
        }.onFailure { Log.w(TAG, "hook HeadsetInfo constructor skipped", it) }
    }

    /**
     * MiLink ultimately delegates its headset-card click to the MxBluetooth SDK. Third-party
     * devices do not have the Xiaomi support record required by that SDK, so route only the
     * exact active snapshot device to the module detail popup. If the module activity cannot be
     * started, leave the Hook result untouched and let the ROM implementation provide its normal
     * fallback. Native Xiaomi devices and every non-target address always keep the original path.
     */
    private fun hookSwitchToHeadsetActivity() {
        runCatching {
            hookBefore(findMethod(
                "com.miui.headset.runtime.ProfileContext",
                "switchToHeadsetActivity",
                BluetoothDevice::class.java,
            )) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isExactSnapshotDevice(device)) return@hookBefore
                captureRuntimeContext(instance)
                if (openModuleHeadsetActivity(device)) {
                    result = null
                }
            }
        }.onFailure {
            Log.w(TAG, "hook ProfileContext.switchToHeadsetActivity skipped: ${it.message}")
        }
    }

    private fun isExactSnapshotDevice(device: BluetoothDevice): Boolean {
        val state = HyperOsHeadphoneAdapter.state
        val address = runCatching { device.address }.getOrNull()
        if (!state.connected || state.deviceId == null || !state.supportsAddress(address)) return false
        currentAddress = address
        currentName = runCatching { device.alias ?: device.name }.getOrNull() ?: currentName
        return true
    }

    private fun openModuleHeadsetActivity(device: BluetoothDevice): Boolean {
        val launchContext = context ?: return false
        val deviceName = runCatching { device.alias ?: device.name }.getOrNull()
        return runCatching {
            launchContext.startActivity(Intent(ACTION_SHOW_PODS_UI).apply {
                setClassName(BuildConfig.APPLICATION_ID, POPUP_ACTIVITY_CLASS)
                putExtra(EXTRA_FORCE_MODULE_POPUP, true)
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra("bluetoothaddress", device.address)
                putExtra("device_name", deviceName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            })
            Log.d(TAG, "MiLink headset detail routed to module popup")
            true
        }.onFailure {
            Log.w(TAG, "module headset detail unavailable; keeping ROM path", it)
        }.getOrDefault(false)
    }

    private fun hookFusionHeadsetCardCapabilities() {
        runCatching {
            hookAfter(findMethod(
                "com.miui.circulateplus.world.headset.HeadSetsDetail",
                "onAttachedToWindow",
            )) {
                val detail = instance ?: return@hookAfter
                val headset = runCatching { callMethod(detail, "getHeadsetDeviceInfo") }.getOrNull()
                    ?: return@hookAfter
                val address = runCatching { getObjectField(headset, "mac") as? String }.getOrNull()
                if (!HyperOsHeadphoneAdapter.supports(address)) return@hookAfter

                val cards = miLinkCardCapabilities(HyperOsHeadphoneAdapter.state)
                runCatching {
                    attachedHeadsetDetail = WeakReference(detail as View)
                    callMethod(detail, "setModeVisible", cards.showNoiseControl)
                    callMethod(detail, "setRingVisible", cards.showRingFind)
                    callMethod(detail, "setAudioEffectVisible", cards.showSpatialAudio)
                    applyCardViewState(detail, cards)
                }.onFailure { Log.w(TAG, "apply fusion headset card capabilities failed", it) }
            }
        }.onFailure { Log.w(TAG, "hook HeadSetsDetail.onAttachedToWindow card capabilities skipped", it) }
    }

    private fun applyCardViewState(
        detail: View,
        cards: org.hyperpods.connect.integration.MiLinkCardCapabilities,
    ) {
        fun view(name: String): View? {
            val id = detail.resources.getIdentifier(name, "id", detail.context.packageName)
            return id.takeIf { it != 0 }?.let(detail::findViewById)
        }
        view("anc_card")?.visibility = if (cards.showNoiseControl) View.VISIBLE else View.GONE
        val selectedMode = miLinkDisplayAncMode(currentAnc)
        listOf(
            Triple("anc_clear", "TRANSPARENCY", 1),
            Triple("anc_noise_cancel", "NOISE_CANCELLATION*", 0),
            Triple("anc_off", "OFF", 2),
        ).forEach { (id, value, mode) ->
            view(id)?.apply {
                val allowed = if (value.endsWith("*")) {
                    cards.allowedNoiseControlValues.any { it.startsWith(value.dropLast(1)) }
                } else {
                    value in cards.allowedNoiseControlValues
                }
                isEnabled = cards.noiseControlWritable && allowed
                isSelected = mode == selectedMode
            }
        }
        view("audio_effect_view")?.apply {
            visibility = if (cards.showSpatialAudio) View.VISIBLE else View.GONE
            isEnabled = cards.spatialAudioWritable
        }
        view("ring_card")?.visibility = if (cards.showRingFind) View.VISIBLE else View.GONE
        if (!HyperOsHeadphoneAdapter.state.isTwsForHyperOs()) {
            applyMiLinkHeadbandCategoryIcon(view("circulate_single_battery_headset_icon"))
        }
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
                val root = instance as? View ?: return@hookAfter
                val iconId = root.resources.getIdentifier("headset_icon", "id", root.context.packageName)
                applyMiLinkHeadbandCategoryIcon(iconId.takeIf { it != 0 }?.let(root::findViewById))
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
                if (!isCurrentSupportedHeadset(device)) return@hookAfter
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
                if (!isCurrentSupportedAddress(address)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookAncCommand(className: String, methodName: String, integrationAnc: Int, result: Int) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                cacheRuntimeOwner(className, instance)
                captureRuntimeContext(instance)
                val resolvedAnc = miLinkIntegrationAncForCommand(
                    integrationAnc,
                    HyperOsHeadphoneAdapter.state.feature(FeatureId.NOISE_CONTROL.name),
                ) ?: return@hookBefore
                currentAnc = resolvedAnc
                sendHeadphoneAnc(resolvedAnc)
                this.result = result
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName command skipped", it) }
    }

    private fun hookAncStateBlock() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setAncStateBlock", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                lastAncBatteryController = instance
                captureRuntimeContext(instance)
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val integrationAnc = miLinkIntegrationAncForCommand(
                    integrationAncFromMiLinkRuntime(miLinkMode),
                    HyperOsHeadphoneAdapter.state.feature(FeatureId.NOISE_CONTROL.name),
                ) ?: return@hookBefore
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                currentAnc = integrationAnc
                sendHeadphoneAnc(integrationAnc, instanceContext)
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
            addAction(HeadphoneActionContract.ACTION_CONFIG_CHANGED)
        }
        context?.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isSentFrom(IpcSenderPolicy.moduleOnly)) return
                if (intent?.action == HeadphoneActionContract.ACTION_CONFIG_CHANGED) refreshConfig()
            }
        }, filter, Context.RECEIVER_EXPORTED)
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
                if (batteryChanged || ancChanged || spatialChanged) {
                    refreshAttachedCard()
                }
                notifyRuntimeSnapshotChanged(
                    batteryChanged = batteryChanged,
                    ancChanged = ancChanged,
                    spatialChanged = spatialChanged,
                )
            }
        }
        receiverRegistered = true
        context?.let(HeadphoneSnapshotReceiver::requestSnapshot)
        context?.sendIdentitySharedBroadcast(Intent(HeadphoneActionContract.ACTION_HEADPHONE_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    private fun refreshAttachedCard() {
        val detail = attachedHeadsetDetail?.get() ?: return
        detail.post {
            if (!detail.isAttachedToWindow) return@post
            val cards = miLinkCardCapabilities(HyperOsHeadphoneAdapter.state)
            runCatching {
                callMethod(detail, "setModeVisible", cards.showNoiseControl)
                callMethod(detail, "setRingVisible", cards.showRingFind)
                callMethod(detail, "setAudioEffectVisible", cards.showSpatialAudio)
                applyCardViewState(detail, cards)
            }.onFailure { Log.w(TAG, "refresh fusion headset card failed", it) }
        }
    }

    internal fun isCurrentSupportedHeadset(device: BluetoothDevice): Boolean {
        val address = runCatching { device.address }.getOrNull()
        val result = HyperOsHeadphoneAdapter.supports(address)
        if (result && address != null) {
            currentAddress = address
            currentName = HyperOsHeadphoneAdapter.state.title
        }
        return result
    }

    internal fun isCurrentSupportedAddress(address: String): Boolean {
        return HyperOsHeadphoneAdapter.supports(address)
    }

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { method ->
            val address = runCatching { callMethod(info, method) as? String }.getOrNull()
            if (address != null && isCurrentSupportedAddress(address)) return true
        }
        return false
    }

    private fun miLinkIsTws(): Boolean {
        return currentIsTws
    }

    private fun miLinkDeviceId(): String {
        return miLinkDevicePresentation(miLinkIsTws(), hyperOsPresentationTypeId()).deviceId
    }

    private fun miLinkDeviceType(): Int {
        return miLinkDevicePresentation(miLinkIsTws(), hyperOsPresentationTypeId()).deviceType
    }

    private fun miLinkBatteryLevels(): List<Int> {
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
        val values = listOfNotNull(currentBattery.left, currentBattery.right)
            .filter { it.isConnected }
            .map { it.battery.coerceIn(0, 100) }
        return values.minOrNull() ?: 0
    }

    private fun batteryValue(params: org.hyperpods.connect.integration.BatterySlotPresentation?): Int {
        if (params?.isConnected != true) return -1
        return params.battery.coerceIn(0, 100)
    }

    private fun chargingValue(params: org.hyperpods.connect.integration.BatterySlotPresentation?): Int {
        return if (params?.isConnected == true && params.isCharging) 1 else 0
    }

    private fun sendHeadphoneAnc(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendHeadphoneAnc skipped: context is null mode=$mode")
            return
        }
        HyperOsHeadphoneAdapter.setNoiseControl(ctx, mode)
    }

    internal fun sendHeadphoneSpatialAudio(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendHeadphoneSpatialAudio skipped: context is null mode=$mode")
            return
        }
        HyperOsHeadphoneAdapter.setSpatialAudio(ctx, mode)
    }

    internal fun miLinkAudioEffectState(): Int {
        return currentAudioEffectState()
    }

    internal fun miLinkSwitchState(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    internal fun updateSpatialAudioMode(mode: Int) {
        currentSpatialAudioMode = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
    }

    private fun currentAudioEffectState(): Int {
        return if (spatialAudioPanelEnabled()) {
            if (currentSpatialAudioMode == ConfigManager.SPATIAL_AUDIO_OFF) 0 else 1
        } else {
            -1
        }
    }

    internal fun spatialAudioPanelEnabled(): Boolean {
        return HyperOsHeadphoneAdapter.state
            .feature(FeatureId.SPATIAL_AUDIO.name)
            ?.visible == true
    }

    internal fun spatialAudioFromMiLink(binaryState: Int): Int? {
        val feature = HyperOsHeadphoneAdapter.state.feature(FeatureId.SPATIAL_AUDIO.name)
        return when (miLinkSpatialModeForBinary(binaryState, feature)) {
            moe.chenxy.headphones.core.feature.SpatialAudioMode.OFF -> ConfigManager.SPATIAL_AUDIO_OFF
            moe.chenxy.headphones.core.feature.SpatialAudioMode.FIXED -> ConfigManager.SPATIAL_AUDIO_FIXED
            moe.chenxy.headphones.core.feature.SpatialAudioMode.HEAD_TRACKING ->
                ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING
            null -> null
        }
    }

    internal fun isTargetAncBatteryModel(model: Any?): Boolean {
        val device = runCatching { callMethod(model, "getBluetoothDevice") as? BluetoothDevice }.getOrNull()
        return device?.let { isCurrentSupportedHeadset(it) } == true
    }

    internal fun cacheRuntimeOwner(className: String, owner: Any?) {
        when (className) {
            "com.miui.headset.runtime.AncBatteryController" -> lastAncBatteryController = owner
            "com.miui.headset.runtime.ProfileContext" -> lastProfileContext = owner
        }
    }

    internal fun captureRuntimeContext(owner: Any?) {
        val ownerContext = runCatching { getObjectField(owner, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(owner, "mContext") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastProfileContext, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastAncBatteryController, "context") as? Context }.getOrNull()
            ?: return
        context = ownerContext.applicationContext ?: ownerContext
    }

    internal fun notifySpatialUiChanged(owner: Any?, device: BluetoothDevice, mode: Int) {
        val audioEffectState = if (mode == ConfigManager.SPATIAL_AUDIO_OFF) 0 else 1
        listOf(owner, lastAncBatteryController, lastProfileContext).distinctBy { it?.javaClass?.name }.forEach { target ->
            notifyHeadsetPropertyChanged(target, device, 9)
            notifyHeadsetPropertyChanged(target, device, 4)
            notifyProfileAudioEffectListeners(target, audioEffectState)
        }
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

    private const val ACTION_SHOW_PODS_UI = "org.hyperpods.connect.action.show_pods_ui"
    private const val POPUP_ACTIVITY_CLASS = "org.hyperpods.connect.PopupActivity"
    private const val EXTRA_FORCE_MODULE_POPUP = "org.hyperpods.connect.extra.FORCE_MODULE_POPUP"
}
