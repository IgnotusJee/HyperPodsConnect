package org.hyperpods.connect.integration

enum class NotificationBatteryComponent {
    SINGLE,
    LEFT,
    RIGHT,
    CASE,
}

fun HeadphonePresentationState.toNotificationProjection(): HeadphoneNotificationProjection =
    HeadphoneNotificationProjection(
        title = title,
        topology = topology,
        slots = batteries.values.map { battery ->
            NotificationBatterySlot(
                component = NotificationBatteryComponent.valueOf(battery.slot.name),
                level = battery.level.coerceIn(0, 100),
                charging = battery.charging,
            )
        },
        canCycleNoiseControl = feature(
            moe.chenxy.headphones.core.feature.FeatureId.NOISE_CONTROL,
        ).writable,
    )

data class NotificationBatterySlot(
    val component: NotificationBatteryComponent,
    val level: Int,
    val charging: Boolean,
)

data class HeadphoneNotificationProjection(
    val title: String,
    val topology: String?,
    val slots: List<NotificationBatterySlot>,
    val canCycleNoiseControl: Boolean,
) {
    val islandSlots: List<NotificationBatterySlot>
        get() = when {
            slots.any { it.component == NotificationBatteryComponent.SINGLE } ->
                slots.filter { it.component in setOf(
                    NotificationBatteryComponent.SINGLE,
                    NotificationBatteryComponent.CASE,
                ) }.take(2)
            else -> {
                val ears = slots.filter { it.component in setOf(
                    NotificationBatteryComponent.LEFT,
                    NotificationBatteryComponent.RIGHT,
                ) }
                (ears + slots.filter { it.component == NotificationBatteryComponent.CASE })
                    .take(2)
            }
        }

    val aodTitle: String
        get() = slots.joinToString(" | ") { slot ->
            val label = when (slot.component) {
                NotificationBatteryComponent.SINGLE -> "B"
                NotificationBatteryComponent.LEFT -> "L"
                NotificationBatteryComponent.RIGHT -> "R"
                NotificationBatteryComponent.CASE -> "C"
            }
            "$label ${slot.level}%"
        }
}

fun HeadphoneBatteryPresentation.toNotificationProjection(fallbackTitle: String): HeadphoneNotificationProjection {
    val slots = buildList {
        single.connectedSlot(NotificationBatteryComponent.SINGLE)?.let(::add)
        if (single?.isConnected != true) {
            left.connectedSlot(NotificationBatteryComponent.LEFT)?.let(::add)
            right.connectedSlot(NotificationBatteryComponent.RIGHT)?.let(::add)
        }
        case.connectedSlot(NotificationBatteryComponent.CASE)?.let(::add)
    }
    return HeadphoneNotificationProjection(
        title = deviceName?.takeIf(String::isNotBlank) ?: fallbackTitle,
        topology = topology,
        slots = slots,
        canCycleNoiseControl = canCycleNoiseControl,
    )
}

private fun BatterySlotPresentation?.connectedSlot(
    component: NotificationBatteryComponent,
): NotificationBatterySlot? = this
    ?.takeIf(BatterySlotPresentation::isConnected)
    ?.let { NotificationBatterySlot(component, it.battery.coerceIn(0, 100), it.isCharging) }
