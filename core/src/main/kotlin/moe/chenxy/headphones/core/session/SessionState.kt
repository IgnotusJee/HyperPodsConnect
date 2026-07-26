package moe.chenxy.headphones.core.session

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.TransportKind

enum class DisconnectCause {
    /** The app asked for it. Do not reconnect on its own. */
    REQUESTED,
    LINK_LOST,
    TRANSPORT_ERROR,
    ADAPTER_OFF,
    BOND_REMOVED,
    PERMISSION_DENIED,
    PROTOCOL_ERROR,
    SUPERSEDED;

    /**
     * Whether reconnecting could plausibly help. Adapter off, an unpaired
     * device or a denied permission will fail identically every time, so
     * retrying them just burns battery.
     */
    val isRetryable: Boolean
        get() = this == LINK_LOST || this == TRANSPORT_ERROR || this == PROTOCOL_ERROR
}

enum class FailureCategory {
    TRANSPORT,
    HANDSHAKE,
    CAPABILITY,
    PERMISSION,
    UNSUPPORTED_DEVICE,
    INTERNAL,
}

/**
 * Where a session is.
 *
 * A connected socket is not a ready session. The old implementation conflated
 * them and then had to bolt on "battery has arrived" as a readiness proxy;
 * splitting transport connectivity from protocol readiness removes the need for
 * such proxies. [generationId] rises on every new attempt so late callbacks from
 * a previous one can be dropped instead of corrupting the current state.
 */
sealed interface SessionState {
    val deviceId: DeviceId
    val generationId: Long

    data class Idle(
        override val deviceId: DeviceId,
        override val generationId: Long,
    ) : SessionState

    data class Detecting(
        override val deviceId: DeviceId,
        override val generationId: Long,
    ) : SessionState

    data class TransportConnecting(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val transport: TransportKind,
    ) : SessionState

    data class ProtocolHandshaking(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val transport: TransportKind,
    ) : SessionState

    data class LoadingCapabilities(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val transport: TransportKind,
    ) : SessionState

    data class SynchronizingState(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val transport: TransportKind,
    ) : SessionState

    data class Ready(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val transport: TransportKind,
    ) : SessionState

    data class Reconnecting(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val attempt: Int,
        val cause: DisconnectCause,
    ) : SessionState

    data class Disconnecting(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val cause: DisconnectCause,
    ) : SessionState

    data class Failed(
        override val deviceId: DeviceId,
        override val generationId: Long,
        val category: FailureCategory,
        val cause: DisconnectCause,
        val detail: String? = null,
    ) : SessionState {
        val canRetry: Boolean get() = cause.isRetryable
    }

    /** True only once the protocol is usable, never merely because a link exists. */
    val isProtocolReady: Boolean get() = this is Ready

    val isActive: Boolean
        get() = this !is Idle && this !is Failed
}
