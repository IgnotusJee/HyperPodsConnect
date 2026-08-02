package moe.chenxy.oppopods.ipc

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.BatteryState
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceTopology
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.HeadphoneState
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.ProtocolDescriptor
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import moe.chenxy.headphones.core.feature.ValueSource
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.headphones.core.operation.OperationEvent
import moe.chenxy.headphones.core.operation.OperationPhase
import moe.chenxy.headphones.core.operation.RequestId
import moe.chenxy.headphones.core.session.SessionState
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.session.FailureCategory
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphoneIpcCodecTest {
    @Test
    fun `all session states use stable IPC values`() {
        val deviceId = DeviceId("device:test")
        val states = listOf(
            SessionState.Idle(deviceId, 1) to IpcConnectionState.IDLE,
            SessionState.Detecting(deviceId, 1) to IpcConnectionState.DETECTING,
            SessionState.TransportConnecting(deviceId, 1, TransportKind.CLASSIC_SPP) to
                IpcConnectionState.TRANSPORT_CONNECTING,
            SessionState.ProtocolHandshaking(deviceId, 1, TransportKind.CLASSIC_SPP) to
                IpcConnectionState.PROTOCOL_HANDSHAKING,
            SessionState.LoadingCapabilities(deviceId, 1, TransportKind.CLASSIC_SPP) to
                IpcConnectionState.LOADING_CAPABILITIES,
            SessionState.SynchronizingState(deviceId, 1, TransportKind.CLASSIC_SPP) to
                IpcConnectionState.SYNCHRONIZING_STATE,
            SessionState.Ready(deviceId, 1, TransportKind.CLASSIC_SPP) to IpcConnectionState.READY,
            SessionState.Reconnecting(deviceId, 1, 1, DisconnectCause.LINK_LOST) to
                IpcConnectionState.RECONNECTING,
            SessionState.Disconnecting(deviceId, 1, DisconnectCause.REQUESTED) to
                IpcConnectionState.DISCONNECTING,
            SessionState.Failed(
                deviceId,
                1,
                FailureCategory.TRANSPORT,
                DisconnectCause.TRANSPORT_ERROR,
            ) to IpcConnectionState.FAILED,
        )

        states.forEach { (state, expected) ->
            val snapshot = HeadphoneSnapshot(
                deviceId = deviceId,
                generationId = 1,
                connection = state,
                emittedAtMillis = 1,
            )
            assertEquals(expected, HeadphoneSnapshotPayload.from(snapshot, "host-1").connection)
        }
    }

    @Test
    fun `all feature commands survive versioned payload round trip`() {
        val commands = listOf(
            FeatureCommand.RefreshAll,
            FeatureCommand.Refresh(FeatureId.BATTERY),
            FeatureCommand.SetNoiseControl(NoiseControlMode.ADAPTIVE),
            FeatureCommand.SetAmbientSoundLevel(17),
            FeatureCommand.SetTransparencyVocalEnhancement(true),
            FeatureCommand.SetEqualizerPreset(EqualizerPreset("oppo:2")),
            FeatureCommand.SetLowLatency(true),
            FeatureCommand.SetSpatialAudio(SpatialAudioMode.HEAD_TRACKING),
            FeatureCommand.SetSpatialSoundSwitch(false),
            FeatureCommand.SetDualDeviceConnection(true),
        )

        commands.forEach { command ->
            val encoded = HeadphoneIpcCodec.encodeCommand(IpcCommandPayload.from(command))
            val decoded = HeadphoneIpcCodec.decodeCommand(encoded)?.toFeatureCommand()
            assertEquals(command, decoded)
        }
    }

    @Test
    fun `malformed or unknown command payload is rejected`() {
        assertNull(HeadphoneIpcCodec.decodeCommand("{not-json"))
        assertNull(IpcCommandPayload("future_command", "1").toFeatureCommand())
        assertNull(
            IpcCommandPayload(
                HeadphoneIpcContract.TYPE_SET_NOISE_CONTROL,
                "vendor-wire-value",
            ).toFeatureCommand(),
        )
    }

    @Test
    fun `snapshot round trip carries profile state connection and operation`() {
        val identity = DeviceIdentity(
            DeviceId("device:test"),
            VendorId.OPPO,
            "11:22:33:44:55:66",
        )
        val capability = FeatureCapability(
            FeatureId.LOW_LATENCY,
            canRead = true,
            canWrite = true,
            evidence = EvidenceLevel.VERIFIED,
            availableOnTransports = setOf(TransportKind.CLASSIC_SPP),
            allowedValues = setOf("false", "true"),
            valueLabels = mapOf("false" to "Standard", "true" to "Low latency"),
        )
        val profile = DeviceProfile(
            identity,
            VendorId.OPPO,
            "OPPO Enco Test",
            "1.2.3",
            DeviceTopology.EARBUDS_WITH_CASE,
            TransportKind.CLASSIC_SPP,
            ProtocolDescriptor("OPPO"),
            mapOf(FeatureId.LOW_LATENCY to capability),
            CompatibilityLevel.CONTROLLED,
        )
        val state = HeadphoneState(
            batteries = mapOf(BatteryComponent.LEFT to BatteryState(88, false)),
            noiseControlActiveMode = NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
            lowLatency = moe.chenxy.headphones.core.feature.FeatureValue<Boolean>()
                .withConfirmed(true, ValueSource.READ_BACK, 10),
            firmware = "1.2.3",
        )
        val operation = OperationEvent(
            RequestId("request-1"),
            FeatureCommand.SetLowLatency(true),
            OperationPhase.READ_BACK_CONFIRMED,
            11,
            7,
        )
        val snapshot = HeadphoneSnapshot(
            identity.id,
            7,
            SessionState.Ready(identity.id, 7, TransportKind.CLASSIC_SPP),
            profile,
            state,
            operation,
            12,
        )

        val payload = HeadphoneSnapshotPayload.from(snapshot, "host-1")
        val decoded = HeadphoneIpcCodec.decodeSnapshot(
            HeadphoneIpcCodec.encodeSnapshot(payload),
        )!!
        val encoded = HeadphoneIpcCodec.encodeSnapshot(payload)

        assertEquals(HeadphoneIpcContract.VERSION, decoded.contractVersion)
        assertEquals(7, decoded.generationId)
        assertEquals("11:22:33:44:55:66", decoded.primaryAddress)
        assertEquals("host-1", decoded.hostInstanceId)
        assertEquals(IpcConnectionState.READY, decoded.connection)
        assertTrue(encoded.contains("\"connection\":\"READY\""))
        assertTrue(decoded.protocolReady)
        assertEquals("EARBUDS_WITH_CASE", decoded.topology)
        assertEquals(88, decoded.batteries.single().level)
        assertEquals("NOISE_CANCELLATION_MEDIUM", decoded.noiseControlActiveMode)
        assertEquals("true", decoded.features[FeatureId.LOW_LATENCY.name]?.confirmed)
        assertEquals("READ_BACK_CONFIRMED", decoded.operation?.phase)
        assertEquals("VERIFIED", decoded.capabilities.single().evidence)
        assertEquals(listOf("false", "true"), decoded.capabilities.single().allowedValues)
        assertEquals(
            mapOf("false" to "Standard", "true" to "Low latency"),
            decoded.capabilities.single().valueLabels,
        )
    }

    @Test
    fun `detected profile does not expose state before read-only evidence`() {
        val identity = DeviceIdentity(
            DeviceId("device:sony"),
            VendorId.SONY,
            "11:22:33:44:55:66",
        )
        val profile = DeviceProfile(
            identity,
            VendorId.SONY,
            "WH-1000XM4",
            "unverified",
            DeviceTopology.HEADBAND,
            TransportKind.CLASSIC_SPP,
            ProtocolDescriptor("Sony Tandem"),
            mapOf(
                FeatureId.BATTERY to FeatureCapability(
                    FeatureId.BATTERY,
                    canRead = true,
                    canWrite = false,
                    evidence = EvidenceLevel.ASSUMED,
                    availableOnTransports = setOf(TransportKind.CLASSIC_SPP),
                ),
            ),
            CompatibilityLevel.DETECTED,
        )
        val snapshot = HeadphoneSnapshot(
            identity.id,
            1,
            SessionState.ProtocolHandshaking(identity.id, 1, TransportKind.CLASSIC_SPP),
            profile,
            HeadphoneState(
                batteries = mapOf(BatteryComponent.SINGLE to BatteryState(99, false)),
                firmware = "unverified",
            ),
            emittedAtMillis = 2,
        )

        val payload = HeadphoneSnapshotPayload.from(snapshot, "host-1")

        assertNull(payload.deviceName)
        assertNull(payload.topology)
        assertNull(payload.firmware)
        assertTrue(payload.batteries.isEmpty())
        assertTrue(payload.features.isEmpty())
        assertTrue(payload.capabilities.isEmpty())
    }
}
