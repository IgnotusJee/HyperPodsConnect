package moe.chenxy.headphones.transport.android

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.GattChunkPolicy
import moe.chenxy.headphones.core.transport.GattMtuFailurePolicy
import moe.chenxy.headphones.core.transport.GattNotificationMode
import moe.chenxy.headphones.core.transport.GattPreparationStep
import moe.chenxy.headphones.core.transport.GattWriteMode
import moe.chenxy.headphones.core.transport.TransportFailure
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult

sealed interface GattReadResult {
    data class Read(val value: ByteArray) : GattReadResult
    data class Rejected(val failure: TransportFailure) : GattReadResult
}

/**
 * Vendor-neutral BLE GATT byte transport.
 *
 * Every callback-backed action runs through [GattOperationQueue]. Notifications
 * use a separate ordered channel because they are unsolicited and must never be
 * mistaken for operation completion.
 */
class GattTransport(
    private val spec: TransportSpec.Gatt,
    private val clientFactory: GattClientFactory,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MS,
) : ByteTransport {
    override val kind: TransportKind = TransportKind.BLE_GATT

    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = INCOMING_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _maxWriteSize = MutableStateFlow(initialWriteSize())
    override val maxWriteSize: StateFlow<Int> = _maxWriteSize.asStateFlow()

    private val openMutex = Mutex()
    private val ioMutex = Mutex()
    private val generations = AtomicLong(0)
    private val lifecycleLock = Any()

    @Volatile
    private var activeGeneration: Long = NO_GENERATION

    @Volatile
    private var client: GattClient? = null

    @Volatile
    private var queue: GattOperationQueue? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var notificationChannel: Channel<ByteArray>? = null

    override suspend fun open() {
        openMutex.withLock {
            if (_state.value is TransportState.Open || _state.value is TransportState.Opening) return

            tearDown(DisconnectCause.REQUESTED, requested = true)
            _state.value = TransportState.Opening
            _maxWriteSize.value = initialWriteSize()

            val generation = generations.incrementAndGet()
            val localQueue = GattOperationQueue(generation, operationTimeoutMillis)
            val localNotifications = Channel<ByteArray>(Channel.UNLIMITED)
            val localScope = CoroutineScope(SupervisorJob() + callbackDispatcher)
            val localClient = try {
                clientFactory.create()
            } catch (error: SecurityException) {
                failOpen(
                    generation,
                    TransportFailure(DisconnectCause.PERMISSION_DENIED, error.message),
                )
                return
            } catch (error: Throwable) {
                failOpen(
                    generation,
                    TransportFailure(DisconnectCause.TRANSPORT_ERROR, error.message),
                )
                return
            }

            synchronized(lifecycleLock) {
                activeGeneration = generation
                queue = localQueue
                notificationChannel = localNotifications
                scope = localScope
                client = localClient
                localScope.launch {
                    for (value in localNotifications) _incoming.emit(value)
                }
            }
            localClient.setCallback { event ->
                handleCallback(generation, localQueue, localNotifications, event)
            }

            try {
                val connected = localQueue.execute(
                    operation = "connect",
                    start = { localClient.connect(generation) },
                ) { event ->
                    (event as? GattCallbackEvent.ConnectionStateChanged)
                        ?.takeIf { it.state == GattLinkState.CONNECTED }
                        ?.let { gattStatusResult("connect", it.status, Unit) }
                }
                if (!requireOpenSuccess(generation, connected)) return

                val requestedMtu = spec.requestMtu
                if (requestedMtu != null) {
                    val mtuResult = localQueue.execute(
                        operation = "requestMtu",
                        start = { localClient.requestMtu(requestedMtu) },
                    ) { event ->
                        (event as? GattCallbackEvent.MtuChanged)
                            ?.let { gattStatusResult("requestMtu", it.status, it.mtu) }
                    }
                    when (mtuResult) {
                        is GattOperationResult.Success ->
                            _maxWriteSize.value = payloadSize(mtuResult.value)
                        is GattOperationResult.Failure -> {
                            if (spec.mtuFailurePolicy == GattMtuFailurePolicy.FAIL_CONNECTION) {
                                failOperation(generation, mtuResult.failure)
                                return
                            }
                            _maxWriteSize.value = initialWriteSize()
                        }
                    }
                }

                val discovered = localQueue.execute(
                    operation = "discoverServices",
                    start = localClient::discoverServices,
                ) { event ->
                    (event as? GattCallbackEvent.ServicesDiscovered)
                        ?.let { gattStatusResult("discoverServices", it.status, Unit) }
                }
                if (!requireOpenSuccess(generation, discovered)) return

                val missing = missingAttribute(localClient)
                if (missing != null) {
                    failOpen(
                        generation,
                        TransportFailure(
                            DisconnectCause.TRANSPORT_ERROR,
                            "required GATT attribute missing: $missing",
                        ),
                    )
                    return
                }

                for (step in spec.preparationSteps) {
                    val prepared = when (step) {
                        is GattPreparationStep.Subscribe -> subscribe(
                            localClient,
                            localQueue,
                            step.characteristicUuid,
                            step.cccdUuid,
                            step.mode,
                        )
                        is GattPreparationStep.ReadWritableLength -> readWritableLength(
                            localClient,
                            localQueue,
                            step.characteristicUuid,
                        )
                    }
                    if (!requireOpenSuccess(generation, prepared)) return
                }

                val subscribed = subscribe(
                    localClient,
                    localQueue,
                    spec.rxCharacteristicUuid,
                    spec.cccdUuid,
                    spec.notificationMode,
                )
                if (!requireOpenSuccess(generation, subscribed)) return

                if (activeGeneration == generation) _state.value = TransportState.Open
            } catch (cancelled: CancellationException) {
                if (activeGeneration == generation) {
                    tearDown(DisconnectCause.REQUESTED, requested = true)
                    _state.value = TransportState.Closed
                }
                throw cancelled
            } catch (error: SecurityException) {
                failOpen(
                    generation,
                    TransportFailure(DisconnectCause.PERMISSION_DENIED, error.message),
                )
            } catch (error: Throwable) {
                failOpen(
                    generation,
                    TransportFailure(DisconnectCause.TRANSPORT_ERROR, error.message),
                )
            }
        }
    }

    /**
     * Reads the configured RX characteristic and also publishes the exact bytes
     * to [incoming], matching notification delivery semantics.
     */
    suspend fun read(): GattReadResult = ioMutex.withLock {
        val localClient = client
        val localQueue = queue
        if (_state.value !is TransportState.Open || localClient == null || localQueue == null) {
            return@withLock GattReadResult.Rejected(closedFailure())
        }
        val result = try {
            localQueue.execute(
                operation = "readCharacteristic",
                start = {
                    localClient.readCharacteristic(
                        spec.serviceUuid,
                        spec.rxCharacteristicUuid,
                    )
                },
            ) { event ->
                (event as? GattCallbackEvent.CharacteristicRead)
                    ?.takeIf { it.characteristicUuid.uuidEquals(spec.rxCharacteristicUuid) }
                    ?.let {
                        gattStatusResult(
                            "readCharacteristic",
                            it.status,
                            it.value.copyOf(),
                        )
                    }
            }
        } catch (error: SecurityException) {
            return@withLock GattReadResult.Rejected(
                TransportFailure(DisconnectCause.PERMISSION_DENIED, error.message),
            )
        }
        when (result) {
            is GattOperationResult.Success -> {
                _incoming.emit(result.value)
                GattReadResult.Read(result.value)
            }
            is GattOperationResult.Failure -> {
                if (result.failure.kind.isFatal) failActiveOperation(result.failure)
                GattReadResult.Rejected(result.failure.toTransportFailure())
            }
        }
    }

    override suspend fun write(bytes: ByteArray): TransportWriteResult = ioMutex.withLock {
        if (bytes.isEmpty()) return@withLock TransportWriteResult.Written
        val localClient = client
        val localQueue = queue
        if (_state.value !is TransportState.Open || localClient == null || localQueue == null) {
            return@withLock TransportWriteResult.Rejected(closedFailure())
        }

        val max = _maxWriteSize.value
        if (bytes.size > max && spec.chunkPolicy == GattChunkPolicy.REJECT_OVERSIZED) {
            return@withLock TransportWriteResult.Rejected(
                TransportFailure(
                    DisconnectCause.TRANSPORT_ERROR,
                    "write size ${bytes.size} exceeds GATT writable length $max",
                ),
            )
        }
        val chunks = bytes.asList().chunked(max).map { it.toByteArray() }
        for (chunk in chunks) {
            val result = try {
                writeChunk(localClient, localQueue, chunk)
            } catch (error: SecurityException) {
                return@withLock TransportWriteResult.Rejected(
                    TransportFailure(DisconnectCause.PERMISSION_DENIED, error.message),
                )
            }
            if (result is GattOperationResult.Failure) {
                if (result.failure.kind.isFatal) failActiveOperation(result.failure)
                return@withLock TransportWriteResult.Rejected(result.failure.toTransportFailure())
            }
        }
        TransportWriteResult.Written
    }

    override suspend fun close(cause: DisconnectCause) {
        tearDown(cause, requested = true)
        _state.value = TransportState.Closed
    }

    private suspend fun writeChunk(
        localClient: GattClient,
        localQueue: GattOperationQueue,
        chunk: ByteArray,
    ): GattOperationResult<Unit> {
        if (spec.writeMode == GattWriteMode.WITHOUT_RESPONSE) {
            return localQueue.executeImmediate("writeCharacteristicWithoutResponse") {
                when (
                    val started = localClient.writeCharacteristic(
                        spec.serviceUuid,
                        spec.txCharacteristicUuid,
                        chunk,
                        spec.writeMode,
                    )
                ) {
                    GattStartResult.Started -> {
                        delay(spec.withoutResponseThrottleMillis)
                        GattOperationResult.Success(Unit)
                    }
                    is GattStartResult.Rejected -> GattOperationResult.Failure(
                        GattOperationFailure(
                            GattOperationFailureKind.START_REJECTED,
                            "writeCharacteristicWithoutResponse",
                            started.status,
                            started.detail,
                        ),
                    )
                }
            }
        }
        return localQueue.execute(
            operation = "writeCharacteristic",
            start = {
                localClient.writeCharacteristic(
                    spec.serviceUuid,
                    spec.txCharacteristicUuid,
                    chunk,
                    spec.writeMode,
                )
            },
        ) { event ->
            (event as? GattCallbackEvent.CharacteristicWrite)
                ?.takeIf { it.characteristicUuid.uuidEquals(spec.txCharacteristicUuid) }
                ?.let { gattStatusResult("writeCharacteristic", it.status, Unit) }
        }
    }

    private fun handleCallback(
        generation: Long,
        localQueue: GattOperationQueue,
        localNotifications: Channel<ByteArray>,
        event: GattCallbackEvent,
    ) {
        if (event.generationId != generation || activeGeneration != generation) return
        if (
            event is GattCallbackEvent.Notification &&
            isIncomingCharacteristic(event.characteristicUuid)
        ) {
            localNotifications.trySend(event.value.copyOf())
            return
        }
        if (
            spec.writeMode == GattWriteMode.WITHOUT_RESPONSE &&
            event is GattCallbackEvent.CharacteristicWrite &&
            event.characteristicUuid.uuidEquals(spec.txCharacteristicUuid)
        ) {
            // Some stacks still emit this callback for a no-response write.
            // The local throttle is authoritative; retaining these unsolicited
            // events would grow the operation queue without bound.
            return
        }
        localQueue.offer(event)
        if (
            event is GattCallbackEvent.ConnectionStateChanged &&
            event.state == GattLinkState.DISCONNECTED &&
            _state.value is TransportState.Open
        ) {
            failOpen(
                generation,
                TransportFailure(
                    event.disconnectCause,
                    "GATT disconnected",
                    event.status,
                ),
            )
        }
    }

    private fun isIncomingCharacteristic(characteristicUuid: String): Boolean =
        characteristicUuid.uuidEquals(spec.rxCharacteristicUuid) ||
            spec.preparationSteps
                .filterIsInstance<GattPreparationStep.Subscribe>()
                .any { characteristicUuid.uuidEquals(it.characteristicUuid) }

    private fun missingAttribute(localClient: GattClient): String? = when {
        !localClient.hasService(spec.serviceUuid) -> spec.serviceUuid
        !localClient.hasCharacteristic(spec.serviceUuid, spec.txCharacteristicUuid) ->
            spec.txCharacteristicUuid
        !localClient.hasCharacteristic(spec.serviceUuid, spec.rxCharacteristicUuid) ->
            spec.rxCharacteristicUuid
        spec.preparationSteps.any { step ->
            val uuid = when (step) {
                is GattPreparationStep.Subscribe -> step.characteristicUuid
                is GattPreparationStep.ReadWritableLength -> step.characteristicUuid
            }
            !localClient.hasCharacteristic(spec.serviceUuid, uuid)
        } -> "preparation characteristic"
        spec.preparationSteps.filterIsInstance<GattPreparationStep.Subscribe>().any { step ->
            !localClient.hasDescriptor(spec.serviceUuid, step.characteristicUuid, step.cccdUuid)
        } -> "preparation CCCD"
        !localClient.hasDescriptor(
            spec.serviceUuid,
            spec.rxCharacteristicUuid,
            spec.cccdUuid,
        ) -> spec.cccdUuid
        else -> null
    }

    private suspend fun subscribe(
        localClient: GattClient,
        localQueue: GattOperationQueue,
        characteristicUuid: String,
        cccdUuid: String,
        mode: GattNotificationMode,
    ): GattOperationResult<Unit> {
        when (
            val enabled = localClient.setCharacteristicNotification(
                spec.serviceUuid,
                characteristicUuid,
                true,
            )
        ) {
            GattStartResult.Started -> Unit
            is GattStartResult.Rejected -> return GattOperationResult.Failure(
                GattOperationFailure(
                    GattOperationFailureKind.START_REJECTED,
                    "setCharacteristicNotification",
                    enabled.status,
                    enabled.detail,
                ),
            )
        }
        return localQueue.execute(
            operation = "writeCccd",
            start = {
                localClient.writeDescriptor(
                    spec.serviceUuid,
                    characteristicUuid,
                    cccdUuid,
                    mode,
                )
            },
        ) { event ->
            (event as? GattCallbackEvent.DescriptorWrite)
                ?.takeIf {
                    it.characteristicUuid.uuidEquals(characteristicUuid) &&
                        it.descriptorUuid.uuidEquals(cccdUuid)
                }
                ?.let { gattStatusResult("writeCccd", it.status, Unit) }
        }
    }

    private suspend fun readWritableLength(
        localClient: GattClient,
        localQueue: GattOperationQueue,
        characteristicUuid: String,
    ): GattOperationResult<Unit> {
        val result = localQueue.execute(
            operation = "readWritableLength",
            start = {
                localClient.readCharacteristic(spec.serviceUuid, characteristicUuid)
            },
        ) { event ->
            (event as? GattCallbackEvent.CharacteristicRead)
                ?.takeIf { it.characteristicUuid.uuidEquals(characteristicUuid) }
                ?.let { gattStatusResult("readWritableLength", it.status, it.value.copyOf()) }
        }
        return when (result) {
            is GattOperationResult.Failure -> result
            is GattOperationResult.Success -> {
                val writableLength = result.value.decodeUnsignedBigEndian()
                if (writableLength == null) {
                    GattOperationResult.Failure(
                        GattOperationFailure(
                            GattOperationFailureKind.STATUS_ERROR,
                            "readWritableLength",
                            detail = "invalid writable length",
                        ),
                    )
                } else {
                    _maxWriteSize.value = minOf(_maxWriteSize.value, writableLength)
                    GattOperationResult.Success(Unit)
                }
            }
        }
    }

    private fun requireOpenSuccess(
        generation: Long,
        result: GattOperationResult<*>,
    ): Boolean {
        if (activeGeneration != generation) return false
        if (result is GattOperationResult.Success) return true
        failOperation(generation, (result as GattOperationResult.Failure).failure)
        return false
    }

    private fun failOperation(generation: Long, failure: GattOperationFailure) {
        failOpen(generation, failure.toTransportFailure())
    }

    private fun failActiveOperation(failure: GattOperationFailure) {
        val generation = activeGeneration
        if (
            generation != NO_GENERATION &&
            _state.value !is TransportState.Closed &&
            _state.value !is TransportState.Failed
        ) {
            failOperation(generation, failure)
        }
    }

    private fun failOpen(generation: Long, failure: TransportFailure) {
        if (activeGeneration != generation && activeGeneration != NO_GENERATION) return
        // A failed operation can leave Android's GATT client logically connected
        // even though this transport is no longer usable. Explicitly disconnect
        // before close so a following generation cannot overlap that client.
        tearDown(failure.cause, requested = true)
        _state.value = TransportState.Failed(failure)
    }

    private fun tearDown(cause: DisconnectCause, requested: Boolean) {
        val oldClient: GattClient?
        val oldQueue: GattOperationQueue?
        val oldScope: CoroutineScope?
        val oldNotifications: Channel<ByteArray>?
        synchronized(lifecycleLock) {
            activeGeneration = NO_GENERATION
            oldClient = client
            oldQueue = queue
            oldScope = scope
            oldNotifications = notificationChannel
            client = null
            queue = null
            scope = null
            notificationChannel = null
        }
        oldQueue?.close()
        oldNotifications?.close()
        if (requested && cause != DisconnectCause.LINK_LOST) oldClient?.disconnect()
        oldClient?.close()
        oldScope?.cancel()
    }

    private fun initialWriteSize(): Int =
        minOf(DEFAULT_ATT_PAYLOAD, spec.writableLength ?: DEFAULT_ATT_PAYLOAD)

    private fun payloadSize(mtu: Int): Int =
        minOf((mtu - ATT_HEADER_SIZE).coerceAtLeast(1), spec.writableLength ?: Int.MAX_VALUE)

    private fun closedFailure() = TransportFailure(
        DisconnectCause.TRANSPORT_ERROR,
        "GATT transport is not open",
    )

    internal fun activeGenerationForTest(): Long = activeGeneration

    internal fun isOperationQueueClosedForTest(): Boolean = queue?.isClosed() ?: true

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 5_000L
        const val DEFAULT_ATT_MTU = 23
        const val ATT_HEADER_SIZE = 3
        const val DEFAULT_ATT_PAYLOAD = DEFAULT_ATT_MTU - ATT_HEADER_SIZE
        private const val INCOMING_BUFFER = 64
        private const val NO_GENERATION = -1L
    }
}

private val GattOperationFailureKind.isFatal: Boolean
    get() = this == GattOperationFailureKind.TIMED_OUT ||
        this == GattOperationFailureKind.DISCONNECTED ||
        this == GattOperationFailureKind.CLOSED

private fun GattOperationFailure.toTransportFailure(): TransportFailure = TransportFailure(
    cause = when (kind) {
        GattOperationFailureKind.DISCONNECTED ->
            disconnectCause ?: DisconnectCause.LINK_LOST
        else -> DisconnectCause.TRANSPORT_ERROR
    },
    detail = buildString {
        append(operation)
        append(": ")
        append(detail ?: kind.name.lowercase())
    },
    vendorStatus = status,
)

private fun String.uuidEquals(other: String): Boolean = equals(other, ignoreCase = true)

private fun ByteArray.decodeUnsignedBigEndian(): Int? {
    if (isEmpty() || size > Int.SIZE_BYTES) return null
    var value = 0L
    for (byte in this) value = (value shl 8) or (byte.toLong() and 0xFF)
    return value.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}
