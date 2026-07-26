package moe.chenxy.headphones.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.driver.HeadphoneSession
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.operation.FailureReason
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.operation.RequestId
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.FailureCategory
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.core.transport.TransportFactory

/**
 * Owns driver selection and the sole active control session.
 *
 * Every session instance receives a manager generation. Collector callbacks are
 * gated by both generation and object identity, so a late state or operation
 * from a superseded connection cannot mutate the current snapshot.
 */
class HeadphoneSessionManager(
    private val driverRegistry: DriverRegistry,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val delayForReconnect: suspend (Long) -> Unit = { delay(it) },
) : SessionRuntimeHost {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<HeadphoneSnapshot?>(null)
    override val snapshot: StateFlow<HeadphoneSnapshot?> = _snapshot.asStateFlow()
    private val _operations = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 32)
    override val operations: Flow<OperationEvent> = _operations.asSharedFlow()

    private var generationCounter = 0L
    @Volatile
    private var active: ActiveSession? = null
    private var desired: DesiredConnection? = null
    private var reconnectJob: Job? = null

    override suspend fun connect(
        candidate: DeviceCandidate,
        transportFactory: TransportFactory,
    ): Long {
        val duplicate = mutex.withLock {
            val current = active
            if (
                current != null &&
                current.candidate.identity.id == candidate.identity.id &&
                current.session.connection.value !is SessionState.Failed
            ) {
                desired = DesiredConnection(candidate, transportFactory)
                current.generation
            } else {
                desired = DesiredConnection(candidate, transportFactory)
                null
            }
        }
        if (duplicate != null) return duplicate
        reconnectJob?.cancel()
        return startSession(candidate, transportFactory, reconnectAttempt = 0)
    }

    override suspend fun refresh(featureIds: Set<FeatureId>) {
        val holder = mutex.withLock { active }
            ?: return
        if (!isCurrent(holder)) return
        holder.session.refresh(featureIds)
    }

    override suspend fun execute(command: FeatureCommand): OperationResult {
        val holder = mutex.withLock { active }
        if (holder == null || !isCurrent(holder)) {
            return OperationResult.failed(
                RequestId("engine-unavailable-${clock()}"),
                FailureReason.NOT_CONNECTED,
            )
        }
        val result = holder.session.execute(command)
        return if (isCurrent(holder)) {
            result
        } else {
            OperationResult.failed(
                result.requestId,
                FailureReason.GENERATION_INVALIDATED,
                "session generation ${holder.generation} is no longer active",
            )
        }
    }

    override suspend fun disconnect(cause: DisconnectCause) {
        reconnectJob?.cancel()
        reconnectJob = null
        val holder = mutex.withLock {
            desired = null
            active.also { active = null }
        }
        holder?.collectorJobs?.forEach(Job::cancel)
        holder?.session?.disconnect(cause)
        val previous = _snapshot.value ?: return
        _snapshot.value = previous.copy(
            connection = SessionState.Idle(previous.deviceId, previous.generationId),
            state = previous.state.markAllStale(),
            emittedAtMillis = clock(),
        )
    }

    suspend fun close() = disconnect(DisconnectCause.REQUESTED)

    /** Debug-only escape hatch for a vendor trace UI; normal commands never use it. */
    suspend fun activeSession(): HeadphoneSession? = mutex.withLock { active?.session }

    private suspend fun startSession(
        candidate: DeviceCandidate,
        transportFactory: TransportFactory,
        reconnectAttempt: Int,
    ): Long {
        val match = driverRegistry.resolve(candidate)
        val generation = mutex.withLock { ++generationCounter }
        if (match == null) {
            _snapshot.value = HeadphoneSnapshot(
                deviceId = candidate.identity.id,
                generationId = generation,
                connection = SessionState.Failed(
                    candidate.identity.id,
                    generation,
                    FailureCategory.UNSUPPORTED_DEVICE,
                    DisconnectCause.PROTOCOL_ERROR,
                    "no driver matched candidate",
                ),
                emittedAtMillis = clock(),
            )
            return generation
        }

        val created = match.provider.createSession(
            DriverSessionContext(candidate, transportFactory),
        )
        val previous = mutex.withLock {
            active.also { active = null }
        }
        previous?.collectorJobs?.forEach(Job::cancel)
        previous?.session?.disconnect(DisconnectCause.SUPERSEDED)

        val holder = ActiveSession(
            generation = generation,
            candidate = candidate,
            transportFactory = transportFactory,
            session = created,
            reconnectAttempt = reconnectAttempt,
        )
        mutex.withLock { active = holder }
        _snapshot.value = HeadphoneSnapshot(
            deviceId = candidate.identity.id,
            generationId = generation,
            connection = SessionState.Detecting(candidate.identity.id, generation),
            emittedAtMillis = clock(),
        )
        attachCollectors(holder)
        created.connect()

        val terminal = created.connection.value
        if (terminal is SessionState.Failed && isCurrent(holder)) {
            scheduleReconnect(holder, terminal.cause)
        }
        return generation
    }

    private fun attachCollectors(holder: ActiveSession) {
        holder.collectorJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.session.connection.collect { value ->
                if (!isCurrent(holder)) return@collect
                val connection = value.withGeneration(holder.generation)
                updateSnapshot(holder, connection = connection)
                if (connection is SessionState.Failed) {
                    scheduleReconnect(holder, connection.cause)
                }
            }
        }
        holder.collectorJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.session.profile.collect { value ->
                if (isCurrent(holder)) updateSnapshot(holder, profile = SnapshotValue(value))
            }
        }
        holder.collectorJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.session.state.collect { value ->
                if (isCurrent(holder)) updateSnapshot(holder, state = value)
            }
        }
        holder.collectorJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            holder.session.operations.collect { value ->
                if (!isCurrent(holder)) return@collect
                val event = value.copy(generationId = holder.generation)
                _operations.emit(event)
                updateSnapshot(holder, lastOperation = SnapshotValue(event))
            }
        }
    }

    private fun updateSnapshot(
        holder: ActiveSession,
        connection: SessionState? = null,
        profile: SnapshotValue<moe.chenxy.headphones.core.feature.DeviceProfile?>? = null,
        state: HeadphoneState? = null,
        lastOperation: SnapshotValue<OperationEvent?>? = null,
    ) {
        if (!isCurrent(holder)) return
        _snapshot.update { current ->
            if (current == null || current.generationId != holder.generation) return@update current
            current.copy(
                connection = connection ?: current.connection,
                profile = profile?.value ?: current.profile,
                state = state ?: current.state,
                lastOperation = lastOperation?.value ?: current.lastOperation,
                emittedAtMillis = clock(),
            )
        }
    }

    private fun scheduleReconnect(holder: ActiveSession, cause: DisconnectCause) {
        if (!isCurrent(holder) || reconnectJob?.isActive == true) return
        val nextAttempt = holder.reconnectAttempt + 1
        val delayMillis = reconnectPolicy.delayMillis(nextAttempt, cause) ?: return
        val wanted = desired ?: return
        updateSnapshot(
            holder,
            connection = SessionState.Reconnecting(
                holder.candidate.identity.id,
                holder.generation,
                nextAttempt,
                cause,
            ),
            state = snapshot.value?.state?.markAllStale(),
        )
        reconnectJob = scope.launch {
            delayForReconnect(delayMillis)
            val stillWanted = mutex.withLock {
                desired?.takeIf { it.candidate.identity.id == wanted.candidate.identity.id }
            } ?: return@launch
            if (!isCurrent(holder)) return@launch
            reconnectJob = null
            startSession(
                stillWanted.candidate,
                stillWanted.transportFactory,
                reconnectAttempt = nextAttempt,
            )
        }
    }

    private fun isCurrent(holder: ActiveSession): Boolean =
        active?.let { it.generation == holder.generation && it.session === holder.session } == true

    private data class DesiredConnection(
        val candidate: DeviceCandidate,
        val transportFactory: TransportFactory,
    )

    private data class ActiveSession(
        val generation: Long,
        val candidate: DeviceCandidate,
        val transportFactory: TransportFactory,
        val session: HeadphoneSession,
        val reconnectAttempt: Int,
        val collectorJobs: MutableList<Job> = mutableListOf(),
    )

    private data class SnapshotValue<T>(val value: T)
}

private fun SessionState.withGeneration(generationId: Long): SessionState = when (this) {
    is SessionState.Idle -> copy(generationId = generationId)
    is SessionState.Detecting -> copy(generationId = generationId)
    is SessionState.TransportConnecting -> copy(generationId = generationId)
    is SessionState.ProtocolHandshaking -> copy(generationId = generationId)
    is SessionState.LoadingCapabilities -> copy(generationId = generationId)
    is SessionState.SynchronizingState -> copy(generationId = generationId)
    is SessionState.Ready -> copy(generationId = generationId)
    is SessionState.Reconnecting -> copy(generationId = generationId)
    is SessionState.Disconnecting -> copy(generationId = generationId)
    is SessionState.Failed -> copy(generationId = generationId)
}
