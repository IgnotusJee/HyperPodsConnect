package moe.chenxy.oppopods.utils.miuiStrongToast.data

object OppoPodsAction {
    const val ACTION_PODS_UI_INIT = "chen.action.oppopods.ui_init"
    const val ACTION_PODS_UI_CLOSED = "chen.action.oppopods.ui_closed"
    const val ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE = "chen.action.oppopods.module_bluetooth_service_alive"
    const val ACTION_PODS_CONNECTED = "chen.action.oppopods.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "chen.action.oppopods.pods_disconnected"
    const val ACTION_CONNECT_POD_REQUEST = "chen.action.oppopods.connect_pod_request"
    const val ACTION_DISCONNECT_POD_REQUEST = "chen.action.oppopods.disconnect_pod_request"
    const val ACTION_PODS_CONNECTION_STATE_CHANGED = "chen.action.oppopods.pods_connection_state_changed"
    const val ACTION_PODS_BATTERY_CHANGED = "chen.action.oppopods.pods_battery_changed"
    const val ACTION_PODS_WEAR_STATUS_CHANGED = "chen.action.oppopods.pods_wear_status_changed"
    const val ACTION_PODS_ANC_CHANGED = "chen.action.oppopods.pods_anc_select"
    const val ACTION_GET_PODS_MAC = "chen.action.oppopods.get_pods_mac"
    const val ACTION_PODS_MAC_RECEIVED = "chen.action.oppopods.get_pods_mac"
    const val ACTION_REFRESH_STATUS = "chen.action.oppopods.refresh_status"
    const val ACTION_PODS_GAME_MODE_CHANGED = "chen.action.oppopods.pods_game_mode_changed"
    const val ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED = "chen.action.oppopods.pods_transparency_vocal_enhancement_changed"
    const val ACTION_PODS_SPATIAL_AUDIO_CHANGED = "chen.action.oppopods.pods_spatial_audio_changed"
    const val ACTION_PODS_EQ_PRESET_CHANGED = "chen.action.oppopods.pods_eq_preset_changed"
    const val ACTION_PODS_SMART_ANC_LEVEL_CHANGED = "chen.action.oppopods.pods_smart_anc_level_changed"
    const val ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED = "chen.action.oppopods.pods_dual_device_connection_changed"
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
    // Adaptive模式开关状态变更广播，用于跨进程同步偏好设置（App → com.android.bluetooth / com.xiaomi.bluetooth）
    const val ACTION_ADAPTIVE_MODE_CHANGED = "chen.action.oppopods.adaptive_mode_changed"
    const val ACTION_CONFIG_CHANGED = "chen.action.oppopods.config_changed"
}
