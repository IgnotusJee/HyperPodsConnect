package moe.chenxy.headphones.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationResult
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.TransportFactory

/**
 * Platform-neutral authority boundary. Android/Xposed hosts implement lifecycle
 * and IPC around this API; the engine remains usable by another host later.
 */
interface SessionRuntimeHost {
    val snapshot: StateFlow<HeadphoneSnapshot?>
    val operations: Flow<OperationEvent>

    suspend fun connect(candidate: DeviceCandidate, transportFactory: TransportFactory): Long
    suspend fun refresh(featureIds: Set<FeatureId> = emptySet())
    suspend fun execute(command: FeatureCommand): OperationResult
    suspend fun disconnect(cause: DisconnectCause = DisconnectCause.REQUESTED)
}
