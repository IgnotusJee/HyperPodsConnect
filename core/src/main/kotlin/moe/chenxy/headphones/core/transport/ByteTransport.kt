package moe.chenxy.headphones.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.session.DisconnectCause

sealed interface TransportState {
    data object Closed : TransportState
    data object Opening : TransportState
    data object Open : TransportState
    data class Failed(val failure: TransportFailure) : TransportState
}

data class TransportFailure(
    val cause: DisconnectCause,
    val detail: String? = null,
    val vendorStatus: Int? = null,
)

sealed interface TransportWriteResult {
    /** Handed to the stack. Says nothing about the peer having processed it. */
    data object Written : TransportWriteResult

    data class Rejected(val failure: TransportFailure) : TransportWriteResult
}

/** Where to connect. Vendor modules supply this; the transport never hardcodes it. */
sealed interface TransportSpec {
    val kind: TransportKind

    data class Spp(val serviceUuid: String, val secure: Boolean = true) : TransportSpec {
        override val kind: TransportKind get() = TransportKind.CLASSIC_SPP
    }

    data class Gatt(
        val serviceUuid: String,
        val txCharacteristicUuid: String,
        val rxCharacteristicUuid: String,
        val cccdUuid: String,
        val requestMtu: Int? = null,
        val writableLength: Int? = null,
        val writeMode: GattWriteMode = GattWriteMode.WITH_RESPONSE,
        val notificationMode: GattNotificationMode = GattNotificationMode.NOTIFICATION,
        val mtuFailurePolicy: GattMtuFailurePolicy = GattMtuFailurePolicy.CONTINUE_WITH_DEFAULT,
        val chunkPolicy: GattChunkPolicy = GattChunkPolicy.SPLIT,
        val withoutResponseThrottleMillis: Long = 10,
    ) : TransportSpec {
        override val kind: TransportKind get() = TransportKind.BLE_GATT

        init {
            require(requestMtu == null || requestMtu in 23..517) {
                "GATT MTU must be between 23 and 517"
            }
            require(writableLength == null || writableLength > 0) {
                "GATT writable length must be positive"
            }
            require(withoutResponseThrottleMillis >= 0) {
                "GATT write throttle cannot be negative"
            }
        }
    }
}

enum class GattWriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }

enum class GattNotificationMode { NOTIFICATION, INDICATION }

enum class GattMtuFailurePolicy { CONTINUE_WITH_DEFAULT, FAIL_CONNECTION }

enum class GattChunkPolicy { SPLIT, REJECT_OVERSIZED }

/**
 * An ordered byte pipe to one device.
 *
 * [incoming] guarantees order and nothing else. A chunk is whatever the link
 * happened to deliver, so it may hold part of a frame, several frames, or a
 * frame boundary in the middle. Real captures show an OPPO response arriving as
 * a 3-byte read followed by a 15-byte read, which is exactly why framing is the
 * protocol layer's job and never this one's.
 */
interface ByteTransport {
    val kind: TransportKind
    val state: StateFlow<TransportState>
    val incoming: Flow<ByteArray>

    /** Largest single write the link accepts; may change after MTU negotiation. */
    val maxWriteSize: StateFlow<Int>

    suspend fun open()

    suspend fun write(bytes: ByteArray): TransportWriteResult

    suspend fun close(cause: DisconnectCause = DisconnectCause.REQUESTED)
}

interface TransportFactory {
    suspend fun create(device: DeviceIdentity, spec: TransportSpec): ByteTransport
}
