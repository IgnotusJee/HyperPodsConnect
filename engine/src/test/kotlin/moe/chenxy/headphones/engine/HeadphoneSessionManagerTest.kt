package moe.chenxy.headphones.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.chenxy.headphones.core.device.DetectionConfidence
import moe.chenxy.headphones.core.device.DetectionEvidence
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.driver.HeadphoneDriverProvider
import moe.chenxy.headphones.core.driver.HeadphoneSession
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.BatteryState
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationPhase
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.operation.RequestId
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.FailureCategory
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportFactory
import moe.chenxy.headphones.core.transport.TransportSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeadphoneSessionManagerTest {

    @Test
    fun `automatic connect rejects name hint without mutating session state`() = runTest {
        val provider = FakeProvider(confidence = DetectionConfidence.HINT)
        val manager = manager(provider)

        assertNull(manager.autoConnect(candidate("11:22:33:44:55:66"), unusedFactory))
        assertTrue(provider.sessions.isEmpty())
        assertNull(manager.snapshot.value)
    }

    @Test
    fun `automatic connect accepts transport evidence`() = runTest {
        val provider = FakeProvider(confidence = DetectionConfidence.TRANSPORT_EVIDENCE)
        val manager = manager(provider)

        assertEquals(1L, manager.autoConnect(candidate("11:22:33:44:55:66"), unusedFactory))
        assertEquals(1, provider.sessions.size)
    }

    @Test
    fun `explicit connect still accepts name hint`() = runTest {
        val provider = FakeProvider(confidence = DetectionConfidence.HINT)
        val manager = manager(provider)

        assertEquals(1L, manager.connect(candidate("11:22:33:44:55:66"), unusedFactory))
        assertEquals(1, provider.sessions.size)
    }

    @Test
    fun `duplicate connect reuses the active session and generation`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        val candidate = candidate("11:22:33:44:55:66")

        val first = manager.connect(candidate, unusedFactory)
        val second = manager.connect(candidate, unusedFactory)
        runCurrent()

        assertEquals(first, second)
        assertEquals(1, provider.sessions.size)
        assertTrue(manager.snapshot.value?.connection is SessionState.Ready)
    }

    @Test
    fun `switching device closes old session before publishing the new one`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)

        val firstGeneration = manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val old = provider.sessions.single()
        val secondGeneration = manager.connect(candidate("AA:BB:CC:DD:EE:FF"), unusedFactory)
        runCurrent()

        assertNotEquals(firstGeneration, secondGeneration)
        assertEquals(DisconnectCause.SUPERSEDED, old.disconnectCause)
        assertEquals(DeviceId.fromAddress("AA:BB:CC:DD:EE:FF"), manager.snapshot.value?.deviceId)
    }

    @Test
    fun `late state from an old generation cannot pollute the new snapshot`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val old = provider.sessions.single()
        manager.connect(candidate("AA:BB:CC:DD:EE:FF"), unusedFactory)
        val currentGeneration = manager.snapshot.value!!.generationId

        old.emitState(HeadphoneState(batteries = mapOf(BatteryComponent.LEFT to BatteryState(1, false))))
        runCurrent()

        assertEquals(currentGeneration, manager.snapshot.value!!.generationId)
        assertTrue(manager.snapshot.value!!.state.batteries.isEmpty())
    }

    @Test
    fun `late operation from an old generation cannot replace current operation`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val old = provider.sessions.single()
        manager.connect(candidate("AA:BB:CC:DD:EE:FF"), unusedFactory)

        old.emitOperation("old")
        runCurrent()

        assertEquals(null, manager.snapshot.value!!.lastOperation)
    }

    @Test
    fun `snapshot contains profile state connection and rebased operation`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        val generation = manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val session = provider.sessions.single()
        session.emitState(
            HeadphoneState(batteries = mapOf(BatteryComponent.LEFT to BatteryState(88, false))),
        )
        session.emitOperation("write-1")
        runCurrent()

        val snapshot = manager.snapshot.value!!
        assertEquals(generation, snapshot.generationId)
        assertTrue(snapshot.connection is SessionState.Ready)
        assertSame(session.profile.value, snapshot.profile)
        assertEquals(88, snapshot.state.batteries[BatteryComponent.LEFT]?.level)
        assertEquals(generation, snapshot.lastOperation?.generationId)
    }

    @Test
    fun `snapshot ordering remains strict when callbacks share a clock millisecond`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider, clock = { 1_000L })
        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        runCurrent()
        val beforeOperation = manager.snapshot.value!!.emittedAtMillis

        provider.sessions.single().emitOperation("write-same-millisecond")
        runCurrent()

        val afterOperation = manager.snapshot.value!!
        assertTrue(afterOperation.emittedAtMillis > beforeOperation)
        assertEquals("write-same-millisecond", afterOperation.lastOperation?.requestId?.value)
    }

    @Test
    fun `caller request id reaches session result operation and snapshot`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val requestId = RequestId("ipc-request-42")

        val result = manager.execute(FeatureCommand.SetLowLatency(true), requestId)
        runCurrent()

        assertEquals(requestId, provider.sessions.single().lastExecuteRequestId)
        assertEquals(requestId, result.requestId)
        assertEquals(requestId, manager.snapshot.value?.lastOperation?.requestId)
        assertEquals(OperationPhase.READ_BACK_CONFIRMED, manager.snapshot.value?.lastOperation?.phase)
    }

    @Test
    fun `unavailable session failure preserves caller request id`() = runTest {
        val manager = manager(FakeProvider())
        val requestId = RequestId("ipc-no-session")

        val result = manager.execute(FeatureCommand.SetLowLatency(true), requestId)

        assertEquals(requestId, result.requestId)
        assertEquals(moe.chenxy.headphones.core.operation.FailureReason.NOT_CONNECTED, result.failure)
    }

    @Test
    fun `driver rejection timeout and cancellation preserve caller request ids`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val session = provider.sessions.single()
        val outcomes = listOf(
            OperationPhase.FAILED to moe.chenxy.headphones.core.operation.FailureReason.DEVICE_REJECTED,
            OperationPhase.TIMED_OUT to moe.chenxy.headphones.core.operation.FailureReason.TIMEOUT,
            OperationPhase.CANCELLED to moe.chenxy.headphones.core.operation.FailureReason.CANCELLED,
        )

        outcomes.forEachIndexed { index, (phase, failure) ->
            val requestId = RequestId("ipc-terminal-$index")
            session.nextExecutePhase = phase
            session.nextExecuteFailure = failure

            val result = manager.execute(FeatureCommand.SetLowLatency(true), requestId)
            runCurrent()

            assertEquals(requestId, result.requestId)
            assertEquals(phase, result.phase)
            assertEquals(failure, result.failure)
            assertEquals(requestId, manager.snapshot.value?.lastOperation?.requestId)
            assertEquals(phase, manager.snapshot.value?.lastOperation?.phase)
        }
    }

    @Test
    fun `superseded generation preserves id and cannot publish its late terminal event`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val old = provider.sessions.single()
        old.executeStarted = CompletableDeferred()
        old.executeGate = CompletableDeferred()
        val requestId = RequestId("ipc-old-generation")

        val pending = async {
            manager.execute(FeatureCommand.SetLowLatency(true), requestId)
        }
        old.executeStarted!!.await()
        manager.connect(candidate("AA:BB:CC:DD:EE:FF"), unusedFactory)
        old.executeGate!!.complete(Unit)
        val result = pending.await()
        runCurrent()

        assertEquals(requestId, result.requestId)
        assertEquals(
            moe.chenxy.headphones.core.operation.FailureReason.GENERATION_INVALIDATED,
            result.failure,
        )
        assertNull(manager.snapshot.value?.lastOperation)
    }

    @Test
    fun `disconnect retains last state as stale and prevents reconnect`() = runTest {
        val provider = FakeProvider()
        val manager = manager(provider)
        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        val session = provider.sessions.single()
        session.emitState(
            HeadphoneState(
                lowLatency = moe.chenxy.headphones.core.feature.FeatureValue<Boolean>()
                    .withConfirmed(
                        true,
                        moe.chenxy.headphones.core.feature.ValueSource.QUERY_RESPONSE,
                        1,
                    ),
            ),
        )
        runCurrent()

        manager.disconnect()

        assertTrue(manager.snapshot.value?.connection is SessionState.Idle)
        assertEquals(true, manager.snapshot.value?.state?.lowLatency?.stale)
        assertEquals(DisconnectCause.REQUESTED, session.disconnectCause)
    }

    @Test
    fun `retryable failure creates bounded new generations`() = runTest {
        val provider = FakeProvider(connectFailure = DisconnectCause.LINK_LOST)
        val manager = HeadphoneSessionManager(
            DriverRegistry(listOf(provider)),
            backgroundScope,
            reconnectPolicy = ReconnectPolicy(
                maxAttempts = 2,
                initialDelayMillis = 0,
                maxDelayMillis = 0,
            ),
            delayForReconnect = {},
        )

        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        repeat(4) { runCurrent() }

        assertEquals(3, provider.sessions.size)
        assertEquals(3L, manager.snapshot.value?.generationId)
        assertTrue(manager.snapshot.value?.connection is SessionState.Failed)
    }

    @Test
    fun `non retryable failure never loops`() = runTest {
        val provider = FakeProvider(connectFailure = DisconnectCause.PERMISSION_DENIED)
        val manager = manager(provider)

        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        advanceUntilIdle()

        assertEquals(1, provider.sessions.size)
    }

    @Test
    fun `switching device cancels pending reconnect before starting replacement`() = runTest {
        val delayStarted = CompletableDeferred<Unit>()
        val holdReconnect = CompletableDeferred<Unit>()
        val provider = FakeProvider(
            connectFailures = listOf(DisconnectCause.LINK_LOST, null),
        )
        val manager = HeadphoneSessionManager(
            DriverRegistry(listOf(provider)),
            backgroundScope,
            reconnectPolicy = ReconnectPolicy(
                maxAttempts = 1,
                initialDelayMillis = 1,
                maxDelayMillis = 1,
            ),
            delayForReconnect = {
                delayStarted.complete(Unit)
                holdReconnect.await()
            },
        )

        manager.connect(candidate("11:22:33:44:55:66"), unusedFactory)
        runCurrent()
        assertTrue(delayStarted.isCompleted)

        manager.connect(candidate("AA:BB:CC:DD:EE:FF"), unusedFactory)
        runCurrent()

        assertEquals(2, provider.sessions.size)
        assertEquals(DeviceId.fromAddress("AA:BB:CC:DD:EE:FF"), manager.snapshot.value?.deviceId)
        assertTrue(manager.snapshot.value?.connection is SessionState.Ready)
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        provider: FakeProvider,
        clock: () -> Long = System::currentTimeMillis,
    ) =
        HeadphoneSessionManager(
            DriverRegistry(listOf(provider)),
            backgroundScope,
            reconnectPolicy = ReconnectPolicy(maxAttempts = 0),
            clock = clock,
        )

    private fun candidate(address: String) = DeviceCandidate(
        identity = DeviceIdentity(
            DeviceId.fromAddress(address),
            VendorId.OPPO,
            address,
        ),
        displayName = "OPPO Enco Test",
        bonded = true,
        advertisedUuids = setOf("oppo-spp"),
        availableTransports = setOf(TransportKind.CLASSIC_SPP),
    )

    private companion object {
        val unusedFactory = object : TransportFactory {
            override suspend fun create(
                device: DeviceIdentity,
                spec: TransportSpec,
            ): ByteTransport = error("fake provider creates no transport")
        }
    }
}

private class FakeProvider(
    private val connectFailure: DisconnectCause? = null,
    private val connectFailures: List<DisconnectCause?>? = null,
    private val confidence: DetectionConfidence = DetectionConfidence.TRANSPORT_EVIDENCE,
) : HeadphoneDriverProvider {
    override val vendorId: VendorId = VendorId.OPPO
    val sessions = mutableListOf<FakeSession>()

    override fun inspect(candidate: DeviceCandidate) = DetectionEvidence(
        vendorId,
        confidence,
        listOf("test"),
        listOf(TransportKind.CLASSIC_SPP),
    )

    override suspend fun createSession(context: DriverSessionContext): HeadphoneSession {
        val failure = connectFailures?.getOrNull(sessions.size) ?: connectFailure
        return FakeSession(context.candidate, failure).also(sessions::add)
    }
}

private class FakeSession(
    private val candidate: DeviceCandidate,
    private val connectFailure: DisconnectCause?,
) : HeadphoneSession {
    private val _connection = MutableStateFlow<SessionState>(
        SessionState.Idle(candidate.identity.id, 1),
    )
    override val connection: StateFlow<SessionState> = _connection.asStateFlow()
    private val _profile = MutableStateFlow<DeviceProfile?>(null)
    override val profile: StateFlow<DeviceProfile?> = _profile.asStateFlow()
    private val _state = MutableStateFlow(HeadphoneState())
    override val state: StateFlow<HeadphoneState> = _state.asStateFlow()
    private val _operations = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 8)
    override val operations: Flow<OperationEvent> = _operations.asSharedFlow()
    var disconnectCause: DisconnectCause? = null
    var lastExecuteRequestId: RequestId? = null
    var executeStarted: CompletableDeferred<Unit>? = null
    var executeGate: CompletableDeferred<Unit>? = null
    var nextExecutePhase = OperationPhase.READ_BACK_CONFIRMED
    var nextExecuteFailure: moe.chenxy.headphones.core.operation.FailureReason? = null

    override suspend fun connect() {
        _connection.value = if (connectFailure == null) {
            SessionState.Ready(candidate.identity.id, 1, TransportKind.CLASSIC_SPP)
        } else {
            SessionState.Failed(
                candidate.identity.id,
                1,
                FailureCategory.TRANSPORT,
                connectFailure,
            )
        }
    }

    override suspend fun refresh(featureIds: Set<FeatureId>) = Unit

    override suspend fun execute(command: FeatureCommand, requestId: RequestId?): OperationResult {
        val id = requestId ?: RequestId("fake")
        lastExecuteRequestId = id
        executeStarted?.complete(Unit)
        executeGate?.await()
        _operations.emit(
            OperationEvent(
                id,
                command,
                nextExecutePhase,
                1,
                1,
                nextExecuteFailure,
            ),
        )
        return OperationResult(id, nextExecutePhase, nextExecuteFailure)
    }

    override suspend fun disconnect(cause: DisconnectCause) {
        disconnectCause = cause
        _connection.value = SessionState.Idle(candidate.identity.id, 1)
    }

    fun emitState(value: HeadphoneState) {
        _state.value = value
    }

    fun emitOperation(requestId: String) {
        _operations.tryEmit(
            OperationEvent(
                RequestId(requestId),
                FeatureCommand.RefreshAll,
                OperationPhase.SENT,
                1,
                1,
            ),
        )
    }
}
