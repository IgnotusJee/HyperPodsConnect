package moe.chenxy.oppopods.pods

/** Intent contract shared by the Bluetooth-process trigger and [moe.chenxy.oppopods.PopupActivity]. */
object ConnectedPopupContract {
    const val EXTRA_FORCE_MODULE_POPUP = "moe.chenxy.oppopods.extra.FORCE_MODULE_POPUP"
    const val EXTRA_CONNECTION_EDGE_POPUP = "moe.chenxy.oppopods.extra.CONNECTION_EDGE_POPUP"
    const val EXTRA_EXPECTED_GENERATION = "moe.chenxy.oppopods.extra.EXPECTED_GENERATION"
    const val EXTRA_EXPECTED_EMITTED_AT = "moe.chenxy.oppopods.extra.EXPECTED_EMITTED_AT"
    const val EXTRA_EXPECTED_DEVICE_ID = "moe.chenxy.oppopods.extra.EXPECTED_DEVICE_ID"
    const val EXTRA_EXPECTED_ADDRESS = "moe.chenxy.oppopods.extra.EXPECTED_ADDRESS"
}

data class ConnectedPopupCandidate(
    val generationId: Long,
    val address: String,
    val deviceId: String,
)

/**
 * Claims a single popup attempt for an eligible Ready edge.
 *
 * A claim is consumed before Android is asked to launch the Activity. Background-start rejection
 * therefore degrades to the existing notification instead of creating a retry loop.
 */
class ConnectedPopupCoordinator(
    private val addressCooldownMs: Long = DEFAULT_ADDRESS_COOLDOWN_MS,
) {
    private var armedKey: String? = null
    private var handledKey: String? = null
    private val lastAttemptByAddress = mutableMapOf<String, Long>()

    fun claim(
        enabled: Boolean,
        transportConnected: Boolean,
        protocolReady: Boolean,
        readyEdge: Boolean,
        generationId: Long,
        address: String?,
        deviceId: String?,
        batteryLevels: Collection<Int>,
        nowMs: Long,
    ): ConnectedPopupCandidate? {
        if (readyEdge) armedKey = null
        if (!enabled || !transportConnected || !protocolReady || generationId < 0L) {
            armedKey = null
            return null
        }
        val normalizedAddress = address?.trim()?.takeIf(String::isNotEmpty)?.uppercase() ?: return null
        val normalizedDeviceId = deviceId?.trim()?.takeIf(String::isNotEmpty) ?: return null

        val key = "$generationId@$normalizedAddress"
        if (readyEdge) armedKey = key
        if (armedKey != key) return null
        if (batteryLevels.none { it in 1..100 }) return null
        armedKey = null
        if (handledKey == key) return null
        handledKey = key

        val previousAttempt = lastAttemptByAddress[normalizedAddress]
        if (previousAttempt != null && nowMs - previousAttempt in 0 until addressCooldownMs) {
            return null
        }
        lastAttemptByAddress[normalizedAddress] = nowMs
        return ConnectedPopupCandidate(generationId, normalizedAddress, normalizedDeviceId)
    }

    companion object {
        const val DEFAULT_ADDRESS_COOLDOWN_MS = 10_000L
    }
}
