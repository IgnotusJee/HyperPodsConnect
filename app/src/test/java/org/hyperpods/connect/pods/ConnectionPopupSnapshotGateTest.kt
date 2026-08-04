package org.hyperpods.connect.pods

import org.hyperpods.connect.integration.HeadphonePresentationState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPopupSnapshotGateTest {
    private val expected = HeadphonePresentationState(
        deviceId = "sony-group-1",
        generationId = 3,
        emittedAtMillis = 200,
        address = "AA:AA:AA:AA:AA:01",
        memberAddresses = setOf("AA:AA:AA:AA:AA:02"),
        connected = true,
    )

    @Test
    fun `target member address updates automatic popup`() {
        assertEquals(
            ConnectionPopupSnapshotDecision.UPDATE,
            decide(expected, address = "AA:AA:AA:AA:AA:02"),
        )
    }

    @Test
    fun `new generation dismisses an already visible popup`() {
        assertEquals(
            ConnectionPopupSnapshotDecision.DISMISS,
            decide(expected.copy(generationId = 4), currentlyShown = true),
        )
    }

    @Test
    fun `disconnect dismisses an already visible popup`() {
        assertEquals(
            ConnectionPopupSnapshotDecision.DISMISS,
            decide(expected.copy(connected = false), currentlyShown = true),
        )
    }

    @Test
    fun `unrelated state waits before target popup was shown`() {
        assertEquals(
            ConnectionPopupSnapshotDecision.WAIT,
            decide(expected.copy(generationId = 2), currentlyShown = false),
        )
    }

    private fun decide(
        state: HeadphonePresentationState,
        currentlyShown: Boolean = false,
        address: String = "AA:AA:AA:AA:AA:01",
    ) = connectionPopupSnapshotDecision(
        connectionEdgePopup = true,
        currentlyShown = currentlyShown,
        expectedGeneration = 3,
        expectedEmittedAtMillis = 100,
        expectedDeviceId = "sony-group-1",
        expectedAddress = address,
        state = state,
    )
}
