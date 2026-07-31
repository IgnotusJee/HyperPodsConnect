package moe.chenxy.oppopods.utils.miuiStrongToast.data

object OppoPodsAction {
    const val ACTION_PODS_UI_INIT = "chen.action.oppopods.ui_init"
    const val ACTION_PODS_UI_CLOSED = "chen.action.oppopods.ui_closed"
    const val ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE = "chen.action.oppopods.module_bluetooth_service_alive"
    const val ACTION_CONNECT_POD_REQUEST = "chen.action.oppopods.connect_pod_request"
    const val ACTION_DISCONNECT_POD_REQUEST = "chen.action.oppopods.disconnect_pod_request"
    const val ACTION_REFRESH_STATUS = "chen.action.oppopods.refresh_status"
    const val ACTION_CYCLE_ANC = "chen.action.oppopods.cycle_anc"
    const val ACTION_AUTO_GAME_MODE_CHANGED = "chen.action.oppopods.auto_game_mode_changed"
    const val ACTION_GAME_MODE_IMPLEMENTATION_CHANGED = "chen.action.oppopods.game_mode_implementation_changed"
    const val ACTION_RFCOMM_LOG_CONNECT = "chen.action.oppopods.rfcomm_log_connect"
    const val ACTION_RFCOMM_LOG_DISCONNECT = "chen.action.oppopods.rfcomm_log_disconnect"
    const val ACTION_RFCOMM_LOG_CLEAR = "chen.action.oppopods.rfcomm_log_clear"
    const val ACTION_RFCOMM_LOG = "chen.action.oppopods.rfcomm_log"
    const val ACTION_RFCOMM_DEBUG_UNLOCK = "chen.action.oppopods.rfcomm_debug_unlock"
    const val ACTION_RFCOMM_DEBUG_LOCK = "chen.action.oppopods.rfcomm_debug_lock"
    const val ACTION_RFCOMM_DEBUG_SEND = "chen.action.oppopods.rfcomm_debug_send"
    const val EXTRA_RFCOMM_DEBUG_SESSION_TOKEN = "rfcomm_debug_session_token"
    val RFCOMM_DEBUG_CONTROL_ACTIONS = listOf(
        ACTION_RFCOMM_DEBUG_UNLOCK,
        ACTION_RFCOMM_DEBUG_LOCK,
        ACTION_RFCOMM_DEBUG_SEND,
    )
    const val ACTION_CONFIG_CHANGED = "chen.action.oppopods.config_changed"
}
