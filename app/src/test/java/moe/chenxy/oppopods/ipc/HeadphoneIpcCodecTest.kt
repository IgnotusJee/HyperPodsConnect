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
import moe.chenxy.headphones.engine.HeadphoneSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphoneIpcCodecTest {
    @Test
    fun `all feature commands survive versioned payload round trip`() {
        val commands = listOf(
            FeatureCommand.RefreshAll,
            FeatureCommand.Refresh(FeatureId.BATTERY),
            FeatureCommand.SetNoiseControl(NoiseControlMode.ADAPTIVE),
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

        val payload = HeadphoneSnapshotPayload.from(snapshot)
        val decoded = HeadphoneIpcCodec.decodeSnapshot(
            HeadphoneIpcCodec.encodeSnapshot(payload),
        )!!

        assertEquals(HeadphoneIpcContract.VERSION, decoded.contractVersion)
        assertEquals(7, decoded.generationId)
        assertEquals("11:22:33:44:55:66", decoded.primaryAddress)
        assertEquals("Ready", decoded.connection)
        assertTrue(decoded.protocolReady)
        assertEquals(88, decoded.batteries.single().level)
        assertEquals("true", decoded.features[FeatureId.LOW_LATENCY.name]?.confirmed)
        assertEquals("READ_BACK_CONFIRMED", decoded.operation?.phase)
        assertEquals("VERIFIED", decoded.capabilities.single().evidence)
        assertEquals(listOf("false", "true"), decoded.capabilities.single().allowedValues)
        assertEquals(
            mapOf("false" to "Standard", "true" to "Low latency"),
            decoded.capabilities.single().valueLabels,
        )
    }
}
