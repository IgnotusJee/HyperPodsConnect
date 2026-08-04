package org.hyperpods.connect.integration

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphonePresentationControllerTest {
    @Test
    fun `Ready edge is emitted once and leaving Ready invalidates notification`() {
        val effects = RecordingEffects()
        val controller = HeadphonePresentationController(effects, nowMs = { 1_000L })
        val ready = snapshot(SessionState.Ready(DEVICE_ID, 7, TransportKind.CLASSIC_SPP))

        controller.onSnapshot(ready, profileGroupId = 3, address = ADDRESS)
        controller.onSnapshot(ready.copy(emittedAtMillis = 2), profileGroupId = 3, address = ADDRESS)

        assertEquals(listOf(true, false), effects.readyEdges)
        assertTrue(effects.invalidations.isEmpty())

        val idle = snapshot(SessionState.Idle(DEVICE_ID, 7))
        controller.onSnapshot(idle, profileGroupId = 3, address = ADDRESS)
        assertEquals(listOf(idle.connection), effects.invalidations)
    }

    @Test
    fun `pre Ready state cannot invalidate a notification`() {
        val effects = RecordingEffects()
        val controller = HeadphonePresentationController(effects, nowMs = { 1_000L })

        controller.onSnapshot(
            snapshot(SessionState.Detecting(DEVICE_ID, 8)),
            profileGroupId = null,
            address = ADDRESS,
        )

        assertFalse(effects.readyEdges.any())
        assertTrue(effects.invalidations.isEmpty())
    }

    private fun snapshot(connection: SessionState) = HeadphoneSnapshot(
        deviceId = DEVICE_ID,
        generationId = connection.generationId,
        connection = connection,
        state = HeadphoneState(),
        emittedAtMillis = 1,
    )

    private class RecordingEffects : HeadphonePresentationEffects {
        val invalidations = mutableListOf<SessionState>()
        val readyEdges = mutableListOf<Boolean>()

        override fun onNotificationInvalidated(connection: SessionState) {
            invalidations += connection
        }

        override fun onBatteryChanged(
            snapshot: HeadphoneSnapshot,
            allowConnectedPresentation: Boolean,
        ) = Unit

        override fun onWearingChanged(previous: HeadphoneState, current: HeadphoneState) = Unit

        override fun onReady(snapshot: HeadphoneSnapshot, enteringReady: Boolean) {
            readyEdges += enteringReady
        }
    }

    companion object {
        private val DEVICE_ID = DeviceId("device:test")
        private const val ADDRESS = "11:22:33:44:55:66"
    }
}
