package org.hyperpods.connect.pods

enum class WearState(val value: Int) {
    DISCONNECTED(0x00),
    IN_CASE(0x04),
    REMOVED(0x05),
    WEARING(0x07);

    companion object {
        fun fromValue(value: Int): WearState? = entries.firstOrNull { it.value == value }
    }
}

data class WearStatus(
    val left: WearState? = null,
    val right: WearState? = null,
    val case: WearState? = null,
)
