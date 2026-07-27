package moe.chenxy.headphones.protocol.sony.message

sealed interface SonyRoute {
    val message: SonyMdrMessage

    data class Known(
        override val message: SonyMdrMessage,
        val name: String,
    ) : SonyRoute

    /** Preserves table and raw payload for diagnostics without executing it. */
    data class Unknown(override val message: SonyMdrMessage) : SonyRoute
}

object SonyMdrRouter {
    private val connectCommands = mapOf(
        SonyCommand.CONNECT_GET_PROTOCOL_INFO to "CONNECT_GET_PROTOCOL_INFO",
        SonyCommand.CONNECT_RET_PROTOCOL_INFO to "CONNECT_RET_PROTOCOL_INFO",
        SonyCommand.CONNECT_GET_CAPABILITY_INFO to "CONNECT_GET_CAPABILITY_INFO",
        SonyCommand.CONNECT_RET_CAPABILITY_INFO to "CONNECT_RET_CAPABILITY_INFO",
        SonyCommand.CONNECT_GET_DEVICE_INFO to "CONNECT_GET_DEVICE_INFO",
        SonyCommand.CONNECT_RET_DEVICE_INFO to "CONNECT_RET_DEVICE_INFO",
        SonyCommand.CONNECT_GET_SUPPORT_FUNCTION to "CONNECT_GET_SUPPORT_FUNCTION",
        SonyCommand.CONNECT_RET_SUPPORT_FUNCTION to "CONNECT_RET_SUPPORT_FUNCTION",
    )
    private val table1Commands = connectCommands + mapOf(
        SonyCommand.COMMON_GET_BATTERY_LEVEL to "COMMON_GET_BATTERY_LEVEL",
        SonyCommand.COMMON_RET_BATTERY_LEVEL to "COMMON_RET_BATTERY_LEVEL",
        SonyCommand.COMMON_NTFY_BATTERY_LEVEL to "COMMON_NTFY_BATTERY_LEVEL",
        SonyCommand.POWER_GET_STATUS to "POWER_GET_STATUS",
        SonyCommand.POWER_RET_STATUS to "POWER_RET_STATUS",
        SonyCommand.POWER_NTFY_STATUS to "POWER_NTFY_STATUS",
    )
    private val table2Commands = connectCommands + mapOf(
        SonyCommand.POWER_GET_STATUS to "POWER_GET_STATUS",
        SonyCommand.POWER_RET_STATUS to "POWER_RET_STATUS",
        SonyCommand.POWER_NTFY_STATUS to "POWER_NTFY_STATUS",
    )

    fun route(message: SonyMdrMessage): SonyRoute {
        val name = when (message.table) {
            SonyCommandTable.TABLE1 -> table1Commands[message.command]
            SonyCommandTable.TABLE2 -> table2Commands[message.command]
        }
        return if (name == null) SonyRoute.Unknown(message) else SonyRoute.Known(message, name)
    }
}
