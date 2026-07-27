package moe.chenxy.oppopods.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.ui.state.HeadphoneUiStore

/**
 * Migration bridge installed in each consumer process.
 *
 * A single replayable v2 snapshot is converted to the legacy per-feature
 * broadcasts understood by the current App/HyperOS adapters. This lets those
 * processes restart and recover atomically while old receivers remain intact.
 */
object HeadphoneIpcEventBridge {
    private val registered = AtomicBoolean(false)

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val appContext = context.applicationContext ?: context
        appContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val target = context ?: return
                val snapshot = intent?.let(HeadphoneIpcContract::decodeSnapshot) ?: return
                HeadphoneUiStore.accept(snapshot)
                publishLegacy(target, snapshot)
            }
        }, IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT), Context.RECEIVER_EXPORTED)
        requestSnapshot(appContext)
    }

    fun requestSnapshot(context: Context) {
        context.sendBroadcast(
            HeadphoneIpcContract.commandIntent(
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
                requestId = "snapshot-${UUID.randomUUID()}",
            ),
        )
    }

    internal fun legacyIntents(
        packageName: String,
        snapshot: HeadphoneSnapshotPayload,
    ): List<Intent> {
        val common: Intent.() -> Unit = {
            setPackage(packageName)
            putExtra("address", snapshot.primaryAddress)
            putExtra("device_name", snapshot.deviceName)
            putExtra("generation_id", snapshot.generationId)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val result = mutableListOf<Intent>()
        val connectionState = when {
            snapshot.protocolReady -> "connected"
            snapshot.connection == "Idle" -> "disconnected"
            snapshot.connection == "Failed" -> "error"
            else -> "connecting"
        }
        result += Intent(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED).apply {
            common()
            putExtra("state", connectionState)
        }
        if (snapshot.protocolReady) {
            result += Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply(common)
        } else if (snapshot.connection == "Idle") {
            result += Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply(common)
        }
        if (snapshot.batteries.isNotEmpty()) {
            val batteries = snapshot.batteries.associateBy { it.component }
            result += Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
                common()
                val left = batteries["LEFT"] ?: batteries["SINGLE"]
                val right = batteries["RIGHT"]
                val case = batteries["CASE"]
                putExtra(
                    "status",
                    BatteryParams(
                        left = left.toLegacyBattery(),
                        right = right.toLegacyBattery(),
                        case = case.toLegacyBattery(),
                    ),
                )
                putBattery("left", left)
                putBattery("right", right)
                putBattery("case", case)
            }
        }
        if (snapshot.wearing.isNotEmpty()) {
            result += Intent(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED).apply {
                common()
                putExtra("left_wear_status", wearValue(snapshot.wearing["LEFT"]))
                putExtra("right_wear_status", wearValue(snapshot.wearing["RIGHT"]))
                putExtra("case_wear_status", -1)
            }
        }
        snapshot.confirmed("NOISE_CONTROL")?.let { mode ->
            result += Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
                common()
                putExtra("status", noiseControlValue(mode))
            }
        }
        snapshot.confirmed("TRANSPARENCY_VOCAL_ENHANCEMENT")?.toBooleanStrictOrNull()?.let {
            result += Intent(
                OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED,
            ).apply {
                common()
                putExtra("enabled", it)
            }
        }
        snapshot.confirmed("LOW_LATENCY")?.toBooleanStrictOrNull()?.let {
            result += Intent(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED).apply {
                common()
                putExtra("enabled", it)
            }
        }
        snapshot.confirmed("SPATIAL_AUDIO")?.let {
            result += Intent(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED).apply {
                common()
                putExtra(
                    "mode",
                    when (it) {
                        "FIXED" -> 1
                        "HEAD_TRACKING" -> 2
                        else -> 0
                    },
                )
            }
        }
        snapshot.confirmed("EQUALIZER")?.substringAfter("oppo:")?.toIntOrNull()?.let {
            result += Intent(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED).apply {
                common()
                putExtra("preset", it)
            }
        }
        snapshot.confirmed("DUAL_DEVICE_CONNECTION")?.toBooleanStrictOrNull()?.let {
            result += Intent(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED).apply {
                common()
                putExtra("enabled", it)
            }
        }
        return result
    }

    private fun publishLegacy(context: Context, snapshot: HeadphoneSnapshotPayload) {
        legacyIntents(context.packageName, snapshot).forEach(context::sendBroadcast)
    }

    private fun Intent.putBattery(prefix: String, value: BatteryPayload?) {
        putExtra("${prefix}_battery", value?.level ?: 0)
        putExtra("${prefix}_charging", value?.charging == true)
        putExtra("${prefix}_connected", value != null)
    }

    private fun BatteryPayload?.toLegacyBattery() = PodParams(
        battery = this?.level ?: 0,
        isCharging = this?.charging == true,
        isConnected = this != null,
        rawStatus = 0,
    )

    private fun HeadphoneSnapshotPayload.confirmed(feature: String) =
        features[feature]?.confirmed

    private fun wearValue(value: String?): Int = when (value) {
        "IN_CASE" -> 0x04
        "REMOVED" -> 0x05
        "WEARING" -> 0x07
        else -> 0x00
    }

    private fun noiseControlValue(value: String): Int = when (value) {
        "NOISE_CANCELLATION" -> 2
        "TRANSPARENCY" -> 3
        "ADAPTIVE" -> 4
        "NOISE_CANCELLATION_SMART" -> 5
        "NOISE_CANCELLATION_LIGHT" -> 6
        "NOISE_CANCELLATION_MEDIUM" -> 7
        "NOISE_CANCELLATION_DEEP" -> 8
        else -> 1
    }
}
