package org.hyperpods.connect.runtime.bluetoothprocess

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.operation.FailureReason
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationPhase
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.operation.RequestId
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationTerminalSnapshotTest {
    private val deviceId = DeviceId("device:test")
    private val command = FeatureCommand.SetLowLatency(true)
    private val requestId = RequestId("ipc-request")

    @Test
    fun `only matching generation id and terminal phase completes a request`() {
        val result = OperationResult(requestId, OperationPhase.READ_BACK_CONFIRMED)
        val accepted = snapshot(
            operation = operation(OperationPhase.DEVICE_ACCEPTED),
        )
        val wrongGeneration = snapshot(
            generationId = 8,
            operation = operation(OperationPhase.READ_BACK_CONFIRMED, generationId = 8),
        )
        val terminal = snapshot(
            operation = operation(OperationPhase.READ_BACK_CONFIRMED),
        )

        assertFalse(accepted.matchesTerminalOperation(7, result))
        assertFalse(wrongGeneration.matchesTerminalOperation(7, result))
        assertTrue(terminal.matchesTerminalOperation(7, result))
    }

    @Test
    fun `fallback uses trusted result and a strictly newer emission timestamp`() {
        val base = snapshot(operation = operation(OperationPhase.DEVICE_ACCEPTED))
        val result = OperationResult(
            requestId,
            OperationPhase.FAILED,
            FailureReason.DEVICE_REJECTED,
            "rejected",
        )

        val fallback = base.withTerminalOperation(command, result, clockMillis = 50)

        assertTrue(fallback.emittedAtMillis > base.emittedAtMillis)
        assertEquals(requestId, fallback.lastOperation?.requestId)
        assertEquals(OperationPhase.FAILED, fallback.lastOperation?.phase)
        assertEquals(FailureReason.DEVICE_REJECTED, fallback.lastOperation?.failure)
        assertEquals("rejected", fallback.lastOperation?.detail)
        assertEquals(fallback.emittedAtMillis, fallback.lastOperation?.atMillis)
    }

    private fun snapshot(
        generationId: Long = 7,
        operation: OperationEvent? = null,
    ) = HeadphoneSnapshot(
        deviceId = deviceId,
        generationId = generationId,
        connection = SessionState.Ready(deviceId, generationId, TransportKind.CLASSIC_SPP),
        lastOperation = operation,
        emittedAtMillis = 100,
    )

    private fun operation(
        phase: OperationPhase,
        generationId: Long = 7,
    ) = OperationEvent(
        requestId = requestId,
        command = command,
        phase = phase,
        atMillis = 99,
        generationId = generationId,
    )
}
