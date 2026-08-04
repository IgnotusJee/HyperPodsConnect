package moe.chenxy.oppopods.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams

class HeadphoneNotificationProjectionTest {
    @Test
    fun `TWS projection keeps ear ordering and limits island to two slots`() {
        val projection = BatteryParams(
            left = battery(91),
            right = battery(83, charging = true),
            case = battery(72),
            deviceName = "LinkBuds S",
            topology = "EARBUDS_WITH_CASE",
            canCycleNoiseControl = true,
        ).toNotificationProjection("fallback")

        assertEquals("LinkBuds S", projection.title)
        assertEquals(
            listOf(
                NotificationBatteryComponent.LEFT,
                NotificationBatteryComponent.RIGHT,
                NotificationBatteryComponent.CASE,
            ),
            projection.slots.map(NotificationBatterySlot::component),
        )
        assertEquals(2, projection.islandSlots.size)
        assertEquals("L 91% | R 83% | C 72%", projection.aodTitle)
        assertTrue(projection.canCycleNoiseControl)
    }

    @Test
    fun `single battery topology does not masquerade as left ear`() {
        val projection = BatteryParams(
            left = battery(90), // Legacy HyperOS fallback retained for old consumers.
            single = battery(90),
            deviceName = "WH-1000XM4",
            topology = "HEADBAND",
        ).toNotificationProjection("fallback")

        assertEquals(
            listOf(NotificationBatteryComponent.SINGLE),
            projection.slots.map(NotificationBatterySlot::component),
        )
        assertEquals("B 90%", projection.aodTitle)
        assertFalse(projection.canCycleNoiseControl)
    }

    @Test
    fun `disconnected slots are omitted and fallback title is deterministic`() {
        val projection = BatteryParams(
            left = battery(0, connected = false),
            right = battery(67),
            case = battery(50),
        ).toNotificationProjection("Headphones")

        assertEquals("Headphones", projection.title)
        assertEquals(
            listOf(NotificationBatteryComponent.RIGHT, NotificationBatteryComponent.CASE),
            projection.islandSlots.map(NotificationBatterySlot::component),
        )
    }

    private fun battery(
        level: Int,
        charging: Boolean = false,
        connected: Boolean = true,
    ) = PodParams(level, charging, connected, 0)
}
