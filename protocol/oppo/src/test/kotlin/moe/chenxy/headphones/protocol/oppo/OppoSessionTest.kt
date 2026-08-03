package moe.chenxy.headphones.protocol.oppo

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.ValueSource
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.FailureReason
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationPhase
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportFactory
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult
import moe.chenxy.headphones.protocol.oppo.feature.equalizer.OppoCustomEqualizerFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec
import moe.chenxy.headphones.protocol.oppo.session.OppoSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class OppoSessionTest {

    @Test
    fun `connect requires protocol replies before ready and reduces initial state`() = runBlocking {
        val transport = FakeOppoTransport()
        val session = session(transport)

        session.connect()

        assertTrue(session.connection.value is SessionState.Ready)
        assertEquals(
            session.state.value.toString(),
            76,
            session.state.value.batteries.values.firstOrNull()?.level,
        )
        assertEquals(NoiseControlMode.OFF, session.state.value.noiseControl.confirmed)
        assertEquals("1.2.3", session.state.value.firmware)
        val requestedBatchFeatures = transport.requests
            .first { it.command == OppoCommand.QUERY_BATCH_STATUS }
            .payload
            .drop(1)
            .map { it.toInt() and 0xFF }
        assertTrue(0x37 in requestedBatchFeatures)
        assertFalse(0x1C in requestedBatchFeatures)
        assertTrue(session.profile.value!!.canWrite(
            moe.chenxy.headphones.core.feature.FeatureId.NOISE_CONTROL,
        ))
        session.disconnect()
    }

    @Test
    fun `set acknowledgement alone never confirms a value`() = runBlocking {
        val transport = FakeOppoTransport(confirmAncWrites = false)
        val session = session(transport)
        session.connect()
        val events = CopyOnWriteArrayList<OperationEvent>()
        val collector: Job = CoroutineScope(Dispatchers.Default).launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            session.operations.collect(events::add)
        }
        yield()

        val result = session.execute(
            FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
        )

        assertEquals(result.toString(), OperationPhase.TIMED_OUT, result.phase)
        assertEquals(NoiseControlMode.OFF, session.state.value.noiseControl.confirmed)
        assertNull(session.state.value.noiseControl.pending)
        assertTrue(events.any { it.phase == OperationPhase.DEVICE_ACCEPTED })
        assertFalse(events.any { it.phase == OperationPhase.READ_BACK_CONFIRMED })
        collector.cancel()
        session.disconnect()
    }

    @Test
    fun `explicit readback confirms an accepted write`() = runBlocking {
        val transport = FakeOppoTransport(confirmAncWrites = true)
        val session = session(transport)
        session.connect()

        val result = session.execute(
            FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
        )

        assertEquals(result.toString(), OperationPhase.READ_BACK_CONFIRMED, result.phase)
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            session.state.value.noiseControl.confirmed,
        )
        assertEquals(ValueSource.READ_BACK, session.state.value.noiseControl.source)
        assertNull(session.state.value.noiseControl.pending)
        session.disconnect()
    }

    @Test
    fun `device rejection rolls pending back without changing confirmed`() = runBlocking {
        val transport = FakeOppoTransport(rejectAncWrites = true)
        val session = session(transport)
        session.connect()

        val result = session.execute(
            FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
        )

        assertEquals(OperationPhase.FAILED, result.phase)
        assertEquals(NoiseControlMode.OFF, session.state.value.noiseControl.confirmed)
        assertNull(session.state.value.noiseControl.pending)
        session.disconnect()
    }

    @Test
    fun `unknown model cannot write an unadvertised spatial feature`() = runBlocking {
        val transport = FakeOppoTransport()
        val session = session(transport, model = "OPPO Enco Buds 3")
        session.connect()

        val result = session.execute(
            FeatureCommand.SetSpatialAudio(
                moe.chenxy.headphones.core.feature.SpatialAudioMode.FIXED,
            ),
        )

        assertEquals(OperationPhase.FAILED, result.phase)
        assertEquals(FailureReason.NOT_SUPPORTED, result.failure)
        assertFalse(result.succeeded)
        assertEquals(
            moe.chenxy.headphones.core.feature.SpatialAudioMode.OFF,
            session.state.value.spatialAudio.confirmed,
        )
        session.disconnect()
    }

    @Test
    fun `smart ANC active strength is part of the unified state`() = runBlocking {
        val transport = FakeOppoTransport()
        val session = session(transport)
        session.connect()

        transport.emitNotification(byteArrayOf(0x03, 0x04, 0x01, 0x20))

        val state = withTimeout(1_000) {
            session.state.first {
                it.noiseControlActiveMode == NoiseControlMode.NOISE_CANCELLATION_MEDIUM
            }
        }
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
            state.noiseControlActiveMode,
        )
        session.disconnect()
    }

    @Test
    fun `wear notification promotes wear capability evidence`() = runBlocking {
        val transport = FakeOppoTransport()
        val session = session(transport)
        session.connect()
        assertEquals(
            EvidenceLevel.ASSUMED,
            session.profile.value!!.capability(FeatureId.WEAR_DETECTION)!!.evidence,
        )

        transport.emitNotification(byteArrayOf(0x02, 0x02, 0x01, 0x07, 0x02, 0x07))

        val profile = withTimeout(1_000) {
            session.profile.first {
                it?.capability(FeatureId.WEAR_DETECTION)?.evidence == EvidenceLevel.VERIFIED
            }
        }
        assertEquals(
            EvidenceLevel.VERIFIED,
            profile!!.capability(FeatureId.WEAR_DETECTION)!!.evidence,
        )
        session.disconnect()
    }

    @Test
    fun `advertised custom EQ performs exact write readback and restore cycle`() = runBlocking {
        val transport = FakeOppoTransport(customEqSupported = true)
        val session = session(transport)
        session.connect()

        val original = requireNotNull(session.state.value.equalizerCurve.confirmed)
        assertEquals(listOf(0, 1, -1, 2, -2, 3), original.gains)
        val spec = requireNotNull(
            session.profile.value!!.capability(FeatureId.EQUALIZER)!!.equalizerCurveSpec,
        )
        assertTrue(original.slotId in spec.writableSlotIds)

        val changed = EqualizerCurve(original.slotId, original.gains.toMutableList().apply {
            this[0] += 1
        })
        val changedResult = session.execute(FeatureCommand.SetEqualizerCurve(changed))

        assertEquals(OperationPhase.READ_BACK_CONFIRMED, changedResult.phase)
        assertEquals(changed, session.state.value.equalizerCurve.confirmed)
        assertEquals(ValueSource.READ_BACK, session.state.value.equalizerCurve.source)
        assertTrue(transport.requests.any { it.command == OppoCommand.SET_CUSTOM_EQ })

        val restoreResult = session.execute(FeatureCommand.SetEqualizerCurve(original))

        assertEquals(OperationPhase.READ_BACK_CONFIRMED, restoreResult.phase)
        assertEquals(original, session.state.value.equalizerCurve.confirmed)
        val writeCount = transport.requests.count { it.command == OppoCommand.SET_CUSTOM_EQ }
        val invalid = session.execute(
            FeatureCommand.SetEqualizerCurve(EqualizerCurve(original.slotId, listOf(7, 1, -1, 2, -2, 3))),
        )
        assertEquals(OperationPhase.FAILED, invalid.phase)
        assertEquals(FailureReason.NOT_SUPPORTED, invalid.failure)
        assertEquals(writeCount, transport.requests.count { it.command == OppoCommand.SET_CUSTOM_EQ })
        session.disconnect()
    }

    @Test
    fun `Air5s without a custom slot creates one and confirms its assigned id`() = runBlocking {
        val transport = FakeOppoTransport(
            customEqSupported = true,
            customEqInitiallyPresent = false,
        )
        val session = session(transport)
        session.connect()

        assertNull(session.state.value.equalizerCurve.confirmed)
        val spec = requireNotNull(
            session.profile.value!!.capability(FeatureId.EQUALIZER)!!.equalizerCurveSpec,
        )
        assertEquals(
            setOf(OppoCustomEqualizerFeature.CREATION_SLOT_ID),
            spec.writableSlotIds,
        )
        val requested = EqualizerCurve(
            OppoCustomEqualizerFeature.CREATION_SLOT_ID,
            List(spec.bands.size) { 0 },
        )

        val result = session.execute(FeatureCommand.SetEqualizerCurve(requested))

        assertEquals(OperationPhase.READ_BACK_CONFIRMED, result.phase)
        val confirmed = requireNotNull(session.state.value.equalizerCurve.confirmed)
        assertEquals("oppo:eq:custom:5", confirmed.slotId)
        assertEquals(requested.gains, confirmed.gains)
        assertNull(session.state.value.equalizerCurve.pending)
        session.disconnect()
    }

    private fun session(
        transport: FakeOppoTransport,
        model: String = "OPPO Enco Air5s",
    ): OppoSession {
        val identity = DeviceIdentity(
            DeviceId.fromAddress("11:22:33:44:55:66"),
            VendorId.OPPO,
            "11:22:33:44:55:66",
        )
        val candidate = DeviceCandidate(
            identity,
            model,
            bonded = true,
            advertisedUuids = setOf(OppoSession.OPPO_SPP_UUID),
            availableTransports = setOf(TransportKind.CLASSIC_SPP),
        )
        val factory = object : TransportFactory {
            override suspend fun create(
                device: DeviceIdentity,
                spec: TransportSpec,
            ): ByteTransport = transport
        }
        return OppoSession(
            DriverSessionContext(candidate, factory),
            responseTimeoutMillis = 200,
            confirmationTimeoutMillis = 50,
            transportSettleDelayMillis = 0,
        )
    }
}

private class FakeOppoTransport(
    private val confirmAncWrites: Boolean = false,
    private val rejectAncWrites: Boolean = false,
    private val customEqSupported: Boolean = false,
    customEqInitiallyPresent: Boolean = true,
) : ByteTransport {
    override val kind: TransportKind = TransportKind.CLASSIC_SPP
    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()
    override val maxWriteSize: StateFlow<Int> = MutableStateFlow(512).asStateFlow()

    private var ancValue = 0x01
    private var customEqGains = mutableListOf(0, 1, -1, 2, -2, 3)
    private var customEqPresent = customEqInitiallyPresent
    val requests = CopyOnWriteArrayList<OppoMessage>()

    override suspend fun open() {
        _state.value = TransportState.Open
    }

    override suspend fun write(bytes: ByteArray): TransportWriteResult {
        val request = OppoMessageCodec.decode(bytes) ?: return TransportWriteResult.Written
        requests += request
        val responsePayload = when (request.command) {
            OppoCommand.QUERY_CAPABILITY -> ByteArray(9).apply {
                this[0] = 0
                if (customEqSupported) this[5] = 0x04
            }
            OppoCommand.QUERY_NOTIFICATION_SUPPORT -> byteArrayOf(0, 3, 1, 2, 3)
            OppoCommand.QUERY_BATTERY -> byteArrayOf(0, 1, 1, 76)
            OppoCommand.QUERY_ANC -> byteArrayOf(0, 1, 1, ancValue.toByte())
            OppoCommand.QUERY_EQ -> byteArrayOf(0, 0)
            OppoCommand.QUERY_CUSTOM_EQ -> if (customEqSupported) customEqResponse() else null
            OppoCommand.QUERY_BATCH_STATUS -> byteArrayOf(
                0,
                4,
                OppoFeature.GAME_MODE.toByte(),
                0,
                OppoFeature.LOW_LATENCY.toByte(),
                0,
                OppoFeature.DUAL_DEVICE.toByte(),
                0,
                OppoFeature.SPATIAL_SOUND_SWITCH.toByte(),
                0,
            )
            OppoCommand.QUERY_FIRMWARE ->
                byteArrayOf(0, 0) + "1,2,1,2,2,2,3,2,3".toByteArray(Charsets.US_ASCII)
            OppoCommand.SET_ANC -> {
                if (!rejectAncWrites && confirmAncWrites && request.payload.size >= 3) {
                    ancValue = request.payload[2].toInt() and 0xFF
                }
                byteArrayOf(if (rejectAncWrites) 1 else 0)
            }
            OppoCommand.SET_CUSTOM_EQ -> {
                if (!customEqSupported || !applyCustomEqUpdate(request.payload)) {
                    byteArrayOf(1)
                } else {
                    byteArrayOf(0)
                }
            }
            OppoCommand.SET_EQ,
            OppoCommand.SET_SWITCH_FEATURE,
            OppoCommand.SET_SPATIAL_AUDIO,
            -> byteArrayOf(0)
            else -> null
        }
        if (responsePayload != null) {
            _incoming.emit(
                OppoMessageCodec.encode(
                    OppoCommand.responseOf(request.command),
                    sequence = request.sequence,
                    payload = responsePayload,
                ),
            )
        }
        return TransportWriteResult.Written
    }

    override suspend fun close(cause: DisconnectCause) {
        _state.value = TransportState.Closed
    }

    suspend fun emitNotification(payload: ByteArray) {
        _incoming.emit(
            OppoMessageCodec.encode(
                OppoCommand.NOTIFICATION_EVENT,
                sequence = 0,
                payload = payload,
            ),
        )
    }

    private fun customEqResponse(): ByteArray {
        if (!customEqPresent) return byteArrayOf(0, 0)
        val name = "Custom 1".toByteArray(Charsets.UTF_8)
        val frequencies = listOf(62, 250, 1_000, 4_000, 8_000, 16_000)
        return ByteArray(2 + 5 + name.size + 1 + frequencies.size * 3).also { payload ->
            payload[0] = 0
            payload[1] = 1
            payload[2] = 1
            payload[3] = (-6).toByte()
            payload[4] = 6
            payload[5] = 5
            payload[6] = name.size.toByte()
            name.copyInto(payload, 7)
            var offset = 7 + name.size
            payload[offset++] = frequencies.size.toByte()
            frequencies.indices.forEach { index ->
                val frequency = frequencies[index]
                payload[offset++] = (frequency and 0xFF).toByte()
                payload[offset++] = ((frequency shr 8) and 0xFF).toByte()
                payload[offset++] = customEqGains[index].toByte()
            }
        }
    }

    private fun applyCustomEqUpdate(payload: ByteArray): Boolean {
        if (payload.size < 6) return false
        val action = payload[0].toInt() and 0xFF
        if (action !in setOf(
                OppoCustomEqualizerFeature.ACTION_ADD,
                OppoCustomEqualizerFeature.ACTION_UPDATE_OR_SELECT,
            )
        ) return false
        if (action == OppoCustomEqualizerFeature.ACTION_UPDATE_OR_SELECT && !customEqPresent) return false
        if (action == OppoCustomEqualizerFeature.ACTION_ADD && (payload[3].toInt() and 0xFF) != 0) return false
        val nameLength = payload[4].toInt() and 0xFF
        var offset = 5 + nameLength
        if (offset >= payload.size) return false
        val bandCount = payload[offset++].toInt() and 0xFF
        if (bandCount != customEqGains.size || offset + bandCount * 3 != payload.size) return false
        customEqGains = MutableList(bandCount) { index ->
            payload[offset + index * 3 + 2].toInt()
        }
        customEqPresent = true
        return true
    }
}
