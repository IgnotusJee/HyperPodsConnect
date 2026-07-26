package moe.chenxy.headphones.core.session

import moe.chenxy.headphones.core.device.TransportKind

/** Things that can move a session. Named for what happened, not for the next state. */
sealed interface SessionEvent {
    data object ConnectRequested : SessionEvent
    data class DetectionSucceeded(val transport: TransportKind) : SessionEvent
    data class DetectionFailed(val detail: String?) : SessionEvent
    data object TransportOpened : SessionEvent
    data object HandshakeCompleted : SessionEvent
    data object CapabilitiesLoaded : SessionEvent
    data object InitialStateSynchronized : SessionEvent
    data class Disconnected(val cause: DisconnectCause) : SessionEvent
    data class Failed(val category: FailureCategory, val cause: DisconnectCause, val detail: String? = null) : SessionEvent
    data class RetryScheduled(val attempt: Int) : SessionEvent
    data object DisconnectRequested : SessionEvent
}

/**
 * Pure session state machine.
 *
 * Kept free of coroutines, timers and IO so every transition is testable
 * directly. Two rules are enforced here rather than left to callers:
 *
 * - an event carrying a stale [SessionEvent] for an old generation is ignored,
 *   which is what stops a late callback from a previous connection attempt from
 *   dragging a fresh session backwards;
 * - a transport-level event never produces [SessionState.Ready]; readiness comes
 *   only after handshake, capabilities and the initial state sync.
 */
class SessionMachine(initialState: SessionState) {

    var state: SessionState = initialState
        private set

    /**
     * Applies an event observed in [generationId].
     *
     * Returns the resulting state. Events from an older generation are dropped
     * and leave the state untouched.
     */
    fun onEvent(event: SessionEvent, generationId: Long = state.generationId): SessionState {
        if (generationId < state.generationId) return state
        state = reduce(state, event)
        return state
    }

    companion object {
        fun reduce(current: SessionState, event: SessionEvent): SessionState {
            val deviceId = current.deviceId
            val generation = current.generationId

            // A disconnect or failure can arrive in any active state, so handle
            // them before the per-state matrix rather than repeating them in it.
            when (event) {
                is SessionEvent.Failed -> return SessionState.Failed(
                    deviceId, generation, event.category, event.cause, event.detail,
                )

                is SessionEvent.Disconnected -> return if (event.cause == DisconnectCause.REQUESTED) {
                    SessionState.Idle(deviceId, generation)
                } else {
                    SessionState.Failed(
                        deviceId, generation, FailureCategory.TRANSPORT, event.cause,
                    )
                }

                is SessionEvent.DisconnectRequested ->
                    return SessionState.Disconnecting(deviceId, generation, DisconnectCause.REQUESTED)

                else -> Unit
            }

            return when (current) {
                is SessionState.Idle,
                is SessionState.Failed,
                -> when (event) {
                    // Every new attempt gets its own generation.
                    is SessionEvent.ConnectRequested ->
                        SessionState.Detecting(deviceId, generation + 1)

                    is SessionEvent.RetryScheduled ->
                        SessionState.Reconnecting(
                            deviceId,
                            generation + 1,
                            event.attempt,
                            (current as? SessionState.Failed)?.cause ?: DisconnectCause.LINK_LOST,
                        )

                    else -> current
                }

                is SessionState.Detecting -> when (event) {
                    is SessionEvent.DetectionSucceeded ->
                        SessionState.TransportConnecting(deviceId, generation, event.transport)

                    is SessionEvent.DetectionFailed -> SessionState.Failed(
                        deviceId, generation, FailureCategory.UNSUPPORTED_DEVICE,
                        DisconnectCause.PROTOCOL_ERROR, event.detail,
                    )

                    else -> current
                }

                is SessionState.TransportConnecting -> when (event) {
                    // Deliberately not Ready: an open link proves nothing about
                    // the protocol on top of it.
                    SessionEvent.TransportOpened ->
                        SessionState.ProtocolHandshaking(deviceId, generation, current.transport)

                    else -> current
                }

                is SessionState.ProtocolHandshaking -> when (event) {
                    SessionEvent.HandshakeCompleted ->
                        SessionState.LoadingCapabilities(deviceId, generation, current.transport)

                    else -> current
                }

                is SessionState.LoadingCapabilities -> when (event) {
                    SessionEvent.CapabilitiesLoaded ->
                        SessionState.SynchronizingState(deviceId, generation, current.transport)

                    else -> current
                }

                is SessionState.SynchronizingState -> when (event) {
                    SessionEvent.InitialStateSynchronized ->
                        SessionState.Ready(deviceId, generation, current.transport)

                    else -> current
                }

                is SessionState.Reconnecting -> when (event) {
                    is SessionEvent.DetectionSucceeded ->
                        SessionState.TransportConnecting(deviceId, generation, event.transport)

                    is SessionEvent.ConnectRequested ->
                        SessionState.Detecting(deviceId, generation)

                    else -> current
                }

                is SessionState.Ready,
                is SessionState.Disconnecting,
                -> current
            }
        }
    }
}
