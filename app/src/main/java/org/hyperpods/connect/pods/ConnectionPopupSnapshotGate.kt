package org.hyperpods.connect.pods

import org.hyperpods.connect.integration.HeadphonePresentationState

enum class ConnectionPopupSnapshotDecision { WAIT, UPDATE, DISMISS }

/** Keeps an automatic popup bound to the exact Ready session that launched it. */
fun connectionPopupSnapshotDecision(
    connectionEdgePopup: Boolean,
    currentlyShown: Boolean,
    expectedGeneration: Long,
    expectedEmittedAtMillis: Long,
    expectedDeviceId: String?,
    expectedAddress: String?,
    state: HeadphonePresentationState,
): ConnectionPopupSnapshotDecision {
    if (!connectionEdgePopup) return ConnectionPopupSnapshotDecision.UPDATE
    val matches =
        state.connected &&
            state.generationId == expectedGeneration &&
            state.emittedAtMillis >= expectedEmittedAtMillis &&
            state.deviceId == expectedDeviceId &&
            state.supportsAddress(expectedAddress)
    if (matches) return ConnectionPopupSnapshotDecision.UPDATE
    if (!currentlyShown) return ConnectionPopupSnapshotDecision.WAIT

    val targetInvalidated =
        !state.connected ||
            state.generationId != expectedGeneration ||
            state.deviceId != expectedDeviceId ||
            (
                state.emittedAtMillis >= expectedEmittedAtMillis &&
                    !state.supportsAddress(expectedAddress)
                )
    return if (targetInvalidated) {
        ConnectionPopupSnapshotDecision.DISMISS
    } else {
        ConnectionPopupSnapshotDecision.WAIT
    }
}
