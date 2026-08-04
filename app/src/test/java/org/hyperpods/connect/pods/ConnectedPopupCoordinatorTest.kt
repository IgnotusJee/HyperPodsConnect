package org.hyperpods.connect.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectedPopupCoordinatorTest {
    @Test
    fun `claims only exact ready connected snapshot with valid battery`() {
        val coordinator = ConnectedPopupCoordinator()

        assertNull(claim(coordinator, enabled = false))
        assertNull(claim(coordinator, transportConnected = false))
        assertNull(claim(coordinator, protocolReady = false))
        assertNull(claim(coordinator, address = null))
        assertNull(claim(coordinator, deviceId = null))
        assertNull(claim(coordinator, batteryLevels = listOf(0, 101)))

        val candidate = claim(coordinator)
        assertEquals(12L, candidate?.generationId)
        assertEquals("AA:BB:CC:DD:EE:FF", candidate?.address)
        assertEquals("device-id", candidate?.deviceId)
    }

    @Test
    fun `same generation and address is consumed once even after launch failure`() {
        val coordinator = ConnectedPopupCoordinator()
        claim(coordinator)
        assertNull(claim(coordinator, readyEdge = false, nowMs = 20_000L))
    }

    @Test
    fun `ready edge can wait for battery but ordinary ready refresh cannot arm a popup`() {
        val coordinator = ConnectedPopupCoordinator()
        assertNull(claim(coordinator, batteryLevels = emptyList()))
        assertEquals(12L, claim(coordinator, readyEdge = false, nowMs = 2_000L)?.generationId)

        val lateCoordinator = ConnectedPopupCoordinator()
        assertNull(claim(lateCoordinator, enabled = false))
        assertNull(claim(lateCoordinator, readyEdge = false, nowMs = 2_000L))
    }

    @Test
    fun `new generation inside address cooldown is consumed without delayed popup`() {
        val coordinator = ConnectedPopupCoordinator(addressCooldownMs = 10_000L)
        claim(coordinator, generationId = 12L, nowMs = 1_000L)
        assertNull(claim(coordinator, generationId = 13L, nowMs = 5_000L))
        assertNull(claim(coordinator, generationId = 13L, nowMs = 20_000L))
        assertEquals(14L, claim(coordinator, generationId = 14L, nowMs = 20_000L)?.generationId)
    }

    private fun claim(
        coordinator: ConnectedPopupCoordinator,
        enabled: Boolean = true,
        transportConnected: Boolean = true,
        protocolReady: Boolean = true,
        readyEdge: Boolean = true,
        generationId: Long = 12L,
        address: String? = "aa:bb:cc:dd:ee:ff",
        deviceId: String? = "device-id",
        batteryLevels: Collection<Int> = listOf(72),
        nowMs: Long = 1_000L,
    ) = coordinator.claim(
        enabled,
        transportConnected,
        protocolReady,
        readyEdge,
        generationId,
        address,
        deviceId,
        batteryLevels,
        nowMs,
    )
}
