package org.hyperpods.connect.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import org.hyperpods.connect.BuildConfig
import org.hyperpods.connect.pods.HeadphoneSessionCoordinator
import org.hyperpods.connect.runtime.bluetoothprocess.BluetoothProcessRuntimeHost
import org.hyperpods.connect.ipc.IpcSenderPolicy
import org.hyperpods.connect.ipc.isSentFrom
import org.hyperpods.connect.ipc.sendIdentitySharedBroadcast
import org.hyperpods.connect.ipc.HeadphoneActionContract

object HeadsetStateDispatcher : HookContext() {
    private const val LE_AUDIO_GROUP_SETTLE_MS = 250L
    private var appRequestReceiverRegistered = false
    private val profileHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private var pendingProfileConnect: Runnable? = null

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                (instance as? Context)?.let {
                    BluetoothProcessRuntimeHost.initialize(it)
                    registerAppRequestReceiver(it)
                }
            }
        }.onFailure {
            Log.w("HyperPodsConnect", "AdapterService.onCreate hook skipped", it)
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
                Log.d("HyperPodsConnect", "A2DP Connection State: $currState")
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    HeadphoneSessionCoordinator.connectPodAutomatically(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    HeadphoneSessionCoordinator.onBluetoothProfileDisconnected(
                        context,
                        device,
                        leAudioGroupId(device),
                    )
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
                val action = intent?.action
                // This platform action is a protected broadcast. Unlike our shared-identity IPC,
                // framework profile broadcasts do not promise a visible sender package.
                if (
                    action != BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED &&
                    !isSentFrom(IpcSenderPolicy.allowedControlSenders(action))
                ) return
                when (intent?.action) {
                    BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(
                            BluetoothProfile.EXTRA_STATE,
                            -1,
                        )
                        val device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        ) ?: return
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            scheduleProfileConnect(context, device)
                        } else if (
                            state == BluetoothProfile.STATE_DISCONNECTING ||
                            state == BluetoothProfile.STATE_DISCONNECTED
                        ) {
                            cancelPendingProfileConnect()
                            Log.d("HyperPodsConnect", "LE Audio profile disconnected")
                            HeadphoneSessionCoordinator.onBluetoothProfileDisconnected(context, device)
                        }
                    }
                    HeadphoneActionContract.ACTION_HEADPHONE_UI_INIT -> {
                        context.sendIdentitySharedBroadcast(Intent(HeadphoneActionContract.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                    HeadphoneActionContract.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                Log.d("HyperPodsConnect", "connect request from app device=${device.name}")
                        HeadphoneSessionCoordinator.connectPod(context, device, prefs)
                    }
                    HeadphoneActionContract.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                Log.d("HyperPodsConnect", "disconnect request from app device=${device.name}")
                        HeadphoneSessionCoordinator.disconnectedPod(context, device)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(HeadphoneActionContract.ACTION_HEADPHONE_UI_INIT)
            addAction(HeadphoneActionContract.ACTION_CONNECT_POD_REQUEST)
            addAction(HeadphoneActionContract.ACTION_DISCONNECT_POD_REQUEST)
            addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    private fun scheduleProfileConnect(context: Context, device: BluetoothDevice) {
        cancelPendingProfileConnect()
        val connect = Runnable {
            pendingProfileConnect = null
            val groupId = leAudioGroupId(device)
            Log.i("HyperPodsConnect", "LE Audio group settled; starting session group=$groupId")
            HeadphoneSessionCoordinator.connectPodFromProfile(context, device, prefs, groupId)
        }
        pendingProfileConnect = connect
        profileHandler.postDelayed(connect, LE_AUDIO_GROUP_SETTLE_MS)
    }

    private fun cancelPendingProfileConnect() {
        pendingProfileConnect?.let(profileHandler::removeCallbacks)
        pendingProfileConnect = null
    }

    private fun leAudioGroupId(device: BluetoothDevice): Int? = runCatching {
        val serviceClass = findClass("com.android.bluetooth.le_audio.LeAudioService")
        val service = serviceClass.getDeclaredMethod("getLeAudioService")
            .apply { isAccessible = true }
            .invoke(null)
            ?: return@runCatching null
        val method = serviceClass.declaredMethods.firstOrNull {
            it.name == "getGroupId" &&
                it.parameterTypes.contentEquals(arrayOf(BluetoothDevice::class.java))
        } ?: return@runCatching null
        (method.apply { isAccessible = true }.invoke(service, device) as? Int)
            ?.takeIf { it >= 0 }
    }.onFailure {
        Log.w("HyperPodsConnect", "LE Audio group lookup unavailable", it)
    }.getOrNull()

}
