package moe.chenxy.headphones.protocol.oppo.session

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
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
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.driver.HeadphoneSession
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.feature.HeadphoneStateReducer
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode
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
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityOverrides
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityProfile
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityRegistry
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatchStatusParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoCapabilityParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoComponent
import moe.chenxy.headphones.protocol.oppo.feature.OppoFirmwareParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoNotificationSupportParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoWearParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoWearState
import moe.chenxy.headphones.protocol.oppo.feature.battery.OppoBatteryFeature
import moe.chenxy.headphones.protocol.oppo.feature.dualdevice.OppoDualDeviceFeature
import moe.chenxy.headphones.protocol.oppo.feature.equalizer.OppoCustomEqualizerFeature
import moe.chenxy.headphones.protocol.oppo.feature.equalizer.OppoCustomEqualizerSlot
import moe.chenxy.headphones.protocol.oppo.feature.equalizer.OppoEqualizerFeature
import moe.chenxy.headphones.protocol.oppo.feature.latency.OppoLatencyFeature
import moe.chenxy.headphones.protocol.oppo.feature.noisecontrol.OppoNoiseControlFeature
import moe.chenxy.headphones.protocol.oppo.feature.spatial.OppoSpatialFeature
import moe.chenxy.headphones.protocol.oppo.frame.OppoFrameStreamDecoder
import moe.chenxy.headphones.protocol.oppo.mapping.OppoDomainMapper
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec

sealed interface OppoSessionEvent {
    data class TxFrame(val bytes: ByteArray) : OppoSessionEvent
    data class RawChunk(val bytes: ByteArray) : OppoSessionEvent
    data class Message(val message: OppoMessage) : OppoSessionEvent
    data class WearReport(val values: Map<OppoComponent, OppoWearState>) : OppoSessionEvent
    data class UnknownMessage(val message: OppoMessage) : OppoSessionEvent
}

/**
 * One OPPO protocol authority over one byte transport.
 *
 * It owns framing, subscription, initial synchronization, state reduction and
 * the complete write lifecycle. A successful set status only advances an
 * operation to DEVICE_ACCEPTED; confirmed state can only enter through
 * [handleMessage], which consumes device-originated reports.
 */
class OppoSession(
    private val context: DriverSessionContext,
    overrides: OppoCompatibilityOverrides = OppoCompatibilityOverrides(),
    private val responseTimeoutMillis: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    private val confirmationTimeoutMillis: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    private val transportSettleDelayMillis: Long = DEFAULT_TRANSPORT_SETTLE_DELAY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeadphoneSession {

    val compatibility: OppoCompatibilityProfile =
        OppoCompatibilityRegistry.resolve(overrides)

    private val machine = SessionMachine(
        SessionState.Idle(context.candidate.identity.id, generationId = 0),
    )
    private val _connection = MutableStateFlow(machine.state)
    override val connection: StateFlow<SessionState> = _connection.asStateFlow()

    private val _profile = MutableStateFlow<DeviceProfile?>(
        OppoCompatibilityRegistry.initialProfile(context.candidate, compatibility),
    )
    override val profile: StateFlow<DeviceProfile?> = _profile.asStateFlow()

    private val _state = MutableStateFlow(HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val _operations = MutableSharedFlow<OperationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val operations: Flow<OperationEvent> = _operations.asSharedFlow()

    private val _events = MutableSharedFlow<OppoSessionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<OppoSessionEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val operationMutex = Mutex()
    private val requestCounter = AtomicLong()
    private val decoder = OppoFrameStreamDecoder()

    @Volatile
    private var transport: ByteTransport? = null

    @Volatile
    private var responseWaiter: ResponseWaiter? = null

    @Volatile
    private var readbackFeature: FeatureId? = null

    private var customEqualizerCommandSupported = false
    private var customEqualizerSlots: List<OppoCustomEqualizerSlot> = emptyList()
    private var supportedCommands: Set<Int>? = null
    private var spatialSoundSwitchSupported = false
    private var spatialAudioSupported = false

    private var incomingJob: Job? = null
    private var transportStateJob: Job? = null

    override suspend fun connect(): Unit = lifecycleMutex.withLock {
        if (_connection.value.isActive) return
        transition(SessionEvent.ConnectRequested)
        transition(SessionEvent.DetectionSucceeded(context.candidate.availableTransports
            .firstOrNull { it == moe.chenxy.headphones.core.device.TransportKind.CLASSIC_SPP }
            ?: moe.chenxy.headphones.core.device.TransportKind.CLASSIC_SPP))

        val created = try {
            context.transportFactory.create(
                context.candidate.identity,
                TransportSpec.Spp(OPPO_SPP_UUID),
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
        val openState = created.state.value
        if (openState !is TransportState.Open) {
            val failure = (openState as? TransportState.Failed)?.failure
            fail(
                FailureCategory.TRANSPORT,
                failure?.cause ?: DisconnectCause.TRANSPORT_ERROR,
                failure?.detail ?: "transport did not open",
            )
            return
        }
        transition(SessionEvent.TransportOpened)
        if (transportSettleDelayMillis > 0) delay(transportSettleDelayMillis)

        val supportReply = sendAndAwait(
            OppoMessageCodec.encode(OppoCommand.QUERY_NOTIFICATION_SUPPORT),
            expectedCommand = OppoCommand.responseOf(OppoCommand.QUERY_NOTIFICATION_SUPPORT),
        )
        val notificationIds = supportReply?.let(OppoNotificationSupportParser::parse)
        if (notificationIds == null) {
            fail(
                FailureCategory.HANDSHAKE,
                DisconnectCause.PROTOCOL_ERROR,
                "notification-support handshake timed out or was rejected",
            )
            created.close(DisconnectCause.PROTOCOL_ERROR)
            return
        }
        transition(SessionEvent.HandshakeCompleted)

        val wantedNotifications = notificationIds.filter { it < 0xF0 }
        if (wantedNotifications.isNotEmpty()) {
            writeOnly(
                OppoMessageCodec.encode(
                    OppoCommand.SUBSCRIBE_NOTIFICATION_BATCH,
                    payload = OppoNotificationSupportParser.subscribePayload(wantedNotifications),
                ),
            )
        }

        val verified = mutableSetOf<FeatureId>()
        val writable = mutableSetOf<FeatureId>()
        val refuted = mutableSetOf<FeatureId>()
        syncCapabilities(verified, writable, refuted)
        if (verified.isEmpty()) {
            fail(
                FailureCategory.CAPABILITY,
                DisconnectCause.PROTOCOL_ERROR,
                "initial state synchronization produced no usable report",
            )
            created.close(DisconnectCause.PROTOCOL_ERROR)
            return
        }
        _profile.value = OppoCompatibilityRegistry.withEvidence(
            requireNotNull(_profile.value),
            verified = verified,
            writable = writable,
            refuted = refuted,
            firmware = _state.value.firmware,
        )
        transition(SessionEvent.CapabilitiesLoaded)
        transition(SessionEvent.InitialStateSynchronized)
    }

    override suspend fun refresh(featureIds: Set<FeatureId>) {
        if (!_connection.value.isProtocolReady) return
        operationMutex.withLock {
            val requested = if (featureIds.isEmpty()) DEFAULT_REFRESH_FEATURES else featureIds
            queryPackets(requested).forEach { (packet, expected) ->
                sendAndAwait(packet, expected)
            }
        }
    }

    override suspend fun execute(
        command: FeatureCommand,
        requestId: RequestId?,
    ): OperationResult {
        val id = requestId ?: nextRequestId()
        if (command is FeatureCommand.RefreshAll) {
            refresh()
            return OperationResult(id, OperationPhase.READ_BACK_CONFIRMED)
        }
        if (command is FeatureCommand.Refresh) {
            refresh(setOf(command.featureId))
            return OperationResult(id, OperationPhase.READ_BACK_CONFIRMED)
        }

        if (!_connection.value.isProtocolReady) {
            return terminalFailure(id, command, FailureReason.NOT_CONNECTED, "session is not ready")
        }
        val capability = _profile.value?.capability(command.featureId)
        if (capability?.isWritable != true) {
            val reason = if (capability?.canWrite == false || capability == null) {
                FailureReason.NOT_SUPPORTED
            } else {
                FailureReason.NOT_WRITABLE
            }
            return terminalFailure(id, command, reason, "feature is not writable")
        }
        if (!isValueSupported(command)) {
            return terminalFailure(
                id,
                command,
                FailureReason.NOT_SUPPORTED,
                "value is not supported by the compatibility profile",
            )
        }
        val packets = packetsFor(command)
            ?: return terminalFailure(id, command, FailureReason.VALUE_OUT_OF_RANGE, "invalid value")

        return operationMutex.withLock {
            apply(StateUpdate.LocalPending(command, clock()))
            emit(id, command, OperationPhase.QUEUED)

            for (packet in packets) {
                emit(id, command, OperationPhase.SENT)
                val response = sendAndAwait(
                    packet,
                    expectedCommand = OppoCommand.responseOf(commandOf(packet) ?: -1),
                    onWritten = { emit(id, command, OperationPhase.TRANSPORT_ACKNOWLEDGED) },
                )
                if (response == null) {
                    abandon(command.featureId)
                    return@withLock terminalFailure(
                        id,
                        command,
                        FailureReason.TIMEOUT,
                        "device acknowledgement timed out",
                        OperationPhase.TIMED_OUT,
                    )
                }
                if (!response.isSuccess) {
                    apply(StateUpdate.WriteAcknowledged(command.featureId, false, clock()))
                    abandon(command.featureId)
                    return@withLock terminalFailure(
                        id,
                        command,
                        FailureReason.DEVICE_REJECTED,
                        "device rejected status=${response.status}",
                    )
                }
                apply(StateUpdate.WriteAcknowledged(command.featureId, true, clock()))
                emit(id, command, OperationPhase.DEVICE_ACCEPTED)
            }

            val readbacks = readbackPackets(command)
            if (readbacks.isEmpty() && isConfirmed(command)) {
                emit(id, command, OperationPhase.STATE_CONFIRMED)
                return@withLock OperationResult(id, OperationPhase.STATE_CONFIRMED)
            }

            if (readbacks.isNotEmpty()) {
                readbackFeature = command.featureId
                try {
                    readbacks.forEach { (packet, expectedCommand) ->
                        sendAndAwait(packet, expectedCommand)
                    }
                } finally {
                    readbackFeature = null
                }
            } else {
                withTimeoutOrNull(confirmationTimeoutMillis) {
                    while (!isConfirmed(command)) kotlinx.coroutines.delay(CONFIRMATION_POLL_MS)
                }
            }

            if (isConfirmed(command)) {
                val phase = if (readbacks.isNotEmpty()) {
                    OperationPhase.READ_BACK_CONFIRMED
                } else {
                    OperationPhase.STATE_CONFIRMED
                }
                emit(id, command, phase)
                OperationResult(id, phase)
            } else {
                abandon(command.featureId)
                terminalFailure(
                    id,
                    command,
                    FailureReason.TIMEOUT,
                    "accepted write was not confirmed by readback",
                    OperationPhase.TIMED_OUT,
                )
            }
        }
    }

    override suspend fun disconnect(cause: DisconnectCause): Unit = lifecycleMutex.withLock {
        if (_connection.value is SessionState.Idle) return
        transition(SessionEvent.DisconnectRequested)
        responseWaiter?.deferred?.cancel()
        responseWaiter = null
        transport?.close(cause)
        incomingJob?.cancel()
        transportStateJob?.cancel()
        decoder.reset()
        apply(StateUpdate.Disconnected(clock()))
        transition(SessionEvent.Disconnected(cause))
        scope.cancel()
    }

    /** Debug-only facade entry; callers remain responsible for release gating. */
    suspend fun sendDebugFrame(bytes: ByteArray): TransportWriteResult =
        transport?.write(bytes) ?: TransportWriteResult.Rejected(
            moe.chenxy.headphones.core.transport.TransportFailure(
                DisconnectCause.TRANSPORT_ERROR,
                "session transport is absent",
            ),
        )

    private fun startCollectors(active: ByteTransport) {
        incomingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.incoming.collect { chunk ->
                _events.emit(OppoSessionEvent.RawChunk(chunk.copyOf()))
                OppoMessageCodec.decodeStream(decoder, chunk).forEach(::handleMessage)
            }
        }
        transportStateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            active.state.collect { state ->
                if (state is TransportState.Failed && transport === active) {
                    apply(StateUpdate.Disconnected(clock()))
                    fail(FailureCategory.TRANSPORT, state.failure.cause, state.failure.detail)
                }
            }
        }
    }

    private suspend fun syncCapabilities(
        verified: MutableSet<FeatureId>,
        writable: MutableSet<FeatureId>,
        refuted: MutableSet<FeatureId>,
    ) {
        val capabilityReply = sendAndAwait(
            OppoMessageCodec.encode(OppoCommand.QUERY_CAPABILITY),
            OppoCommand.responseOf(OppoCommand.QUERY_CAPABILITY),
        )
        val capabilityBitmap = capabilityReply?.let(OppoCapabilityParser::parse)
        supportedCommands = capabilityBitmap?.let(OppoCapabilityParser::commands)
        customEqualizerCommandSupported = supportsCommand(OppoCommand.QUERY_CUSTOM_EQ) &&
            supportsCommand(OppoCommand.SET_CUSTOM_EQ)
        spatialAudioSupported = supportsCommand(OppoCommand.SET_SPATIAL_AUDIO)

        val battery = sendIfSupported(
            OppoCommand.QUERY_BATTERY,
            OppoBatteryFeature.query(),
        )
        if (battery != null && OppoBatteryFeature.parse(battery) != null) {
            verified += FeatureId.BATTERY
        } else if (!supportsCommand(OppoCommand.QUERY_BATTERY)) {
            refuted += FeatureId.BATTERY
        }

        val anc = sendIfSupported(OppoCommand.QUERY_ANC, OppoNoiseControlFeature.query())
        if (anc != null && OppoNoiseControlFeature.parseNoiseControl(anc, compatibility.ancEncoding) != null) {
            verified += FeatureId.NOISE_CONTROL
            if (supportsCommand(OppoCommand.SET_ANC)) {
                writable += FeatureId.NOISE_CONTROL
            }
        } else if (!supportsCommand(OppoCommand.QUERY_ANC)) {
            refuted += FeatureId.NOISE_CONTROL
            refuted += FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT
        }

        val eq = sendIfSupported(OppoCommand.QUERY_EQ, OppoEqualizerFeature.query())
        if (eq != null && OppoEqualizerFeature.parse(eq) != null) {
            verified += FeatureId.EQUALIZER
            if (supportsCommand(OppoCommand.SET_EQ)) writable += FeatureId.EQUALIZER
        } else if (!supportsCommand(OppoCommand.QUERY_EQ)) {
            refuted += FeatureId.EQUALIZER
        }

        if (customEqualizerCommandSupported) {
            val customEq = sendAndAwait(
                OppoCustomEqualizerFeature.query(),
                OppoCommand.responseOf(OppoCommand.QUERY_CUSTOM_EQ),
            )
            if (customEq != null && OppoCustomEqualizerFeature.parse(customEq) != null) {
                verified += FeatureId.EQUALIZER
                writable += FeatureId.EQUALIZER
            }
        }

        val batch = sendAndAwait(batchQuery(), OppoCommand.responseOf(OppoCommand.QUERY_BATCH_STATUS))
        val values = batch?.let(OppoBatchStatusParser::parse)
        if (values != null) {
            resolveBatchFeature(
                values,
                FeatureId.LOW_LATENCY,
                setOf(OppoFeature.GAME_MODE, OppoFeature.LOW_LATENCY),
                verified,
                refuted,
            )
            resolveBatchFeature(
                values,
                FeatureId.DUAL_DEVICE_CONNECTION,
                setOf(OppoFeature.DUAL_DEVICE),
                verified,
                refuted,
            )
            resolveBatchFeature(
                values,
                FeatureId.SPATIAL_SOUND_SWITCH,
                setOf(OppoFeature.SPATIAL_SOUND_SWITCH),
                verified,
                refuted,
            )
            spatialSoundSwitchSupported = OppoFeature.SPATIAL_SOUND_SWITCH in values
            if (supportsCommand(OppoCommand.SET_SWITCH_FEATURE)) {
                setOf(
                    FeatureId.LOW_LATENCY,
                    FeatureId.DUAL_DEVICE_CONNECTION,
                    FeatureId.SPATIAL_SOUND_SWITCH,
                ).filterTo(writable) { it in verified }
            }
        }

        if (spatialAudioSupported) {
            verified += FeatureId.SPATIAL_AUDIO
            writable += FeatureId.SPATIAL_AUDIO
        } else {
            refuted += FeatureId.SPATIAL_AUDIO
        }

        val firmware = sendIfSupported(
            OppoCommand.QUERY_FIRMWARE,
            OppoMessageCodec.encode(OppoCommand.QUERY_FIRMWARE),
        )
        if (firmware?.let(OppoFirmwareParser::parse) != null) verified += FeatureId.FIRMWARE_VERSION
        else if (!supportsCommand(OppoCommand.QUERY_FIRMWARE)) refuted += FeatureId.FIRMWARE_VERSION
    }

    private fun supportsCommand(command: Int): Boolean = supportedCommands?.contains(command) != false

    private suspend fun sendIfSupported(command: Int, packet: ByteArray): OppoMessage? =
        if (supportsCommand(command)) {
            sendAndAwait(packet, OppoCommand.responseOf(command))
        } else {
            null
        }

    private fun resolveBatchFeature(
        values: Map<Int, Int>,
        featureId: FeatureId,
        vendorIds: Set<Int>,
        verified: MutableSet<FeatureId>,
        refuted: MutableSet<FeatureId>,
    ) {
        if (vendorIds.any(values::containsKey)) verified += featureId else refuted += featureId
    }

    private fun handleMessage(message: OppoMessage) {
        _events.tryEmit(OppoSessionEvent.Message(message))
        var recognized = false

        OppoCustomEqualizerFeature.parse(message)?.let { slots ->
            recognized = true
            customEqualizerSlots = slots
            updateCustomEqualizerProfile(slots)
            val selected = slots.singleOrNull { it.selected }
            if (selected != null) {
                val pendingCurve = _state.value.equalizerCurve.pending
                if (
                    pendingCurve?.slotId == OppoCustomEqualizerFeature.CREATION_SLOT_ID &&
                    pendingCurve.gains == selected.gains
                ) {
                    // ADD uses virtual id 0/new, while the readback contains the id
                    // assigned by the device. Drop the virtual intent before applying truth.
                    abandon(FeatureId.EQUALIZER)
                }
                apply(
                    StateUpdate.DeviceReported(
                        OppoCustomEqualizerFeature.toReport(selected),
                        if (readbackFeature == FeatureId.EQUALIZER) {
                            ValueSource.READ_BACK
                        } else {
                            ValueSource.QUERY_RESPONSE
                        },
                        clock(),
                    ),
                )
            } else {
                apply(
                    StateUpdate.DeviceReported(
                        DeviceReport.EqualizerCurveUnavailable,
                        if (readbackFeature == FeatureId.EQUALIZER) {
                            ValueSource.READ_BACK
                        } else {
                            ValueSource.QUERY_RESPONSE
                        },
                        clock(),
                    ),
                )
            }
        }

        val reports = buildList {
            OppoBatteryFeature.parse(message)?.let(::add)
            OppoNoiseControlFeature.parseNoiseControl(message, compatibility.ancEncoding)?.let(::add)
            OppoNoiseControlFeature.parseTransparencyVocalEnhancement(message)?.let(::add)
            OppoEqualizerFeature.parse(message)?.let(::add)
            OppoLatencyFeature.parse(message, compatibility.lowLatencyStrategy)?.let(::add)
            OppoDualDeviceFeature.parse(message)?.let(::add)
            addAll(OppoSpatialFeature.parse(message))
            OppoFirmwareParser.parse(message)?.let { add(DeviceReport.Firmware(it)) }
        }
        reports.forEach { report ->
            recognized = true
            val feature = featureOf(report)
            if (feature != null) promoteReportedFeature(feature, report)
            val source = when {
                feature != null && feature == readbackFeature -> ValueSource.READ_BACK
                message.command == OppoCommand.NOTIFICATION_EVENT ||
                    message.command == OppoCommand.SPATIAL_AUDIO_NOTIFICATION -> ValueSource.NOTIFICATION
                else -> ValueSource.QUERY_RESPONSE
            }
            apply(StateUpdate.DeviceReported(report, source, clock()))
            if (report is DeviceReport.Firmware) {
                _profile.value = _profile.value?.copy(firmware = report.version)
            }
        }

        OppoWearParser.parse(message)?.let { vendorWear ->
            recognized = true
            _events.tryEmit(OppoSessionEvent.WearReport(vendorWear))
            apply(
                StateUpdate.DeviceReported(
                    OppoDomainMapper.toWearReport(vendorWear),
                    ValueSource.NOTIFICATION,
                    clock(),
                ),
            )
            // Wear state is notification-only on this protocol family, so it
            // cannot be promoted during the query-based initial sync above.
            // A successfully parsed device notification is direct evidence.
            _profile.value = _profile.value?.let { profile ->
                OppoCompatibilityRegistry.withEvidence(
                    profile,
                    verified = setOf(FeatureId.WEAR_DETECTION),
                    firmware = profile.firmware,
                )
            }
        }
        parseSmartAncLevel(message)?.let { activeMode ->
            recognized = true
            _state.value = _state.value.copy(noiseControlActiveMode = activeMode)
        }
        if (
            message.command == OppoCommand.responseOf(OppoCommand.QUERY_NOTIFICATION_SUPPORT) ||
            message.command == OppoCommand.responseOf(OppoCommand.QUERY_CAPABILITY) ||
            message.command == OppoCommand.responseOf(OppoCommand.SUBSCRIBE_NOTIFICATION_BATCH) ||
            message.command == OppoCommand.responseOf(OppoCommand.SET_SWITCH_FEATURE) ||
            message.command == OppoCommand.responseOf(OppoCommand.SET_ANC) ||
            message.command == OppoCommand.responseOf(OppoCommand.SET_EQ) ||
            message.command == OppoCommand.responseOf(OppoCommand.SET_CUSTOM_EQ) ||
            message.command == OppoCommand.responseOf(OppoCommand.SET_SPATIAL_AUDIO)
        ) {
            recognized = true
        }
        if (!recognized) _events.tryEmit(OppoSessionEvent.UnknownMessage(message))

        responseWaiter?.let { waiter ->
            if (waiter.predicate(message)) waiter.deferred.complete(message)
        }
    }

    private suspend fun sendAndAwait(
        packet: ByteArray,
        expectedCommand: Int,
        onWritten: () -> Unit = {},
    ): OppoMessage? {
        val active = transport ?: return null
        val deferred = CompletableDeferred<OppoMessage>()
        val waiter = ResponseWaiter({ it.command == expectedCommand }, deferred)
        check(responseWaiter == null) { "only one OPPO request may await a response" }
        responseWaiter = waiter
        return try {
            _events.emit(OppoSessionEvent.TxFrame(packet.copyOf()))
            when (active.write(packet)) {
                TransportWriteResult.Written -> onWritten()
                is TransportWriteResult.Rejected -> return null
            }
            withTimeoutOrNull(responseTimeoutMillis) { deferred.await() }
        } finally {
            if (responseWaiter === waiter) responseWaiter = null
        }
    }

    private suspend fun writeOnly(packet: ByteArray): Boolean {
        val active = transport ?: return false
        _events.emit(OppoSessionEvent.TxFrame(packet.copyOf()))
        return active.write(packet) is TransportWriteResult.Written
    }

    private fun promoteReportedFeature(featureId: FeatureId, report: DeviceReport) {
        val profile = _profile.value ?: return
        val writable = when (featureId) {
            FeatureId.NOISE_CONTROL,
            FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT,
            -> supportsCommand(OppoCommand.SET_ANC)
            FeatureId.EQUALIZER ->
                supportsCommand(OppoCommand.SET_EQ) || supportsCommand(OppoCommand.SET_CUSTOM_EQ)
            FeatureId.LOW_LATENCY,
            FeatureId.DUAL_DEVICE_CONNECTION,
            FeatureId.SPATIAL_SOUND_SWITCH,
            -> supportsCommand(OppoCommand.SET_SWITCH_FEATURE)
            FeatureId.SPATIAL_AUDIO -> supportsCommand(OppoCommand.SET_SPATIAL_AUDIO)
            else -> false
        }
        var updated = OppoCompatibilityRegistry.withEvidence(
            profile,
            verified = setOf(featureId),
            writable = if (writable) setOf(featureId) else emptySet(),
            firmware = profile.firmware,
        )
        if (report is DeviceReport.NoiseControl) {
            val capability = updated.capability(FeatureId.NOISE_CONTROL) ?: return
            val value = report.mode.name
            updated = updated.copy(
                features = updated.features + (
                    FeatureId.NOISE_CONTROL to capability.copy(
                        allowedValues = capability.allowedValues + value,
                        valueLabels = capability.valueLabels + (value to noiseControlLabel(report.mode)),
                    )
                    ),
            )
        }
        _profile.value = updated
    }

    private fun noiseControlLabel(mode: NoiseControlMode): String = when (mode) {
        NoiseControlMode.OFF -> "Off"
        NoiseControlMode.NOISE_CANCELLATION -> "Noise cancellation"
        NoiseControlMode.NOISE_CANCELLATION_SMART -> "Smart"
        NoiseControlMode.NOISE_CANCELLATION_LIGHT -> "Light"
        NoiseControlMode.NOISE_CANCELLATION_MEDIUM -> "Medium"
        NoiseControlMode.NOISE_CANCELLATION_DEEP -> "Deep"
        NoiseControlMode.TRANSPARENCY -> "Transparency"
        NoiseControlMode.ADAPTIVE -> "Adaptive"
    }

    private fun queryPackets(features: Set<FeatureId>): List<Pair<ByteArray, Int>> {
        val result = mutableListOf<Pair<ByteArray, Int>>()
        if (FeatureId.BATTERY in features && supportsCommand(OppoCommand.QUERY_BATTERY)) {
            result += OppoBatteryFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_BATTERY)
        }
        if (
            (
                FeatureId.NOISE_CONTROL in features ||
                    FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT in features
                ) && supportsCommand(OppoCommand.QUERY_ANC)
        ) {
            result += OppoNoiseControlFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_ANC)
        }
        if (FeatureId.EQUALIZER in features) {
            if (supportsCommand(OppoCommand.QUERY_EQ)) {
                result += OppoEqualizerFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_EQ)
            }
            if (customEqualizerCommandSupported) {
                result += OppoCustomEqualizerFeature.query() to
                    OppoCommand.responseOf(OppoCommand.QUERY_CUSTOM_EQ)
            }
        }
        if (
            FeatureId.LOW_LATENCY in features ||
            FeatureId.DUAL_DEVICE_CONNECTION in features ||
            FeatureId.SPATIAL_SOUND_SWITCH in features
        ) {
            result += batchQuery() to OppoCommand.responseOf(OppoCommand.QUERY_BATCH_STATUS)
        }
        if (FeatureId.FIRMWARE_VERSION in features && supportsCommand(OppoCommand.QUERY_FIRMWARE)) {
            result += OppoMessageCodec.encode(OppoCommand.QUERY_FIRMWARE) to
                OppoCommand.responseOf(OppoCommand.QUERY_FIRMWARE)
        }
        return result
    }

    private fun packetsFor(command: FeatureCommand): List<ByteArray>? = when (command) {
        is FeatureCommand.SetNoiseControl ->
            OppoNoiseControlFeature.set(command.mode, compatibility.ancEncoding)?.let(::listOf)
        is FeatureCommand.SetAmbientSoundLevel -> null
        is FeatureCommand.SetTransparencyVocalEnhancement ->
            listOf(OppoNoiseControlFeature.setTransparencyVocalEnhancement(command.enabled))
        is FeatureCommand.SetEqualizerPreset -> {
            val customEqId = OppoCustomEqualizerFeature.eqId(command.preset.id)
            if (customEqId == null) {
                OppoEqualizerFeature.set(command.preset)?.let(::listOf)
            } else {
                customEqualizerSlots.singleOrNull { it.eqId == customEqId }
                    ?.let(OppoCustomEqualizerFeature::select)
                    ?.let(::listOf)
            }
        }
        is FeatureCommand.SetEqualizerCurve -> {
            if (command.curve.slotId == OppoCustomEqualizerFeature.CREATION_SLOT_ID) {
                OppoCustomEqualizerFeature.create(command.curve)?.let(::listOf)
            } else {
                val activeSlot = customEqualizerSlots.singleOrNull { it.selected }
                val spec = _profile.value?.capability(FeatureId.EQUALIZER)?.equalizerCurveSpec
                if (activeSlot == null || spec == null) null else {
                    OppoCustomEqualizerFeature.setCurve(command.curve, activeSlot, spec)?.let(::listOf)
                }
            }
        }
        is FeatureCommand.RenameEqualizerPreset -> {
            val customEqId = OppoCustomEqualizerFeature.eqId(command.preset.id)
            val name = command.preset.displayName?.trim()
            if (customEqId == null || name == null) null else {
                customEqualizerSlots.singleOrNull { it.eqId == customEqId }
                    ?.let { OppoCustomEqualizerFeature.rename(it, name) }
                    ?.let(::listOf)
            }
        }
        is FeatureCommand.DeleteEqualizerPreset -> {
            val customEqId = OppoCustomEqualizerFeature.eqId(command.presetId)
            if (customEqId == null) null else {
                customEqualizerSlots.singleOrNull { it.eqId == customEqId }
                    ?.let(OppoCustomEqualizerFeature::delete)
                    ?.let(::listOf)
            }
        }
        is FeatureCommand.SetLowLatency ->
            OppoLatencyFeature.set(command.enabled, compatibility.lowLatencyStrategy)
        is FeatureCommand.SetSpatialAudio ->
            listOf(OppoSpatialFeature.set(command.mode, useSwitch = false))
        is FeatureCommand.SetSpatialSoundSwitch ->
            listOf(
                OppoSpatialFeature.set(
                    if (command.enabled) SpatialAudioMode.FIXED else SpatialAudioMode.OFF,
                    useSwitch = true,
                ),
            )
        is FeatureCommand.SetDualDeviceConnection ->
            listOf(OppoDualDeviceFeature.set(command.enabled))
        is FeatureCommand.Refresh, FeatureCommand.RefreshAll -> null
    }

    private fun readbackPackets(command: FeatureCommand): List<Pair<ByteArray, Int>> = when (command) {
        is FeatureCommand.SetEqualizerCurve ->
            listOf(OppoCustomEqualizerFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_CUSTOM_EQ))
        is FeatureCommand.SetEqualizerPreset -> {
            if (OppoCustomEqualizerFeature.eqId(command.preset.id) != null) {
                listOf(OppoCustomEqualizerFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_CUSTOM_EQ))
            } else {
                listOf(OppoEqualizerFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_EQ))
            }
        }
        is FeatureCommand.RenameEqualizerPreset ->
            listOf(OppoCustomEqualizerFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_CUSTOM_EQ))
        is FeatureCommand.DeleteEqualizerPreset -> listOf(
            OppoCustomEqualizerFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_CUSTOM_EQ),
            OppoEqualizerFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_EQ),
        )
        is FeatureCommand.SetNoiseControl ->
            listOf(OppoNoiseControlFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_ANC))
        is FeatureCommand.SetTransparencyVocalEnhancement ->
            listOf(OppoNoiseControlFeature.query() to OppoCommand.responseOf(OppoCommand.QUERY_ANC))
        is FeatureCommand.SetLowLatency,
        is FeatureCommand.SetSpatialSoundSwitch,
        is FeatureCommand.SetDualDeviceConnection,
        -> listOf(batchQuery() to OppoCommand.responseOf(OppoCommand.QUERY_BATCH_STATUS))
        else -> emptyList()
    }

    private fun updateCustomEqualizerProfile(slots: List<OppoCustomEqualizerSlot>) {
        val profile = _profile.value ?: return
        val capability = profile.capability(FeatureId.EQUALIZER) ?: return
        val builtInValues = capability.allowedValues.filterTo(linkedSetOf()) {
            OppoCustomEqualizerFeature.eqId(it) == null
        }
        val builtInLabels = capability.valueLabels.filterKeys {
            OppoCustomEqualizerFeature.eqId(it) == null
        }
        val customValues = slots.mapTo(linkedSetOf()) { it.slotId }
        val customLabels = slots.associate { slot ->
            slot.slotId to slot.name.ifBlank { "Custom ${slot.eqId}" }
        }
        val selected = slots.singleOrNull { it.selected }
        val canCreate = customEqualizerCommandSupported &&
            slots.size < DEFAULT_CUSTOM_EQUALIZER_MAX_SLOTS
        val editingSpec = selected?.let(OppoCustomEqualizerFeature::curveSpec)
            ?: slots.firstOrNull()?.let(OppoCustomEqualizerFeature::curveSpec)
            ?: OppoCustomEqualizerFeature.creationSpec().takeIf { canCreate }
        val curveSpec = editingSpec?.copy(
            writableSlotIds = buildSet {
                selected?.slotId?.let(::add)
                if (canCreate) add(OppoCustomEqualizerFeature.CREATION_SLOT_ID)
            },
        )
        val updatedCapability = capability.copy(
            allowedValues = builtInValues + customValues,
            valueLabels = builtInLabels + customLabels,
            equalizerCurveSpec = curveSpec,
            source = "device-response:custom-eq",
        )
        _profile.value = profile.copy(
            features = profile.features + (FeatureId.EQUALIZER to updatedCapability),
        )
    }

    private fun batchQuery(): ByteArray = OppoMessageCodec.encode(
        OppoCommand.QUERY_BATCH_STATUS,
        sequence = 0,
        payload = OppoBatchStatusParser.requestPayload(BATCH_VENDOR_FEATURES),
    )

    private fun commandOf(frame: ByteArray): Int? = OppoMessageCodec.decode(frame)?.command

    private fun isValueSupported(command: FeatureCommand): Boolean = when (command) {
        is FeatureCommand.SetNoiseControl ->
            command.mode.name in
                _profile.value?.capability(FeatureId.NOISE_CONTROL)?.allowedValues.orEmpty()
        is FeatureCommand.SetSpatialAudio ->
            spatialAudioSupported
        is FeatureCommand.SetSpatialSoundSwitch -> spatialSoundSwitchSupported
        is FeatureCommand.SetEqualizerPreset -> {
            val capability = _profile.value?.capability(FeatureId.EQUALIZER)
            command.preset.id in capability?.allowedValues.orEmpty() &&
                (OppoCustomEqualizerFeature.eqId(command.preset.id) == null ||
                    customEqualizerCommandSupported)
        }
        is FeatureCommand.SetEqualizerCurve ->
            customEqualizerCommandSupported &&
                (
                    command.curve.slotId != OppoCustomEqualizerFeature.CREATION_SLOT_ID ||
                        customEqualizerSlots.size < DEFAULT_CUSTOM_EQUALIZER_MAX_SLOTS
                    ) &&
                _profile.value?.capability(FeatureId.EQUALIZER)
                    ?.equalizerCurveSpec?.accepts(command.curve) == true
        is FeatureCommand.RenameEqualizerPreset -> {
            val customEqId = OppoCustomEqualizerFeature.eqId(command.preset.id)
            val name = command.preset.displayName?.trim()
            customEqualizerCommandSupported && customEqId != null && name != null &&
                OppoCustomEqualizerFeature.isValidName(name) &&
                customEqualizerSlots.any { it.eqId == customEqId }
        }
        is FeatureCommand.DeleteEqualizerPreset -> {
            val customEqId = OppoCustomEqualizerFeature.eqId(command.presetId)
            customEqualizerCommandSupported && customEqId != null &&
                customEqualizerSlots.any { it.eqId == customEqId }
        }
        else -> true
    }

    private fun isConfirmed(command: FeatureCommand): Boolean = when (command) {
        is FeatureCommand.SetNoiseControl -> _state.value.noiseControl.let {
            it.pending == null && it.confirmed == command.mode
        }
        is FeatureCommand.SetAmbientSoundLevel -> false
        is FeatureCommand.SetTransparencyVocalEnhancement ->
            _state.value.transparencyVocalEnhancement.let {
                it.pending == null && it.confirmed == command.enabled
            }
        is FeatureCommand.SetEqualizerPreset -> _state.value.equalizer.let {
            it.pending == null && it.confirmed?.id == command.preset.id
        }
        is FeatureCommand.SetEqualizerCurve -> _state.value.equalizerCurve.let {
            it.pending == null && if (
                command.curve.slotId == OppoCustomEqualizerFeature.CREATION_SLOT_ID
            ) {
                it.confirmed?.slotId != OppoCustomEqualizerFeature.CREATION_SLOT_ID &&
                    it.confirmed?.gains == command.curve.gains
            } else {
                it.confirmed == command.curve
            }
        }
        is FeatureCommand.RenameEqualizerPreset -> {
            val name = command.preset.displayName?.trim()
            name != null && customEqualizerSlots.any {
                it.slotId == command.preset.id && it.name == name
            }
        }
        is FeatureCommand.DeleteEqualizerPreset ->
            customEqualizerSlots.none { it.slotId == command.presetId }
        is FeatureCommand.SetLowLatency -> _state.value.lowLatency.let {
            it.pending == null && it.confirmed == command.enabled
        }
        is FeatureCommand.SetSpatialAudio -> _state.value.spatialAudio.let {
            it.pending == null && it.confirmed == command.mode
        }
        is FeatureCommand.SetSpatialSoundSwitch -> _state.value.spatialSoundSwitch.let {
            it.pending == null && it.confirmed == command.enabled
        }
        is FeatureCommand.SetDualDeviceConnection -> _state.value.dualDeviceConnection.let {
            it.pending == null && it.confirmed == command.enabled
        }
        is FeatureCommand.Refresh, FeatureCommand.RefreshAll -> true
    }

    private fun featureOf(report: DeviceReport): FeatureId? = when (report) {
        is DeviceReport.Batteries -> FeatureId.BATTERY
        is DeviceReport.Wearing -> FeatureId.WEAR_DETECTION
        is DeviceReport.NoiseControl -> FeatureId.NOISE_CONTROL
        is DeviceReport.AmbientSoundLevel -> FeatureId.AMBIENT_SOUND_LEVEL
        is DeviceReport.TransparencyVocalEnhancement -> FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT
        is DeviceReport.Equalizer -> FeatureId.EQUALIZER
        DeviceReport.EqualizerCurveUnavailable -> FeatureId.EQUALIZER
        is DeviceReport.LowLatency -> FeatureId.LOW_LATENCY
        is DeviceReport.SpatialAudio -> FeatureId.SPATIAL_AUDIO
        is DeviceReport.SpatialSoundSwitch -> FeatureId.SPATIAL_SOUND_SWITCH
        is DeviceReport.DualDeviceConnection -> FeatureId.DUAL_DEVICE_CONNECTION
        is DeviceReport.Firmware -> FeatureId.FIRMWARE_VERSION
    }

    private fun parseSmartAncLevel(message: OppoMessage): NoiseControlMode? {
        if (!message.isComplete || message.command != OppoCommand.NOTIFICATION_EVENT) return null
        val payload = message.payload
        if (payload.size < 4 || payload[0] != 0x03.toByte() ||
            payload[1] != 0x04.toByte() || payload[2] != 0x01.toByte()
        ) return null
        for (index in 3 until payload.size) {
            val bits = payload[index].toInt() and 0xFF
            for (offset in 0..7) {
                if ((bits and (1 shl offset)) == 0) continue
                return when ((index - 3) * 8 + offset) {
                    4 -> NoiseControlMode.NOISE_CANCELLATION_DEEP
                    5 -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
                    6 -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
                    else -> null
                }
            }
        }
        return null
    }

    private fun apply(update: StateUpdate) {
        _state.value = HeadphoneStateReducer.reduce(_state.value, update)
    }

    private fun abandon(featureId: FeatureId) {
        apply(StateUpdate.PendingAbandoned(featureId, clock()))
    }

    private fun transition(event: SessionEvent) {
        _connection.value = machine.onEvent(event)
    }

    private fun fail(category: FailureCategory, cause: DisconnectCause, detail: String?) {
        transition(SessionEvent.Failed(category, cause, detail))
    }

    private fun nextRequestId(): RequestId =
        RequestId("oppo-${requestCounter.incrementAndGet()}")

    private fun emit(
        requestId: RequestId,
        command: FeatureCommand,
        phase: OperationPhase,
        failure: FailureReason? = null,
        detail: String? = null,
    ) {
        _operations.tryEmit(
            OperationEvent(
                requestId,
                command,
                phase,
                clock(),
                _connection.value.generationId,
                failure,
                detail,
            ),
        )
    }

    private fun terminalFailure(
        requestId: RequestId,
        command: FeatureCommand,
        reason: FailureReason,
        detail: String,
        phase: OperationPhase = OperationPhase.FAILED,
    ): OperationResult {
        emit(requestId, command, phase, reason, detail)
        return OperationResult(requestId, phase, reason, detail)
    }

    private data class ResponseWaiter(
        val predicate: (OppoMessage) -> Boolean,
        val deferred: CompletableDeferred<OppoMessage>,
    )

    companion object {
        const val OPPO_SPP_UUID = "0000079A-D102-11E1-9B23-00025B00A5A5"
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 1_500L
        const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 1_000L
        const val DEFAULT_TRANSPORT_SETTLE_DELAY_MS = 300L
        private const val CONFIRMATION_POLL_MS = 20L
        private const val DEFAULT_CUSTOM_EQUALIZER_MAX_SLOTS = 3

        private val DEFAULT_REFRESH_FEATURES = setOf(
            FeatureId.BATTERY,
            FeatureId.NOISE_CONTROL,
            FeatureId.EQUALIZER,
            FeatureId.LOW_LATENCY,
            FeatureId.SPATIAL_SOUND_SWITCH,
            FeatureId.DUAL_DEVICE_CONNECTION,
        )

        private val BATCH_VENDOR_FEATURES = listOf(
            0x0B, 0x05, 0x04, 0x11, 0x13,
            0x18, 0x06, 0x1B, 0x37, 0x27, 0x28,
        )
    }
}
