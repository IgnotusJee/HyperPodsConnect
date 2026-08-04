package moe.chenxy.oppopods.integration

/**
 * Suppresses presentation-only effects while the same logical headset is churning transports.
 * Every Ready edge extends the quiet period so a headset settling in its case cannot eventually
 * escape the gate by repeatedly reconnecting.
 */
class ConnectionPresentationGate(
    private val reconnectQuietPeriodMs: Long = DEFAULT_RECONNECT_QUIET_PERIOD_MS,
) {
    private val lastReadyEdgeByDevice = mutableMapOf<String, Long>()

    fun claim(logicalDeviceKey: String?, nowMs: Long): Boolean {
        val key = logicalDeviceKey?.trim()?.takeIf(String::isNotEmpty)?.uppercase() ?: return true
        val previous = lastReadyEdgeByDevice.put(key, nowMs) ?: return true
        return nowMs - previous !in 0 until reconnectQuietPeriodMs
    }

    companion object {
        const val DEFAULT_RECONNECT_QUIET_PERIOD_MS = 120_000L
    }
}

fun connectionPresentationKey(
    vendorId: String?,
    deviceName: String?,
    profileGroupId: Int? = null,
    address: String? = null,
): String? {
    val vendor = vendorId?.trim()?.takeIf(String::isNotEmpty)?.lowercase().orEmpty()
    if (profileGroupId != null) return "group:$vendor:$profileGroupId"
    val name = deviceName?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
    if (name != null) return "model:$vendor:$name"
    return address?.trim()?.takeIf(String::isNotEmpty)?.uppercase()?.let { "address:$it" }
}
