package moe.chenxy.headphones.core.session

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMachineTest {

    private val deviceId = DeviceId("bt:AABBCCDDEEFF")

    private fun machine() = SessionMachine(SessionState.Idle(deviceId, generationId = 0))

    private fun connectToReady(machine: SessionMachine): SessionState {
        machine.onEvent(SessionEvent.ConnectRequested)
        machine.onEvent(SessionEvent.DetectionSucceeded(TransportKind.CLASSIC_SPP))
        machine.onEvent(SessionEvent.TransportOpened)
        machine.onEvent(SessionEvent.HandshakeCompleted)
        machine.onEvent(SessionEvent.CapabilitiesLoaded)
        return machine.onEvent(SessionEvent.InitialStateSynchronized)
    }

    @Test
    fun `an open transport is not a ready session`() {
        val machine = machine()
        machine.onEvent(SessionEvent.ConnectRequested)
        machine.onEvent(SessionEvent.DetectionSucceeded(TransportKind.CLASSIC_SPP))

        val afterOpen = machine.onEvent(SessionEvent.TransportOpened)

        // The old implementation called this connected; the protocol has not
        // even been spoken to yet.
        assertTrue(afterOpen is SessionState.ProtocolHandshaking)
        assertFalse(afterOpen.isProtocolReady)
    }

    @Test
    fun `readiness requires handshake capabilities and the initial sync`() {
        val machine = machine()

        val ready = connectToReady(machine)

        assertTrue(ready is SessionState.Ready)
        assertTrue(ready.isProtocolReady)
        assertEquals(TransportKind.CLASSIC_SPP, (ready as SessionState.Ready).transport)
    }

    @Test
    fun `each connect attempt gets a new generation`() {
        val machine = machine()

        machine.onEvent(SessionEvent.ConnectRequested)
        val first = machine.state.generationId
        machine.onEvent(SessionEvent.Disconnected(DisconnectCause.LINK_LOST))
        machine.onEvent(SessionEvent.ConnectRequested)

        assertEquals(first + 1, machine.state.generationId)
    }

    @Test
    fun `events from an older generation are ignored`() {
        val machine = machine()
        connectToReady(machine)
        val currentGeneration = machine.state.generationId

        // A callback from a previous attempt arriving late must not tear down
        // the session that replaced it.
        val unchanged = machine.onEvent(
            SessionEvent.Disconnected(DisconnectCause.LINK_LOST),
            generationId = currentGeneration - 1,
        )

        assertTrue(unchanged is SessionState.Ready)
    }

    @Test
    fun `a requested disconnect ends idle while a lost link ends failed`() {
        val requested = machine()
        connectToReady(requested)
        assertTrue(requested.onEvent(SessionEvent.Disconnected(DisconnectCause.REQUESTED)) is SessionState.Idle)

        val lost = machine()
        connectToReady(lost)
        val failed = lost.onEvent(SessionEvent.Disconnected(DisconnectCause.LINK_LOST))
        assertTrue(failed is SessionState.Failed)
        assertTrue((failed as SessionState.Failed).canRetry)
    }

    @Test
    fun `causes that cannot succeed on retry are not retryable`() {
        val machine = machine()
        connectToReady(machine)

        val failed = machine.onEvent(
            SessionEvent.Failed(FailureCategory.PERMISSION, DisconnectCause.PERMISSION_DENIED),
        ) as SessionState.Failed

        // Retrying a denied permission or a powered-off adapter just burns battery.
        assertFalse(failed.canRetry)
        assertFalse(DisconnectCause.ADAPTER_OFF.isRetryable)
        assertFalse(DisconnectCause.BOND_REMOVED.isRetryable)
        assertTrue(DisconnectCause.LINK_LOST.isRetryable)
    }

    @Test
    fun `detection failure reports an unsupported device rather than a transport fault`() {
        val machine = machine()
        machine.onEvent(SessionEvent.ConnectRequested)

        val failed = machine.onEvent(SessionEvent.DetectionFailed("no vendor evidence")) as SessionState.Failed

        assertEquals(FailureCategory.UNSUPPORTED_DEVICE, failed.category)
    }

    @Test
    fun `out of order events leave the state untouched`() {
        val machine = machine()
        machine.onEvent(SessionEvent.ConnectRequested)

        // Handshake cannot complete before a transport exists.
        val stillDetecting = machine.onEvent(SessionEvent.HandshakeCompleted)

        assertTrue(stillDetecting is SessionState.Detecting)
    }
}
