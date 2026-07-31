package moe.chenxy.oppopods.ui.state

import moe.chenxy.oppopods.ipc.BatteryPayload
import moe.chenxy.oppopods.ipc.CapabilityPayload
import moe.chenxy.oppopods.ipc.FeatureValuePayload
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotPayload
import moe.chenxy.oppopods.ipc.OperationPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphoneUiStateStoreTest {
    @Test
    fun `switching driver keeps the same vendor-neutral UI`() {
        val first = HeadphoneUiStateStore().apply {
            accept(fakeSnapshot(vendor = "OPPO", deviceId = "oppo-device"))
        }.state.value
        val second = HeadphoneUiStateStore().apply {
            accept(fakeSnapshot(vendor = "FAKE", deviceId = "fake-device"))
        }.state.value

        assertEquals(first.batteries, second.batteries)
        assertEquals(first.features, second.features)
        assertEquals(first.connection, second.connection)
    }

    @Test
    fun `fake driver renders battery ANC EQ and failure state`() {
        val store = HeadphoneUiStateStore()
        store.accept(
            fakeSnapshot(
                operation = OperationPayload(
                    requestId = "fake-failure",
                    featureId = "EQUALIZER",
                    phase = "FAILED",
                    failure = "DEVICE_REJECTED",
                    detail = "fake rejection",
                    atMillis = 12,
                ),
            ),
        )

        val state = store.state.value
        assertEquals(82, state.batteries["LEFT"]?.level)
        assertEquals("NOISE_CANCELLATION", state.feature("NOISE_CONTROL")?.displayed)
        assertEquals("fake:bass", state.feature("EQUALIZER")?.displayed)
        assertEquals(UiOperationStatus.FAILED, state.feature("EQUALIZER")?.operation?.status)
    }

    @Test
    fun `headband topology reaches the vendor neutral UI state`() {
        val store = HeadphoneUiStateStore()
        store.accept(fakeSnapshot(topology = "HEADBAND"))

        assertEquals("HEADBAND", store.state.value.topology)
    }

    @Test
    fun `smart noise control active mode reaches the vendor neutral UI state`() {
        val store = HeadphoneUiStateStore()
        store.accept(fakeSnapshot(noiseControlActiveMode = "NOISE_CANCELLATION_MEDIUM"))

        assertEquals("NOISE_CANCELLATION_MEDIUM", store.state.value.noiseControlActiveMode)
    }

    @Test
    fun `unsupported feature is hidden and readable feature is read only`() {
        val store = HeadphoneUiStateStore()
        val snapshot = fakeSnapshot().copy(
            capabilities = listOf(
                capability("SPATIAL_AUDIO", canRead = false, canWrite = false, evidence = "REFUTED"),
                capability("EQUALIZER", canRead = true, canWrite = false, evidence = "VERIFIED"),
            ),
        )
        store.accept(snapshot)

        assertNull(store.state.value.feature("SPATIAL_AUDIO"))
        assertTrue(store.state.value.feature("EQUALIZER")!!.readOnly)
        assertFalse(store.state.value.feature("EQUALIZER")!!.writable)
    }

    @Test
    fun `pending timeout and confirmed are distinct UI states`() {
        val store = HeadphoneUiStateStore()
        store.accept(
            fakeSnapshot(
                lowLatency = FeatureValuePayload("false", "true", false, "LOCAL_PENDING"),
                operation = operation("SENT"),
            ),
        )
        assertEquals("true", store.state.value.feature("LOW_LATENCY")?.displayed)
        assertEquals(UiOperationStatus.PENDING, store.state.value.lastOperation?.status)

        store.accept(
            fakeSnapshot(
                lowLatency = FeatureValuePayload("false", null, false, "READ_BACK"),
                operation = operation("TIMED_OUT", "TIMEOUT"),
            ),
        )
        assertEquals("false", store.state.value.feature("LOW_LATENCY")?.displayed)
        assertEquals(UiOperationStatus.TIMED_OUT, store.state.value.lastOperation?.status)

        store.accept(
            fakeSnapshot(
                lowLatency = FeatureValuePayload("true", null, false, "READ_BACK"),
                operation = operation("READ_BACK_CONFIRMED"),
            ),
        )
        assertEquals("true", store.state.value.feature("LOW_LATENCY")?.confirmed)
        assertEquals(UiOperationStatus.CONFIRMED, store.state.value.lastOperation?.status)
    }

    @Test
    fun `ambient level exposes only the driver supplied verified range`() {
        val store = HeadphoneUiStateStore()
        val snapshot = fakeSnapshot().copy(
            features = fakeSnapshot().features + (
                "AMBIENT_SOUND_LEVEL" to
                    FeatureValuePayload("10", null, false, "QUERY_RESPONSE")
                ),
            capabilities = fakeSnapshot().capabilities + capability(
                "AMBIENT_SOUND_LEVEL",
                options = (1..20).map(Int::toString),
            ),
        )

        store.accept(snapshot)

        val level = store.state.value.feature("AMBIENT_SOUND_LEVEL")!!
        assertTrue(level.writable)
        assertEquals("10", level.displayed)
        assertEquals((1..20).map(Int::toString), level.options.map { it.value })
    }

    @Test
    fun `older generation cannot replace current UI state`() {
        val store = HeadphoneUiStateStore()
        store.accept(fakeSnapshot(generation = 7, title = "Current"))
        store.accept(fakeSnapshot(generation = 6, title = "Old"))

        assertEquals("Current", store.state.value.title)
        assertEquals(7, store.state.value.generationId)
    }

    @Test
    fun `older snapshot in the same generation cannot replace current UI state`() {
        val store = HeadphoneUiStateStore()
        store.accept(fakeSnapshot(title = "Current", emittedAtMillis = 20))
        store.accept(fakeSnapshot(title = "Old", emittedAtMillis = 10))

        assertEquals("Current", store.state.value.title)
        assertEquals(20, store.state.value.emittedAtMillis)
    }

    private fun fakeSnapshot(
        vendor: String = "FAKE",
        deviceId: String = "fake-device",
        generation: Long = 4,
        title: String = "Fake Headphones",
        lowLatency: FeatureValuePayload = FeatureValuePayload("false", null, false, "READ_BACK"),
        operation: OperationPayload? = null,
        emittedAtMillis: Long = 12,
        topology: String? = null,
        noiseControlActiveMode: String? = null,
    ) = HeadphoneSnapshotPayload(
        deviceId = deviceId,
        generationId = generation,
        vendorId = vendor,
        primaryAddress = "11:22:33:44:55:66",
        deviceName = title,
        connection = "Ready",
        protocolReady = true,
        transport = "FAKE",
        topology = topology,
        firmware = "1",
        compatibility = "CONTROLLED",
        batteries = listOf(BatteryPayload("LEFT", 82, false)),
        wearing = mapOf("LEFT" to "WEARING"),
        noiseControlActiveMode = noiseControlActiveMode,
        features = mapOf(
            "NOISE_CONTROL" to FeatureValuePayload(
                "NOISE_CANCELLATION",
                null,
                false,
                "QUERY_RESPONSE",
            ),
            "EQUALIZER" to FeatureValuePayload("fake:bass", null, false, "QUERY_RESPONSE"),
            "LOW_LATENCY" to lowLatency,
        ),
        capabilities = listOf(
            capability(
                "NOISE_CONTROL",
                options = listOf("OFF", "NOISE_CANCELLATION"),
            ),
            capability(
                "EQUALIZER",
                options = listOf("fake:flat", "fake:bass"),
                labels = mapOf("fake:flat" to "Flat", "fake:bass" to "Bass"),
            ),
            capability("LOW_LATENCY"),
        ),
        operation = operation,
        emittedAtMillis = emittedAtMillis,
    )

    private fun capability(
        id: String,
        canRead: Boolean = true,
        canWrite: Boolean = true,
        evidence: String = "VERIFIED",
        options: List<String> = emptyList(),
        labels: Map<String, String> = emptyMap(),
    ) = CapabilityPayload(
        featureId = id,
        canRead = canRead,
        canWrite = canWrite,
        evidence = evidence,
        requiresReadback = true,
        allowedValues = options,
        valueLabels = labels,
    )

    private fun operation(phase: String, failure: String? = null) = OperationPayload(
        requestId = "request",
        featureId = "LOW_LATENCY",
        phase = phase,
        failure = failure,
        detail = null,
        atMillis = 12,
    )
}
