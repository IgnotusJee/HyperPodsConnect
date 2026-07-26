package moe.chenxy.headphones.transport.android

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportFailure
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult

/**
 * Bluetooth Classic SPP implementation of [ByteTransport].
 *
 * Fixes four things the previous socket handling got wrong:
 *
 * - every write went out on the caller's thread with no serialisation, so two
 *   concurrent commands could interleave mid-frame. Writes now pass through a
 *   mutex and carry a timeout;
 * - each connect, reader and reconnect spawned its own `CoroutineScope`, owned
 *   by nobody, so nothing could reliably cancel them. This transport owns one
 *   scope and cancels it on close;
 * - `connect()` could block indefinitely. It is now bounded;
 * - the reader looped on a flag meaning "should stay connected" rather than on
 *   the socket's own state, which kept it spinning after the socket had gone.
 *
 * It never inspects the bytes. Framing belongs to the protocol module, which is
 * what lets one transport serve every vendor.
 */
class SppTransport(
    private val socketFactory: SppSocketFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val writeTimeoutMillis: Long = DEFAULT_WRITE_TIMEOUT_MS,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER,
) : ByteTransport {

    override val kind: TransportKind = TransportKind.CLASSIC_SPP

    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    // No replay: a late subscriber must not be handed bytes that were already
    // delivered, since the protocol decoder is stateful. The buffer keeps a slow
    // consumer from stalling the reader outright.
    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = INCOMING_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _maxWriteSize = MutableStateFlow(DEFAULT_MAX_WRITE)
    override val maxWriteSize: StateFlow<Int> = _maxWriteSize.asStateFlow()

    private val writeMutex = Mutex()
    private val openMutex = Mutex()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var socket: SppSocket? = null

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var activeWriteJob: Deferred<Unit>? = null

    /** Guarantees the teardown path runs once per connection. */
    private val terminated = AtomicBoolean(false)

    override suspend fun open() {
        openMutex.withLock {
            if (_state.value is TransportState.Open || _state.value is TransportState.Opening) return

            terminated.set(false)
            _state.value = TransportState.Opening

            val created = try {
                socketFactory.create()
            } catch (error: SecurityException) {
                fail(DisconnectCause.PERMISSION_DENIED, "socket create denied: ${error.message}")
                return
            } catch (error: IOException) {
                fail(DisconnectCause.TRANSPORT_ERROR, "socket create failed: ${error.message}")
                return
            }
            socket = created

            val ownScope = CoroutineScope(SupervisorJob() + ioDispatcher)

            // The connect runs in its own job and is awaited, rather than being
            // wrapped in withContext. A blocking call ignores cancellation, and
            // withContext will not return until its body finishes, so a timeout
            // around it could never fire while connect was stuck — the very case
            // the timeout exists for. Awaiting a Deferred is cancellable, and
            // closing the socket is what actually unblocks the parked thread.
            val connectJob = ownScope.async { created.connect() }
            try {
                withTimeout(connectTimeoutMillis) { connectJob.await() }
            } catch (_: TimeoutCancellationException) {
                created.close()
                connectJob.cancel()
                withContext(NonCancellable) { connectJob.join() }
                ownScope.cancel()
                socket = null
                fail(DisconnectCause.TRANSPORT_ERROR, "connect timed out after ${connectTimeoutMillis}ms")
                return
            } catch (error: IOException) {
                created.close()
                ownScope.cancel()
                socket = null
                fail(DisconnectCause.TRANSPORT_ERROR, "connect failed: ${error.message}")
                return
            } catch (error: SecurityException) {
                created.close()
                ownScope.cancel()
                socket = null
                fail(DisconnectCause.PERMISSION_DENIED, "connect denied: ${error.message}")
                return
            } catch (cancelled: CancellationException) {
                // The caller owns the connection attempt. Cancelling it must
                // also stop the independent blocking-IO job and close the
                // socket that wakes that job.
                created.close()
                connectJob.cancel()
                withContext(NonCancellable) { connectJob.join() }
                ownScope.cancel()
                socket = null
                scope = null
                terminated.set(true)
                _state.value = TransportState.Closed
                throw cancelled
            }

            scope = ownScope
            _state.value = TransportState.Open
            readerJob = ownScope.launch { readLoop(created) }
        }
    }

    private suspend fun readLoop(activeSocket: SppSocket) {
        val buffer = ByteArray(readBufferSize)
        try {
            while (true) {
                val read = activeSocket.read(buffer)
                if (read < 0) {
                    // Peer closed cleanly. Not an error, but the link is gone.
                    terminate(DisconnectCause.LINK_LOST, "end of stream", requested = false)
                    return
                }
                if (read == 0) continue
                _incoming.emit(buffer.copyOfRange(0, read))
            }
        } catch (error: IOException) {
            // A read woken by our own close is an expected unblock, not a fault.
            if (!terminated.get()) {
                terminate(DisconnectCause.TRANSPORT_ERROR, "read failed: ${error.message}", requested = false)
            }
        } catch (error: SecurityException) {
            if (!terminated.get()) {
                terminate(DisconnectCause.PERMISSION_DENIED, "read denied: ${error.message}", requested = false)
            }
        }
    }

    override suspend fun write(bytes: ByteArray): TransportWriteResult {
        if (bytes.isEmpty()) return TransportWriteResult.Written

        // Serialised so two commands cannot interleave halfway through a frame.
        return writeMutex.withLock {
            val active = socket
            if (_state.value !is TransportState.Open || active == null) {
                return@withLock TransportWriteResult.Rejected(
                    TransportFailure(DisconnectCause.TRANSPORT_ERROR, "transport is not open"),
                )
            }
            // Same reasoning as connect: a blocking write cannot be interrupted,
            // so it runs as an awaitable job and the timeout path closes the
            // socket to unblock the thread it is parked on.
            val writeScope = scope ?: return@withLock TransportWriteResult.Rejected(
                TransportFailure(DisconnectCause.TRANSPORT_ERROR, "transport is not open"),
            )
            val writeJob = writeScope.async { active.write(bytes) }
            activeWriteJob = writeJob
            try {
                withTimeout(writeTimeoutMillis) { writeJob.await() }
                TransportWriteResult.Written
            } catch (_: TimeoutCancellationException) {
                val detail = "write timed out after ${writeTimeoutMillis}ms"
                terminate(DisconnectCause.TRANSPORT_ERROR, detail, requested = false)
                writeJob.cancel()
                TransportWriteResult.Rejected(TransportFailure(DisconnectCause.TRANSPORT_ERROR, detail))
            } catch (error: IOException) {
                val detail = "write failed: ${error.message}"
                terminate(DisconnectCause.TRANSPORT_ERROR, detail, requested = false)
                TransportWriteResult.Rejected(TransportFailure(DisconnectCause.TRANSPORT_ERROR, detail))
            } catch (error: SecurityException) {
                val detail = "write denied: ${error.message}"
                terminate(DisconnectCause.PERMISSION_DENIED, detail, requested = false)
                TransportWriteResult.Rejected(TransportFailure(DisconnectCause.PERMISSION_DENIED, detail))
            } catch (cancelled: CancellationException) {
                active.close()
                writeJob.cancel()
                throw cancelled
            } finally {
                // Closing the socket above unblocks a blocking platform write.
                // Join in NonCancellable so no child IO task survives the call.
                withContext(NonCancellable) {
                    writeJob.cancelAndJoin()
                }
                if (activeWriteJob === writeJob) activeWriteJob = null
            }
        }
    }

    override suspend fun close(cause: DisconnectCause) {
        val oldReader = readerJob
        val oldWriter = activeWriteJob
        terminate(cause, detail = null, requested = true)
        withContext(NonCancellable) {
            oldWriter?.cancelAndJoin()
            oldReader?.cancelAndJoin()
        }
        if (activeWriteJob === oldWriter) activeWriteJob = null
        if (readerJob === oldReader) readerJob = null
    }

    /**
     * Tears the connection down exactly once.
     *
     * The socket is closed before the scope is cancelled because the reader is
     * parked in a blocking read that only the close can wake; cancelling first
     * would leave the thread stuck until the peer happened to send something.
     *
     * The scope is cancelled without joining: this runs from inside the reader
     * on the failure path, and joining a coroutine from within itself deadlocks.
     */
    private fun terminate(cause: DisconnectCause, detail: String?, requested: Boolean) {
        if (!terminated.compareAndSet(false, true)) {
            if (requested) _state.value = TransportState.Closed
            return
        }

        socket?.close()
        socket = null

        scope?.cancel()
        scope = null

        _state.value = if (requested) {
            TransportState.Closed
        } else {
            TransportState.Failed(TransportFailure(cause, detail))
        }
    }

    private fun fail(cause: DisconnectCause, detail: String) {
        terminated.set(true)
        _state.value = TransportState.Failed(TransportFailure(cause, detail))
    }

    /** Visible for tests: whether the reader coroutine is still alive. */
    internal fun isReaderActive(): Boolean = readerJob?.isActive == true

    /** Visible for tests: whether a blocking write child is still alive. */
    internal fun isWriteActive(): Boolean = activeWriteJob?.isActive == true

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_WRITE_TIMEOUT_MS = 3_000L
        const val DEFAULT_READ_BUFFER = 1024
        const val DEFAULT_MAX_WRITE = 512
        private const val INCOMING_BUFFER = 64
    }
}
