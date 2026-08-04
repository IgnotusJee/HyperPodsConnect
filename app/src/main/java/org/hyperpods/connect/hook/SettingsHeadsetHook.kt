package org.hyperpods.connect.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.hyperpods.connect.BuildConfig
import org.hyperpods.connect.ipc.HeadphoneSnapshotReceiver
import org.hyperpods.connect.ipc.IpcSenderPolicy
import org.hyperpods.connect.ipc.isSentFrom
import org.hyperpods.connect.ipc.sendIdentitySharedBroadcast
import org.hyperpods.connect.integration.HyperOsHeadphoneAdapter
import org.hyperpods.connect.integration.miuiAncLevel
import org.hyperpods.connect.integration.toIntegrationState
import org.hyperpods.connect.ui.state.HeadphoneUiStore
import org.hyperpods.connect.utils.PodImageLoader
import moe.chenxy.headphones.core.operation.FeatureCommand
import org.hyperpods.connect.integration.HeadphoneBatteryPresentation
import org.hyperpods.connect.ipc.HeadphoneActionContract
import org.hyperpods.connect.integration.BatterySlotPresentation
import java.lang.ref.WeakReference
import java.util.WeakHashMap

@SuppressLint("MissingPermission")
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "HyperPodsConnect-Settings"
    private const val SETTINGS_REFRESH_INTERVAL_MS = 3_000L
    private val batteryViews = WeakHashMap<Any, BluetoothDevice>()
    private val headsetFragments = WeakHashMap<Any, Boolean>()
    private var context: Context? = null
    private var receiverRegistered = false
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: HeadphoneBatteryPresentation = HeadphoneBatteryPresentation()
    private var currentAnc = 1
    private var currentTransparencyVocalEnhancement = false
    private var proxyCheckSupportCalls = 0
    private var proxySetCommonCommandCalls = 0
    private var proxyGetDeviceConfigCalls = 0
    private var proxyGetCommonConfigCalls = 0
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (headsetFragments.keys.any { isSupportedFragment(it) }) {
                requestBluetoothStatus("settings-periodic")
                refreshHandler.postDelayed(this, SETTINGS_REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
                Log.d(TAG, "settings periodic refresh stopped: no active fragment")
            }
        }
    }

    override fun onHook() {
        HyperOsHookContractResolver(this).install(
            HyperOsHookGroup.SETTINGS_HEADSET,
            listOf(
                HyperOsMethodContract(
                    "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy",
                    "getDeviceInfo",
                    listOf(String::class.java),
                ),
                HyperOsMethodContract(
                    "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy",
                    "isSupportAudioSwitch",
                    listOf(String::class.java),
                ),
            ),
        ) {
            hookActivityEntry()
            hookSupportChecks()
            hookServiceProxy()
            hookBatteryView()
            hookFragmentState()
            hookHeadsetArtwork()
        }
    }

    private fun hookHeadsetArtwork() {
        runCatching {
            hookBefore(
                findMethodByParamCount(
                    "com.android.settings.bluetooth.tws.MiuiHeadsetAnimation",
                    "loadDefaultInternal",
                    0,
                ),
            ) {
                val deviceId = runCatching { getObjectField(instance, "mDeviceId") as? String }.getOrNull()
                if (deviceId != hyperOsPresentationTypeId()) return@hookBefore
                val settingsContext = weakFieldValue(instance, "mContext") as? Context
                    ?: return@hookBefore
                val rootView = weakFieldValue(instance, "mRootView") as? View
                    ?: return@hookBefore
                val address = supportedAddressForRoot(rootView) ?: return@hookBefore
                val bitmap = PodImageLoader.loadSettingsHeroBitmap(
                    context = settingsContext,
                    prefs = prefs,
                    address = address,
                ) ?: run {
                    Log.w(TAG, "Settings hero cache unavailable address=$address; keeping MIUI default")
                    return@hookBefore
                }
                val imageId = settingsContext.resources.getIdentifier(
                    "tic",
                    "id",
                    "com.android.settings",
                )
                val imageView = rootView.findViewById<ImageView>(imageId) ?: return@hookBefore
                imageView.setImageBitmap(bitmap)
                result = null
                Log.i(
                    TAG,
                    "Settings hero replaced address=$address bitmap=${bitmap.width}x${bitmap.height}",
                )
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetAnimation.loadDefaultInternal skipped", it) }
    }

    private fun supportedAddressForRoot(rootView: View): String? {
        return headsetFragments.keys.toList().firstNotNullOfOrNull { fragment ->
            val fragmentRoot = runCatching { getObjectField(fragment, "mRootView") as? View }.getOrNull()
            if (fragmentRoot !== rootView) return@firstNotNullOfOrNull null
            val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
            if (!isCurrentSupportedHeadset(device)) return@firstNotNullOfOrNull null
            runCatching { device?.address }.getOrNull()
        }
    }

    private fun weakFieldValue(instance: Any?, fieldName: String): Any? {
        return (runCatching { getObjectField(instance, fieldName) }.getOrNull() as? WeakReference<*>)?.get()
    }

    private fun hookActivityEntry() {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivity", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Activity.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} profile=${HyperOsHeadphoneAdapter.state.deviceId}")
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", hyperOsSupportDescriptor())
                intent.putExtra("COME_FROM", intent.getStringExtra("COME_FROM") ?: "MIUI_BLUETOOTH_SETTINGS")
                intent.putExtra("DEVICE_ID", hyperOsPresentationTypeId())
                Log.d(TAG, "MiuiHeadsetActivity intent patched address=${device?.address}")
            }
            hookActivityStringGetter("getDeviceID") { hyperOsPresentationTypeId() }
            hookActivityStringGetter("getSupport") { hyperOsSupportDescriptor() }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivityPlugin", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Plugin.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} profile=${HyperOsHeadphoneAdapter.state.deviceId}")
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", hyperOsSupportDescriptor())
                intent.putExtra("DEVICE_ID", hyperOsPresentationTypeId())
                Log.d(TAG, "MiuiHeadsetActivityPlugin intent patched address=${device?.address}")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivityPlugin skipped", it) }
    }

    private fun hookActivityStringGetter(methodName: String, value: () -> String) {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetActivity", methodName, 0)) {
                val device = runCatching { getObjectField(instance, "mDevice") as? BluetoothDevice }.getOrNull()
                Log.d(TAG, "Activity.$methodName old=$result device=${device.describe()} isSupported=${isCurrentSupportedHeadset(device)}")
                if (!isCurrentSupportedHeadset(device)) return@hookAfter
                result = value()
                Log.d(TAG, "Activity.$methodName forced=$result")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity.$methodName skipped", it) }
    }

    private fun hookSupportChecks() {
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "checkSupport") { support ->
            support.startsWith(hyperOsPresentationTypeId()) || support.contains(hyperOsPresentationTypeId())
        }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isTWS01Headset") {
            it == hyperOsPresentationTypeId() && HyperOsHeadphoneAdapter.isTwsDevice()
        }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isK77sHeadset") { false }
        hookBleMmaConnectByContext()
        hookBleMmaConnectByService()
    }

    private fun hookStringStaticResult(className: String, methodName: String, resultForValue: (String) -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val value = args[0] as? String ?: return@hookAfter
                Log.d(TAG, "$className.$methodName value=$value old=$result")
                val deviceId = hyperOsPresentationTypeId()
                if (value != deviceId && !value.startsWith(deviceId)) return@hookAfter
                result = resultForValue(value)
                Log.d(TAG, "$className.$methodName forced value=$value result=$result")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookBleMmaConnectByContext() {
        runCatching {
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", Context::class.java, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Context) old=$result device=${device.describe()} deviceId=$deviceId service=${runCatching { callMethod(args[0], "getService") }.getOrNull()}")
                if (deviceId == hyperOsPresentationTypeId() || isCurrentSupportedHeadset(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Context) forced true")
                }
            }
        }.onFailure { Log.w(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Context) skipped", it) }
    }

    private fun hookBleMmaConnectByService() {
        runCatching {
            val serviceClass = findClass("com.android.bluetooth.ble.app.IMiuiHeadsetService")
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", serviceClass, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Service) old=$result service=${args[0]} device=${device.describe()} deviceId=$deviceId")
                if (deviceId == hyperOsPresentationTypeId() || isCurrentSupportedHeadset(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Service) forced true")
                }
            }
        }.onFailure { Log.w(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Service) skipped", it) }
    }

    private fun hookServiceProxy() {
        val proxyClass = "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy"
        hookProxyStringResult(proxyClass, "checkSupport", BluetoothDevice::class.java) { hyperOsSupportDescriptor() }
        hookProxyStringArgResult(proxyClass, "getDeviceInfo", String::class.java) { hyperOsSupportDescriptor() }
        hookProxyStringArgResult(proxyClass, "isSupportAudioSwitch", String::class.java) { "1" }
        hookProxyStringArgResult(proxyClass, "setCommonCommand", Int::class.java, String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val command = commandArgs[0] as? Int
            if (command == 102) "0" else "1"
        }
        hookProxyVoidDeviceNoop(proxyClass, "connect", BluetoothDevice::class.java)
        hookProxyVoidDeviceNoop(proxyClass, "getDeviceConfig", BluetoothDevice::class.java)
        hookProxyVoidDeviceStringNoop(proxyClass, "getCommonConfig", BluetoothDevice::class.java, String::class.java)
        hookProxyBooleanStringResult(proxyClass, "isMiTWS") { HyperOsHeadphoneAdapter.isTwsDevice() }
        hookProxyBooleanStringResult(proxyClass, "checkIsMiTWS") { HyperOsHeadphoneAdapter.isTwsDevice() }
        hookProxyBooleanStringResult(proxyClass, "getRingFindState") { false }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncMode", Int::class.java, BluetoothDevice::class.java) { commandArgs ->
            val miMode = commandArgs[0] as? Int ?: return@hookProxyVoidDeviceCommand null
            noiseControlFromSettings(miMode)
        }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncLevel", String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val level = commandArgs[0] as? String ?: return@hookProxyVoidDeviceCommand null
            noiseControlFromLevelCommand(level)
        }
    }

    private fun hookProxyStringResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: () -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val isSupported = isCurrentSupportedHeadset(device)
                if (methodName == "checkSupport") proxyCheckSupportCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "checkSupport") proxyCheckSupportCalls else -1} device=${device.describe()} isSupported=$isSupported")
                if (!isSupported) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyStringArgResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: (List<Any?>) -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val address = args.firstOrNull { it is String } as? String
                val isSupported = isCurrentSupportedHeadset(device) || (address != null && isCurrentSupportedAddress(address))
                if (methodName == "setCommonCommand") proxySetCommonCommandCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "setCommonCommand") proxySetCommonCommandCalls else -1} args=${args.describeArgs()} device=${device.describe()} addressArg=$address isSupported=$isSupported")
                if (!isSupported) return@hookBefore
                this.result = result(args)
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address ?: address}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyBooleanStringResult(className: String, methodName: String, result: () -> Boolean) {
        runCatching {
            hookBefore(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookBefore
                val isSupported = isCurrentSupportedAddress(address)
                Log.d(TAG, "$methodName proxy string call address=$address supported=$isSupported profile=${HyperOsHeadphoneAdapter.state.deviceId}")
                if (!isSupported) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=$address")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceCommand(className: String, methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                Log.d(TAG, "$methodName proxy command args=${args.describeArgs()} device=${device.describe()} isSupported=${isCurrentSupportedHeadset(device)}")
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                val domainMode = mode(args) ?: return@hookBefore
                currentAnc = domainMode
                sendHeadphoneAnc(domainMode)
                this.result = null
                Log.d(TAG, "$methodName proxy command handled address=${device?.address} domainMode=$domainMode")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls++
                val isSupported = isCurrentSupportedHeadset(device)
                Log.d(TAG, "$methodName proxy before#${if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls else -1} device=${device.describe()} isSupported=$isSupported")
                if (!isSupported) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for supported headset")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceStringNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                proxyGetCommonConfigCalls++
                val isSupported = isCurrentSupportedHeadset(device)
                Log.d(TAG, "$methodName proxy before#$proxyGetCommonConfigCalls args=${args.describeArgs()} device=${device.describe()} isSupported=$isSupported")
                if (!isSupported) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for supported headset")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookBatteryView() {
        runCatching {
            hookConstructorAfter(findConstructorByParamCount("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", 4)) {
                val device = args[0] as? BluetoothDevice ?: return@hookConstructorAfter
                val ctx = args[1] as? Context
                registerStatusReceiver(ctx)
                Log.d(TAG, "Battery.<init> device=${device.describe()} isSupported=${isCurrentSupportedHeadset(device)} ctx=$ctx currentBattery=${settingsBatteryString()}")
                if (!isCurrentSupportedHeadset(device)) return@hookConstructorAfter
                batteryViews[instance ?: return@hookConstructorAfter] = device
                requestBluetoothStatus("battery-init")
                updateBatteryView(instance)
                Log.d(TAG, "MiuiHeadsetBattery registered address=${device.address}")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetBattery constructor skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", "onBatteryChanged", String::class.java)) {
                val device = batteryViews[instance]
                Log.d(TAG, "Battery.onBatteryChanged(String) original=${args[0]} mappedDevice=${device.describe()} isSupported=${isCurrentSupportedHeadset(device)} forced=${settingsBatteryString()}")
                if (!isCurrentSupportedHeadset(device)) return@hookBefore
                result = null
                updateBatteryView(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetBattery.onBatteryChanged(String) skipped", it) }
    }

    private fun hookFragmentState() {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onCreateView", 3)) {
                registerStatusReceiver(runCatching { getObjectField(instance, "mActivity") as? Context }.getOrNull())
                Log.d(TAG, "Fragment.onCreateView after ${fragmentDebug(instance)} isSupported=${isSupportedFragment(instance)}")
                if (!isSupportedFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("fragment-create")
                startPeriodicRefresh()
                updateBatteryTopology(instance)
                injectFragmentStatus(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onCreateView skipped", it) }

        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onServiceConnected", 0)) {
                Log.d(TAG, "Fragment.onServiceConnected after ${fragmentDebug(instance)} isSupported=${isSupportedFragment(instance)}")
                if (!isSupportedFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("service-connected")
                startPeriodicRefresh()
                updateBatteryTopology(instance)
                injectFragmentStatus(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onServiceConnected skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "refreshStatus", String::class.java, String::class.java)) {
                val key = args[0] as? String
                val data = args[1] as? String
                Log.d(TAG, "Fragment.refreshStatus before key=$key data=$data ${fragmentDebug(instance)} isSupported=${isSupportedFragment(instance)}")
                if (isSupportedFragment(instance) && key?.startsWith("MMA_CONNECTION_FAILED") == true) {
                    Log.w(TAG, "Fragment.refreshStatus swallowed MMA failure for supported headset key=$key")
                    injectFragmentStatus(instance)
                    result = null
                }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.refreshStatus skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "handleConnectMmaFailed", String::class.java)) {
                Log.w(TAG, "Fragment.handleConnectMmaFailed arg=${args[0]} ${fragmentDebug(instance)} isSupported=${isSupportedFragment(instance)}")
                if (isSupportedFragment(instance)) {
                    injectFragmentStatus(instance)
                    result = null
                    Log.w(TAG, "Fragment.handleConnectMmaFailed swallowed for supported headset")
                }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.handleConnectMmaFailed skipped", it) }

        hookFragmentAncCommand("updateAncMode", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            noiseControlFromSettings(commandArgs[0] as? Int ?: 0)
        }
        hookFragmentAncCommand("updateAncLevel", String::class.java, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            val level = commandArgs[0] as? String ?: ""
            noiseControlFromLevelCommand(level)
        }
    }

    private fun hookFragmentAncCommand(methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", methodName, *parameterTypes)) {
                Log.d(TAG, "MiuiHeadsetFragment.$methodName before args=${args.describeArgs()} ${fragmentDebug(instance)} isSupported=${isSupportedFragment(instance)}")
                if (!isSupportedFragment(instance)) return@hookBefore
                val updateDevice = args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val domainMode = mode(args) ?: return@hookBefore
                currentAnc = domainMode
                sendHeadphoneAnc(domainMode)
                runCatching { callMethod(instance, "updateAncUi", settingsAncLevel(), false) }
                injectFragmentStatus(instance)
                result = null
                Log.d(TAG, "MiuiHeadsetFragment.$methodName handled domainMode=$domainMode")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.$methodName skipped", it) }
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
                updateFragments()
            }
        }, filter, Context.RECEIVER_EXPORTED)
        stateScope.launch {
            HeadphoneUiStore.state.collect { state ->
                val projected = state.toIntegrationState()
                currentAddress = projected.address ?: currentAddress
                currentName = projected.name ?: currentName
                projected.battery?.let { currentBattery = it }
                projected.anc?.let { currentAnc = it }
                projected.transparencyVocalEnhancement?.let {
                    currentTransparencyVocalEnhancement = it
                }
                refreshHandler.post {
                    updateBatteryViews()
                    updateFragments()
                }
                Log.d(
                    TAG,
                    "snapshot generation=${state.generationId} address=$currentAddress " +
                        "anc=$currentAnc battery=${settingsBatteryString()}",
                )
            }
        }
        receiverRegistered = true
        context?.let(HeadphoneSnapshotReceiver::requestSnapshot)
        requestBluetoothStatus("receiver-register")
        Log.d(TAG, "registered status receiver context=$context")
    }

    private fun requestBluetoothStatus(reason: String) {
        val ctx = context ?: return
        ctx.sendIdentitySharedBroadcast(Intent(HeadphoneActionContract.ACTION_HEADPHONE_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        HyperOsHeadphoneAdapter.execute(ctx, FeatureCommand.RefreshAll)
        Log.d(TAG, "requested bluetooth status reason=$reason")
    }

    private fun startPeriodicRefresh() {
        if (refreshLoopStarted) return
        refreshLoopStarted = true
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, SETTINGS_REFRESH_INTERVAL_MS)
        Log.d(TAG, "settings periodic refresh started")
    }

    private fun updateBatteryViews() {
        batteryViews.keys.toList().forEach { view ->
            runCatching { updateBatteryView(view) }
                .onFailure { Log.w(TAG, "update battery view failed", it) }
        }
    }

    private fun updateBatteryView(view: Any?) {
        val values = settingsBatteryValues()
        callMethod(view, "onBatteryChanged", values[0], values[1], values[2])
        Log.d(TAG, "Battery.onBatteryChanged(int,int,int) forced=${values.joinToString(",")}")
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach { fragment ->
            if (isSupportedFragment(fragment)) {
                injectFragmentStatus(fragment)
            }
        }
    }

    private fun injectFragmentStatus(fragment: Any?) {
        runCatching {
            updateBatteryTopology(fragment)
            val payload = "${settingsAncMode()}|0100;0101;0102;0103;0200;0201|${settingsBatteryString()}|00"
            Log.d(TAG, "injectFragmentStatus payload=$payload ${fragmentDebug(fragment)}")
            callMethod(fragment, "updateAtUiInfo", payload)
            callMethod(fragment, "updateAncUi", settingsAncLevel(), false)
            val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
            val address = device?.address
            if (address != null) {
                val refreshPayload = settingsRefreshPayload()
                Log.d(TAG, "injectFragmentStatus refreshPayload=$refreshPayload address=$address")
                callMethod(fragment, "refreshStatus", address, refreshPayload)
            }
            Log.d(TAG, "fragment status injected anc=$currentAnc battery=${settingsBatteryString()}")
        }.onFailure { Log.w(TAG, "inject fragment status failed", it) }
    }

    /** Collapse Xiaomi's fixed left/right/case row for any capability-reported single battery. */
    private fun updateBatteryTopology(fragment: Any?) {
        if (HyperOsHeadphoneAdapter.isTwsDevice()) return
        val root = runCatching { getObjectField(fragment, "mRootView") as? View }.getOrNull() ?: return
        val resources = root.resources
        fun view(idName: String): View? {
            val id = resources.getIdentifier(idName, "id", "com.android.settings")
            return id.takeIf { it != 0 }?.let(root::findViewById)
        }
        listOf("batteryright", "batterybox", "diverleft", "diverright").forEach { idName ->
            view(idName)?.visibility = View.GONE
        }
        view("batteryleft")?.let { left ->
            left.visibility = View.VISIBLE
            left.layoutParams = left.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        (view("textViewHeadset") as? TextView)?.text = "耳机"
        Log.d(TAG, "single-device battery topology applied")
    }

    private fun isSupportedFragment(fragment: Any?): Boolean {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        val hyperOsPresentationTypeId = hyperOsPresentationTypeId()
        return isCurrentSupportedHeadset(device) || deviceId == hyperOsPresentationTypeId || support?.startsWith(hyperOsPresentationTypeId) == true
    }

    private fun isCurrentSupportedHeadset(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        val result = HyperOsHeadphoneAdapter.supports(address)
        if (result && address != null) {
            currentAddress = address
            currentName = HyperOsHeadphoneAdapter.state.title
        }
        return result
    }

    private fun BluetoothDevice?.describe(): String {
        if (this == null) return "null"
        val address = runCatching { this.address }.getOrNull()
        val name = runCatching { this.name }.getOrNull()
        val alias = runCatching { this.alias }.getOrNull()
        return "BluetoothDevice(address=$address,name=$name,alias=$alias)"
    }

    private fun List<Any?>.describeArgs(): String {
        return joinToString(prefix = "[", postfix = "]") { arg ->
            when (arg) {
                is BluetoothDevice -> arg.describe()
                else -> arg?.toString() ?: "null"
            }
        }
    }

    private fun fragmentDebug(fragment: Any?): String {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        val service = runCatching { getObjectField(fragment, "mService") }.getOrNull()
        val hfp = runCatching { getObjectField(fragment, "mBluetoothHfp") }.getOrNull()
        val cached = runCatching { getObjectField(fragment, "mCachedDevice") }.getOrNull()
        val supportAnc = runCatching { getObjectField(fragment, "mSupportAnc") }.getOrNull()
        val ancCached = runCatching { getObjectField(fragment, "mAncCached") }.getOrNull()
        val pendingAnc = runCatching { getObjectField(fragment, "mPendingAnc") }.getOrNull()
        val ancPendingStatus = runCatching { getObjectField(fragment, "mAncPendingStatus") }.getOrNull()
        return "fragment(device=${device.describe()},deviceId=$deviceId,support=$support,service=$service,hfp=$hfp,cached=$cached,supportAnc=$supportAnc,ancCached=$ancCached,pendingAnc=$pendingAnc,ancPending=$ancPendingStatus)"
    }

    private fun isCurrentSupportedAddress(address: String): Boolean {
        return HyperOsHeadphoneAdapter.supports(address)
    }

    private fun settingsBatteryString(): String {
        return settingsBatteryValues().joinToString(",")
    }

    private fun settingsBatteryValues(): List<Int> {
        return listOf(
            batteryValue(currentBattery.left),
            batteryValue(currentBattery.right),
            batteryValue(currentBattery.case)
        )
    }

    private fun batteryValue(params: BatterySlotPresentation?): Int {
        if (params?.isConnected != true) return 255
        val value = params.battery.coerceIn(0, 100)
        return if (params.isCharging) value or 128 else value
    }

    private fun settingsAncMode(): String {
        return when (currentAnc) {
            2, 5, 6, 7, 8 -> "1"
            3 -> "2"
            else -> "0"
        }
    }

    private fun settingsAncLevel(): String {
        val noise = when (currentAnc) {
            2 -> "NOISE_CANCELLATION"
            3 -> "TRANSPARENCY"
            5 -> "NOISE_CANCELLATION_SMART"
            6 -> "NOISE_CANCELLATION_LIGHT"
            7 -> "NOISE_CANCELLATION_MEDIUM"
            8 -> "NOISE_CANCELLATION_DEEP"
            else -> "OFF"
        }
        return miuiAncLevel(noise, currentTransparencyVocalEnhancement)
    }

    private fun settingsRefreshPayload(): String {
        val battery = settingsBatteryString().split(",")
        val left = battery.getOrNull(0).orEmpty()
        val right = battery.getOrNull(1).orEmpty()
        val box = battery.getOrNull(2).orEmpty()
        val values = MutableList(16) { "" }
        values[0] = left
        values[1] = right
        values[2] = box
        values[7] = settingsAncLevel()
        values[8] = "false"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun noiseControlFromSettings(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun noiseControlFromLevel(level: String): Int {
        // Convert MIUI Settings level code back to internal domain ANC intensity state.
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

    private fun sendHeadphoneTransparencyVocalEnhancementFromLevel(level: String) {
        when {
            level.startsWith("0201") -> sendHeadphoneTransparencyVocalEnhancement(true)
            level.startsWith("0200") -> sendHeadphoneTransparencyVocalEnhancement(false)
        }
    }

    private fun noiseControlFromLevelCommand(level: String): Int? {
        if (level.startsWith("02")) {
            currentAnc = 3
            sendHeadphoneTransparencyVocalEnhancementFromLevel(level)
            return null
        }
        return noiseControlFromLevel(level)
    }

    private fun sendHeadphoneAnc(mode: Int) {
        val ctx = context ?: run {
            Log.w(TAG, "sendHeadphoneAnc skipped: context is null mode=$mode")
            return
        }
        HyperOsHeadphoneAdapter.setNoiseControl(ctx, mode)
    }

    private fun sendHeadphoneTransparencyVocalEnhancement(enabled: Boolean) {
        val ctx = context ?: run {
            Log.w(TAG, "sendHeadphoneTransparencyVocalEnhancement skipped: context is null enabled=$enabled")
            return
        }
        currentTransparencyVocalEnhancement = enabled
        HyperOsHeadphoneAdapter.execute(
            ctx,
            FeatureCommand.SetTransparencyVocalEnhancement(enabled),
        )
        Log.d(TAG, "sendHeadphoneTransparencyVocalEnhancement broadcast sent enabled=$enabled")
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return runCatching { getParcelableExtra(key, BluetoothDevice::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BluetoothDevice>(key) }.getOrNull()
    }

}
