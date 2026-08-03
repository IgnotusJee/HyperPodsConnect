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
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.EqualizerCurve
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
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyEqualizerFeature
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolGeneration
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
    fun `LinkBuds S prefers validated GATT when a cached SPP UUID is also present`() {
        val candidate = candidate(
            advertisedUuids = setOf(SonyProfile.SONY_SPP_V2_UUID),
            availableTransports = setOf(TransportKind.CLASSIC_SPP, TransportKind.BLE_GATT),
        ).copy(displayName = "LinkBuds S")

        val route = SonySession.resolveTransport(candidate)

        assertEquals(TransportKind.BLE_GATT, route?.kind)
        assertTrue(route?.spec is TransportSpec.Gatt)
        assertEquals(
            moe.chenxy.headphones.protocol.sony.session.DetectionBasis.BONDED_SERVICE_VALIDATION,
            route?.detectionBasis,
        )
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

            assertEquals(CompatibilityLevel.STABLE, session.profile.value?.compatibilityLevel)
            assertTrue(session.profile.value?.capability(FeatureId.NOISE_CONTROL)?.isWritable == true)
            assertTrue(
                session.profile.value
                    ?.capability(FeatureId.AMBIENT_SOUND_LEVEL)
                    ?.isWritable == true,
            )
            assertTrue(
                session.profile.value
                    ?.capability(FeatureId.TRANSPARENCY_VOCAL_ENHANCEMENT)
                    ?.isWritable == true,
            )
            assertEquals(
                (1..20).map(Int::toString).toSet(),
                session.profile.value
                    ?.capability(FeatureId.AMBIENT_SOUND_LEVEL)
                    ?.allowedValues,
            )
            assertEquals(NoiseControlMode.OFF, session.state.value.noiseControl.confirmed)
            assertEquals(10, session.state.value.ambientSoundLevel.confirmed)
            assertEquals(false, session.state.value.transparencyVocalEnhancement.confirmed)

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
    fun `ambient level requires transparency reads back and restores original value`() =
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

            val writesBeforeRejected = transport.commandWrites.size
            val rejectedOutsideAmbient = session.execute(
                FeatureCommand.SetAmbientSoundLevel(11),
            )
            assertEquals(FailureReason.NOT_WRITABLE, rejectedOutsideAmbient.failure)
            val rejectedVoiceOutsideAmbient = session.execute(
                FeatureCommand.SetTransparencyVocalEnhancement(true),
            )
            assertEquals(FailureReason.NOT_WRITABLE, rejectedVoiceOutsideAmbient.failure)
            assertEquals(writesBeforeRejected, transport.commandWrites.size)

            assertTrue(
                session.execute(
                    FeatureCommand.SetNoiseControl(NoiseControlMode.TRANSPARENCY),
                ).succeeded,
            )
            val changed = session.execute(FeatureCommand.SetAmbientSoundLevel(11))
            assertTrue(changed.succeeded)
            assertEquals(11, session.state.value.ambientSoundLevel.confirmed)
            assertEquals(
                byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0B).toList(),
                transport.commandWrites.last {
                    it.first().toInt() and 0xFF == SonyCommand.NCASM_SET_PARAM
                }.toList(),
            )
            assertEquals(
                SonyCommand.NCASM_GET_PARAM,
                transport.commandWrites.last().first().toInt() and 0xFF,
            )

            val voice = session.execute(
                FeatureCommand.SetTransparencyVocalEnhancement(true),
            )
            assertTrue(voice.succeeded)
            assertEquals(true, session.state.value.transparencyVocalEnhancement.confirmed)
            assertEquals(11, session.state.value.ambientSoundLevel.confirmed)
            assertEquals(
                byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x01, 0x01, 0x0B).toList(),
                transport.commandWrites.last {
                    it.first().toInt() and 0xFF == SonyCommand.NCASM_SET_PARAM
                }.toList(),
            )

            val normal = session.execute(
                FeatureCommand.SetTransparencyVocalEnhancement(false),
            )
            assertTrue(normal.succeeded)
            assertEquals(false, session.state.value.transparencyVocalEnhancement.confirmed)
            assertEquals(11, session.state.value.ambientSoundLevel.confirmed)

            val writesBeforeRangeFailure = transport.commandWrites.size
            val outOfRange = session.execute(FeatureCommand.SetAmbientSoundLevel(21))
            assertEquals(FailureReason.VALUE_OUT_OF_RANGE, outOfRange.failure)
            assertEquals(writesBeforeRangeFailure, transport.commandWrites.size)

            val restored = session.execute(FeatureCommand.SetAmbientSoundLevel(10))
            assertTrue(restored.succeeded)
            assertEquals(10, session.state.value.ambientSoundLevel.confirmed)
            session.disconnect()
        }

    @Test
    fun `all captured LinkBuds S EQ presets notify read back and restore`() = runBlocking {
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

        assertTrue(session.profile.value?.capability(FeatureId.EQUALIZER)?.isWritable == true)
        assertEquals(
            SonyEqualizerFeature.allowedPresetIds,
            session.profile.value?.capability(FeatureId.EQUALIZER)?.allowedValues,
        )
        assertEquals(
            setOf(
                SonyEqualizerFeature.MANUAL_ID,
                SonyEqualizerFeature.CUSTOM_1_ID,
                SonyEqualizerFeature.CUSTOM_2_ID,
            ),
            session.profile.value?.capability(FeatureId.EQUALIZER)
                ?.equalizerCurveSpec?.writableSlotIds,
        )
        assertEquals(
            SonyEqualizerFeature.BASS_BOOST_ID,
            session.state.value.equalizer.confirmed?.id,
        )

        val speech = session.execute(
            FeatureCommand.SetEqualizerPreset(
                EqualizerPreset(SonyEqualizerFeature.SPEECH_ID),
            ),
        )
        assertTrue(speech.succeeded)
        assertEquals(SonyEqualizerFeature.SPEECH_ID, session.state.value.equalizer.confirmed?.id)
        assertEquals(
            byteArrayOf(0x58, 0x00, 0x17, 0x00).toList(),
            transport.commandWrites.last {
                it.first().toInt() and 0xFF == SonyCommand.EQEBB_SET_PARAM
            }.toList(),
        )
        assertEquals(
            SonyCommand.EQEBB_GET_PARAM,
            transport.commandWrites.last().first().toInt() and 0xFF,
        )

        SonyEqualizerFeature.allowedPresetIds.forEach { presetId ->
            val result = session.execute(
                FeatureCommand.SetEqualizerPreset(EqualizerPreset(presetId)),
            )
            assertTrue(presetId, result.succeeded)
            assertEquals(presetId, session.state.value.equalizer.confirmed?.id)
            assertEquals(
                SonyCommand.EQEBB_GET_PARAM,
                transport.commandWrites.last().first().toInt() and 0xFF,
            )
        }

        assertTrue(
            session.execute(
                FeatureCommand.SetEqualizerPreset(
                    EqualizerPreset(SonyEqualizerFeature.CUSTOM_1_ID),
                ),
            ).succeeded,
        )
        val originalCurve = requireNotNull(session.state.value.equalizerCurve.confirmed)
        val modifiedCurve = originalCurve.copy(
            gains = originalCurve.gains.toMutableList().apply { this[1] -= 1 },
        )
        assertTrue(session.execute(FeatureCommand.SetEqualizerCurve(modifiedCurve)).succeeded)
        assertEquals(modifiedCurve, session.state.value.equalizerCurve.confirmed)
        assertEquals(
            byteArrayOf(
                0x58, 0x00, 0xA1.toByte(), 0x06,
                0x08, 0x10, 0x0A, 0x0A, 0x0B, 0x0C,
            ).toList(),
            transport.commandWrites.last {
                it.first().toInt() and 0xFF == SonyCommand.EQEBB_SET_PARAM && it.size > 4
            }.toList(),
        )
        assertTrue(session.execute(FeatureCommand.SetEqualizerCurve(originalCurve)).succeeded)
        assertEquals(originalCurve, session.state.value.equalizerCurve.confirmed)

        val restore = session.execute(
            FeatureCommand.SetEqualizerPreset(
                EqualizerPreset(SonyEqualizerFeature.BASS_BOOST_ID),
            ),
        )
        assertTrue(restore.succeeded)
        assertEquals(
            SonyEqualizerFeature.BASS_BOOST_ID,
            session.state.value.equalizer.confirmed?.id,
        )

        val writesBeforeInvalid = transport.commandWrites.size
        val invalid = session.execute(
            FeatureCommand.SetEqualizerPreset(EqualizerPreset("sony:eq:18")),
        )
        assertEquals(FailureReason.VALUE_OUT_OF_RANGE, invalid.failure)
        assertEquals(writesBeforeInvalid, transport.commandWrites.size)
        session.disconnect()
    }

    @Test
    fun `unlisted Sony V2 model exposes custom EQ from live capability`() = runBlocking {
        val transport = FakeSonyTransport(
            kind = TransportKind.BLE_GATT,
            model = "WF-1000XM5",
            firmware = "9.9.9",
            equalizerSupported = true,
        )
        val candidate = candidate(
            advertisedUuids = emptySet(),
            availableTransports = setOf(TransportKind.BLE_GATT),
        ).copy(displayName = "WF-1000XM5")
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

        val capability = requireNotNull(session.profile.value?.capability(FeatureId.EQUALIZER))
        assertTrue(capability.isWritable)
        assertEquals(
            setOf(
                SonyEqualizerFeature.MANUAL_ID,
                SonyEqualizerFeature.CUSTOM_1_ID,
                SonyEqualizerFeature.CUSTOM_2_ID,
            ),
            capability.equalizerCurveSpec?.writableSlotIds,
        )
        assertEquals(CompatibilityLevel.CONTROLLED, session.profile.value?.compatibilityLevel)
        session.disconnect()
    }

    @Test
    fun `unlisted Sony V1 model queries and exposes custom EQ without enabling noise control`() =
        runBlocking {
            val transport = FakeSonyTransport(
                protocolGeneration = SonyProtocolGeneration.V1,
                model = "WH-1000XM3",
                firmware = "4.5.2",
            )
            val candidate = candidate(
                advertisedUuids = setOf(SonyProfile.SONY_SPP_V1_UUID),
                availableTransports = setOf(TransportKind.CLASSIC_SPP),
            ).copy(displayName = "WH-1000XM3")
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

            val equalizer = requireNotNull(session.profile.value?.capability(FeatureId.EQUALIZER))
            assertTrue(equalizer.isWritable)
            assertEquals(
                setOf(SonyEqualizerFeature.CUSTOM_1_ID),
                equalizer.equalizerCurveSpec?.writableSlotIds,
            )
            assertFalse(
                session.profile.value?.capability(FeatureId.NOISE_CONTROL)?.isWritable == true,
            )
            session.disconnect()
        }

    @Test
    fun `exact WH v1 SPP writes notifies reads back and restores NCASM and EQ`() = runBlocking {
        val transport = FakeSonyTransport(protocolGeneration = SonyProtocolGeneration.V1)
        val factory = object : TransportFactory {
            override suspend fun create(
                device: DeviceIdentity,
                spec: TransportSpec,
            ): ByteTransport {
                assertEquals(SonyProfile.SONY_SPP_V1_UUID, (spec as TransportSpec.Spp).serviceUuid)
                return transport
            }
        }
        val session = SonySession(
            DriverSessionContext(
                candidate(advertisedUuids = setOf(SonyProfile.SONY_SPP_V1_UUID)),
                factory,
            ),
            responseTimeoutMillis = 300,
            transportSettleDelayMillis = 0,
        )

        session.connect()

        assertTrue(session.connection.value is SessionState.Ready)
        assertEquals(CompatibilityLevel.STABLE, session.profile.value?.compatibilityLevel)
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, session.state.value.noiseControl.confirmed)
        assertEquals(SonyEqualizerFeature.BASS_BOOST_ID, session.state.value.equalizer.confirmed?.id)
        assertTrue(session.profile.value?.capability(FeatureId.NOISE_CONTROL)?.canRead == true)
        assertTrue(session.profile.value?.capability(FeatureId.NOISE_CONTROL)?.canWrite == true)
        assertTrue(session.profile.value?.capability(FeatureId.EQUALIZER)?.canRead == true)
        assertTrue(session.profile.value?.capability(FeatureId.EQUALIZER)?.canWrite == true)
        assertEquals(
            setOf("OFF", "NOISE_CANCELLATION", "TRANSPARENCY"),
            session.profile.value?.capability(FeatureId.NOISE_CONTROL)?.allowedValues,
        )

        val ambient = session.execute(
            FeatureCommand.SetNoiseControl(NoiseControlMode.TRANSPARENCY),
        )
        assertTrue(ambient.succeeded)
        assertEquals(NoiseControlMode.TRANSPARENCY, session.state.value.noiseControl.confirmed)
        assertEquals(20, session.state.value.ambientSoundLevel.confirmed)
        assertEquals(
            byteArrayOf(0x68, 0x02, 0x03, 0x02, 0x00, 0x01, 0x00, 0x14).toList(),
            transport.commandWrites.last {
                it.first().toInt() and 0xFF == SonyCommand.NCASM_SET_PARAM
            }.toList(),
        )

        assertTrue(session.execute(FeatureCommand.SetAmbientSoundLevel(11)).succeeded)
        assertTrue(
            session.execute(
                FeatureCommand.SetTransparencyVocalEnhancement(true),
            ).succeeded,
        )
        assertEquals(11, session.state.value.ambientSoundLevel.confirmed)
        assertEquals(true, session.state.value.transparencyVocalEnhancement.confirmed)
        assertTrue(
            session.execute(
                FeatureCommand.SetTransparencyVocalEnhancement(false),
            ).succeeded,
        )
        assertTrue(
            session.execute(
                FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
            ).succeeded,
        )
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, session.state.value.noiseControl.confirmed)

        assertTrue(
            session.execute(
                FeatureCommand.SetEqualizerPreset(
                    EqualizerPreset(SonyEqualizerFeature.OFF_ID),
                ),
            ).succeeded,
        )
        assertEquals(SonyEqualizerFeature.OFF_ID, session.state.value.equalizer.confirmed?.id)
        assertEquals(
            byteArrayOf(0x58, 0x01, 0x00, 0x00).toList(),
            transport.commandWrites.last {
                it.first().toInt() and 0xFF == SonyCommand.EQEBB_SET_PARAM
            }.toList(),
        )
        assertTrue(
            session.execute(
                FeatureCommand.SetEqualizerPreset(
                    EqualizerPreset(SonyEqualizerFeature.BASS_BOOST_ID),
                ),
            ).succeeded,
        )
        assertEquals(SonyEqualizerFeature.BASS_BOOST_ID, session.state.value.equalizer.confirmed?.id)

        assertTrue(
            session.execute(
                FeatureCommand.SetEqualizerPreset(
                    EqualizerPreset(SonyEqualizerFeature.CUSTOM_1_ID),
                ),
            ).succeeded,
        )
        val originalCurve = requireNotNull(session.state.value.equalizerCurve.confirmed)
        val modifiedCurve = EqualizerCurve(
            slotId = SonyEqualizerFeature.CUSTOM_1_ID,
            gains = originalCurve.gains.toMutableList().apply { this[0] += 1 },
        )
        assertTrue(session.execute(FeatureCommand.SetEqualizerCurve(modifiedCurve)).succeeded)
        assertEquals(modifiedCurve, session.state.value.equalizerCurve.confirmed)
        assertEquals(
            byteArrayOf(
                0x58, 0x01, 0xFF.toByte(), 0x06,
                0x09, 0x11, 0x0A, 0x0A, 0x0B, 0x0C,
            ).toList(),
            transport.commandWrites.last {
                it.first().toInt() and 0xFF == SonyCommand.EQEBB_SET_PARAM && it.size > 4
            }.toList(),
        )
        assertTrue(session.execute(FeatureCommand.SetEqualizerCurve(originalCurve)).succeeded)
        assertEquals(originalCurve, session.state.value.equalizerCurve.confirmed)

        val off = session.execute(FeatureCommand.SetNoiseControl(NoiseControlMode.OFF))
        assertTrue(off.succeeded)
        assertEquals(NoiseControlMode.OFF, session.state.value.noiseControl.confirmed)
        assertEquals(
            byteArrayOf(0x68, 0x02, 0x00, 0x02, 0x02, 0x01, 0x00, 0x00).toList(),
            transport.commandWrites.last {
                it.first().toInt() and 0xFF == SonyCommand.NCASM_SET_PARAM
            }.toList(),
        )
        assertTrue(
            session.execute(
                FeatureCommand.SetNoiseControl(NoiseControlMode.NOISE_CANCELLATION),
            ).succeeded,
        )
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, session.state.value.noiseControl.confirmed)
        session.disconnect()
    }

    @Test
    fun `nearby firmware keeps noise control gated but enables capability verified EQ`() = runBlocking {
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
        val eqResult = session.execute(
            FeatureCommand.SetEqualizerPreset(
                EqualizerPreset(SonyEqualizerFeature.SPEECH_ID),
            ),
        )

        assertEquals(FailureReason.NOT_SUPPORTED, result.failure)
        assertTrue(eqResult.succeeded)
        assertTrue(transport.commandWrites.size > writesBefore)
        assertFalse(
            transport.commandWrites.any {
                it.first().toInt() and 0xFF == SonyCommand.NCASM_SET_PARAM
            },
        )
        assertTrue(
            transport.commandWrites.any {
                it.first().toInt() and 0xFF == SonyCommand.EQEBB_SET_PARAM
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

        assertTrue(
            session.execute(
                FeatureCommand.SetNoiseControl(NoiseControlMode.TRANSPARENCY),
            ).succeeded,
        )
        val voiceResult = session.execute(
            FeatureCommand.SetTransparencyVocalEnhancement(true),
        )
        assertTrue(voiceResult.succeeded)
        assertEquals(true, session.state.value.transparencyVocalEnhancement.confirmed)
        assertEquals(
            SonyCommand.NCASM_GET_PARAM,
            transport.commandWrites.last().first().toInt() and 0xFF,
        )

        val eqResult = session.execute(
            FeatureCommand.SetEqualizerPreset(
                EqualizerPreset(SonyEqualizerFeature.SPEECH_ID),
            ),
        )
        assertTrue(eqResult.succeeded)
        assertEquals(
            SonyCommand.EQEBB_GET_PARAM,
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
    private val protocolGeneration: SonyProtocolGeneration = SonyProtocolGeneration.V2,
    private val model: String = "WH-1000XM4",
    private val firmware: String = "2.5.1",
    private val supportFunctions: IntArray = intArrayOf(0x0001),
    private val notifyOnSet: Boolean = true,
    private val equalizerSupported: Boolean =
        model == "LinkBuds S" || protocolGeneration == SonyProtocolGeneration.V1,
) : ByteTransport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Closed)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()
    override val maxWriteSize: StateFlow<Int> = MutableStateFlow(512).asStateFlow()
    val commandWrites = CopyOnWriteArrayList<ByteArray>()
    var ackWrites = 0
    private var noiseControl = byteArrayOf(0x17, 0x01, 0x00, 0x00, 0x00, 0x0A)
    private var v1NoiseControl = byteArrayOf(0x02, 0x01, 0x02, 0x02, 0x01, 0x00, 0x00)
    private var equalizer: ByteArray? = if (equalizerSupported) {
        if (protocolGeneration == SonyProtocolGeneration.V1) {
            byteArrayOf(0x01, 0x16, 0x06, 0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)
        } else {
            byteArrayOf(0x00, 0x16, 0x06, 0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)
        }
    } else null

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
            if (protocolGeneration == SonyProtocolGeneration.V1) {
                byteArrayOf(0x01, 0, 0x70, 0)
            } else {
                byteArrayOf(0x01, 0, 0, 0, 0, 2, 1, 1)
            }
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
        SonyCommand.COMMON_GET_BATTERY_LEVEL ->
            byteArrayOf(SonyCommand.COMMON_RET_BATTERY_LEVEL.toByte(), request[1], 76, 0)
        SonyCommand.NCASM_GET_CAPABILITY ->
            if (protocolGeneration == SonyProtocolGeneration.V1) {
                byteArrayOf(
                    SonyCommand.NCASM_RET_CAPABILITY.toByte(),
                    0x02, 0x02, 0x03, 0x01, 0x02,
                    0x00, 0x14, 0x01, 0x14,
                )
            } else {
                null
            }
        SonyCommand.NCASM_GET_PARAM ->
            if (protocolGeneration == SonyProtocolGeneration.V1) {
                byteArrayOf(SonyCommand.NCASM_RET_PARAM.toByte()) + v1NoiseControl
            } else {
                byteArrayOf(SonyCommand.NCASM_RET_PARAM.toByte()) + noiseControl
            }
        SonyCommand.NCASM_SET_PARAM -> {
            if (protocolGeneration == SonyProtocolGeneration.V1) {
                if (request.size != 8 || request[1] != 0x02.toByte()) return null
                v1NoiseControl = request.copyOfRange(1, request.size)
            } else {
                noiseControl = request.copyOfRange(1, request.size)
            }
            if (notifyOnSet) {
                byteArrayOf(SonyCommand.NCASM_NTFY_PARAM.toByte()) +
                    if (protocolGeneration == SonyProtocolGeneration.V1) {
                        v1NoiseControl
                    } else {
                        noiseControl
                    }
            } else {
                null
            }
        }
        SonyCommand.EQEBB_GET_CAPABILITY ->
            if (protocolGeneration == SonyProtocolGeneration.V1) {
                byteArrayOf(
                    SonyCommand.EQEBB_RET_CAPABILITY.toByte(),
                    0x01, 0x06, 0x15, 0x03,
                    0x00, 0x00,
                    0x16, 0x00,
                    0xA1.toByte(), 0x00,
                )
            } else {
                byteArrayOf(
                    SonyCommand.EQEBB_RET_CAPABILITY.toByte(),
                    0x00, 0x06, 0x15, 0x0C,
                    0x00, 0x00,
                    0x10, 0x00,
                    0x11, 0x00,
                    0x12, 0x00,
                    0x13, 0x00,
                    0x14, 0x00,
                    0x15, 0x00,
                    0x16, 0x00,
                    0x17, 0x00,
                    0xA0.toByte(), 0x00,
                    0xA1.toByte(), 0x00,
                    0xA2.toByte(), 0x00,
                )
            }
        SonyCommand.EQEBB_GET_PARAM ->
            equalizer?.let { byteArrayOf(SonyCommand.EQEBB_RET_PARAM.toByte()) + it }
        SonyCommand.EQEBB_SET_PARAM -> {
            val selector = if (protocolGeneration == SonyProtocolGeneration.V1) 0x01 else 0x00
            if (
                request.size == 10 &&
                request[1] == selector.toByte() &&
                request[3] == 0x06.toByte() &&
                (
                    protocolGeneration == SonyProtocolGeneration.V1 && request[2] == 0xFF.toByte() ||
                        protocolGeneration == SonyProtocolGeneration.V2 &&
                        request[2] == equalizer?.getOrNull(1)
                    )
            ) {
                val activePreset = if (protocolGeneration == SonyProtocolGeneration.V1) {
                    equalizer?.getOrNull(1) ?: return null
                } else {
                    request[2]
                }
                equalizer = byteArrayOf(selector.toByte(), activePreset, 0x06) +
                    request.copyOfRange(4, request.size)
                return if (notifyOnSet) {
                    byteArrayOf(SonyCommand.EQEBB_NTFY_PARAM.toByte()) + requireNotNull(equalizer)
                } else {
                    null
                }
            }
            if (
                request.size != 4 || request[1] != selector.toByte() ||
                request[3] != 0x00.toByte()
            ) return null
            equalizer = when (request[2].toInt() and 0xFF) {
                0x00 -> byteArrayOf(selector.toByte(), 0x00, 0x06, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)
                0x10 -> byteArrayOf(selector.toByte(), 0x10, 0x06, 0x09, 0x0A, 0x0F, 0x11, 0x11, 0x13)
                0x11 -> byteArrayOf(selector.toByte(), 0x11, 0x06, 0x12, 0x09, 0x0B, 0x0A, 0x0D, 0x0F)
                0x12 -> byteArrayOf(selector.toByte(), 0x12, 0x06, 0x07, 0x09, 0x08, 0x07, 0x06, 0x04)
                0x13 -> byteArrayOf(selector.toByte(), 0x13, 0x06, 0x01, 0x07, 0x09, 0x07, 0x05, 0x02)
                0x14 -> byteArrayOf(selector.toByte(), 0x14, 0x06, 0x0A, 0x10, 0x0E, 0x0C, 0x0D, 0x09)
                0x15 -> byteArrayOf(selector.toByte(), 0x15, 0x06, 0x0A, 0x0A, 0x0A, 0x0C, 0x10, 0x14)
                0x16 -> byteArrayOf(selector.toByte(), 0x16, 0x06, 0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)
                0x17 -> byteArrayOf(selector.toByte(), 0x17, 0x06, 0x00, 0x0E, 0x0D, 0x0B, 0x0C, 0x00)
                0xA0 -> byteArrayOf(selector.toByte(), 0xA0.toByte(), 0x06, 0x00, 0x0A, 0x0A, 0x0A, 0x06, 0x02)
                0xA1 -> byteArrayOf(selector.toByte(), 0xA1.toByte(), 0x06, 0x08, 0x11, 0x0A, 0x0A, 0x0B, 0x0C)
                0xA2 -> byteArrayOf(selector.toByte(), 0xA2.toByte(), 0x06, 0x01, 0x01, 0x14, 0x0A, 0x0B, 0x0C)
                else -> return null
            }
            if (notifyOnSet) {
                byteArrayOf(SonyCommand.EQEBB_NTFY_PARAM.toByte()) + requireNotNull(equalizer)
            } else {
                null
            }
        }
        else -> null
    }
}
