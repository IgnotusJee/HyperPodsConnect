package org.hyperpods.connect.runtime.bluetoothprocess

import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.engine.HeadphoneSnapshot

internal fun HeadphoneSnapshot.matchesTerminalOperation(
    generationId: Long,
    result: OperationResult,
): Boolean {
    val operation = lastOperation ?: return false
    return this.generationId == generationId &&
        operation.generationId == generationId &&
        operation.requestId == result.requestId &&
        operation.phase == result.phase &&
        operation.phase.isTerminal
}

internal fun HeadphoneSnapshot.withTerminalOperation(
    command: FeatureCommand,
    result: OperationResult,
    clockMillis: Long,
): HeadphoneSnapshot {
    require(result.phase.isTerminal) { "operation result must be terminal" }
    val terminalAtMillis = maxOf(clockMillis, emittedAtMillis + 1)
    return copy(
        lastOperation = OperationEvent(
            requestId = result.requestId,
            command = command,
            phase = result.phase,
            atMillis = terminalAtMillis,
            generationId = generationId,
            failure = result.failure,
            detail = result.detail,
        ),
        emittedAtMillis = terminalAtMillis,
    )
}
