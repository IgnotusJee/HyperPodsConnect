package moe.chenxy.headphones.protocol.sony

import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.protocol.sony.feature.SonyHandshake
import moe.chenxy.headphones.protocol.sony.feature.SonyProtocolGeneration
import moe.chenxy.headphones.protocol.sony.feature.battery.SonyBatteryFeature
import moe.chenxy.headphones.protocol.sony.feature.equalizer.SonyEqualizerFeature
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyAmbientSoundMode
import moe.chenxy.headphones.protocol.sony.feature.noisecontrol.SonyNoiseControlFeature
import moe.chenxy.headphones.protocol.sony.frame.TandemDecodeResult
import moe.chenxy.headphones.protocol.sony.frame.TandemStreamDecoder
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyDataType
import moe.chenxy.headphones.protocol.sony.message.SonyDeviceInfoType
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage
import moe.chenxy.headphones.protocol.sony.message.SonyMdrRouter
import moe.chenxy.headphones.protocol.sony.message.SonyRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyParsersTest {
    @Test
    fun `parses v2 protocol tables and device strings strictly`() {
        val protocol = SonyHandshake.parseProtocolInfo(
            message(byteArrayOf(0x01, 0, 0, 0, 0, 2, 1, 1)),
        )
        val modelBytes = "LinkBuds S".toByteArray()
        val model = SonyHandshake.parseDeviceInfo(
            message(byteArrayOf(0x05, 0x01, modelBytes.size.toByte()) + modelBytes),
            SonyDeviceInfoType.MODEL_NAME,
        )

        assertEquals(SonyProtocolGeneration.V2, protocol?.generation)
        assertEquals(2L, protocol?.version)
        assertTrue(protocol!!.table1Enabled)
        assertTrue(protocol.table2Enabled)
        assertEquals("LinkBuds S", model)
    }

    @Test
    fun `captured v2 reserved zeros do not disable MDR command tables`() {
        val protocol = SonyHandshake.parseProtocolInfo(
            message(byteArrayOf(0x01, 0x00, 0x03, 0x00, 0x20, 0x15, 0x00, 0x00)),
        )

        assertEquals(SonyProtocolGeneration.V2, protocol?.generation)
        assertTrue(protocol!!.table1Enabled)
        assertTrue(protocol.table2Enabled)
    }

    @Test
    fun `rejects malformed support count and device length`() {
        assertNull(
            SonyHandshake.parseSupportFunction(
                message(byteArrayOf(0x07, 0, 3, 0, 1, 2)),
            ),
        )
        assertNull(
            SonyHandshake.parseDeviceInfo(
                message(byteArrayOf(0x05, 0x01, 3, 'W'.code.toByte())),
                SonyDeviceInfoType.MODEL_NAME,
            ),
        )
    }

    @Test
    fun `v1 support length is bytes and yields eleven two-byte functions`() {
        val support = SonyHandshake.parseSupportFunction(
            message(
                byteArrayOf(
                    0x07, 0x00, 0x16,
                    0x71, 0x62, 0xF5.toByte(), 0x81.toByte(),
                    0x51, 0xA1.toByte(), 0xE1.toByte(), 0xE2.toByte(),
                    0xD2.toByte(), 0xF6.toByte(), 0xD1.toByte(), 0xF4.toByte(),
                    0xF3.toByte(), 0x39, 0x12, 0x13, 0x11, 0x30,
                    0xC1.toByte(), 0x14, 0x22, 0x21,
                ),
            ),
        )

        assertEquals(11, support?.functions?.size)
        assertEquals(0x7162, support?.functions?.first())
        assertEquals(0x2221, support?.functions?.last())
    }

    @Test
    fun `v2 support count yields forty two-byte functions from LinkBuds S fixture`() {
        val functions = intArrayOf(
            0x10FF, 0x12FF, 0x13FF, 0x14FF, 0x17FF, 0x23FF, 0x251F, 0x11FF,
            0x27FF, 0x29FF, 0x2AFF, 0x92FF, 0x3224, 0x401A, 0x44FF, 0x94FF,
            0x4222, 0x500A, 0x6B06, 0x7001, 0x90FF, 0xA20C, 0xC1FF, 0xC2FF,
            0xE20F, 0xF121, 0xFC09, 0x93FF, 0xF61E, 0xF925, 0x4D2A, 0x472B,
            0x4E2C, 0x4C2D, 0xFE15, 0x4BFF, 0x482E, 0x4AFF, 0x49FF, 0x46FF,
        )
        val payload = byteArrayOf(0x07, 0x00, functions.size.toByte()) +
            functions.flatMap { value ->
                listOf((value ushr 8).toByte(), value.toByte())
            }.toByteArray()

        val support = SonyHandshake.parseSupportFunction(message(payload))

        assertEquals(40, support?.functions?.size)
        assertEquals(0x10FF, support?.functions?.first())
        assertEquals(0x46FF, support?.functions?.last())
    }

    @Test
    fun `maps left right and cradle battery without treating charged as charging`() {
        val pair = SonyBatteryFeature.parse(
            SonyProtocolGeneration.V2,
            message(byteArrayOf(0x23, 1, 81, 1, 75, 3)),
        )!!
        val cradle = SonyBatteryFeature.parse(
            SonyProtocolGeneration.V2,
            message(byteArrayOf(0x25, 2, 66, 0)),
        )!!

        assertEquals(81, pair.values[BatteryComponent.LEFT]?.level)
        assertTrue(pair.values[BatteryComponent.LEFT]?.charging == true)
        assertFalse(pair.values[BatteryComponent.RIGHT]?.charging == true)
        assertEquals(66, cradle.values[BatteryComponent.CASE]?.level)
    }

    @Test
    fun `parses captured LinkBuds S NCASM readback and builds exact reversible writes`() {
        val off = SonyNoiseControlFeature.parse(
            message(byteArrayOf(0x67, 0x17, 0x01, 0x00, 0x00, 0x00, 0x0A)),
        )!!
        val ambient = SonyNoiseControlFeature.parse(
            message(byteArrayOf(0x69, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0A)),
        )!!

        assertEquals(NoiseControlMode.OFF, off.mode)
        assertEquals(10, off.ambientLevel)
        assertEquals(
            byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x0A).toList(),
            SonyNoiseControlFeature.set(NoiseControlMode.NOISE_CANCELLATION, off)?.toList(),
        )
        assertEquals(NoiseControlMode.TRANSPARENCY, ambient.mode)
        assertEquals(SonyAmbientSoundMode.NORMAL, ambient.ambientSoundMode)
        assertEquals(
            byteArrayOf(0x68, 0x17, 0x01, 0x00, 0x01, 0x00, 0x0A).toList(),
            SonyNoiseControlFeature.set(NoiseControlMode.OFF, ambient)?.toList(),
        )
    }

    @Test
    fun `ambient level write preserves voice mode and rejects unsafe values`() {
        val voice = SonyNoiseControlFeature.parse(
            message(byteArrayOf(0x67, 0x17, 0x01, 0x01, 0x01, 0x01, 0x0A)),
        )!!

        assertEquals(SonyAmbientSoundMode.VOICE, voice.ambientSoundMode)
        assertEquals(
            byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x01, 0x01, 0x14).toList(),
            SonyNoiseControlFeature.setAmbientLevel(20, voice)?.toList(),
        )
        assertNull(SonyNoiseControlFeature.setAmbientLevel(0, voice))
        assertNull(SonyNoiseControlFeature.setAmbientLevel(21, voice))
        assertEquals(
            byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x00, 0x01, 0x0A).toList(),
            SonyNoiseControlFeature.set(NoiseControlMode.NOISE_CANCELLATION, voice)?.toList(),
        )
    }

    @Test
    fun `ambient sound mode write preserves level and requires transparency`() {
        val ambient = SonyNoiseControlFeature.parse(
            message(byteArrayOf(0x67, 0x17, 0x01, 0x01, 0x01, 0x00, 0x14)),
        )!!
        val off = SonyNoiseControlFeature.parse(
            message(byteArrayOf(0x67, 0x17, 0x01, 0x00, 0x01, 0x00, 0x14)),
        )!!

        assertEquals(
            byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x01, 0x01, 0x14).toList(),
            SonyNoiseControlFeature.setAmbientSoundMode(
                SonyAmbientSoundMode.VOICE,
                ambient,
            )?.toList(),
        )
        assertEquals(
            byteArrayOf(0x68, 0x17, 0x01, 0x01, 0x01, 0x00, 0x14).toList(),
            SonyNoiseControlFeature.setAmbientSoundMode(
                SonyAmbientSoundMode.NORMAL,
                ambient,
            )?.toList(),
        )
        assertNull(
            SonyNoiseControlFeature.setAmbientSoundMode(SonyAmbientSoundMode.VOICE, off),
        )
    }

    @Test
    fun `rejects unobserved NCASM layouts and unsupported modes`() {
        val off = SonyNoiseControlFeature.parse(
            message(byteArrayOf(0x67, 0x17, 0x01, 0x00, 0x00, 0x00, 0x0A)),
        )!!

        assertNull(
            SonyNoiseControlFeature.parse(
                message(byteArrayOf(0x67, 0x17, 0x02, 0x00, 0x00, 0x00, 0x0A)),
            ),
        )
        assertNull(
            SonyNoiseControlFeature.parse(
                message(byteArrayOf(0x69, 0x17, 0x00, 0x01, 0x01, 0x00, 0x0A)),
            ),
        )
        assertNull(
            SonyNoiseControlFeature.parse(
                message(byteArrayOf(0x67, 0x17, 0x01, 0x01, 0x01, 0x00, 0x00)),
            ),
        )
        assertNull(
            SonyNoiseControlFeature.set(NoiseControlMode.ADAPTIVE, off),
        )
        assertNull(SonyNoiseControlFeature.setAmbientLevel(10, off))
    }

    @Test
    fun `parses captured EQ presets and builds only exact reversible writes`() {
        val bass = SonyEqualizerFeature.parse(
            message(byteArrayOf(0x57, 0x00, 0x16, 0x06, 0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)),
        )!!
        val speech = SonyEqualizerFeature.parse(
            message(byteArrayOf(0x59, 0x00, 0x17, 0x06, 0x00, 0x0E, 0x0D, 0x0B, 0x0C, 0x00)),
        )!!

        assertEquals(SonyEqualizerFeature.BASS_BOOST_ID, bass.preset.id)
        assertEquals(listOf(0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A), bass.bandValues)
        assertEquals(SonyEqualizerFeature.SPEECH_ID, speech.preset.id)
        assertEquals(
            byteArrayOf(0x58, 0x00, 0x17, 0x00).toList(),
            SonyEqualizerFeature.set(EqualizerPreset(SonyEqualizerFeature.SPEECH_ID))?.toList(),
        )
        assertEquals(
            byteArrayOf(0x56, 0x00).toList(),
            SonyEqualizerFeature.query().toList(),
        )
        assertEquals(12, SonyEqualizerFeature.allowedPresetIds.size)
        assertEquals(
            byteArrayOf(0x58, 0x00, 0x00, 0x00).toList(),
            SonyEqualizerFeature.set(EqualizerPreset(SonyEqualizerFeature.OFF_ID))?.toList(),
        )
        assertEquals(
            byteArrayOf(0x58, 0x00, 0xA2.toByte(), 0x00).toList(),
            SonyEqualizerFeature.set(EqualizerPreset(SonyEqualizerFeature.CUSTOM_2_ID))?.toList(),
        )
        assertNull(SonyEqualizerFeature.set(EqualizerPreset("sony:eq:18")))
    }

    @Test
    fun `rejects malformed or unverified EQ layouts`() {
        assertNull(
            SonyEqualizerFeature.parse(
                message(byteArrayOf(0x57, 0x00, 0x18, 0x06, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)),
            ),
        )
        assertNull(
            SonyEqualizerFeature.parse(
                message(byteArrayOf(0x57, 0x00, 0x16, 0x05, 0x11, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)),
            ),
        )
        assertNull(
            SonyEqualizerFeature.parse(
                message(byteArrayOf(0x57, 0x00, 0x16, 0x06, 0x15, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A)),
            ),
        )
    }

    @Test
    fun `sanitized NCASM fixture contains only valid Tandem frames`() {
        val frames = requireNotNull(
            javaClass.getResourceAsStream(
                "/fixtures/sony/device-capture/linkbuds-s-4.2.1/linkbuds-s-ncasm.hex",
            ),
        ).bufferedReader().readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::hex)

        assertEquals(19, frames.size)
        assertTrue(
            frames.all { frame ->
                TandemStreamDecoder().feed(frame).singleOrNull() is TandemDecodeResult.Frame
            },
        )
    }

    @Test
    fun `sanitized ambient voice fixture contains three reversible cycles`() {
        val frames = requireNotNull(
            javaClass.getResourceAsStream(
                "/fixtures/sony/device-capture/linkbuds-s-4.2.1/" +
                    "linkbuds-s-ambient-voice-mode.hex",
            ),
        ).bufferedReader().readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::hex)
        val messages = frames.mapNotNull { frame ->
            val decoded = TandemStreamDecoder().feed(frame).singleOrNull()
                as? TandemDecodeResult.Frame
            decoded?.value?.let(SonyMdrMessage::from)
        }
        val states = messages.mapNotNull(SonyNoiseControlFeature::parse)

        assertEquals(18, frames.size)
        assertTrue(
            frames.all { frame ->
                TandemStreamDecoder().feed(frame).singleOrNull() is TandemDecodeResult.Frame
            },
        )
        assertEquals(6, messages.count { it.command == SonyCommand.NCASM_SET_PARAM })
        assertEquals(6, messages.count { it.command == SonyCommand.NCASM_NTFY_PARAM })
        assertEquals(
            listOf(
                SonyAmbientSoundMode.VOICE,
                SonyAmbientSoundMode.NORMAL,
                SonyAmbientSoundMode.VOICE,
                SonyAmbientSoundMode.NORMAL,
                SonyAmbientSoundMode.VOICE,
                SonyAmbientSoundMode.NORMAL,
            ),
            states.map { it.ambientSoundMode },
        )
        assertTrue(states.all { it.ambientLevel == 20 })
    }

    @Test
    fun `sanitized EQ fixture contains three reversible cycles`() {
        val frames = requireNotNull(
            javaClass.getResourceAsStream(
                "/fixtures/sony/device-capture/linkbuds-s-4.2.1/linkbuds-s-eq-preset.hex",
            ),
        ).bufferedReader().readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::hex)
        val messages = frames.mapNotNull { frame ->
            val decoded = TandemStreamDecoder().feed(frame).singleOrNull()
                as? TandemDecodeResult.Frame
            decoded?.value?.let(SonyMdrMessage::from)
        }

        assertEquals(28, frames.size)
        assertTrue(
            frames.all { frame ->
                TandemStreamDecoder().feed(frame).singleOrNull() is TandemDecodeResult.Frame
            },
        )
        assertEquals(6, messages.count { it.command == SonyCommand.EQEBB_SET_PARAM })
        assertEquals(6, messages.count { it.command == SonyCommand.EQEBB_NTFY_PARAM })
        assertEquals(
            SonyEqualizerFeature.BASS_BOOST_ID,
            messages.mapNotNull(SonyEqualizerFeature::parse).lastOrNull()?.preset?.id,
        )
    }

    @Test
    fun `sanitized all-preset EQ fixture covers carousel and restores baseline`() {
        val frames = requireNotNull(
            javaClass.getResourceAsStream(
                "/fixtures/sony/device-capture/linkbuds-s-4.2.1/linkbuds-s-eq-all-presets.hex",
            ),
        ).bufferedReader().readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::hex)
        val messages = frames.mapNotNull { frame ->
            val decoded = TandemStreamDecoder().feed(frame).singleOrNull()
                as? TandemDecodeResult.Frame
            decoded?.value?.let(SonyMdrMessage::from)
        }
        val selectedPresetIds = messages
            .filter { it.command == SonyCommand.EQEBB_NTFY_PARAM }
            .mapNotNull(SonyEqualizerFeature::parse)
            .map { it.preset.id }

        assertTrue(
            frames.all { frame ->
                TandemStreamDecoder().feed(frame).singleOrNull() is TandemDecodeResult.Frame
            },
        )
        assertEquals(12, messages.count { it.command == SonyCommand.EQEBB_SET_PARAM })
        assertEquals(12, messages.count { it.command == SonyCommand.EQEBB_NTFY_PARAM })
        assertEquals(
            listOf(
                SonyEqualizerFeature.SPEECH_ID,
                SonyEqualizerFeature.MANUAL_ID,
                SonyEqualizerFeature.CUSTOM_1_ID,
                SonyEqualizerFeature.CUSTOM_2_ID,
                SonyEqualizerFeature.OFF_ID,
                SonyEqualizerFeature.BRIGHT_ID,
                SonyEqualizerFeature.EXCITED_ID,
                SonyEqualizerFeature.MELLOW_ID,
                SonyEqualizerFeature.RELAXED_ID,
                SonyEqualizerFeature.VOCAL_ID,
                SonyEqualizerFeature.TREBLE_BOOST_ID,
                SonyEqualizerFeature.BASS_BOOST_ID,
            ),
            selectedPresetIds,
        )
        assertEquals(SonyEqualizerFeature.allowedPresetIds, selectedPresetIds.toSet())
    }

    @Test
    fun `router distinguishes command tables and preserves unknown payload`() {
        val table2 = SonyMdrMessage(
            SonyDataType.DATA_MDR_NO2,
            1,
            0x7F,
            byteArrayOf(0x7F, 0x3C),
        )
        val routed = SonyMdrRouter.route(table2)

        assertTrue(routed is SonyRoute.Unknown)
        assertEquals(table2, routed.message)
        assertEquals(0x3C, routed.message.payload[1].toInt() and 0xFF)
    }

    private fun message(payload: ByteArray) = SonyMdrMessage(
        SonyDataType.DATA_MDR,
        sequence = 0,
        command = payload.first().toInt() and 0xFF,
        payload = payload,
    )

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
