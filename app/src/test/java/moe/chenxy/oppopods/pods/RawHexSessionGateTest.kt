package moe.chenxy.oppopods.pods

import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction
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
                LegacyPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK,
                LegacyPodsAction.ACTION_RFCOMM_DEBUG_LOCK,
                LegacyPodsAction.ACTION_RFCOMM_DEBUG_SEND,
            ),
            LegacyPodsAction.RFCOMM_DEBUG_CONTROL_ACTIONS.toSet(),
        )
    }
}
