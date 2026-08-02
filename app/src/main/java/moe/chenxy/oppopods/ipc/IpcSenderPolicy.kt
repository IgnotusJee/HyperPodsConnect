package moe.chenxy.oppopods.ipc

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction

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

    fun allowedBluetoothLegacySenders(action: String?): Set<String> = when (action) {
        LegacyPodsAction.ACTION_PODS_UI_INIT -> uiInitSenders
        LegacyPodsAction.ACTION_CONNECT_POD_REQUEST,
        LegacyPodsAction.ACTION_DISCONNECT_POD_REQUEST,
        LegacyPodsAction.ACTION_PODS_UI_CLOSED,
        LegacyPodsAction.ACTION_AUTO_GAME_MODE_CHANGED,
        LegacyPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED,
        LegacyPodsAction.ACTION_CONFIG_CHANGED,
        LegacyPodsAction.ACTION_RFCOMM_LOG_CONNECT,
        LegacyPodsAction.ACTION_RFCOMM_LOG_DISCONNECT,
        LegacyPodsAction.ACTION_RFCOMM_LOG_CLEAR,
        LegacyPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK,
        LegacyPodsAction.ACTION_RFCOMM_DEBUG_LOCK,
        LegacyPodsAction.ACTION_RFCOMM_DEBUG_SEND -> moduleOnly
        LegacyPodsAction.ACTION_CYCLE_ANC -> xiaomiBluetoothOnly
        else -> emptySet()
    }
}

fun BroadcastReceiver.isSentFrom(allowedPackages: Set<String>): Boolean {
    val senderPackage = getSentFromPackage()
    val allowed = IpcSenderPolicy.isAllowed(senderPackage, allowedPackages)
    if (!allowed) {
        Log.w(
            "OppoPods-IpcSecurity",
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
