package org.hyperpods.connect.ipc

/**
 * Frozen compatibility actions used by existing LSPosed scope processes and upgrade paths.
 *
 * New state and feature commands must use HeadphoneIpcContract instead of adding constants here.
 */
object HeadphoneActionContract {
    const val ACTION_HEADPHONE_UI_INIT = "org.hyperpods.connect.action.ui_init"
    const val ACTION_HEADPHONE_UI_CLOSED = "org.hyperpods.connect.action.ui_closed"
    const val ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE = "org.hyperpods.connect.action.module_bluetooth_service_alive"
    const val ACTION_CONNECT_POD_REQUEST = "org.hyperpods.connect.action.connect_pod_request"
    const val ACTION_DISCONNECT_POD_REQUEST = "org.hyperpods.connect.action.disconnect_pod_request"
    const val ACTION_CYCLE_ANC = "org.hyperpods.connect.action.cycle_anc"
    const val ACTION_AUTO_GAME_MODE_CHANGED = "org.hyperpods.connect.action.auto_game_mode_changed"
    const val ACTION_GAME_MODE_IMPLEMENTATION_CHANGED = "org.hyperpods.connect.action.game_mode_implementation_changed"
    const val ACTION_RFCOMM_LOG_CONNECT = "org.hyperpods.connect.action.rfcomm_log_connect"
    const val ACTION_RFCOMM_LOG_DISCONNECT = "org.hyperpods.connect.action.rfcomm_log_disconnect"
    const val ACTION_RFCOMM_LOG_CLEAR = "org.hyperpods.connect.action.rfcomm_log_clear"
    const val ACTION_RFCOMM_LOG = "org.hyperpods.connect.action.rfcomm_log"
    const val ACTION_RFCOMM_DEBUG_UNLOCK = "org.hyperpods.connect.action.rfcomm_debug_unlock"
    const val ACTION_RFCOMM_DEBUG_LOCK = "org.hyperpods.connect.action.rfcomm_debug_lock"
    const val ACTION_RFCOMM_DEBUG_SEND = "org.hyperpods.connect.action.rfcomm_debug_send"
    const val EXTRA_RFCOMM_DEBUG_SESSION_TOKEN = "rfcomm_debug_session_token"
    val RFCOMM_DEBUG_CONTROL_ACTIONS = listOf(
        ACTION_RFCOMM_DEBUG_UNLOCK,
        ACTION_RFCOMM_DEBUG_LOCK,
        ACTION_RFCOMM_DEBUG_SEND,
    )
    const val ACTION_CONFIG_CHANGED = "org.hyperpods.connect.action.config_changed"
}
