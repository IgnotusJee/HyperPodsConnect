package moe.chenxy.headphones.transport.android

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import moe.chenxy.headphones.core.session.DisconnectCause

enum class GattOperationFailureKind {
    START_REJECTED,
    STATUS_ERROR,
    TIMED_OUT,
    DISCONNECTED,
    CLOSED,
}

data class GattOperationFailure(
    val kind: GattOperationFailureKind,
    val operation: String,
    val status: Int? = null,
    val detail: String? = null,
    val disconnectCause: DisconnectCause? = null,
)

sealed interface GattOperationResult<out T> {
    data class Success<T>(val value: T) : GattOperationResult<T>
    data class Failure(val failure: GattOperationFailure) : GattOperationResult<Nothing>
}

/**
 * Serialises every callback-backed GATT operation for one connection.
 *
 * Android callbacks do not carry request IDs. A single queue is therefore not
 * an optimisation but a correctness requirement: at most one operation may be
 * waiting for a callback. Connection generations prevent callbacks retained by
 * an already closed BluetoothGatt from completing work on a newer connection.
 */
class GattOperationQueue(
    private val generationId: Long,
    private val operationTimeoutMillis: Long,
) {
    private val operationMutex = Mutex()
    private val callbacks = Channel<GattCallbackEvent>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)

    fun offer(event: GattCallbackEvent) {
        if (!closed.get()) callbacks.trySend(event)
    }

    suspend fun <T> execute(
        operation: String,
        start: () -> GattStartResult,
        match: (GattCallbackEvent) -> GattOperationResult<T>?,
    ): GattOperationResult<T> = operationMutex.withLock {
        if (closed.get()) {
            return@withLock GattOperationResult.Failure(
                GattOperationFailure(GattOperationFailureKind.CLOSED, operation),
            )
        }

        when (val started = start()) {
            GattStartResult.Started -> Unit
            is GattStartResult.Rejected -> {
                return@withLock GattOperationResult.Failure(
                    GattOperationFailure(
                        GattOperationFailureKind.START_REJECTED,
                        operation,
                        started.status,
                        started.detail,
                    ),
                )
            }
        }

        try {
            withTimeout(operationTimeoutMillis) {
                while (true) {
                    val event = callbacks.receiveCatching().getOrNull()
                        ?: return@withTimeout GattOperationResult.Failure(
                            GattOperationFailure(GattOperationFailureKind.CLOSED, operation),
                        )
                    if (event.generationId != generationId) continue
                    if (
                        event is GattCallbackEvent.ConnectionStateChanged &&
                        event.state == GattLinkState.DISCONNECTED
                    ) {
                        return@withTimeout GattOperationResult.Failure(
                            GattOperationFailure(
                                GattOperationFailureKind.DISCONNECTED,
                                operation,
                                event.status,
                                disconnectCause = event.disconnectCause,
                            ),
                        )
                    }
                    match(event)?.let { return@withTimeout it }
                }
                @Suppress("UNREACHABLE_CODE")
                error("callback loop exited")
            }
        } catch (_: TimeoutCancellationException) {
            GattOperationResult.Failure(
                GattOperationFailure(
                    GattOperationFailureKind.TIMED_OUT,
                    operation,
                    detail = "timed out after ${operationTimeoutMillis}ms",
                ),
            )
        }
    }

    suspend fun <T> executeImmediate(
        operation: String,
        action: suspend () -> GattOperationResult<T>,
    ): GattOperationResult<T> = operationMutex.withLock {
        if (closed.get()) {
            GattOperationResult.Failure(
                GattOperationFailure(GattOperationFailureKind.CLOSED, operation),
            )
        } else {
            action()
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) callbacks.close()
    }

    internal fun isClosed(): Boolean = closed.get()
}

internal fun <T> gattStatusResult(
    operation: String,
    status: Int,
    value: T,
): GattOperationResult<T> = if (status == GATT_STATUS_SUCCESS) {
    GattOperationResult.Success(value)
} else {
    GattOperationResult.Failure(
        GattOperationFailure(
            GattOperationFailureKind.STATUS_ERROR,
            operation,
            status,
        ),
    )
}
