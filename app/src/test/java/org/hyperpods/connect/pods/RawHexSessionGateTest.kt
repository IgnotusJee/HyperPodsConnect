package org.hyperpods.connect.pods

import org.hyperpods.connect.ipc.HeadphoneActionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHexSessionGateTest {
    @Test
    fun `raw HEX gate is session scoped and impossible in release`() {
        val debugGate = RawHexSessionGate(debugBuild = true)
        assertFalse(debugGate.canSend("session-a"))
        assertTrue(debugGate.unlock("session-a"))
        assertTrue(debugGate.canSend("session-a"))
        assertFalse(debugGate.canSend("session-b"))
        debugGate.lock("session-b")
        assertTrue(debugGate.canSend("session-a"))
        debugGate.lock("session-a")
        assertFalse(debugGate.canSend("session-a"))

        val releaseGate = RawHexSessionGate(debugBuild = false)
        assertFalse(releaseGate.unlock("session-a"))
        assertFalse(releaseGate.canSend("session-a"))
    }

    @Test
    fun `raw HEX receiver action group cannot omit unlock or lock`() {
        assertEquals(
            setOf(
                HeadphoneActionContract.ACTION_RFCOMM_DEBUG_UNLOCK,
                HeadphoneActionContract.ACTION_RFCOMM_DEBUG_LOCK,
                HeadphoneActionContract.ACTION_RFCOMM_DEBUG_SEND,
            ),
            HeadphoneActionContract.RFCOMM_DEBUG_CONTROL_ACTIONS.toSet(),
        )
    }
}
