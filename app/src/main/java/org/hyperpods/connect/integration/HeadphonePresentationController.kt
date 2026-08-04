package org.hyperpods.connect.integration

import android.os.SystemClock
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.engine.HeadphoneSnapshot

interface HeadphonePresentationEffects {
    fun onNotificationInvalidated(connection: SessionState)
    fun onBatteryChanged(snapshot: HeadphoneSnapshot, allowConnectedPresentation: Boolean)
    fun onWearingChanged(previous: HeadphoneState, current: HeadphoneState)
    fun onReady(snapshot: HeadphoneSnapshot, enteringReady: Boolean)
}

/**
 * Brand-neutral state machine for all connection presentation effects.
 * Renderers remain Android/HyperOS-specific, but no renderer decides device support or Ready edges.
 */
class HeadphonePresentationController(
    private val effects: HeadphonePresentationEffects,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastState = HeadphoneState()
    private var readyGeneration = -1L
    private var presentationGeneration = -1L
    private var presentationAllowed = false
    private var presentationFingerprint: String? = null
    private val connectionPresentationGate = ConnectionPresentationGate()

    fun onSnapshot(snapshot: HeadphoneSnapshot, profileGroupId: Int?, address: String) {
        val previous = lastState
        val ready = snapshot.connection is SessionState.Ready
        val enteringReady = ready && readyGeneration != snapshot.generationId
        val nextPresentationFingerprint = snapshot.profile?.let {
            listOf(
                it.model.orEmpty(),
                it.topology.name,
                it.canWrite(FeatureId.NOISE_CONTROL).toString(),
            ).joinToString("|")
        }
        val presentationChanged = nextPresentationFingerprint != presentationFingerprint

        if (enteringReady) {
            presentationGeneration = snapshot.generationId
            presentationAllowed = connectionPresentationGate.claim(
                logicalDeviceKey = connectionPresentationKey(
                    vendorId = snapshot.profile?.vendorId?.value,
                    deviceName = snapshot.profile?.model,
                    profileGroupId = profileGroupId,
                    address = address,
                ),
                nowMs = nowMs(),
            )
        }
        val allowConnectedPresentation =
            presentationGeneration == snapshot.generationId && presentationAllowed

        if (readyGeneration >= 0L && !ready) {
            effects.onNotificationInvalidated(snapshot.connection)
            readyGeneration = -1L
        }
        if (
            ready && snapshot.state.batteries.isNotEmpty() &&
            (snapshot.state.batteries != previous.batteries || presentationChanged || enteringReady)
        ) {
            effects.onBatteryChanged(snapshot, allowConnectedPresentation)
        }
        if (snapshot.state.wearing != previous.wearing && ready) {
            effects.onWearingChanged(previous, snapshot.state)
        }
        if (ready) {
            effects.onReady(snapshot, enteringReady && allowConnectedPresentation)
            readyGeneration = snapshot.generationId
        }
        lastState = snapshot.state
        presentationFingerprint = nextPresentationFingerprint
    }

    fun reset() {
        lastState = HeadphoneState()
        readyGeneration = -1L
        presentationGeneration = -1L
        presentationAllowed = false
        presentationFingerprint = null
    }
}
