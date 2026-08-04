package moe.chenxy.oppopods.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPresentationGateTest {
    @Test
    fun `same logical headset reconnect is suppressed and extends quiet period`() {
        val gate = ConnectionPresentationGate(reconnectQuietPeriodMs = 120_000L)

        assertTrue(gate.claim("group:sony:1", 1_000L))
        assertFalse(gate.claim("group:sony:1", 90_000L))
        assertFalse(gate.claim("group:sony:1", 180_000L))
        assertTrue(gate.claim("group:sony:1", 301_000L))
    }

    @Test
    fun `different logical headset can present immediately`() {
        val gate = ConnectionPresentationGate(reconnectQuietPeriodMs = 120_000L)

        assertTrue(gate.claim("group:sony:1", 1_000L))
        assertTrue(gate.claim("group:sony:2", 2_000L))
    }

    @Test
    fun `group key is stable across member addresses`() {
        val first = connectionPresentationKey("sony", "LinkBuds S", 1, "AA:AA:AA:AA:AA:AA")
        val second = connectionPresentationKey("sony", "LinkBuds S", 1, "BB:BB:BB:BB:BB:BB")
        val otherGroup = connectionPresentationKey("sony", "LinkBuds S", 2, "BB:BB:BB:BB:BB:BB")

        assertTrue(first == second)
        assertNotEquals(first, otherGroup)
    }
}
