package moe.chenxy.oppopods.core

import android.bluetooth.BluetoothDevice
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.chenxy.headphones.core.transport.TransportFailure
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult
import moe.chenxy.headphones.protocol.oppo.frame.OppoFrameStreamDecoder
import moe.chenxy.headphones.transport.android.AndroidSppSocket
import moe.chenxy.headphones.transport.android.SppTransport

/**
 * Compatibility facade over [SppTransport] for the existing controller.
 *
 * `RfcommController` is synchronous and callback-driven; rewriting it to be
 * coroutine-native belongs to Phase 4. This bridge lets the new transport carry
 * real traffic first, so the socket handling and the session rework are verified
 * separately instead of landing as one large untested change.
 *
 * Chunks are handed on exactly as received. Reassembly is the decoder's job, and
 * the whole point of the transport rewrite is that this layer stops guessing at
 * frame boundaries.
 */
class RfcommTransportBridge(
    private val serviceUuid: UUID,
    private val onRawChunk: (ByteArray) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onDisconnected: (DisconnectCause, String?) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val failureDeliveryMutex = Mutex()
    private val frameDecoder = OppoFrameStreamDecoder()

    @Volatile
    private var transport: SppTransport? = null
    private var collectorJob: Job? = null
    private var stateJob: Job? = null
    @Volatile
    private var generation: Long = 0
    private var consumedWriteFailure: TransportFailure? = null

    val isOpen: Boolean
        get() = transport?.state?.value is TransportState.Open

    /**
     * Opens a connection and starts delivering complete OPOv1 frames.
     *
     * Any previous connection is torn down first: a stale reader outliving its
     * replacement was one of the failure modes of the old implementation.
     *
     * @return null on success, otherwise the transport failure that prevented
     * the connection from opening.
     */
    suspend fun connect(device: BluetoothDevice): TransportFailure? = lifecycleMutex.withLock {
        closeLocked(DisconnectCause.SUPERSEDED)
        val connectionGeneration = ++generation
        frameDecoder.reset()

        val created = SppTransport(
            socketFactory = AndroidSppSocket.factory(device, serviceUuid),
        )
        transport = created

        // Subscribe before open(): the reader starts as soon as open returns,
        // and SharedFlow intentionally has no replay.
        collectorJob = scope.launch {
            created.incoming.collect { chunk ->
                if (connectionGeneration != generation) return@collect
                onRawChunk(chunk)
                frameDecoder.feed(chunk).forEach(onFrame)
            }
        }

        try {
            created.open()
        } catch (cancelled: CancellationException) {
            closeLocked(DisconnectCause.SUPERSEDED)
            throw cancelled
        }

        when (val openedState = created.state.value) {
            TransportState.Open -> {
                // Start after open so an initial Closed/Opening value cannot be
                // mistaken for a disconnect. StateFlow still immediately gives
                // us a failure if the reader died between these two statements.
                stateJob = scope.launch {
                    created.state.collect { state ->
                        if (connectionGeneration == generation && state is TransportState.Failed) {
                            failureDeliveryMutex.withLock {
                                if (consumedWriteFailure == state.failure) {
                                    consumedWriteFailure = null
                                } else {
                                    onDisconnected(state.failure.cause, state.failure.detail)
                                }
                            }
                        }
                    }
                }
                null
            }
            is TransportState.Failed -> {
                val failure = openedState.failure
                closeLocked(DisconnectCause.SUPERSEDED)
                failure
            }
            else -> {
                val failure = TransportFailure(
                    DisconnectCause.TRANSPORT_ERROR,
                    "transport did not reach open state: $openedState",
                )
                closeLocked(DisconnectCause.SUPERSEDED)
                failure
            }
        }
    }

    suspend fun send(bytes: ByteArray): TransportWriteResult =
        failureDeliveryMutex.withLock {
            val result = transport?.write(bytes) ?: TransportWriteResult.Rejected(
                TransportFailure(DisconnectCause.TRANSPORT_ERROR, "transport is not open"),
            )
            if (result is TransportWriteResult.Rejected) {
                // The synchronous caller owns this failure and can preserve its
                // immediate/delayed reconnect policy. The StateFlow observer
                // must not deliver the same failure a second time.
                consumedWriteFailure = result.failure
            }
            result
        }

    /**
     * Performs an ordered close. A following [connect] waits for this teardown,
     * so old collectors and decoder bytes cannot leak into the replacement.
     */
    suspend fun close(cause: DisconnectCause = DisconnectCause.REQUESTED) {
        lifecycleMutex.withLock {
            closeLocked(cause)
        }
    }

    private suspend fun closeLocked(cause: DisconnectCause) {
        generation++

        // Cancel callbacks before changing the transport state. A requested
        // close must never look like a lost link to the old reconnect policy.
        val oldStateJob = stateJob
        stateJob = null
        oldStateJob?.cancelAndJoin()

        val oldCollectorJob = collectorJob
        collectorJob = null
        oldCollectorJob?.cancelAndJoin()

        val oldTransport = transport
        transport = null
        oldTransport?.close(cause)
        frameDecoder.reset()
        failureDeliveryMutex.withLock {
            consumedWriteFailure = null
        }
    }
}
