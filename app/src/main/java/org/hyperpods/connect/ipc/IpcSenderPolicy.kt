package org.hyperpods.connect.ipc

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.hyperpods.connect.ipc.HeadphoneActionContract

object IpcSenderPolicy {
    val moduleOnly: Set<String> = setOf(HeadphoneIpcContract.MODULE_PACKAGE)
    val bluetoothOnly: Set<String> = setOf(HeadphoneIpcContract.BLUETOOTH_HOST_PACKAGE)
    val uiInitSenders: Set<String> = setOf(
        HeadphoneIpcContract.MODULE_PACKAGE,
        HeadphoneIpcContract.MILINK_PACKAGE,
        HeadphoneIpcContract.SETTINGS_PACKAGE,
    )
    val commandSenders: Set<String> = HeadphoneIpcContract.trustedClientPackages
    val xiaomiBluetoothOnly: Set<String> = setOf(HeadphoneIpcContract.XIAOMI_BLUETOOTH_PACKAGE)

    fun isAllowed(senderPackage: String?, allowedPackages: Set<String>): Boolean =
        senderPackage != null && senderPackage in allowedPackages

    fun allowedControlSenders(action: String?): Set<String> = when (action) {
        HeadphoneActionContract.ACTION_HEADPHONE_UI_INIT -> uiInitSenders
        HeadphoneActionContract.ACTION_CONNECT_POD_REQUEST,
        HeadphoneActionContract.ACTION_DISCONNECT_POD_REQUEST,
        HeadphoneActionContract.ACTION_HEADPHONE_UI_CLOSED,
        HeadphoneActionContract.ACTION_AUTO_GAME_MODE_CHANGED,
        HeadphoneActionContract.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED,
        HeadphoneActionContract.ACTION_CONFIG_CHANGED,
        HeadphoneActionContract.ACTION_RFCOMM_LOG_CONNECT,
        HeadphoneActionContract.ACTION_RFCOMM_LOG_DISCONNECT,
        HeadphoneActionContract.ACTION_RFCOMM_LOG_CLEAR,
        HeadphoneActionContract.ACTION_RFCOMM_DEBUG_UNLOCK,
        HeadphoneActionContract.ACTION_RFCOMM_DEBUG_LOCK,
        HeadphoneActionContract.ACTION_RFCOMM_DEBUG_SEND -> moduleOnly
        HeadphoneActionContract.ACTION_CYCLE_ANC -> xiaomiBluetoothOnly
        else -> emptySet()
    }
}

fun BroadcastReceiver.isSentFrom(allowedPackages: Set<String>): Boolean {
    val senderPackage = getSentFromPackage()
    val allowed = IpcSenderPolicy.isAllowed(senderPackage, allowedPackages)
    if (!allowed) {
        Log.w(
            "HyperPodsConnect-IpcSecurity",
            "Rejected broadcast sender=${senderPackage ?: "<unknown>"}",
        )
    }
    return allowed
}

fun Context.sendIdentitySharedBroadcast(intent: Intent) {
    val options = BroadcastOptions.makeBasic()
        .setShareIdentityEnabled(true)
        .toBundle()
    sendBroadcast(intent, null, options)
}
