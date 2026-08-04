package moe.chenxy.headphones.core.driver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.chenxy.headphones.core.device.DetectionEvidence
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.operation.RequestId
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.core.transport.TransportFactory

/**
 * Dependencies a vendor driver needs to create one session.
 *
 * Android and Xposed stay outside this type. The runtime supplies a transport
 * factory; the driver supplies protocol and feature behaviour.
 */
data class DriverSessionContext(
    val candidate: DeviceCandidate,
    val transportFactory: TransportFactory,
)

interface HeadphoneDriverProvider {
    val vendorId: VendorId

    /** Cheap, side-effect-free evidence used before opening a transport. */
    fun inspect(candidate: DeviceCandidate): DetectionEvidence?

    suspend fun createSession(context: DriverSessionContext): HeadphoneSession
}

interface HeadphoneSession {
    val connection: StateFlow<SessionState>
    val profile: StateFlow<DeviceProfile?>
    val state: StateFlow<HeadphoneState>
    val operations: Flow<OperationEvent>

    suspend fun connect()

    suspend fun refresh(featureIds: Set<FeatureId> = emptySet())

    suspend fun execute(command: FeatureCommand, requestId: RequestId? = null): OperationResult

    suspend fun disconnect(cause: DisconnectCause = DisconnectCause.REQUESTED)
}
