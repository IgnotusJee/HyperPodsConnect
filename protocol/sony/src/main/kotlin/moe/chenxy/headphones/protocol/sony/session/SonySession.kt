package moe.chenxy.headphones.protocol.sony.session

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.driver.HeadphoneSession
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.feature.HeadphoneStateReducer
import moe.chenxy.headphones.core.feature.StateUpdate
import moe.chenxy.headphones.core.feature.ValueSource
import moe.chenxy.headphones.core.operation.FailureReason
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationPhase
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.operation.RequestId
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.FailureCategory
import moe.chenxy.headphones.core.session.SessionEvent
import moe.chenxy.headphones.core.session.SessionMachine
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult
import moe.chenxy.headphones.protocol.sony.feature.SonyCapabilityInfo
import moe.chenxy.headphones.protocol.sony.feature.SonyHandshake
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolGeneration
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolInfo
import moe.chenxy.headphones.protocol.sony.feature.SonySupportInfo
import moe.chenxy.headphones.protocol.sony.feature.battery.SonyBatteryFeature
import moe.chenxy.headphones.protocol.sony.feature.battery.SonyBatteryType
import moe.chenxy.headphones.protocol.sony.frame.TandemCodec
import moe.chenxy.headphones.protocol.sony.frame.TandemDecodeResult
import moe.chenxy.headphones.protocol.sony.frame.TandemFrame
import moe.chenxy.headphones.protocol.sony.frame.TandemStreamDecoder
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyCommandTable
import moe.chenxy.headphones.protocol.sony.message.SonyDataType
import moe.chenxy.headphones.protocol.sony.message.SonyDeviceInfoType
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage
import moe.chenxy.headphones.protocol.sony.profile.SonyProfile

sealed interface SonySessionEvent {
    data class TxFrame(val bytes: ByteArray) : SonySessionEvent
    data class RxChunk(val bytes: ByteArray) : SonySessionEvent
    data class RxFrame(val frame: TandemFrame) : SonySessionEvent
    data class DecodeRejected(val reason: String) : SonySessionEvent
}

/**
 * Sony Phase 8 Classic-SPP session.
 *
 * Only documented GET requests and mandatory Tandem ACK frames can leave this
 * class. Every feature write is rejected before packet construction.
 */
class SonySession(
    private val context: DriverSessionContext,
    private val responseTimeoutMillis: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    private val transportSettleDelayMillis: Long = DEFAULT_TRANSPORT_SETTLE_DELAY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeadphoneSession {
    private val serviceUuid = resolveServiceUuid(context.candidate)
    private val machine = SessionMachine(
        SessionState.Idle(context.candidate.identity.id, generationId = 0),
    )
    private val _connection = MutableStateFlow(machine.state)
    override val connection: StateFlow<SessionState> = _connection.asStateFlow()
    private val _profile = MutableStateFlow<DeviceProfile?>(
        SonyProfile.initial(context.candidate, serviceUuid ?: "unresolved"),
    )
    override val profile: StateFlow<DeviceProfile?> = _profile.asStateFlow()
    private val _state = MutableStateFlow(HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()
    private val _operations = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 32)
    override val operations: Flow<OperationEvent> = _operations.asSharedFlow()
    private val _events = MutableSharedFlow<SonySessionEvent>(extraBufferCapacity = 64)
    val events: Flow<SonySessionEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val exchangeMutex = Mutex()
    private val decoder = TandemStreamDecoder()
    private val requestCounter = AtomicLong()
    private var transport: ByteTransport? = null
    private var incomingJob: Job? = null
    private var transportStateJob: Job? = null
    private var outgoingSequence = 0

    @Volatile
    private var ackWaiter: AckWaiter? = null

    @Volatile
    private var responseWaiter: ResponseWaiter? = null

    private var protocolInfo: SonyProtocolInfo? = null
    private var capabilityInfo: SonyCapabilityInfo? = null
    private var supportInfo: SonySupportInfo? = null

    override suspend fun connect(): Unit = lifecycleMutex.withLock {
        if (_connection.value.isActive) return
        transition(SessionEvent.ConnectRequested)
        val uuid = serviceUuid
        if (uuid == null) {
            transition(
                SessionEvent.DetectionFailed(
                    "Sony model-name hint has no advertised Sony SPP UUID; refusing guessed RFCOMM",
                ),
            )
            return
        }
        transition(SessionEvent.DetectionSucceeded(TransportKind.CLASSIC_SPP))
        val created = try {
            context.transportFactory.create(
                context.candidate.identity,
                TransportSpec.Spp(uuid, secure = true),
            )
        } catch (error: Throwable) {
            fail(FailureCategory.TRANSPORT, DisconnectCause.TRANSPORT_ERROR, error.message)
            return
        }
        transport = created
        startCollectors(created)
        try {
            created.open()
        } catch (error: Throwable) {
            fail(FailureCategory.TRANSPORT, DisconnectCause.TRANSPORT_ERROR, error.message)
            return
        }
        val open = created.state.value
        if (open !is TransportState.Open) {
            val failure = (open as? TransportState.Failed)?.failure
            fail(
                FailureCategory.TRANSPORT,
                failure?.cause ?: DisconnectCause.TRANSPORT_ERROR,
                failure?.detail ?: "transport did not open",
            )
            return
        }
        transition(SessionEvent.TransportOpened)
        if (transportSettleDelayMillis > 0) delay(transportSettleDelayMillis)

        var protocol: SonyProtocolInfo? = null
        repeat(INITIAL_PROTOCOL_ATTEMPTS) {
            if (protocol == null) {
                protocol = exchange(
                    SonyHandshake.getProtocolInfo(),
                    expectedCommand = SonyCommand.CONNECT_RET_PROTOCOL_INFO,
                )?.let(SonyHandshake::parseProtocolInfo)
            }
        }
        if (protocol == null) {
            failAndClose(created, FailureCategory.HANDSHAKE, "protocol-info handshake failed")
            return
        }
        protocolInfo = protocol
        transition(SessionEvent.HandshakeCompleted)

        val capability = exchange(
            SonyHandshake.getCapabilityInfo(),
            SonyCommand.CONNECT_RET_CAPABILITY_INFO,
        )?.let(SonyHandshake::parseCapabilityInfo)
        val model = exchange(
            SonyHandshake.getDeviceInfo(SonyDeviceInfoType.MODEL_NAME),
            SonyCommand.CONNECT_RET_DEVICE_INFO,
        )?.let { SonyHandshake.parseDeviceInfo(it, SonyDeviceInfoType.MODEL_NAME) }
        val firmware = exchange(
            SonyHandshake.getDeviceInfo(SonyDeviceInfoType.FIRMWARE_VERSION),
            SonyCommand.CONNECT_RET_DEVICE_INFO,
        )?.let { SonyHandshake.parseDeviceInfo(it, SonyDeviceInfoType.FIRMWARE_VERSION) }
        val support = exchange(
            SonyHandshake.getSupportFunction(),
            SonyCommand.CONNECT_RET_SUPPORT_FUNCTION,
        )?.let(SonyHandshake::parseSupportFunction)
        if (capability == null || model == null || firmware == null || support == null) {
            failAndClose(
                created,
                FailureCategory.CAPABILITY,
                "Sony read-only evidence incomplete (capability/model/firmware/support)",
            )
            return
        }
        capabilityInfo = capability
        supportInfo = support
        applyReport(DeviceReport.Firmware(firmware), ValueSource.QUERY_RESPONSE)
        _state.value = _state.value.copy(
            vendorStates = mapOf(
                "sony.protocol.generation" to protocol.generation.name,
                "sony.protocol.fingerprint" to protocol.rawFingerprint,
                "sony.capability.fingerprint" to capability.fingerprint,
                "sony.support.fingerprint" to support.fingerprint,
                "sony.support.count" to support.functions.size.toString(),
            ),
        )
        transition(SessionEvent.CapabilitiesLoaded)

        var batteryObserved = false
        SonyProfile.batteryTypes(model).forEach { type ->
            val response = exchange(
                SonyBatteryFeature.query(protocol.generation, type),
                expectedCommand = if (protocol.generation == SonyProtocolGeneration.V2) {
                    SonyCommand.POWER_RET_STATUS
                } else {
                    SonyCommand.COMMON_RET_BATTERY_LEVEL
                },
            )
            if (response?.let { SonyBatteryFeature.parse(protocol.generation, it) } != null) {
                batteryObserved = true
            }
        }
        if (!batteryObserved) {
            failAndClose(created, FailureCategory.CAPABILITY, "initial Sony battery read failed")
            return
        }
        _profile.value = SonyProfile.readOnly(
            requireNotNull(_profile.value),
            protocol,
            model,
            firmware,
        )
        transition(SessionEvent.InitialStateSynchronized)
    }

    override suspend fun refresh(featureIds: Set<FeatureId>) {
        if (!_connection.value.isProtocolReady) return
        val generation = protocolInfo?.generation ?: return
        val requested = if (featureIds.isEmpty()) {
            setOf(FeatureId.BATTERY, FeatureId.FIRMWARE_VERSION)
        } else {
            featureIds
        }
        if (FeatureId.FIRMWARE_VERSION in requested) {
            exchange(
                SonyHandshake.getDeviceInfo(SonyDeviceInfoType.FIRMWARE_VERSION),
                SonyCommand.CONNECT_RET_DEVICE_INFO,
            )
        }
        if (FeatureId.BATTERY in requested) {
            SonyProfile.batteryTypes(_profile.value?.model).forEach { type ->
                exchange(
                    SonyBatteryFeature.query(generation, type),
                    if (generation == SonyProtocolGeneration.V2) {
                        SonyCommand.POWER_RET_STATUS
                    } else {
                        SonyCommand.COMMON_RET_BATTERY_LEVEL
                    },
                )
            }
        }
    }

    override suspend fun execute(command: FeatureCommand): OperationResult {
        val id = RequestId("sony-${requestCounter.incrementAndGet()}")
        if (command is FeatureCommand.RefreshAll) {
            refresh()
            return OperationResult(id, OperationPhase.READ_BACK_CONFIRMED)
        }
        if (command is FeatureCommand.Refresh) {
            refresh(setOf(command.featureId))
            return OperationResult(id, OperationPhase.READ_BACK_CONFIRMED)
        }
        val reason = if (!_connection.value.isProtocolReady) {
            FailureReason.NOT_CONNECTED
        } else {
            FailureReason.NOT_WRITABLE
        }
        val detail = if (reason == FailureReason.NOT_CONNECTED) {
            "Sony session is not ready"
        } else {
            "Sony Phase 8 is read-only; no control bytes were emitted"
        }
        val event = OperationEvent(
            id,
            command,
            OperationPhase.FAILED,
            clock(),
            _connection.value.generationId,
            reason,
            detail,
        )
        _operations.tryEmit(event)
        return OperationResult(id, OperationPhase.FAILED, reason, detail)
    }

    override suspend fun disconnect(cause: DisconnectCause): Unit = lifecycleMutex.withLock {
        if (_connection.value is SessionState.Idle) return
        transition(SessionEvent.DisconnectRequested)
        ackWaiter?.deferred?.cancel()
        responseWaiter?.deferred?.cancel()
        ackWaiter = null
        responseWaiter = null
        transport?.close(cause)
        incomingJob?.cancel()
        transportStateJob?.cancel()
        decoder.reset()
        _state.value = HeadphoneStateReducer.reduce(
            _state.value,
            StateUpdate.Disconnected(clock()),
        )
        transition(SessionEvent.Disconnected(cause))
        scope.cancel()
    }

    private fun startCollectors(active: ByteTransport) {
        incomingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.incoming.collect { chunk ->
                _events.emit(SonySessionEvent.RxChunk(chunk.copyOf()))
                decoder.feed(chunk).forEach { result ->
                    when (result) {
                        is TandemDecodeResult.Frame -> {
                            _events.emit(SonySessionEvent.RxFrame(result.value))
                            handleFrame(active, result.value)
                        }
                        is TandemDecodeResult.Rejected ->
                            _events.emit(SonySessionEvent.DecodeRejected(result.reason))
                    }
                }
            }
        }
        transportStateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.state.collect { state ->
                if (state is TransportState.Failed && transport === active) {
                    fail(FailureCategory.TRANSPORT, state.failure.cause, state.failure.detail)
                }
            }
        }
    }

    private suspend fun handleFrame(active: ByteTransport, frame: TandemFrame) {
        val type = SonyDataType.fromCode(frame.dataType) ?: return
        if (type == SonyDataType.ACK) {
            ackWaiter?.let { waiter ->
                if (frame.sequence == waiter.expectedSequence) {
                    outgoingSequence = frame.sequence and 1
                    waiter.deferred.complete(Unit)
                }
            }
            return
        }
        if (type.requiresAcknowledgement) {
            val ack = TandemFrame(
                SonyDataType.ACK.code,
                1 - (frame.sequence and 1),
                byteArrayOf(),
            )
            val encodedAck = TandemCodec.encode(ack)
            _events.emit(SonySessionEvent.TxFrame(encodedAck.copyOf()))
            active.write(encodedAck)
        }
        val message = SonyMdrMessage.from(frame) ?: return
        val generation = protocolInfo?.generation
        if (generation != null) {
            SonyBatteryFeature.parse(generation, message)?.let {
                applyBattery(it, if (message.command == SonyCommand.POWER_NTFY_STATUS ||
                    message.command == SonyCommand.COMMON_NTFY_BATTERY_LEVEL
                ) ValueSource.NOTIFICATION else ValueSource.QUERY_RESPONSE)
            }
        }
        if (message.command == SonyCommand.CONNECT_RET_DEVICE_INFO) {
            SonyHandshake.parseDeviceInfo(message, SonyDeviceInfoType.FIRMWARE_VERSION)?.let {
                applyReport(DeviceReport.Firmware(it), ValueSource.QUERY_RESPONSE)
                _profile.value = _profile.value?.copy(firmware = it)
            }
        }
        responseWaiter?.let { waiter ->
            if (message.table == waiter.table && message.command == waiter.expectedCommand) {
                waiter.deferred.complete(message)
            }
        }
    }

    private suspend fun exchange(
        payload: ByteArray,
        expectedCommand: Int,
        table: SonyCommandTable = SonyCommandTable.TABLE1,
    ): SonyMdrMessage? = exchangeMutex.withLock {
        val active = transport ?: return@withLock null
        val sequence = outgoingSequence and 1
        val ack = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SonyMdrMessage>()
        val ackHolder = AckWaiter(1 - sequence, ack)
        val responseHolder = ResponseWaiter(table, expectedCommand, response)
        check(ackWaiter == null && responseWaiter == null) {
            "only one Sony request may be in flight"
        }
        ackWaiter = ackHolder
        responseWaiter = responseHolder
        try {
            val dataType = if (table == SonyCommandTable.TABLE1) {
                SonyDataType.DATA_MDR
            } else {
                SonyDataType.DATA_MDR_NO2
            }
            val frame = TandemCodec.encode(TandemFrame(dataType.code, sequence, payload))
            _events.emit(SonySessionEvent.TxFrame(frame.copyOf()))
            if (active.write(frame) !is TransportWriteResult.Written) return@withLock null
            if (withTimeoutOrNull(responseTimeoutMillis) { ack.await() } == null) {
                return@withLock null
            }
            withTimeoutOrNull(responseTimeoutMillis) { response.await() }
        } finally {
            if (ackWaiter === ackHolder) ackWaiter = null
            if (responseWaiter === responseHolder) responseWaiter = null
        }
    }

    private fun applyBattery(report: DeviceReport.Batteries, source: ValueSource) {
        val merged = when {
            BatteryComponent.CASE in report.values ->
                _state.value.batteries.filterKeys { it != BatteryComponent.CASE } + report.values
            BatteryComponent.LEFT in report.values || BatteryComponent.RIGHT in report.values ->
                _state.value.batteries.filterKeys {
                    it != BatteryComponent.LEFT && it != BatteryComponent.RIGHT
                } + report.values
            else -> report.values
        }
        applyReport(DeviceReport.Batteries(merged), source)
    }

    private fun applyReport(report: DeviceReport, source: ValueSource) {
        _state.value = HeadphoneStateReducer.reduce(
            _state.value,
            StateUpdate.DeviceReported(report, source, clock()),
        )
    }

    private suspend fun failAndClose(
        active: ByteTransport,
        category: FailureCategory,
        detail: String,
    ) {
        fail(category, DisconnectCause.PROTOCOL_ERROR, detail)
        active.close(DisconnectCause.PROTOCOL_ERROR)
    }

    private fun transition(event: SessionEvent) {
        _connection.value = machine.onEvent(event)
    }

    private fun fail(category: FailureCategory, cause: DisconnectCause, detail: String?) {
        transition(SessionEvent.Failed(category, cause, detail))
    }

    private data class AckWaiter(
        val expectedSequence: Int,
        val deferred: CompletableDeferred<Unit>,
    )

    private data class ResponseWaiter(
        val table: SonyCommandTable,
        val expectedCommand: Int,
        val deferred: CompletableDeferred<SonyMdrMessage>,
    )

    companion object {
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 1_500L
        const val DEFAULT_TRANSPORT_SETTLE_DELAY_MS = 300L
        private const val INITIAL_PROTOCOL_ATTEMPTS = 3

        fun resolveServiceUuid(candidate: DeviceCandidate): String? {
            val advertised = candidate.advertisedUuids
            return when {
                advertised.any {
                    it.equals(SonyProfile.SONY_SPP_V2_UUID, ignoreCase = true)
                } -> SonyProfile.SONY_SPP_V2_UUID
                advertised.any {
                    it.equals(SonyProfile.SONY_SPP_V1_UUID, ignoreCase = true)
                } -> SonyProfile.SONY_SPP_V1_UUID
                else -> null
            }
        }
    }
}
