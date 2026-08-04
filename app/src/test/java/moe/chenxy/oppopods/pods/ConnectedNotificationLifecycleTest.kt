package moe.chenxy.oppopods.pods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedNotificationLifecycleTest {
    @Test
    fun `leaving a ready generation cancels the connected notification`() {
        assertTrue(shouldCancelConnectedNotification(7L, notificationReady = false))
    }

    @Test
    fun `ready and pre-ready snapshots do not cancel the connected notification`() {
        assertFalse(shouldCancelConnectedNotification(7L, notificationReady = true))
        assertFalse(shouldCancelConnectedNotification(-1L, notificationReady = false))
    }
}
