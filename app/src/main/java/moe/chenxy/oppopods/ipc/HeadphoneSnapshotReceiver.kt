package moe.chenxy.oppopods.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.profile.DeviceProfileRepository
import moe.chenxy.oppopods.ui.state.HeadphoneUiStore

/** Receives one replayable versioned snapshot without republishing legacy events. */
object HeadphoneSnapshotReceiver {
    private val registered = AtomicBoolean(false)

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val appContext = context.applicationContext ?: context
        appContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isSentFrom(IpcSenderPolicy.bluetoothOnly)) return
                val target = context ?: return
                val snapshot = intent?.let(HeadphoneIpcContract::decodeSnapshot) ?: return
                val accepted = HeadphoneUiStore.accept(snapshot)
                if (accepted && target.packageName == BuildConfig.APPLICATION_ID) {
                    val prefs = target.getSharedPreferences(
                        ConfigManager.PREFS_NAME,
                        Context.MODE_PRIVATE,
                    )
                    runCatching { DeviceProfileRepository(prefs).acceptSnapshot(snapshot) }
                }
            }
        }, IntentFilter(HeadphoneIpcContract.ACTION_HEADPHONE_EVENT), Context.RECEIVER_EXPORTED)
        requestSnapshot(appContext)
    }

    fun requestSnapshot(context: Context) {
        context.sendIdentitySharedBroadcast(
            HeadphoneIpcContract.commandIntent(
                IpcCommandPayload(HeadphoneIpcContract.TYPE_REQUEST_SNAPSHOT),
                requestId = "snapshot-${UUID.randomUUID()}",
            ),
        )
    }
}
