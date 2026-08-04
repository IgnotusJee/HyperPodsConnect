package org.hyperpods.connect.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.hyperpods.connect.BuildConfig
import org.hyperpods.connect.config.ConfigManager
import org.hyperpods.connect.ipc.HeadphoneSnapshotReceiver
import org.hyperpods.connect.ipc.IpcSenderPolicy
import org.hyperpods.connect.ipc.isSentFrom
import org.hyperpods.connect.integration.HyperOsHeadphoneAdapter
import org.hyperpods.connect.integration.HyperOsOfficialIslandConfig
import org.hyperpods.connect.integration.OfficialHeadsetIslandPayload
import org.hyperpods.connect.integration.ConnectionPresentationGate
import org.hyperpods.connect.integration.connectionPresentationKey
import org.hyperpods.connect.integration.isOfficialHeadsetReadyEdge
import org.hyperpods.connect.integration.officialIslandEmissionToken
import org.hyperpods.connect.integration.shouldTriggerOfficialHeadsetIsland
import org.hyperpods.connect.integration.toIntegrationState
import org.hyperpods.connect.integration.toOfficialHeadsetIslandPayload
import org.hyperpods.connect.ui.state.HeadphoneUiState
import org.hyperpods.connect.ui.state.HeadphoneUiStore
import moe.chenxy.headphones.core.operation.FeatureCommand
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation
import org.hyperpods.connect.ipc.HeadphoneActionContract
import org.hyperpods.connect.integration.BatterySlotPresentation
import org.json.JSONObject

@SuppressLint("MissingPermission")
class BluetoothUpstreamHeadsetHook : HookContext() {
    private val TAG = "HyperPodsConnect-Upstream"
    private val callbacks = linkedMapOf<IBinder, Any>()
    private val handler = Handler(Looper.getMainLooper())
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hookedBinderClasses = linkedSetOf<String>()
    private var currentHeadsetDevice: BluetoothDevice? = null
    private var context: Context? = null
    private var receiverRegistered = false
    private var currentBattery: HeadphoneBatteryPresentation? = null
    private var currentAnc = 1
    private var currentTransparencyVocalEnhancement = false
    private var hasTransparencyVocalEnhancementState = false
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var previousOfficialIslandState = HeadphoneUiState()
    private var pendingOfficialIslandAddress: String? = null
    private var lastOfficialEmissionToken: String? = null
    private val officialIslandPresentationGate = ConnectionPresentationGate()

    override fun onHook() {
        val resolver = HyperOsHookContractResolver(this)
        resolver.install(
            HyperOsHookGroup.BLUETOOTH_BINDER,
            listOf(
                HyperOsMethodContract(
                    "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService",
                    "onBind",
                    listOf(Intent::class.java),
                ),
            ),
        ) { hookHeadsetServiceBinder() }
        resolver.install(
            HyperOsHookGroup.OFFICIAL_ISLAND,
            listOf(
                HyperOsMethodContract(
                    "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
                    "handleShowConnectedToast",
                    listOf(
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        BluetoothDevice::class.java,
                        String::class.java,
                    ),
                ),
                HyperOsMethodContract(
                    "com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi",
                    "addConnectManager",
                    listOf(
                        BluetoothDevice::class.java,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    ),
                ),
                HyperOsMethodContract(
                    "com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi",
                    "showNewConnectedToast",
                    listOf(
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        BluetoothDevice::class.java,
                        String::class.java,
                    ),
                ),
            ),
        ) { hookNotificationBatteryUpstream() }
    }

    private fun hookNotificationBatteryUpstream() {
        val notificationClass = findClassOrNull("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
        if (notificationClass != null) {
            runCatching {
                hookBefore(
                    notificationClass.method(
                        "handleShowConnectedToast",
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        BluetoothDevice::class.java,
                        String::class.java,
                    ),
                ) {
                    val device = args[4] as? BluetoothDevice ?: return@hookBefore
                    if (!isCurrentSupportedHeadset(device)) return@hookBefore
                    val payload = HyperOsHeadphoneAdapter.state.toOfficialHeadsetIslandPayload()
                        ?: return@hookBefore
                    pendingOfficialIslandAddress = device.address
                    setArg(1, payload.leftBattery)
                    setArg(2, payload.rightBattery)
                    setArg(3, payload.wearState)
                    setArg(5, HyperOsOfficialIslandConfig.presentationTypeId)
                    Log.d(
                        TAG,
                        "handleShowConnectedToast patched device=${device.describe()} " +
                            "left=${payload.leftBattery} right=${payload.rightBattery} " +
                            "wear=${payload.wearState}",
                    )
                }
            }.onFailure {
                Log.w(TAG, "hook MiuiBluetoothNotification.handleShowConnectedToast skipped: ${it.message}")
            }
            runCatching {
                hookAfter(
                    notificationClass.method(
                        "checkIfSetStopShowDialog",
                        BluetoothDevice::class.java,
                    ),
                ) {
                    val device = args[0] as? BluetoothDevice ?: return@hookAfter
                    val state = HyperOsHeadphoneAdapter.state
                    val isCurrentOfficialProjection =
                        ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_OFFICIAL &&
                            state.supportsAddress(pendingOfficialIslandAddress) &&
                            state.supportsAddress(device.address) &&
                            isCurrentSupportedHeadset(device)
                    if (!isCurrentOfficialProjection) return@hookAfter

                    // The module already owns this exact Ready-session request, so there is no
                    // separate Fast Connect dialog that the native island must wait for.
                    result = -1
                    Log.d(
                        TAG,
                        "official island Fast Connect wait bypassed device=${device.describe()}",
                    )
                }
                Log.d(TAG, "MiuiBluetoothNotification.checkIfSetStopShowDialog hook installed")
            }.onFailure {
                Log.w(TAG, "hook MiuiBluetoothNotification.checkIfSetStopShowDialog skipped", it)
            }
            runCatching {
                hookBefore(notificationClass.method("invokeStatusBar", Context::class.java, String::class.java, Bundle::class.java)) {
                    val bundle = args[2] as? Bundle
                    if (shouldInterceptHeadsetWearIsland(bundle)) {
                        when (ConfigManager.islandMode()) {
                            ConfigManager.ISLAND_MODE_NONE, ConfigManager.ISLAND_MODE_MODULE -> {
                                result = null
                                Log.d(TAG, "invokeStatusBar swallowed headset_wear_notification island mode=${ConfigManager.islandMode()}")
                                return@hookBefore
                            }
                        }
                    }
                    patchHeadsetWearIslandBundle(bundle)
                    Log.d(TAG, "invokeStatusBar upstream action=${args[1]} bundle=$bundle focus=${bundle?.getString("miui.focus.param")}")
                }
                Log.d(TAG, "MiuiBluetoothNotification.invokeStatusBar debug hook installed")
            }.onFailure { Log.w(TAG, "hook MiuiBluetoothNotification.invokeStatusBar skipped", it) }
        }
    }

    private fun hookHeadsetServiceBinder() {
        val serviceClassName = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
        val serviceClass = findClassOrNull(serviceClassName)
        if (serviceClass != null) {
            runCatching {
                hookAfter(serviceClass.method("onBind", Intent::class.java)) {
                    registerStatusReceiver(instance as? Context)
                    val binder = result ?: return@hookAfter
                    installHeadsetBinderHooks(binder.javaClass)
                }
                Log.d(TAG, "BluetoothHeadsetService.onBind hook installed package=$packageName")
            }.onFailure { Log.w(TAG, "hook BluetoothHeadsetService.onBind failed package=$packageName", it) }
            runCatching {
                hookAfter(serviceClass.method("onCreate")) {
                    registerStatusReceiver(instance as? Context)
                }
                Log.d(TAG, "BluetoothHeadsetService.onCreate hook installed package=$packageName")
            }.onFailure { Log.d(TAG, "hook BluetoothHeadsetService.onCreate skipped package=$packageName: ${it.message}") }
        } else {
            Log.d(TAG, "BluetoothHeadsetService class not present package=$packageName")
        }

    }

    private fun findClassOrNull(className: String): Class<*>? {
        return runCatching { findClass(className) }.getOrNull()
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
                if (intent?.action != HeadphoneActionContract.ACTION_CONFIG_CHANGED) return
                refreshConfig()
                notifyRealStatus("config-changed")
                if (ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_OFFICIAL) {
                    showOfficialHeadsetIsland(HeadphoneUiStore.state.value, "config-changed")
                }
            }
        }, filter, Context.RECEIVER_EXPORTED)
        stateScope.launch {
            HeadphoneUiStore.state.collect { state ->
                val readyEdge = isOfficialHeadsetReadyEdge(previousOfficialIslandState, state)
                val presentationAllowed = !readyEdge || officialIslandPresentationGate.claim(
                    logicalDeviceKey = connectionPresentationKey(
                        vendorId = state.vendorId,
                        deviceName = state.title,
                        address = state.address,
                    ),
                    nowMs = SystemClock.elapsedRealtime(),
                )
                val bridgeOfficialIsland =
                    ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_OFFICIAL &&
                        shouldTriggerOfficialHeadsetIsland(previousOfficialIslandState, state) &&
                        presentationAllowed
                if (readyEdge && !presentationAllowed) {
                    Log.i(
                        TAG,
                        "suppress official island reconnect generation=${state.generationId} " +
                            "device=${state.title}",
                    )
                }
                previousOfficialIslandState = state
                val projected = state.toIntegrationState()
                currentAddress = projected.address ?: currentAddress
                currentName = projected.name ?: currentName
                projected.battery?.let { currentBattery = it }
                projected.anc?.let { currentAnc = it }
                projected.transparencyVocalEnhancement?.let {
                    currentTransparencyVocalEnhancement = it
                    hasTransparencyVocalEnhancementState = true
                }
                Log.d(
                    TAG,
                    "snapshot generation=${state.generationId} address=$currentAddress " +
                        "name=$currentName anc=$currentAnc battery=${currentBattery.debugString()}",
                )
                handler.post {
                    notifyRealStatus("snapshot")
                    if (bridgeOfficialIsland) {
                        showOfficialHeadsetIsland(state, "snapshot")
                    }
                }
            }
        }
        receiverRegistered = true
        context?.let(HeadphoneSnapshotReceiver::requestSnapshot)
        Log.d(TAG, "registered status receiver context=$context")
    }

    private fun installHeadsetBinderHooks(binderClass: Class<*>) {
        val className = binderClass.name
        if (!hookedBinderClasses.add(className)) return
        Log.d(TAG, "BluetoothHeadsetService binder class=$className")

        runCatching {
            hookBefore(binderClass.method("checkSupport", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                currentHeadsetDevice = device
                result = hyperOsSupportDescriptor()
                Log.d(TAG, "runtimeBinder.checkSupport forced device=${device.describe()} support=$result")
            }
            Log.d(TAG, "runtimeBinder.checkSupport hook installed")
        }.onFailure { Log.w(TAG, "hook runtimeBinder.checkSupport skipped", it) }

        hookAddressStringResult(binderClass, listOf("getDeviceInfo"), "getDeviceInfo") { hyperOsSupportDescriptor() }
        hookAddressStringResult(binderClass, listOf("isSupportAudioSwitch", "z1"), "isSupportAudioSwitch") { "1" }
        hookAddressBooleanResult(binderClass, listOf("isMiTWS", "O0"), "isMiTWS") {
            HyperOsHeadphoneAdapter.isTwsDevice()
        }
        hookAddressBooleanResult(binderClass, listOf("checkIsMiTWS", "B"), "checkIsMiTWS") {
            HyperOsHeadphoneAdapter.isTwsDevice()
        }
        hookAddressBooleanResult(binderClass, listOf("getRingFindState", "m0"), "getRingFindState") { false }

        runCatching {
            hookBefore(binderClass.method("setCommonCommand", Int::class.java, String::class.java, BluetoothDevice::class.java)) {
                val command = args[0] as? Int
                val value = args[1] as? String
                val device = args[2] as? BluetoothDevice
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                currentHeadsetDevice = device
                result = when (command) {
                    102 -> "1"
                    123 -> "4"
                    else -> "1"
                }
                Log.d(TAG, "runtimeBinder.setCommonCommand forced command=$command value=$value device=${device.describe()} result=$result")
                sendRealStatus(device, "setCommonCommand:$command")
            }
            Log.d(TAG, "runtimeBinder.setCommonCommand hook installed")
        }.onFailure { Log.w(TAG, "hook runtimeBinder.setCommonCommand skipped", it) }

        hookBinderVoidDevice(binderClass, "connect") { device, method -> sendRealStatus(device, method) }
        hookBinderVoidDevice(binderClass, "getDeviceConfig") { device, method -> sendRealStatus(device, method) }
        hookBinderVoidDeviceString(binderClass, "getCommonConfig") { device, method -> sendRealStatus(device, method) }
        hookBinderAncMode(binderClass)
        hookBinderAncLevel(binderClass)

        runCatching {
            val callbackClass = findClass("com.android.bluetooth.ble.app.IMiuiHeadsetCallback")
            hookBefore(binderClass.method("register", callbackClass)) {
                val callback = args[0]
                if (callback != null && currentHeadsetDevice != null) {
                    rememberCallback(callback)
                    result = null
                    Log.d(TAG, "runtimeBinder.register swallowed callback=$callback device=${currentHeadsetDevice.describe()}")
                    requestBluetoothStatus("register")
                    sendRealStatus(currentHeadsetDevice, "register")
                    sendRealStatusDelayed(currentHeadsetDevice, "register-refresh", 350L)
                }
            }
            hookBefore(binderClass.method("registerCallbackDevice", callbackClass, BluetoothDevice::class.java)) {
                val callback = args[0]
                val device = args[1] as? BluetoothDevice
                if (!isCurrentSupportedHeadset(device) || callback == null) return@hookBefore
                currentHeadsetDevice = device
                rememberCallback(callback)
                result = null
                Log.d(TAG, "runtimeBinder.registerCallbackDevice swallowed callback=$callback device=${device.describe()}")
                requestBluetoothStatus("registerCallbackDevice")
                sendRealStatus(device, "registerCallbackDevice")
                sendRealStatusDelayed(device, "registerCallbackDevice-refresh", 350L)
            }
            hookBefore(binderClass.method("unregister", callbackClass, BluetoothDevice::class.java)) {
                val callback = args[0]
                val device = args[1] as? BluetoothDevice
                if (!isCurrentSupportedHeadset(device) || callback == null) return@hookBefore
                forgetCallback(callback)
                result = null
                Log.d(TAG, "runtimeBinder.unregister swallowed callback=$callback device=${device.describe()}")
            }
            Log.d(TAG, "runtimeBinder callback hooks installed")
        }.onFailure { Log.w(TAG, "hook runtimeBinder callback methods skipped", it) }
    }

    private fun hookBinderVoidDevice(binderClass: Class<*>, methodName: String, after: (BluetoothDevice?, String) -> Unit) {
        runCatching {
            hookBefore(binderClass.method(methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                currentHeadsetDevice = device
                result = null
                Log.d(TAG, "runtimeBinder.$methodName swallowed device=${device.describe()}")
                requestBluetoothStatus(methodName)
                after(device, methodName)
                sendRealStatusDelayed(device, "$methodName-refresh", 350L)
            }
        }.onFailure { Log.w(TAG, "hook runtimeBinder.$methodName skipped", it) }
    }

    private fun hookAddressStringResult(binderClass: Class<*>, methodNames: List<String>, label: String, forced: () -> String) {
        val methodName = methodNames.firstOrNull { name ->
            runCatching { binderClass.method(name, String::class.java) }.isSuccess
        } ?: run {
            Log.w(TAG, "hook runtimeBinder.$label skipped: no method in $methodNames")
            return
        }
        runCatching {
            hookBefore(binderClass.method(methodName, String::class.java)) {
                val address = args[0] as? String
                if (address == null || !isCurrentSupportedAddress(address)) return@hookBefore
                result = forced()
                Log.d(TAG, "runtimeBinder.$label forced address=$address result=$result method=$methodName")
            }
            Log.d(TAG, "runtimeBinder.$label hook installed method=$methodName")
        }.onFailure { Log.w(TAG, "hook runtimeBinder.$label skipped", it) }
    }

    private fun hookAddressBooleanResult(
        binderClass: Class<*>,
        methodNames: List<String>,
        label: String,
        forced: () -> Boolean,
    ) {
        val methodName = methodNames.firstOrNull { name ->
            runCatching { binderClass.method(name, String::class.java) }.isSuccess
        } ?: run {
            Log.w(TAG, "hook runtimeBinder.$label skipped: no method in $methodNames")
            return
        }
        runCatching {
            hookBefore(binderClass.method(methodName, String::class.java)) {
                val address = args[0] as? String
                if (address == null || !isCurrentSupportedAddress(address)) return@hookBefore
                result = forced()
                Log.d(TAG, "runtimeBinder.$label forced address=$address result=$result method=$methodName")
            }
            Log.d(TAG, "runtimeBinder.$label hook installed method=$methodName")
        }.onFailure { Log.w(TAG, "hook runtimeBinder.$label skipped", it) }
    }

    private fun hookBinderVoidDeviceString(binderClass: Class<*>, methodName: String, after: (BluetoothDevice?, String) -> Unit) {
        runCatching {
            hookBefore(binderClass.method(methodName, BluetoothDevice::class.java, String::class.java)) {
                val device = args[0] as? BluetoothDevice
                val value = args[1] as? String
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                currentHeadsetDevice = device
                result = null
                Log.d(TAG, "runtimeBinder.$methodName swallowed value=$value device=${device.describe()}")
                requestBluetoothStatus("$methodName:$value")
                after(device, "$methodName:$value")
                sendRealStatusDelayed(device, "$methodName-refresh:$value", 350L)
            }
        }.onFailure { Log.w(TAG, "hook runtimeBinder.$methodName skipped", it) }
    }

    private fun hookBinderAncMode(binderClass: Class<*>) {
        runCatching {
            hookBefore(binderClass.method("changeAncMode", Int::class.java, BluetoothDevice::class.java)) {
                val mode = args[0] as? Int
                val device = args[1] as? BluetoothDevice
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                currentHeadsetDevice = device
                result = null
                Log.d(TAG, "runtimeBinder.changeAncMode swallowed mode=$mode device=${device.describe()}")
                mode?.let { sendHeadphoneAnc(noiseControlFromMiuiMode(it)) }
                sendRealStatus(device, "changeAncMode:$mode")
            }
        }.onFailure { Log.w(TAG, "hook runtimeBinder.changeAncMode skipped", it) }
    }

    private fun hookBinderAncLevel(binderClass: Class<*>) {
        runCatching {
            hookBefore(binderClass.method("changeAncLevel", String::class.java, BluetoothDevice::class.java)) {
                val level = args[0] as? String
                val device = args[1] as? BluetoothDevice
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                currentHeadsetDevice = device
                result = null
                Log.d(TAG, "runtimeBinder.changeAncLevel swallowed level=$level device=${device.describe()}")
                level?.let { sendHeadphoneAncLevel(it) }
                sendRealStatus(device, "changeAncLevel:$level")
            }
        }.onFailure { Log.w(TAG, "hook runtimeBinder.changeAncLevel skipped", it) }
    }

    private fun rememberCallback(callback: Any) {
        (callMethod(callback, "asBinder") as? IBinder)?.let { callbacks[it] = callback }
    }

    private fun forgetCallback(callback: Any) {
        (callMethod(callback, "asBinder") as? IBinder)?.let { callbacks.remove(it) }
    }

    private fun Class<*>.method(name: String, vararg parameterTypes: Class<*>): Method {
        return getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
    }

    private fun isCurrentSupportedHeadset(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        return HyperOsHeadphoneAdapter.supports(address)
    }

    private fun notifyRealStatus(reason: String) {
        val device = currentHeadsetDevice
        if (device != null) {
            sendRealStatus(device, reason)
            return
        }
        val address = currentAddress ?: return
        sendRealStatus(address, reason)
    }

    private fun sendRealStatus(device: BluetoothDevice?, reason: String) {
        val address = device?.address ?: return
        sendRealStatus(address, reason)
    }

    private fun sendRealStatusDelayed(device: BluetoothDevice?, reason: String, delayMs: Long) {
        val address = device?.address ?: return
        handler.postDelayed({ sendRealStatus(address, reason) }, delayMs)
    }

    private fun sendRealStatus(address: String, reason: String) {
        if (callbacks.isEmpty()) {
            Log.d(TAG, "send real status skipped: no callback reason=$reason address=$address")
            return
        }
        val payload = realRefreshPayload()
        handler.post {
            callbacks.values.toList().forEach { callback ->
                runCatching {
                    callMethod(callback, "refreshStatus", address, payload)
                    Log.d(TAG, "sent real refreshStatus reason=$reason address=$address payload=$payload callback=$callback")
                }.onFailure {
                    forgetCallback(callback)
                    Log.w(TAG, "send real refreshStatus failed reason=$reason callback=$callback", it)
                }
            }
        }
    }

    private fun realRefreshPayload(): String {
        val state = HyperOsHeadphoneAdapter.state
        if (state.deviceId != null) {
            currentAddress = state.address
            currentName = state.title
            return HyperOsHeadphoneAdapter.miuiRefreshPayload()
        }
        return cachedPlatformRefreshPayload(
            currentBattery,
            currentAnc,
            currentTransparencyVocalEnhancement,
        )
    }

    private fun showOfficialHeadsetIsland(
        state: HeadphoneUiState,
        reason: String,
    ) {
        if (ConfigManager.islandMode() != ConfigManager.ISLAND_MODE_OFFICIAL) return
        val payload = state.toOfficialHeadsetIslandPayload() ?: return
        val emissionToken = state.officialIslandEmissionToken() ?: return
        if (lastOfficialEmissionToken == emissionToken) {
            Log.d(TAG, "official island duplicate suppressed token=$emissionToken reason=$reason")
            return
        }
        val current = HyperOsHeadphoneAdapter.state
        if (current.deviceId != state.deviceId || !current.supportsAddress(state.address)) return
        val appContext = context ?: return
        val device = runCatching {
            appContext.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(state.address)
        }.getOrNull() ?: return
        if (!HyperOsHeadphoneAdapter.supports(device.address)) return

        currentHeadsetDevice = device
        pendingOfficialIslandAddress = device.address
        runCatching {
            val apiClass = findClass("com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi")
            apiClass.getDeclaredMethod(
                "addConnectManager",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ).invoke(null, device, payload.requestFlag, 0)
            apiClass.getDeclaredMethod(
                "showNewConnectedToast",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                BluetoothDevice::class.java,
                String::class.java,
            ).invoke(
                null,
                payload.requestFlag,
                payload.leftBattery,
                payload.rightBattery,
                payload.wearState,
                device,
                HyperOsOfficialIslandConfig.presentationTypeId,
            )
        }.onSuccess {
            lastOfficialEmissionToken = emissionToken
            Log.i(
                TAG,
                "official island bridge reason=$reason device=${device.describe()} " +
                    "left=${payload.leftBattery} right=${payload.rightBattery} wear=${payload.wearState} " +
                    "officialTypeId=${HyperOsOfficialIslandConfig.presentationTypeId}",
            )
        }.onFailure {
            Log.w(TAG, "official island bridge failed reason=$reason device=${device.describe()}", it)
        }
    }

    private fun patchHeadsetWearIslandBundle(bundle: Bundle?) {
        if (bundle == null) return
        if (!shouldInterceptHeadsetWearIsland(bundle)) return
        if (ConfigManager.islandMode() != ConfigManager.ISLAND_MODE_OFFICIAL) return
        val state = HyperOsHeadphoneAdapter.state
        val payload = state.toOfficialHeadsetIslandPayload() ?: return
        if (!state.supportsAddress(pendingOfficialIslandAddress)) return
        val leftText = payload.leftBattery
            .takeIf { it != OfficialHeadsetIslandPayload.BATTERY_UNAVAILABLE }
            ?.let { "$it%" }
        val rightText = payload.rightBattery
            .takeIf { it != OfficialHeadsetIslandPayload.BATTERY_UNAVAILABLE }
            ?.let { "$it%" }
        if (leftText == null && rightText == null) return
        patchIslandJson(bundle, "param", leftText, rightText)
        patchIslandJson(bundle, "island_param", leftText, rightText)
        pendingOfficialIslandAddress = null
        Log.d(TAG, "patched headset_wear_notification island text left=$leftText right=$rightText")
    }

    private fun shouldInterceptHeadsetWearIsland(bundle: Bundle?): Boolean {
        return bundle?.getString("notifyId") == "headset_wear_notification"
    }

    private fun patchIslandJson(bundle: Bundle, key: String, leftText: String?, rightText: String?) {
        val raw = bundle.getString(key) ?: return
        runCatching {
            val json = JSONObject(raw)
            leftText?.let { putTextParams(json.optJSONObject("left"), it) }
            rightText?.let { putTextParams(json.optJSONObject("right"), it) }
            bundle.putString(key, json.toString())
        }.onFailure {
            Log.w(TAG, "patch island json failed key=$key raw=$raw", it)
        }
    }

    private fun putTextParams(area: JSONObject?, text: String) {
        if (area == null) return
        area.put(
            "textParams",
            JSONObject().apply {
                put("text", text)
                put("textColor", -1)
                put("turnAnim", true)
            }
        )
    }

    private fun requestBluetoothStatus(reason: String) {
        runCatching {
            context?.let {
                HyperOsHeadphoneAdapter.execute(it, FeatureCommand.RefreshAll)
            }
            Log.d(TAG, "requested bluetooth status reason=$reason package=$packageName")
        }.onFailure {
            Log.w(TAG, "request bluetooth status failed reason=$reason package=$packageName", it)
        }
    }

    private fun cachedPlatformRefreshPayload(
        battery: HeadphoneBatteryPresentation?,
        anc: Int,
        transparencyVocalEnhancement: Boolean,
    ): String {
        fun batteryValue(value: BatterySlotPresentation?): String {
            if (value?.isConnected != true) return "255"
            val level = value.battery.coerceIn(0, 100)
            return (if (value.isCharging) level or 128 else level).toString()
        }
        val values = MutableList(16) { "" }
        values[0] = batteryValue(battery?.left)
        values[1] = batteryValue(battery?.right)
        values[2] = batteryValue(battery?.case)
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

    private fun noiseControlFromMiuiMode(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun noiseControlFromMiuiLevel(level: String): Int {
        // MIUI binder level codes: 0103=Smart, 0101=Light, 0100=Medium, 0102=Deep.
        return when {
            level.startsWith("0103") -> 5
            level.startsWith("0101") -> 6
            level.startsWith("0100") -> 7
            level.startsWith("0102") -> 8
            level.startsWith("01") -> 7
            level.startsWith("02") -> 3
            else -> 1
        }
    }

    private fun sendHeadphoneAncLevel(level: String) {
        when {
            level.startsWith("0201") -> {
                currentAnc = 3
                sendHeadphoneTransparencyVocalEnhancement(true)
            }
            level.startsWith("0200") -> {
                currentAnc = 3
                sendHeadphoneTransparencyVocalEnhancement(false)
            }
            else -> sendHeadphoneAnc(noiseControlFromMiuiLevel(level))
        }
    }

    private fun sendHeadphoneAnc(mode: Int) {
        currentAnc = mode
        val ctx = context ?: run {
            Log.w(TAG, "sendHeadphoneAnc skipped: context is null mode=$mode")
            return
        }
        HyperOsHeadphoneAdapter.setNoiseControl(ctx, mode)
        Log.d(TAG, "headphone ANC command sent mode=$mode")
    }

    private fun sendHeadphoneTransparencyVocalEnhancement(enabled: Boolean) {
        currentTransparencyVocalEnhancement = enabled
        hasTransparencyVocalEnhancementState = true
        val ctx = context ?: run {
            Log.w(TAG, "sendHeadphoneTransparencyVocalEnhancement skipped: context is null enabled=$enabled")
            return
        }
        HyperOsHeadphoneAdapter.execute(
            ctx,
            FeatureCommand.SetTransparencyVocalEnhancement(enabled),
        )
        Log.d(TAG, "headphone transparency command sent enabled=$enabled")
    }

    private fun HeadphoneBatteryPresentation?.debugString(): String {
        if (this == null) return "null"
        return "left=${left?.battery}/${left?.isCharging}/${left?.isConnected} right=${right?.battery}/${right?.isCharging}/${right?.isConnected} case=${case?.battery}/${case?.isCharging}/${case?.isConnected}"
    }

    private fun isCurrentSupportedAddress(address: String): Boolean {
        return HyperOsHeadphoneAdapter.supports(address)
    }

    private fun BluetoothDevice?.describe(): String {
        if (this == null) return "null"
        val address = runCatching { this.address }.getOrNull()
        val name = runCatching { this.name }.getOrNull()
        val alias = runCatching { this.alias }.getOrNull()
        return "BluetoothDevice(address=$address,name=$name,alias=$alias)"
    }
}
