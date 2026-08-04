package moe.chenxy.oppopods.integration

import moe.chenxy.oppopods.ui.state.HeadphoneUiState

/** Values consumed by HyperOS' built-in headset island request. */
data class OfficialHeadsetIslandPayload(
    val requestFlag: Int = REQUEST_CONNECTED,
    val leftBattery: Int,
    val rightBattery: Int,
    val wearState: Int,
) {
    companion object {
        const val REQUEST_CONNECTED = 2
        const val BATTERY_UNAVAILABLE = 255
        const val WEAR_NONE = 0
        const val WEAR_BOTH = 1
        const val WEAR_LEFT = 2
        const val WEAR_RIGHT = 3
        const val WEAR_NOT_SUPPORTED = 4
    }
}

/**
 * Projects a Ready, exactly identified app device into Xiaomi's official two-slot model.
 * SINGLE devices occupy the left slot; 255 is Xiaomi's unavailable-battery sentinel.
 */
fun HeadphoneUiState.toOfficialHeadsetIslandPayload(): OfficialHeadsetIslandPayload? {
    if (!connected || deviceId == null || address.isNullOrBlank()) return null

    val single = batteries["SINGLE"]
    val left = batteries["LEFT"] ?: single
    val right = batteries["RIGHT"]
    if (left == null && right == null) return null

    return OfficialHeadsetIslandPayload(
        leftBattery = left?.level?.coerceIn(0, 100)
            ?: OfficialHeadsetIslandPayload.BATTERY_UNAVAILABLE,
        rightBattery = right?.level?.coerceIn(0, 100)
            ?: OfficialHeadsetIslandPayload.BATTERY_UNAVAILABLE,
        wearState = officialWearState(),
    )
}

/** Only connection readiness and real wear transitions should create a new official island. */
fun shouldTriggerOfficialHeadsetIsland(
    previous: HeadphoneUiState,
    current: HeadphoneUiState,
): Boolean {
    if (current.toOfficialHeadsetIslandPayload() == null) return false
    val newlyReady = !previous.connected ||
        previous.deviceId != current.deviceId ||
        !previous.address.equals(current.address, ignoreCase = true)
    if (newlyReady) return true
    return current.wearing.isNotEmpty() && previous.wearing != current.wearing
}

private fun HeadphoneUiState.officialWearState(): Int {
    if (wearing.isEmpty()) return OfficialHeadsetIslandPayload.WEAR_NOT_SUPPORTED

    val single = wearing["SINGLE"]
    if (single != null) {
        return if (single.isWearing()) {
            OfficialHeadsetIslandPayload.WEAR_LEFT
        } else {
            OfficialHeadsetIslandPayload.WEAR_NONE
        }
    }

    val left = wearing["LEFT"]?.isWearing() == true
    val right = wearing["RIGHT"]?.isWearing() == true
    return when {
        left && right -> OfficialHeadsetIslandPayload.WEAR_BOTH
        left -> OfficialHeadsetIslandPayload.WEAR_LEFT
        right -> OfficialHeadsetIslandPayload.WEAR_RIGHT
        else -> OfficialHeadsetIslandPayload.WEAR_NONE
    }
}

private fun String.isWearing(): Boolean = equals("WEARING", ignoreCase = true)
