package moe.chenxy.headphones.protocol.sony

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.operation.FailureReason
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportFactory
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.core.transport.TransportState
import moe.chenxy.headphones.core.transport.TransportWriteResult
import moe.chenxy.headphones.protocol.sony.frame.TandemCodec
import moe.chenxy.headphones.protocol.sony.frame.TandemDecodeResult
import moe.chenxy.headphones.protocol.sony.frame.TandemFrame
import moe.chenxy.headphones.protocol.sony.frame.TandemStreamDecoder
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyDataType
import moe.chenxy.headphones.protocol.sony.profile.SonyProfile
import moe.chenxy.headphones.protocol.sony.session.SonySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonySessionTest {
    @Test
    fun `complete read-only handshake reaches ready and records evidence`() = runBlocking {
        val transport = FakeSonyTransport()
        val session = session(transport)

        session.connect()

        assertTrue(session.connection.value is SessionState.Ready)
        assertEquals(CompatibilityLevel.READ_ONLY, session.profile.value?.compatibilityLevel)
        assertEquals("WH-1000XM4", session.profile.value?.model)
        assertEquals("2.5.1", session.state.value.firmware)
        assertEquals(76, session.state.value.batteries[BatteryComponent.SINGLE]?.level)
        assertTrue(session.state.value.vendorStates["sony.capability.fingerprint"]?.length == 64)
        assertTrue(transport.ackWrites > 0)
        session.disconnect()
    }

    @Test
    fun `control is denied without emitting a Sony command`() = runBlocking {
        val transport = FakeSonyTransport()
        val session = session(transport)
        session.connect()
        val commandWrites = transport.commandWrites.size

        val result = session.execute(
            FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
        )

        assertEquals(FailureReason.NOT_SUPPORTED, result.failure)
        assertFalse(result.succeeded)
        assertEquals(commandWrites, transport.commandWrites.size)
        session.disconnect()
    }

    @Test
    fun `name hint without advertised uuid never opens a transport`() = runBlocking {
        var factoryCalls = 0
        val candidate = candidate(advertisedUuids = emptySet())
        val factory = object : TransportFactory {
            override suspend fun create(
                device: DeviceIdentity,
                spec: TransportSpec,
            ): ByteTransport {
                factoryCalls++
                return FakeSonyTransport()
            }
        }
        val session = SonySession(
            DriverSessionContext(candidate, factory),
            responseTimeoutMillis = 100,
            transportSettleDelayMillis = 0,
        )

        session.connect()

        assertTrue(session.connection.value is SessionState.Failed)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `bonded Sony GATT route validates the exact profile and reuses handshake`() = runBlocking {
        val transport = FakeSonyTransport(TransportKind.BLE_GATT)
        val candidate = candidate(
            advertisedUuids = emptySet(),
            availableTransports = setOf(TransportKind.CLASSIC_SPP, TransportKind.BLE_GATT),
        ).copy(displayName = "LinkBuds S")
        val factory = object : TransportFactory {
            override suspend fun create(
                device: DeviceIdentity,
                spec: TransportSpec,
            ): ByteTransport {
                val gatt = spec as TransportSpec.Gatt
                assertEquals(
                    SonyProfile.SONY_GATT_TANDEM_V2_HPC_SERVICE_UUID,
                    gatt.serviceUuid,
                )
                assertEquals(
                    SonyProfile.SONY_GATT_TANDEM_TO_ACCESSORY_UUID,
                    gatt.txCharacteristicUuid,
                )
                assertEquals(
                    SonyProfile.SONY_GATT_TANDEM_FROM_ACCESSORY_UUID,
                    gatt.rxCharacteristicUuid,
                )
                assertEquals(2, gatt.preparationSteps.size)
                return transport
            }
        }
        val session = SonySession(
            DriverSessionContext(candidate, factory),
            responseTimeoutMillis = 300,
            transportSettleDelayMillis = 0,
        )

        session.connect()

        assertTrue(session.connection.value is SessionState.Ready)
        assertEquals(TransportKind.BLE_GATT, session.profile.value?.transport)
        assertEquals(
            setOf(TransportKind.BLE_GATT),
            session.profile.value?.features?.values?.first()?.availableOnTransports,
        )
        session.disconnect()
    }

    @Test
    fun `failed GATT validation never falls back to an unadvertised SPP service`() = runBlocking {
        val specs = CopyOnWriteArrayList<TransportSpec>()
        val candidate = candidate(
            advertisedUuids = emptySet(),
            availableTransports = setOf(TransportKind.CLASSIC_SPP, TransportKind.BLE_GATT),
        ).copy(displayName = "LinkBuds S")
        val factory = object : TransportFactory {
            override suspend fun create(
                device: DeviceIdentity,
                spec: TransportSpec,
            ): ByteTransport {
                specs += spec
                return FailingSonyTransport()
            }
        }
        val session = SonySession(
            DriverSessionContext(candidate, factory),
            responseTimeoutMillis = 100,
            transportSettleDelayMillis = 0,
        )

        session.connect()

        assertTrue(session.connection.value is SessionState.Failed)
        assertEquals(1, specs.size)
        assertTrue(specs.single() is TransportSpec.Gatt)
        assertFalse(specs.any { it is TransportSpec.Spp })
    }

    @Test
    fun `exact LinkBuds S profile writes notifies reads back and restores noise control`() =
        runBlocking {
            val transport = FakeSonyTransport(
                kind = TransportKind.BLE_GATT,
                model = "LinkBuds S",
                firmware = "4.2.1",
                supportFunctions = intArrayOf(0x17FF),
            )
            val candidate = candidate(
                advertisedUuids = emptySet(),
                availableTransports = setOf(TransportKind.BLE_GATT),
            ).copy(displayName = "LinkBuds S")
            val factory = object : TransportFactory {
                override suspend fun create(
                    device: DeviceIdentity,
                    spec: TransportSpec,
                ): ByteTransport = transport
            }
            val session = SonySession(
                DriverSessionContext(candidate, factory),
                responseTimeoutMillis = 300,
                transportSettleDelayMillis = 0,
            )

            session.connect()

            assertEquals(CompatibilityLevel.CONTROLLED, session.profile.value?.compatibilityLevel)
            assertTrue(session.profile.value?.capability(FeatureId.NOISE_CONTROL)?.isWritable == true)
            assertEquals(NoiseControlMode.OFF, session.state.value.noiseControl.confirmed)

            val enable = session.execute(
                FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
            )
            assertTrue(enable.succeeded)
            assertEquals(NoiseControlMode.NOISE_CANCELLATION, session.state.value.noiseControl.confirmed)
            assertEquals(
                byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x0A).toList(),
                transport.commandWrites.last {
                    it.first().toInt() and 0xFF == SonyCommand.NCASM_SET_PARAM
                }.toList(),
            )
            assertEquals(
                SonyCommand.NCASM_GET_PARAM,
                transport.commandWrites.last().first().toInt() and 0xFF,
            )

            val restore = session.execute(
                FeatureCommand.SetNoiseControl(NoiseControlMode.OFF),
            )
            assertTrue(restore.succeeded)
            assertEquals(NoiseControlMode.OFF, session.state.value.noiseControl.confirmed)
            session.disconnect()
        }

    @Test
    fun `nearby firmware misses Sony write whitelist without emitting SET`() = runBlocking {
        val transport = FakeSonyTransport(
            kind = TransportKind.BLE_GATT,
            model = "LinkBuds S",
            firmware = "4.2.2",
            supportFunctions = intArrayOf(0x17FF),
        )
        val candidate = candidate(
            advertisedUuids = emptySet(),
            availableTransports = setOf(TransportKind.BLE_GATT),
        ).copy(displayName = "LinkBuds S")
        val session = SonySession(
            DriverSessionContext(
                candidate,
                object : TransportFactory {
                    override suspend fun create(
                        device: DeviceIdentity,
                        spec: TransportSpec,
                    ): ByteTransport = transport
                },
            ),
            responseTimeoutMillis = 300,
            transportSettleDelayMillis = 0,
        )
        session.connect()
        val writesBefore = transport.commandWrites.size

        val result = session.execute(
            FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
        )

        assertEquals(FailureReason.NOT_SUPPORTED, result.failure)
        assertEquals(writesBefore, transport.commandWrites.size)
        assertFalse(
            transport.commandWrites.any {
                it.first().toInt() and 0xFF == SonyCommand.NCASM_SET_PARAM
            },
        )
        session.disconnect()
    }

    @Test
    fun `missing NCASM notification falls back to explicit GET readback`() = runBlocking {
        val transport = FakeSonyTransport(
            kind = TransportKind.BLE_GATT,
            model = "LinkBuds S",
            firmware = "4.2.1",
            supportFunctions = intArrayOf(0x17FF),
            notifyOnSet = false,
        )
        val candidate = candidate(
            advertisedUuids = emptySet(),
            availableTransports = setOf(TransportKind.BLE_GATT),
        ).copy(displayName = "LinkBuds S")
        val session = SonySession(
            DriverSessionContext(
                candidate,
                object : TransportFactory {
                    override suspend fun create(
                        device: DeviceIdentity,
                        spec: TransportSpec,
                    ): ByteTransport = transport
                },
            ),
            responseTimeoutMillis = 300,
            transportSettleDelayMillis = 0,
        )
        session.connect()

        val result = session.execute(
            FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
        )

        assertTrue(result.succeeded)
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, session.state.value.noiseControl.confirmed)
        assertEquals(
            SonyCommand.NCASM_GET_PARAM,
            transport.commandWrites.last().first().toInt() and 0xFF,
        )
        session.disconnect()
    }

    private fun session(transport: FakeSonyTransport): SonySession {
        val factory = object : TransportFactory {
            override suspend fun create(
                device: DeviceIdentity,
                spec: TransportSpec,
            ): ByteTransport {
                assertEquals(SonyProfile.SONY_SPP_V2_UUID, (spec as TransportSpec.Spp).serviceUuid)
                assertTrue(spec.secure)
                return transport
            }
        }
        return SonySession(
            DriverSessionContext(candidate(), factory),
            responseTimeoutMillis = 300,
            transportSettleDelayMillis = 0,
        )
    }

    private fun candidate(
        advertisedUuids: Set<String> = setOf(SonyProfile.SONY_SPP_V2_UUID),
        availableTransports: Set<TransportKind> = setOf(TransportKind.CLASSIC_SPP),
    ) =
        DeviceCandidate(
            identity = DeviceIdentity(
                DeviceId.fromAddress("11:22:33:44:55:66"),
                VendorId.SONY,
                "11:22:33:44:55:66",
            ),
            displayName = "WH-1000XM4",
            bonded = true,
            advertisedUuids = advertisedUuids,
            availableTransports = availableTransports,
        )
}

private class FailingSonyTransport : ByteTransport {
    override val kind: TransportKind = TransportKind.BLE_GATT
    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    override val incoming: Flow<ByteArray> = MutableSharedFlow()
    override val maxWriteSize: StateFlow<Int> = MutableStateFlow(20).asStateFlow()

    override suspend fun open() {
        _state.value = TransportState.Failed(
            moe.chenxy.headphones.core.transport.TransportFailure(
                DisconnectCause.TRANSPORT_ERROR,
                "Sony GATT service missing",
            ),
        )
    }

    override suspend fun write(bytes: ByteArray): TransportWriteResult =
        error("write must not run")

    override suspend fun close(cause: DisconnectCause) {
        _state.value = TransportState.Closed
    }
}

private class FakeSonyTransport(
    override val kind: TransportKind = TransportKind.CLASSIC_SPP,
    private val model: String = "WH-1000XM4",
    private val firmware: String = "2.5.1",
    private val supportFunctions: IntArray = intArrayOf(0x0001),
    private val notifyOnSet: Boolean = true,
) : ByteTransport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()
    override val maxWriteSize: StateFlow<Int> = MutableStateFlow(512).asStateFlow()
    val commandWrites = CopyOnWriteArrayList<ByteArray>()
    var ackWrites = 0
    private var noiseControl = byteArrayOf(0x17, 0x01, 0x00, 0x00, 0x00, 0x0A)

    override suspend fun open() {
        _state.value = TransportState.Open
    }

    override suspend fun write(bytes: ByteArray): TransportWriteResult {
        val frame = TandemStreamDecoder().feed(bytes)
            .filterIsInstance<TandemDecodeResult.Frame>()
            .singleOrNull()
            ?.value
            ?: return TransportWriteResult.Written
        if (frame.dataType == SonyDataType.ACK.code) {
            ackWrites++
            return TransportWriteResult.Written
        }
        commandWrites += frame.payload.copyOf()
        _incoming.emit(
            TandemCodec.encode(
                TandemFrame(SonyDataType.ACK.code, 1 - (frame.sequence and 1), byteArrayOf()),
            ),
        )
        response(frame.payload)?.let { payload ->
            _incoming.emit(
                TandemCodec.encode(
                    TandemFrame(SonyDataType.DATA_MDR.code, frame.sequence and 1, payload),
                ),
            )
        }
        return TransportWriteResult.Written
    }

    override suspend fun close(cause: DisconnectCause) {
        _state.value = TransportState.Closed
    }

    private fun response(request: ByteArray): ByteArray? = when (request.firstOrNull()?.toInt()?.and(0xFF)) {
        SonyCommand.CONNECT_GET_PROTOCOL_INFO ->
            byteArrayOf(0x01, 0, 0, 0, 0, 2, 1, 1)
        SonyCommand.CONNECT_GET_CAPABILITY_INFO ->
            byteArrayOf(0x03, 0, 0x12, 0x34)
        SonyCommand.CONNECT_GET_DEVICE_INFO -> {
            val type = request.getOrNull(1) ?: return null
            val value = if (type.toInt() == 1) model else firmware
            byteArrayOf(0x05, type, value.length.toByte()) + value.toByteArray()
        }
        SonyCommand.CONNECT_GET_SUPPORT_FUNCTION ->
            byteArrayOf(0x07, 0, supportFunctions.size.toByte()) +
                supportFunctions.flatMap { value ->
                    listOf((value ushr 8).toByte(), value.toByte())
                }.toByteArray()
        SonyCommand.POWER_GET_STATUS ->
            byteArrayOf(0x23, request[1], 76, 0)
        SonyCommand.NCASM_GET_PARAM ->
            byteArrayOf(SonyCommand.NCASM_RET_PARAM.toByte()) + noiseControl
        SonyCommand.NCASM_SET_PARAM -> {
            noiseControl = request.copyOfRange(1, request.size)
            if (notifyOnSet) {
                byteArrayOf(SonyCommand.NCASM_NTFY_PARAM.toByte()) + noiseControl
            } else {
                null
            }
        }
        else -> null
    }
}
