package moe.chenxy.headphones.engine

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.session.SessionState

/**
 * One replayable view of the control authority.
 *
 * Consumers never need to reconstruct state by racing a collection of feature
 * broadcasts: connection, profile, state and the latest operation are captured
 * under one manager generation.
 */
data class HeadphoneSnapshot(
    val deviceId: DeviceId,
    val generationId: Long,
    val connection: SessionState,
    val profile: DeviceProfile? = null,
    val state: HeadphoneState = HeadphoneState(),
    val lastOperation: OperationEvent? = null,
    val emittedAtMillis: Long,
)
