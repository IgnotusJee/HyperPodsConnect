package moe.chenxy.oppopods.pods

import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
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
                OppoPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK,
                OppoPodsAction.ACTION_RFCOMM_DEBUG_LOCK,
                OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND,
            ),
            OppoPodsAction.RFCOMM_DEBUG_CONTROL_ACTIONS.toSet(),
        )
    }
}
