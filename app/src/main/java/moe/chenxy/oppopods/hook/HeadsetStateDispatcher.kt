package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.OppoSystemIntegrationAdapter
import moe.chenxy.oppopods.runtime.bluetoothprocess.BluetoothProcessRuntimeHost
import moe.chenxy.oppopods.ipc.IpcSenderPolicy
import moe.chenxy.oppopods.ipc.isSentFrom
import moe.chenxy.oppopods.ipc.sendIdentitySharedBroadcast
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                (instance as? Context)?.let {
                    BluetoothProcessRuntimeHost.initialize(it)
                    registerAppRequestReceiver(it)
                }
            }
        }.onFailure {
            Log.w("OppoPods", "AdapterService.onCreate hook skipped", it)
        }

        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                Log.d("OppoPods", "A2DP Connection State: $currState, isOppoPod ${isOppoPod(device)}")
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    if (isOppoPod(device)) {
                        statusBarManager.setIconVisibility("wireless_headset", true)
                    }
                    OppoSystemIntegrationAdapter.connectPodAutomatically(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    if (isOppoPod(device)) {
                        statusBarManager.setIconVisibility("wireless_headset", false)
                    }
                    if (OppoSystemIntegrationAdapter.isCurrentDevice(device)) {
                        OppoSystemIntegrationAdapter.disconnectedPod(context, device)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                if (!isSentFrom(IpcSenderPolicy.allowedBluetoothLegacySenders(intent?.action))) return
                when (intent?.action) {
                    LegacyPodsAction.ACTION_PODS_UI_INIT -> {
                        context.sendIdentitySharedBroadcast(Intent(LegacyPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                    LegacyPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                Log.d("OppoPods", "connect request from app device=${device.name}")
                        OppoSystemIntegrationAdapter.connectPod(context, device, prefs)
                    }
                    LegacyPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                Log.d("OppoPods", "disconnect request from app device=${device.name}")
                        OppoSystemIntegrationAdapter.disconnectedPod(context, device)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(LegacyPodsAction.ACTION_PODS_UI_INIT)
            addAction(LegacyPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(LegacyPodsAction.ACTION_DISCONNECT_POD_REQUEST)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    /**
     * Detect OPPO earphones by checking if the device name contains "oppo" (case insensitive).
     */
    @SuppressLint("MissingPermission")
    fun isOppoPod(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        return name.contains("oppo", ignoreCase = true)
    }
}
