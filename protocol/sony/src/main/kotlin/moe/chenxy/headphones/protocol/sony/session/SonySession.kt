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
import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.feature.HeadphoneStateReducer
import moe.chenxy.headphones.core.feature.NoiseControlMode
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
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyEqualizerFeature
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyEqualizerState
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyV1EqualizerCapability
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyV1EqualizerFeature
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyV2EqualizerCapability
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyAmbientSoundMode
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyNoiseControlCapability
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyNoiseControlFeature
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyNoiseControlState
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyV1NoiseControlCapability
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyV1NoiseControlFeature
import moe.chenxy.headphones.protocol.sony.feature.wearing.SonyAutoPlayWearingFeature
import moe.chenxy.headphones.protocol.sony.feature.wearing.SonyWearingFeature
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
    data class AutoPlayTx(val bytes: ByteArray) : SonySessionEvent
    data class AutoPlayRx(val bytes: ByteArray) : SonySessionEvent
    data class AutoPlayUnavailable(val detail: String) : SonySessionEvent
}

/**
 * Sony Tandem session shared by Classic SPP and BLE GATT.
 *
 * Writes are limited to exact dynamic-evidence profiles in [SonyProfile].
 * Every accepted write is followed by an explicit GET and value comparison.
 */
class SonySession(
    private val context: DriverSessionContext,
    private val responseTimeoutMillis: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    private val transportSettleDelayMillis: Long = DEFAULT_TRANSPORT_SETTLE_DELAY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeadphoneSession {
    private val transportRoute = resolveTransport(context.candidate)
    private val machine = SessionMachine(
        SessionState.Idle(context.candidate.identity.id, generationId = 0),
    )
    private val _connection = MutableStateFlow(machine.state)
    override val connection: StateFlow<SessionState> = _connection.asStateFlow()
    private val _profile = MutableStateFlow<DeviceProfile?>(
        SonyProfile.initial(
            context.candidate,
            transportRoute?.kind ?: TransportKind.CLASSIC_SPP,
            transportRoute?.label ?: "unresolved",
        ),
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
    private val autoPlayExchangeMutex = Mutex()
    private val operationMutex = Mutex()
    private val decoder = TandemStreamDecoder()
    private val requestCounter = AtomicLong()
    private var transport: ByteTransport? = null
    private var incomingJob: Job? = null
    private var transportStateJob: Job? = null
    private var autoPlayTransport: ByteTransport? = null
    private var autoPlayIncomingJob: Job? = null
    private var outgoingSequence = 0

    @Volatile
    private var ackWaiter: AckWaiter? = null

    @Volatile
    private var responseWaiter: ResponseWaiter? = null

    @Volatile
    private var autoPlayResponseWaiter: AutoPlayResponseWaiter? = null

    @Volatile
    private var autoPlayWearingActive = false

    private var protocolInfo: SonyProtocolInfo? = null
    private var capabilityInfo: SonyCapabilityInfo? = null
    private var supportInfo: SonySupportInfo? = null
    private var v2NoiseControlCapability: SonyNoiseControlCapability? = null
    private var v1NoiseControlCapability: SonyV1NoiseControlCapability? = null
    private var v1EqualizerCapability: SonyV1EqualizerCapability? = null
    private var v2EqualizerCapability: SonyV2EqualizerCapability? = null
    private val observedBatteryTypes = linkedSetOf<SonyBatteryType>()

    @Volatile
    private var noiseControlState: SonyNoiseControlState? = null

    @Volatile
    private var equalizerState: SonyEqualizerState? = null

    override suspend fun connect(): Unit = lifecycleMutex.withLock {
        if (_connection.value.isActive) return
        transition(SessionEvent.ConnectRequested)
        val route = transportRoute
        if (route == null) {
            transition(
                SessionEvent.DetectionFailed(
                    "Sony hint has neither an advertised SPP UUID nor a bonded GATT validation route",
                ),
            )
            return
        }
        transition(SessionEvent.DetectionSucceeded(route.kind))
        val created = try {
            context.transportFactory.create(
                context.candidate.identity,
                route.spec,
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
            responsePredicate = { SonyHandshake.matchesDeviceInfo(it, SonyDeviceInfoType.MODEL_NAME) },
        )?.let { SonyHandshake.parseDeviceInfo(it, SonyDeviceInfoType.MODEL_NAME) }
        val firmware = exchange(
            SonyHandshake.getDeviceInfo(SonyDeviceInfoType.FIRMWARE_VERSION),
            SonyCommand.CONNECT_RET_DEVICE_INFO,
            responsePredicate = { SonyHandshake.matchesDeviceInfo(it, SonyDeviceInfoType.FIRMWARE_VERSION) },
        )?.let { SonyHandshake.parseDeviceInfo(it, SonyDeviceInfoType.FIRMWARE_VERSION) }
        val seriesAndColor = exchange(
            SonyHandshake.getDeviceInfo(SonyDeviceInfoType.SERIES_AND_COLOR),
            SonyCommand.CONNECT_RET_DEVICE_INFO,
            responsePredicate = { SonyHandshake.matchesDeviceInfo(it, SonyDeviceInfoType.SERIES_AND_COLOR) },
        )?.let(SonyHandshake::parseSeriesAndColor)
        val support = exchange(
            SonyHandshake.getSupportFunction(),
            SonyCommand.CONNECT_RET_SUPPORT_FUNCTION,
        )?.let { SonyHandshake.parseSupportFunction(protocol.generation, it) }
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
            vendorStates = buildMap {
                put("sony.transport", route.kind.name)
                put("sony.protocol.generation", protocol.generation.name)
                put("sony.protocol.fingerprint", protocol.rawFingerprint)
                put("sony.capability.fingerprint", capability.fingerprint)
                put("sony.support.fingerprint", support.fingerprint)
                put("sony.support.count", support.functions.size.toString())
                seriesAndColor?.let {
                    put("sony.model.series", "0x%02X".format(it.seriesCode))
                    put("sony.model.color", "0x%02X".format(it.colorCode))
                }
            },
        )
        transition(SessionEvent.CapabilitiesLoaded)

        var batteryObserved = false
        SonyProfile.batteryTypes(protocol, support).forEach { type ->
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
                observedBatteryTypes += type
            }
        }
        if (!batteryObserved) {
            failAndClose(created, FailureCategory.CAPABILITY, "initial Sony battery read failed")
            return
        }
        var noiseControlObserved = false
        var equalizerObserved = false
        val tableWearingObserved = if (SonyProfile.shouldQueryWearing(protocol, support)) {
            exchange(
                SonyWearingFeature.query(),
                SonyCommand.SYSTEM_RET_STATUS,
                table = SonyCommandTable.TABLE2,
                responsePredicate = SonyWearingFeature::matches,
            )?.let(SonyWearingFeature::parse) != null
        } else {
            false
        }
        val shouldConnectAutoPlayWearing =
            SonyProfile.shouldQueryAutoPlayWearing(route.kind, protocol, support)
        if (SonyProfile.shouldQueryNoiseControl(protocol, support)) {
            v2NoiseControlCapability = exchange(
                SonyNoiseControlFeature.queryCapability(),
                SonyCommand.NCASM_RET_CAPABILITY,
                responsePredicate = SonyNoiseControlFeature::capabilityMatches,
            )?.let(SonyNoiseControlFeature::parseCapability)
            val statusEnabled = if (v2NoiseControlCapability != null) {
                exchange(
                    SonyNoiseControlFeature.queryStatus(),
                    SonyCommand.NCASM_RET_STATUS,
                    responsePredicate = SonyNoiseControlFeature::statusMatches,
                )?.let(SonyNoiseControlFeature::parseStatusEnabled) == true
            } else {
                false
            }
            if (statusEnabled) {
                val response = exchange(
                    SonyNoiseControlFeature.query(),
                    SonyCommand.NCASM_RET_PARAM,
                    responsePredicate = SonyNoiseControlFeature::matches,
                )
                noiseControlObserved = response?.let(SonyNoiseControlFeature::parse) != null
            }
        } else if (SonyProfile.shouldQueryV1NoiseControl(protocol)) {
            v1NoiseControlCapability = exchange(
                SonyV1NoiseControlFeature.queryCapability(),
                SonyCommand.NCASM_RET_CAPABILITY,
                responsePredicate = SonyV1NoiseControlFeature::capabilityMatches,
            )?.let(SonyV1NoiseControlFeature::parseCapability)
            val v1Capability = v1NoiseControlCapability
            if (v1Capability != null) {
                noiseControlObserved = exchange(
                    SonyV1NoiseControlFeature.query(),
                    SonyCommand.NCASM_RET_PARAM,
                    responsePredicate = SonyV1NoiseControlFeature::matches,
                )?.let { SonyV1NoiseControlFeature.parse(it, v1Capability) } != null
            }
        }
        if (SonyProfile.shouldQueryEqualizer(protocol)) {
            v2EqualizerCapability = exchange(
                SonyEqualizerFeature.queryCapability(),
                SonyCommand.EQEBB_RET_CAPABILITY,
                responsePredicate = SonyEqualizerFeature::capabilityMatches,
            )?.let(SonyEqualizerFeature::parseCapability)
            val v2Capability = v2EqualizerCapability
            if (v2Capability != null) {
                equalizerObserved = exchange(
                    SonyEqualizerFeature.query(),
                    SonyCommand.EQEBB_RET_PARAM,
                    responsePredicate = SonyEqualizerFeature::matches,
                )?.let { SonyEqualizerFeature.parse(it, v2Capability) } != null
            }
        } else if (SonyProfile.shouldQueryV1Equalizer(protocol)) {
            v1EqualizerCapability = exchange(
                SonyV1EqualizerFeature.queryCapability(),
                SonyCommand.EQEBB_RET_CAPABILITY,
                responsePredicate = SonyV1EqualizerFeature::capabilityMatches,
            )?.let(SonyV1EqualizerFeature::parseCapability)
            val v1Capability = v1EqualizerCapability
            if (v1Capability != null) {
                equalizerObserved = exchange(
                    SonyV1EqualizerFeature.query(),
                    SonyCommand.EQEBB_RET_PARAM,
                    responsePredicate = SonyV1EqualizerFeature::matches,
                )?.let { SonyV1EqualizerFeature.parse(it, v1Capability) } != null
            }
        }
        fun verifiedProfile(wearingReadVerified: Boolean): DeviceProfile =
            SonyProfile.verified(
                requireNotNull(_profile.value),
                protocol,
                model,
                firmware,
                support,
                noiseControlObserved,
                equalizerObserved,
                wearingReadVerified = wearingReadVerified,
                equalizerPresetIds = v1EqualizerCapability?.presetIds
                    ?: v2EqualizerCapability?.presetIds
                    ?: emptySet(),
                equalizerCurveSpec = when (protocol.generation) {
                    SonyProtocolGeneration.V1 ->
                        v1EqualizerCapability?.let(SonyV1EqualizerFeature::curveSpec)
                    SonyProtocolGeneration.V2 ->
                        v2EqualizerCapability?.let(SonyEqualizerFeature::curveSpec)
                },
                equalizerValueLabels = v2EqualizerCapability?.valueLabels
                    ?: SonyEqualizerFeature.valueLabels,
                batteryComponents = _state.value.batteries.keys,
                ambientLevelRange = when (protocol.generation) {
                    SonyProtocolGeneration.V1 -> v1NoiseControlCapability
                        ?.takeIf {
                            it.ambientSettingType ==
                                moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyV1AmbientSettingType.LEVEL_ADJUSTMENT
                        }
                        ?.ambientSteps
                        ?.values
                        ?.maxOrNull()
                        ?.let { SonyV1NoiseControlFeature.AMBIENT_LEVEL_MIN..it }
                    SonyProtocolGeneration.V2 ->
                        v2NoiseControlCapability?.ambientLevelRange
                }.takeIf { noiseControlObserved },
                transparencyVocalEnhancementSupported = noiseControlObserved && when (protocol.generation) {
                    SonyProtocolGeneration.V1 -> v1NoiseControlCapability?.ambientSteps?.keys
                        ?.containsAll(SonyAmbientSoundMode.entries) == true
                    SonyProtocolGeneration.V2 -> true
                },
            )

        _profile.value = verifiedProfile(tableWearingObserved)
        transition(SessionEvent.InitialStateSynchronized)

        // Auto Play is an optional, separate GATT channel. Do not hold back the connection UI,
        // notification or core controls while Android scans for that service.
        if (shouldConnectAutoPlayWearing && connectAutoPlayWearing()) {
            _profile.value = verifiedProfile(wearingReadVerified = true)
        }
    }

    override suspend fun refresh(featureIds: Set<FeatureId>) {
        if (!_connection.value.isProtocolReady) return
        val generation = protocolInfo?.generation ?: return
        val requested = if (featureIds.isEmpty()) {
            _profile.value?.features?.keys
                ?: setOf(FeatureId.BATTERY, FeatureId.FIRMWARE_VERSION)
        } else {
            featureIds
        }
        if (FeatureId.FIRMWARE_VERSION in requested) {
            exchange(
                SonyHandshake.getDeviceInfo(SonyDeviceInfoType.FIRMWARE_VERSION),
                SonyCommand.CONNECT_RET_DEVICE_INFO,
                responsePredicate = {
                    SonyHandshake.matchesDeviceInfo(it, SonyDeviceInfoType.FIRMWARE_VERSION)
                },
            )
        }
        if (FeatureId.BATTERY in requested) {
            observedBatteryTypes.forEach { type ->
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
        if (
            FeatureId.NOISE_CONTROL in requested ||
            FeatureId.AMBIENT_SOUND_LEVEL in requested ||
            FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT in requested
        ) {
            val v1Capability = v1NoiseControlCapability
            if (generation == SonyProtocolGeneration.V1 && v1Capability != null) {
                exchange(
                    SonyV1NoiseControlFeature.query(),
                    SonyCommand.NCASM_RET_PARAM,
                    responsePredicate = SonyV1NoiseControlFeature::matches,
                )
            } else if (generation == SonyProtocolGeneration.V2) {
                exchange(
                    SonyNoiseControlFeature.query(),
                    SonyCommand.NCASM_RET_PARAM,
                    responsePredicate = SonyNoiseControlFeature::matches,
                )
            }
        }
        if (
            FeatureId.WEAR_DETECTION in requested &&
            _profile.value?.capability(FeatureId.WEAR_DETECTION)?.canRead == true
        ) {
            if (autoPlayWearingActive) {
                autoPlayExchange(
                    SonyAutoPlayWearingFeature.query(),
                    SonyAutoPlayWearingFeature.HEADPHONE_STATUS_MESSAGE,
                )
            }
            val support = supportInfo
            val protocol = protocolInfo
            if (
                support != null &&
                protocol != null &&
                SonyProfile.shouldQueryWearing(protocol, support)
            ) {
                exchange(
                    SonyWearingFeature.query(),
                    SonyCommand.SYSTEM_RET_STATUS,
                    table = SonyCommandTable.TABLE2,
                    responsePredicate = SonyWearingFeature::matches,
                )
            }
        }
        if (FeatureId.EQUALIZER in requested) {
            val v1Capability = v1EqualizerCapability
            if (generation == SonyProtocolGeneration.V1 && v1Capability != null) {
                exchange(
                    SonyV1EqualizerFeature.query(),
                    SonyCommand.EQEBB_RET_PARAM,
                    responsePredicate = SonyV1EqualizerFeature::matches,
                )
            } else if (generation == SonyProtocolGeneration.V2) {
                if (v2EqualizerCapability != null) {
                    exchange(
                        SonyEqualizerFeature.query(),
                        SonyCommand.EQEBB_RET_PARAM,
                        responsePredicate = SonyEqualizerFeature::matches,
                    )
                }
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
        if (!_connection.value.isProtocolReady) {
            return terminalFailure(
                id,
                command,
                FailureReason.NOT_CONNECTED,
                "Sony session is not ready",
            )
        }
        val capability = _profile.value?.capability(command.featureId)
        if (capability?.isWritable != true) {
            val reason = if (capability == null || !capability.canWrite) {
                FailureReason.NOT_SUPPORTED
            } else {
                FailureReason.NOT_WRITABLE
            }
            return terminalFailure(
                id,
                command,
                reason,
                "Sony feature is outside the exact write whitelist",
            )
        }
        when (command) {
            is FeatureCommand.SetNoiseControl -> {
                if (command.mode !in SONY_NOISE_CONTROL_MODES) {
                    return terminalFailure(
                        id,
                        command,
                        FailureReason.NOT_SUPPORTED,
                        "Sony noise-control value is not in the verified set",
                    )
                }
                return executeNoiseControl(id, command)
            }
            is FeatureCommand.SetAmbientSoundLevel ->
                return executeAmbientSoundLevel(id, command)
            is FeatureCommand.SetTransparencyVocalEnhancement ->
                return executeTransparencyVocalEnhancement(id, command)
            is FeatureCommand.SetEqualizerPreset ->
                return executeEqualizerPreset(id, command)
            is FeatureCommand.SetEqualizerCurve ->
                return executeEqualizerCurve(id, command)
            else -> {
                return terminalFailure(
                    id,
                    command,
                    FailureReason.NOT_SUPPORTED,
                    "Sony command is not implemented",
                )
            }
        }
    }

    override suspend fun disconnect(cause: DisconnectCause): Unit = lifecycleMutex.withLock {
        if (_connection.value is SessionState.Idle) return
        transition(SessionEvent.DisconnectRequested)
        ackWaiter?.deferred?.cancel()
        responseWaiter?.deferred?.cancel()
        autoPlayResponseWaiter?.deferred?.cancel()
        ackWaiter = null
        responseWaiter = null
        autoPlayResponseWaiter = null
        autoPlayTransport?.close(cause)
        autoPlayIncomingJob?.cancel()
        autoPlayTransport = null
        autoPlayIncomingJob = null
        autoPlayWearingActive = false
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
        val support = supportInfo
        val protocol = protocolInfo
        if (
            support != null &&
            protocol != null &&
            SonyProfile.shouldQueryWearing(protocol, support)
        ) {
            SonyWearingFeature.parse(message)?.let { report ->
                applyWearingReport(
                    report,
                    if (message.command == SonyCommand.SYSTEM_NTFY_STATUS) {
                        ValueSource.NOTIFICATION
                    } else {
                        ValueSource.QUERY_RESPONSE
                    },
                    "table2",
                )
            }
        }
        val noiseControl = when (generation) {
            SonyProtocolGeneration.V1 -> v1NoiseControlCapability?.let {
                SonyV1NoiseControlFeature.parse(message, it)
            }
            SonyProtocolGeneration.V2 -> SonyNoiseControlFeature.parse(message)
            null -> null
        }
        noiseControl?.let { state ->
            applyNoiseControlState(
                state,
                if (message.command == SonyCommand.NCASM_NTFY_PARAM) {
                    ValueSource.NOTIFICATION
                } else {
                    ValueSource.QUERY_RESPONSE
                },
            )
        }
        val equalizer = when (generation) {
            SonyProtocolGeneration.V1 -> v1EqualizerCapability?.let {
                SonyV1EqualizerFeature.parse(message, it, equalizerState?.preset)
            }
            SonyProtocolGeneration.V2 -> v2EqualizerCapability?.let {
                SonyEqualizerFeature.parse(message, it)
            }
            null -> null
        }
        equalizer?.let { state ->
            applyEqualizerState(
                state,
                if (message.command == SonyCommand.EQEBB_NTFY_PARAM) {
                    ValueSource.NOTIFICATION
                } else {
                    ValueSource.QUERY_RESPONSE
                },
            )
        }
        if (message.command == SonyCommand.CONNECT_RET_DEVICE_INFO) {
            SonyHandshake.parseDeviceInfo(message, SonyDeviceInfoType.FIRMWARE_VERSION)?.let {
                applyReport(DeviceReport.Firmware(it), ValueSource.QUERY_RESPONSE)
                _profile.value = _profile.value?.copy(firmware = it)
            }
        }
        responseWaiter?.let { waiter ->
            if (
                message.table == waiter.table &&
                message.command == waiter.expectedCommand &&
                waiter.predicate(message)
            ) {
                waiter.deferred.complete(message)
            }
        }
    }

    private suspend fun exchange(
        payload: ByteArray,
        expectedCommand: Int,
        table: SonyCommandTable = SonyCommandTable.TABLE1,
        responsePredicate: (SonyMdrMessage) -> Boolean = { true },
        onTransportAcknowledged: () -> Unit = {},
    ): SonyMdrMessage? = exchangeDetailed(
        payload,
        expectedCommand,
        table,
        responsePredicate,
        onTransportAcknowledged = onTransportAcknowledged,
    )?.response

    private suspend fun exchangeDetailed(
        payload: ByteArray,
        expectedCommand: Int,
        table: SonyCommandTable = SonyCommandTable.TABLE1,
        responsePredicate: (SonyMdrMessage) -> Boolean = { true },
        responseWaitMillis: Long = responseTimeoutMillis,
        onTransportAcknowledged: () -> Unit = {},
    ): SonyExchangeResult? = exchangeMutex.withLock {
        val active = transport ?: return@withLock null
        val sequence = outgoingSequence and 1
        val ack = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SonyMdrMessage>()
        val ackHolder = AckWaiter(1 - sequence, ack)
        val responseHolder = ResponseWaiter(
            table,
            expectedCommand,
            responsePredicate,
            response,
        )
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
            onTransportAcknowledged()
            SonyExchangeResult(
                response = withTimeoutOrNull(responseWaitMillis) { response.await() },
            )
        } finally {
            if (ackWaiter === ackHolder) ackWaiter = null
            if (responseWaiter === responseHolder) responseWaiter = null
        }
    }

    private fun applyBattery(report: DeviceReport.Batteries, source: ValueSource) {
        val merged = SonyBatteryFeature.merge(_state.value.batteries, report)
        applyReport(DeviceReport.Batteries(merged), source)
    }

    private suspend fun connectAutoPlayWearing(): Boolean {
        val created = try {
            context.transportFactory.create(
                context.candidate.identity,
                SonyProfile.autoPlayGattSpec(),
            )
        } catch (error: Throwable) {
            _events.emit(
                SonySessionEvent.AutoPlayUnavailable(
                    error.message ?: "Auto Play GATT transport creation failed",
                ),
            )
            return false
        }
        autoPlayTransport = created
        autoPlayIncomingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.incoming.collect(::handleAutoPlayMessage)
        }
        try {
            created.open()
        } catch (error: Throwable) {
            _events.emit(
                SonySessionEvent.AutoPlayUnavailable(
                    error.message ?: "Auto Play GATT open failed",
                ),
            )
            closeAutoPlayWearing(created)
            return false
        }
        val openState = created.state.value
        if (openState !is TransportState.Open) {
            val detail = (openState as? TransportState.Failed)?.failure?.detail
                ?: "Auto Play GATT transport did not open"
            _events.emit(SonySessionEvent.AutoPlayUnavailable(detail))
            closeAutoPlayWearing(created)
            return false
        }
        val connected = autoPlayExchange(
            SonyAutoPlayWearingFeature.connect(),
            SonyAutoPlayWearingFeature.CONNECT_MESSAGE,
        ) != null
        if (!connected) {
            closeAutoPlayWearing(created)
            return false
        }
        val status = autoPlayExchange(
            SonyAutoPlayWearingFeature.query(),
            SonyAutoPlayWearingFeature.HEADPHONE_STATUS_MESSAGE,
        )
        autoPlayWearingActive = status?.let(SonyAutoPlayWearingFeature::parse) != null
        if (!autoPlayWearingActive) closeAutoPlayWearing(created)
        return autoPlayWearingActive
    }

    private suspend fun closeAutoPlayWearing(active: ByteTransport) {
        autoPlayResponseWaiter?.deferred?.cancel()
        autoPlayResponseWaiter = null
        autoPlayWearingActive = false
        runCatching { active.close(DisconnectCause.REQUESTED) }
        autoPlayIncomingJob?.cancel()
        autoPlayIncomingJob = null
        if (autoPlayTransport === active) autoPlayTransport = null
    }

    private fun handleAutoPlayMessage(bytes: ByteArray) {
        _events.tryEmit(SonySessionEvent.AutoPlayRx(bytes.copyOf()))
        SonyAutoPlayWearingFeature.parse(bytes)?.let { report ->
            applyWearingReport(report, ValueSource.NOTIFICATION, "auto-play-ble")
        }
        autoPlayResponseWaiter?.let { waiter ->
            if (SonyAutoPlayWearingFeature.matches(bytes, waiter.expectedMessageType)) {
                waiter.deferred.complete(bytes.copyOf())
            }
        }
    }

    private suspend fun autoPlayExchange(
        payload: ByteArray,
        expectedMessageType: Int,
    ): ByteArray? = autoPlayExchangeMutex.withLock {
        val active = autoPlayTransport ?: return@withLock null
        if (active.state.value !is TransportState.Open) return@withLock null
        val response = CompletableDeferred<ByteArray>()
        val holder = AutoPlayResponseWaiter(expectedMessageType, response)
        check(autoPlayResponseWaiter == null) {
            "only one Sony Auto Play request may be in flight"
        }
        autoPlayResponseWaiter = holder
        try {
            _events.emit(SonySessionEvent.AutoPlayTx(payload.copyOf()))
            if (active.write(payload) !is TransportWriteResult.Written) return@withLock null
            withTimeoutOrNull(responseTimeoutMillis) { response.await() }
        } finally {
            if (autoPlayResponseWaiter === holder) autoPlayResponseWaiter = null
        }
    }

    private fun applyWearingReport(
        report: DeviceReport.Wearing,
        source: ValueSource,
        channel: String,
    ) {
        applyReport(report, source)
        _state.value = _state.value.copy(
            vendorStates = _state.value.vendorStates + ("sony.wearing.transport" to channel),
        )
    }

    private fun applyNoiseControlState(state: SonyNoiseControlState, source: ValueSource) {
        noiseControlState = state
        applyReport(DeviceReport.NoiseControl(state.mode), source)
        applyReport(DeviceReport.AmbientSoundLevel(state.ambientLevel), source)
        applyReport(
            DeviceReport.TransparencyVocalEnhancement(
                state.ambientSoundMode == SonyAmbientSoundMode.VOICE,
            ),
            source,
        )
    }

    private fun applyEqualizerState(state: SonyEqualizerState, source: ValueSource) {
        equalizerState = state
        val curve = when (protocolInfo?.generation) {
            SonyProtocolGeneration.V1 -> v1EqualizerCapability?.let {
                SonyV1EqualizerFeature.toDomainCurve(state, it)
            }
            SonyProtocolGeneration.V2 -> v2EqualizerCapability?.let {
                SonyEqualizerFeature.toDomainCurve(state, it)
            }
            null -> null
        }
        applyReport(DeviceReport.Equalizer(state.preset, curve), source)
    }

    private fun applyReport(report: DeviceReport, source: ValueSource) {
        _state.value = HeadphoneStateReducer.reduce(
            _state.value,
            StateUpdate.DeviceReported(report, source, clock()),
        )
    }

    private suspend fun executeNoiseControl(
        requestId: RequestId,
        command: FeatureCommand.SetNoiseControl,
    ): OperationResult = operationMutex.withLock {
        val original = noiseControlState
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.NOT_WRITABLE,
                "Sony noise-control state is not confirmed",
            )
        val payload = encodeNoiseControlSet(command.mode, original)
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.VALUE_OUT_OF_RANGE,
                "Sony noise-control value cannot be encoded",
            )

        apply(StateUpdate.LocalPending(command, clock()))
        emit(requestId, command, OperationPhase.QUEUED)
        emit(requestId, command, OperationPhase.SENT)
        val setResult = exchangeDetailed(
            payload,
            SonyCommand.NCASM_NTFY_PARAM,
            responsePredicate = ::noiseControlMatches,
            responseWaitMillis = NOTIFICATION_GRACE_MS,
            onTransportAcknowledged = {
                emit(requestId, command, OperationPhase.TRANSPORT_ACKNOWLEDGED)
            },
        )
        if (setResult == null) {
            abandon(command.featureId)
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.TIMEOUT,
                "Sony SET transport acknowledgement timed out",
                OperationPhase.TIMED_OUT,
            )
        }
        val notified = setResult.response?.let(::parseNoiseControl)
        if (
            notified?.mode == command.mode &&
            notified.ambientSoundMode == original.ambientSoundMode
        ) {
            emit(requestId, command, OperationPhase.STATE_CONFIRMED)
            OperationResult(requestId, OperationPhase.STATE_CONFIRMED)
        } else {
            val readback = exchange(
                noiseControlQuery(),
                SonyCommand.NCASM_RET_PARAM,
                responsePredicate = ::noiseControlMatches,
            )?.let(::parseNoiseControl)
            if (
                readback?.mode == command.mode &&
                readback.ambientSoundMode == original.ambientSoundMode
            ) {
                applyNoiseControlState(readback, ValueSource.READ_BACK)
                emit(requestId, command, OperationPhase.READ_BACK_CONFIRMED)
                OperationResult(requestId, OperationPhase.READ_BACK_CONFIRMED)
            } else {
                abandon(command.featureId)
                terminalFailure(
                    requestId,
                    command,
                    FailureReason.TIMEOUT,
                    "Sony NC/ASM SET was not confirmed by 0x69 or explicit GET readback",
                    OperationPhase.TIMED_OUT,
                )
            }
        }
    }

    private suspend fun executeAmbientSoundLevel(
        requestId: RequestId,
        command: FeatureCommand.SetAmbientSoundLevel,
    ): OperationResult = operationMutex.withLock {
        val original = noiseControlState
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.NOT_WRITABLE,
                "Sony NC/ASM state is not confirmed",
            )
        if (original.mode != NoiseControlMode.TRANSPARENCY) {
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.NOT_WRITABLE,
                "Ambient level can only be changed while ambient sound is active",
            )
        }
        val payload = encodeAmbientLevelSet(command.level, original)
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.VALUE_OUT_OF_RANGE,
                "Sony ambient level must be in " +
                    "${SonyNoiseControlFeature.AMBIENT_LEVEL_MIN}.." +
                    SonyNoiseControlFeature.AMBIENT_LEVEL_MAX,
            )

        apply(StateUpdate.LocalPending(command, clock()))
        emit(requestId, command, OperationPhase.QUEUED)
        emit(requestId, command, OperationPhase.SENT)
        val setResult = exchangeDetailed(
            payload,
            SonyCommand.NCASM_NTFY_PARAM,
            responsePredicate = ::noiseControlMatches,
            responseWaitMillis = NOTIFICATION_GRACE_MS,
            onTransportAcknowledged = {
                emit(requestId, command, OperationPhase.TRANSPORT_ACKNOWLEDGED)
            },
        )
        if (setResult == null) {
            abandon(command.featureId)
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.TIMEOUT,
                "Sony ambient-level SET transport acknowledgement timed out",
                OperationPhase.TIMED_OUT,
            )
        }
        val notified = setResult.response?.let(::parseNoiseControl)
        if (notified.matchesAmbientLevel(command.level, original.ambientSoundMode)) {
            emit(requestId, command, OperationPhase.STATE_CONFIRMED)
            OperationResult(requestId, OperationPhase.STATE_CONFIRMED)
        } else {
            val readback = exchange(
                noiseControlQuery(),
                SonyCommand.NCASM_RET_PARAM,
                responsePredicate = ::noiseControlMatches,
            )?.let(::parseNoiseControl)
            if (readback.matchesAmbientLevel(command.level, original.ambientSoundMode)) {
                applyNoiseControlState(requireNotNull(readback), ValueSource.READ_BACK)
                emit(requestId, command, OperationPhase.READ_BACK_CONFIRMED)
                OperationResult(requestId, OperationPhase.READ_BACK_CONFIRMED)
            } else {
                abandon(command.featureId)
                terminalFailure(
                    requestId,
                    command,
                    FailureReason.TIMEOUT,
                    "Sony ambient-level SET was not confirmed by 0x69 or explicit GET readback",
                    OperationPhase.TIMED_OUT,
                )
            }
        }
    }

    private suspend fun executeTransparencyVocalEnhancement(
        requestId: RequestId,
        command: FeatureCommand.SetTransparencyVocalEnhancement,
    ): OperationResult = operationMutex.withLock {
        val original = noiseControlState
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.NOT_WRITABLE,
                "Sony NC/ASM state is not confirmed",
            )
        if (original.mode != NoiseControlMode.TRANSPARENCY) {
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.NOT_WRITABLE,
                "Voice focus can only be changed while ambient sound is active",
            )
        }
        val expectedMode = if (command.enabled) {
            SonyAmbientSoundMode.VOICE
        } else {
            SonyAmbientSoundMode.NORMAL
        }
        val payload = encodeAmbientSoundModeSet(expectedMode, original)
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.VALUE_OUT_OF_RANGE,
                "Sony ambient sound mode cannot be encoded",
            )

        apply(StateUpdate.LocalPending(command, clock()))
        emit(requestId, command, OperationPhase.QUEUED)
        emit(requestId, command, OperationPhase.SENT)
        val setResult = exchangeDetailed(
            payload,
            SonyCommand.NCASM_NTFY_PARAM,
            responsePredicate = ::noiseControlMatches,
            responseWaitMillis = NOTIFICATION_GRACE_MS,
            onTransportAcknowledged = {
                emit(requestId, command, OperationPhase.TRANSPORT_ACKNOWLEDGED)
            },
        )
        if (setResult == null) {
            abandon(command.featureId)
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.TIMEOUT,
                "Sony ambient-mode SET transport acknowledgement timed out",
                OperationPhase.TIMED_OUT,
            )
        }
        val notified = setResult.response?.let(::parseNoiseControl)
        if (notified.matchesAmbientSoundMode(expectedMode, original.ambientLevel)) {
            emit(requestId, command, OperationPhase.STATE_CONFIRMED)
            OperationResult(requestId, OperationPhase.STATE_CONFIRMED)
        } else {
            val readback = exchange(
                noiseControlQuery(),
                SonyCommand.NCASM_RET_PARAM,
                responsePredicate = ::noiseControlMatches,
            )?.let(::parseNoiseControl)
            if (readback.matchesAmbientSoundMode(expectedMode, original.ambientLevel)) {
                applyNoiseControlState(requireNotNull(readback), ValueSource.READ_BACK)
                emit(requestId, command, OperationPhase.READ_BACK_CONFIRMED)
                OperationResult(requestId, OperationPhase.READ_BACK_CONFIRMED)
            } else {
                abandon(command.featureId)
                terminalFailure(
                    requestId,
                    command,
                    FailureReason.TIMEOUT,
                    "Sony ambient-mode SET was not confirmed by 0x69 or explicit GET readback",
                    OperationPhase.TIMED_OUT,
                )
            }
        }
    }

    private suspend fun executeEqualizerPreset(
        requestId: RequestId,
        command: FeatureCommand.SetEqualizerPreset,
    ): OperationResult = operationMutex.withLock {
        if (equalizerState == null) {
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.NOT_WRITABLE,
                "Sony equalizer state is not confirmed",
            )
        }
        val payload = encodeEqualizerSet(command.preset)
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.VALUE_OUT_OF_RANGE,
                "Sony EQ preset is outside the dynamically verified preset set",
            )

        apply(StateUpdate.LocalPending(command, clock()))
        emit(requestId, command, OperationPhase.QUEUED)
        emit(requestId, command, OperationPhase.SENT)
        val setResult = exchangeDetailed(
            payload,
            SonyCommand.EQEBB_NTFY_PARAM,
            responsePredicate = ::equalizerMatches,
            responseWaitMillis = NOTIFICATION_GRACE_MS,
            onTransportAcknowledged = {
                emit(requestId, command, OperationPhase.TRANSPORT_ACKNOWLEDGED)
            },
        )
        if (setResult == null) {
            abandon(command.featureId)
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.TIMEOUT,
                "Sony EQ SET transport acknowledgement timed out",
                OperationPhase.TIMED_OUT,
            )
        }
        val notified = setResult.response?.let(::parseEqualizer)
        if (notified?.preset == command.preset) {
            emit(requestId, command, OperationPhase.STATE_CONFIRMED)
        }

        val readback = exchange(
            equalizerQuery(),
            SonyCommand.EQEBB_RET_PARAM,
            responsePredicate = ::equalizerMatches,
        )?.let(::parseEqualizer)
        if (readback?.preset == command.preset) {
            applyEqualizerState(readback, ValueSource.READ_BACK)
            emit(requestId, command, OperationPhase.READ_BACK_CONFIRMED)
            OperationResult(requestId, OperationPhase.READ_BACK_CONFIRMED)
        } else {
            abandon(command.featureId)
            terminalFailure(
                requestId,
                command,
                FailureReason.TIMEOUT,
                "Sony EQ write was not confirmed by explicit GET readback",
                OperationPhase.TIMED_OUT,
            )
        }
    }

    private suspend fun executeEqualizerCurve(
        requestId: RequestId,
        command: FeatureCommand.SetEqualizerCurve,
    ): OperationResult = operationMutex.withLock {
        val original = equalizerState
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.NOT_WRITABLE,
                "Sony equalizer state is not confirmed",
            )
        val payload = encodeEqualizerCurveSet(command.curve, original.preset)
            ?: return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.VALUE_OUT_OF_RANGE,
                "Sony EQ curve does not match the active writable slot or capability",
            )

        apply(StateUpdate.LocalPending(command, clock()))
        emit(requestId, command, OperationPhase.QUEUED)
        emit(requestId, command, OperationPhase.SENT)
        val setResult = exchangeDetailed(
            payload,
            SonyCommand.EQEBB_NTFY_PARAM,
            responsePredicate = ::equalizerMatches,
            responseWaitMillis = NOTIFICATION_GRACE_MS,
            onTransportAcknowledged = {
                emit(requestId, command, OperationPhase.TRANSPORT_ACKNOWLEDGED)
            },
        )
        if (setResult == null) {
            abandon(command.featureId)
            return@withLock terminalFailure(
                requestId,
                command,
                FailureReason.TIMEOUT,
                "Sony EQ curve SET transport acknowledgement timed out",
                OperationPhase.TIMED_OUT,
            )
        }
        val notified = setResult.response?.let(::parseEqualizer)
        if (notified.toDomainCurve() == command.curve) {
            emit(requestId, command, OperationPhase.STATE_CONFIRMED)
        }

        val readback = exchange(
            equalizerQuery(),
            SonyCommand.EQEBB_RET_PARAM,
            responsePredicate = ::equalizerMatches,
        )?.let(::parseEqualizer)
        if (readback.toDomainCurve() == command.curve) {
            applyEqualizerState(requireNotNull(readback), ValueSource.READ_BACK)
            emit(requestId, command, OperationPhase.READ_BACK_CONFIRMED)
            OperationResult(requestId, OperationPhase.READ_BACK_CONFIRMED)
        } else {
            abandon(command.featureId)
            terminalFailure(
                requestId,
                command,
                FailureReason.TIMEOUT,
                "Sony EQ curve write was not confirmed by explicit GET readback",
                OperationPhase.TIMED_OUT,
            )
        }
    }

    private fun SonyEqualizerState?.toDomainCurve(): EqualizerCurve? =
        this?.let { state ->
            when (protocolInfo?.generation) {
                SonyProtocolGeneration.V1 -> v1EqualizerCapability?.let { capability ->
                    SonyV1EqualizerFeature.toDomainCurve(state, capability)
                }
                SonyProtocolGeneration.V2 -> v2EqualizerCapability?.let {
                    SonyEqualizerFeature.toDomainCurve(state, it)
                }
                null -> null
            }
        }

    private fun SonyNoiseControlState?.matchesAmbientLevel(
        level: Int,
        expectedAmbientSoundMode: SonyAmbientSoundMode,
    ): Boolean =
        this?.mode == NoiseControlMode.TRANSPARENCY &&
            ambientLevel == level &&
            ambientSoundMode == expectedAmbientSoundMode

    private fun SonyNoiseControlState?.matchesAmbientSoundMode(
        mode: SonyAmbientSoundMode,
        expectedAmbientLevel: Int,
    ): Boolean =
            this?.mode == NoiseControlMode.TRANSPARENCY &&
            ambientSoundMode == mode &&
            ambientLevel == expectedAmbientLevel

    private fun encodeNoiseControlSet(
        mode: NoiseControlMode,
        current: SonyNoiseControlState,
    ): ByteArray? = when (protocolInfo?.generation) {
        SonyProtocolGeneration.V1 -> v1NoiseControlCapability?.let {
            SonyV1NoiseControlFeature.set(mode, current, it)
        }
        SonyProtocolGeneration.V2 -> SonyNoiseControlFeature.set(mode, current)
        null -> null
    }

    private fun encodeAmbientLevelSet(
        level: Int,
        current: SonyNoiseControlState,
    ): ByteArray? = when (protocolInfo?.generation) {
        SonyProtocolGeneration.V1 -> v1NoiseControlCapability?.let {
            SonyV1NoiseControlFeature.setAmbientLevel(level, current, it)
        }
        SonyProtocolGeneration.V2 -> SonyNoiseControlFeature.setAmbientLevel(level, current)
        null -> null
    }

    private fun encodeAmbientSoundModeSet(
        mode: SonyAmbientSoundMode,
        current: SonyNoiseControlState,
    ): ByteArray? = when (protocolInfo?.generation) {
        SonyProtocolGeneration.V1 -> v1NoiseControlCapability?.let {
            SonyV1NoiseControlFeature.setAmbientSoundMode(mode, current, it)
        }
        SonyProtocolGeneration.V2 -> SonyNoiseControlFeature.setAmbientSoundMode(mode, current)
        null -> null
    }

    private fun noiseControlQuery(): ByteArray = when (protocolInfo?.generation) {
        SonyProtocolGeneration.V1 -> SonyV1NoiseControlFeature.query()
        else -> SonyNoiseControlFeature.query()
    }

    private fun parseNoiseControl(message: SonyMdrMessage): SonyNoiseControlState? =
        when (protocolInfo?.generation) {
            SonyProtocolGeneration.V1 -> v1NoiseControlCapability?.let {
                SonyV1NoiseControlFeature.parse(message, it)
            }
            SonyProtocolGeneration.V2 -> SonyNoiseControlFeature.parse(message)
            null -> null
        }

    private fun noiseControlMatches(message: SonyMdrMessage): Boolean =
        when (protocolInfo?.generation) {
            SonyProtocolGeneration.V1 -> SonyV1NoiseControlFeature.matches(message)
            SonyProtocolGeneration.V2 -> SonyNoiseControlFeature.matches(message)
            null -> false
        }

    private fun encodeEqualizerSet(
        preset: moe.chenxy.headphones.core.feature.EqualizerPreset,
    ): ByteArray? = when (protocolInfo?.generation) {
        SonyProtocolGeneration.V1 -> v1EqualizerCapability?.let {
            SonyV1EqualizerFeature.set(preset, it)
        }
        SonyProtocolGeneration.V2 -> v2EqualizerCapability?.let {
            SonyEqualizerFeature.set(preset, it)
        }
        null -> null
    }

    private fun encodeEqualizerCurveSet(
        curve: EqualizerCurve,
        activePreset: moe.chenxy.headphones.core.feature.EqualizerPreset,
    ): ByteArray? = when (protocolInfo?.generation) {
        SonyProtocolGeneration.V1 -> v1EqualizerCapability?.let {
            SonyV1EqualizerFeature.setCurve(curve, activePreset, it)
        }
        SonyProtocolGeneration.V2 -> v2EqualizerCapability?.let {
            SonyEqualizerFeature.setCurve(curve, activePreset, it)
        }
        null -> null
    }

    private fun equalizerQuery(): ByteArray = when (protocolInfo?.generation) {
        SonyProtocolGeneration.V1 -> SonyV1EqualizerFeature.query()
        else -> SonyEqualizerFeature.query()
    }

    private fun parseEqualizer(message: SonyMdrMessage): SonyEqualizerState? =
        when (protocolInfo?.generation) {
            SonyProtocolGeneration.V1 -> v1EqualizerCapability?.let {
                SonyV1EqualizerFeature.parse(message, it, equalizerState?.preset)
            }
            SonyProtocolGeneration.V2 -> v2EqualizerCapability?.let {
                SonyEqualizerFeature.parse(message, it)
            }
            null -> null
        }

    private fun equalizerMatches(message: SonyMdrMessage): Boolean =
        when (protocolInfo?.generation) {
            SonyProtocolGeneration.V1 -> SonyV1EqualizerFeature.matches(message)
            SonyProtocolGeneration.V2 -> SonyEqualizerFeature.matches(message)
            null -> false
        }

    private fun apply(update: StateUpdate) {
        _state.value = HeadphoneStateReducer.reduce(_state.value, update)
    }

    private fun abandon(featureId: FeatureId) {
        apply(StateUpdate.PendingAbandoned(featureId, clock()))
    }

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
        val predicate: (SonyMdrMessage) -> Boolean,
        val deferred: CompletableDeferred<SonyMdrMessage>,
    )

    private data class AutoPlayResponseWaiter(
        val expectedMessageType: Int,
        val deferred: CompletableDeferred<ByteArray>,
    )

    private data class SonyExchangeResult(
        val response: SonyMdrMessage?,
    )

    companion object {
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 1_500L
        const val DEFAULT_TRANSPORT_SETTLE_DELAY_MS = 300L
        private const val INITIAL_PROTOCOL_ATTEMPTS = 3
        private const val NOTIFICATION_GRACE_MS = 400L
        private val SONY_NOISE_CONTROL_MODES = setOf(
            NoiseControlMode.OFF,
            NoiseControlMode.NOISE_CANCELLATION,
            NoiseControlMode.TRANSPARENCY,
        )

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

        fun resolveTransport(candidate: DeviceCandidate): SonyTransportRoute? {
            val advertisedGatt = candidate.advertisedUuids.any {
                it.equals(
                    SonyProfile.SONY_GATT_TANDEM_V2_HPC_SERVICE_UUID,
                    ignoreCase = true,
                )
            }
            val bondedNameRoute = candidate.bonded && isSonyNameHint(candidate.displayName)
            val gattEligible =
                TransportKind.BLE_GATT in candidate.availableTransports &&
                    (advertisedGatt || bondedNameRoute)
            val sppUuid = resolveServiceUuid(candidate)
            if (
                gattEligible &&
                (
                    advertisedGatt ||
                        sppUuid?.equals(SonyProfile.SONY_SPP_V2_UUID, ignoreCase = true) == true
                    )
            ) {
                return SonyTransportRoute(
                    TransportKind.BLE_GATT,
                    SonyProfile.gattSpec(),
                    "gatt-tandem-v2-hpc",
                    if (advertisedGatt) {
                        DetectionBasis.ADVERTISED_SERVICE
                    } else {
                        DetectionBasis.BONDED_SERVICE_VALIDATION
                    },
                )
            }
            if (sppUuid != null && TransportKind.CLASSIC_SPP in candidate.availableTransports) {
                return SonyTransportRoute(
                    TransportKind.CLASSIC_SPP,
                    TransportSpec.Spp(sppUuid, secure = true),
                    SonyProfile.sppTransportLabel(sppUuid),
                    DetectionBasis.ADVERTISED_SERVICE,
                )
            }
            if (gattEligible) {
                return SonyTransportRoute(
                    TransportKind.BLE_GATT,
                    SonyProfile.gattSpec(),
                    "gatt-tandem-v2-hpc",
                    if (advertisedGatt) {
                        DetectionBasis.ADVERTISED_SERVICE
                    } else {
                        DetectionBasis.BONDED_SERVICE_VALIDATION
                    },
                )
            }
            return null
        }

        fun isSonyNameHint(name: String?): Boolean {
            val value = name.orEmpty()
            return value.startsWith("WH-", ignoreCase = true) ||
                value.startsWith("WF-", ignoreCase = true) ||
                value.startsWith("WI-", ignoreCase = true) ||
                value.contains("LinkBuds", ignoreCase = true)
        }
    }
}

data class SonyTransportRoute(
    val kind: TransportKind,
    val spec: TransportSpec,
    val label: String,
    val detectionBasis: DetectionBasis,
)

enum class DetectionBasis {
    ADVERTISED_SERVICE,
    BONDED_SERVICE_VALIDATION,
}
