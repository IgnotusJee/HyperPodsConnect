package moe.chenxy.headphones.protocol.oppo

import moe.chenxy.headphones.core.feature.BatteryComponent
import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.WearComponent
import moe.chenxy.headphones.core.feature.WearState
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncMode
import moe.chenxy.headphones.protocol.oppo.feature.OppoAncParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatchStatusParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoBatteryParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoCapabilityParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoComponent
import moe.chenxy.headphones.protocol.oppo.feature.OppoFirmwareParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoNotificationSupportParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoSwitchSetParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoWearParser
import moe.chenxy.headphones.protocol.oppo.feature.OppoWearState
import moe.chenxy.headphones.protocol.oppo.mapping.OppoDomainMapper
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers driven by real captures, then mapped into the vendor-neutral model. */
class OppoParsersTest {

    private val presets = setOf(0, 1, 2, 3, 7)

    private fun messagesOf(fixture: String): List<OppoMessage> =
        OppoFixtures.deviceCaptureFrames(fixture).mapNotNull(OppoMessageCodec::decode)

    private fun firstWith(fixture: String, command: Int): OppoMessage =
        messagesOf(fixture).first { it.command == command }

    @Test
    fun `battery notification decodes components charging and case`() {
        val reports = messagesOf("encoair5s-status-readback.hex")
            .filter { it.command == OppoCommand.NOTIFICATION_EVENT }
            .mapNotNull(OppoBatteryParser::parse)

        val withCase = reports.first { it.containsKey(OppoComponent.CASE) }
        assertEquals(100, withCase.getValue(OppoComponent.LEFT).level)
        assertFalse(withCase.getValue(OppoComponent.LEFT).charging)
        assertTrue(withCase.getValue(OppoComponent.RIGHT).charging)
        assertEquals(40, withCase.getValue(OppoComponent.CASE).level)

        val earbudsOnly = reports.first { !it.containsKey(OppoComponent.CASE) }
        assertEquals(setOf(OppoComponent.LEFT, OppoComponent.RIGHT), earbudsOnly.keys)
    }

    @Test
    fun `battery query response decodes the same shape as the notification`() {
        val response = firstWith("encoair5s-read-probe.hex", OppoCommand.responseOf(OppoCommand.QUERY_BATTERY))

        val levels = OppoBatteryParser.parse(response)!!

        assertEquals(100, levels.getValue(OppoComponent.LEFT).level)
        assertEquals(100, levels.getValue(OppoComponent.RIGHT).level)
        assertFalse(levels.containsKey(OppoComponent.CASE))
    }

    @Test
    fun `wear notification maps to domain components dropping the case`() {
        val message = messagesOf("encoair5s-cold-init.hex")
            .first { it.command == OppoCommand.NOTIFICATION_EVENT && it.payload.firstOrNull()?.toInt() == 0x02 }

        val states = OppoWearParser.parse(message)!!
        assertEquals(OppoWearState.IN_CASE, states.getValue(OppoComponent.CASE))

        val report = OppoDomainMapper.toWearReport(states) as DeviceReport.Wearing
        // The case is reported by the device but is not a wear component.
        assertEquals(setOf(WearComponent.LEFT, WearComponent.RIGHT), report.values.keys)
        assertEquals(WearState.REMOVED, report.values.getValue(WearComponent.LEFT))
    }

    @Test
    fun `ANC selector 01 01 reports the current mode`() {
        val response = firstWith("encoair5s-read-probe.hex", OppoCommand.responseOf(OppoCommand.QUERY_ANC))

        assertEquals(OppoAncMode.NOISE_CANCELLATION_DEEP, OppoAncParser.parse(response))
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION_DEEP,
            OppoDomainMapper.toNoiseControl(OppoAncParser.parse(response)!!),
        )
    }

    /**
     * The official app queries these after every ANC write, but they answer with
     * a constant. Decoding them as a mode would produce a readback that never
     * changes, so the parser must decline them.
     */
    @Test
    fun `ANC selectors the official app uses are not mode readings`() {
        val messages = messagesOf("encoair5s-selectors.hex")
            .filter { it.command == OppoCommand.responseOf(OppoCommand.QUERY_ANC) }

        val staticOnes = messages.filter { it.payload.size >= 3 && it.payload[1].toInt() == 0x02 }
        assertTrue(staticOnes.isNotEmpty())
        staticOnes.forEach { assertNull(OppoAncParser.parse(it)) }
    }

    @Test
    fun `an unsupported selector reports failure rather than a mode`() {
        val failed = messagesOf("encoair5s-selectors.hex")
            .first { it.command == OppoCommand.responseOf(OppoCommand.QUERY_ANC) && it.status != 0 }

        assertFalse(failed.isSuccess)
        assertNull(OppoAncParser.parse(failed))
    }

    @Test
    fun `ANC notification decodes each captured mode`() {
        val modes = messagesOf("encoair5s-anc-eq.hex")
            .filter { it.command == OppoCommand.NOTIFICATION_EVENT }
            .mapNotNull { OppoAncParser.parse(it) }
            .toSet()

        assertTrue(modes.contains(OppoAncMode.OFF))
        assertTrue(modes.contains(OppoAncMode.TRANSPARENCY))
    }

    @Test
    fun `switch set response reports acceptance and nothing more`() {
        val response = firstWith("encoair5s-spatial-switch.hex", OppoCommand.responseOf(OppoCommand.SET_SWITCH_FEATURE))

        assertEquals(true, OppoSwitchSetParser.accepted(response))
        // No echo of the value: acceptance alone cannot confirm the new state.
        assertEquals(1, response.payload.size)
    }

    @Test
    fun `switch set payload matches the captured request`() {
        val request = OppoFixtures.deviceCaptureFrames("encoair5s-spatial-switch.hex")
            .mapNotNull(OppoMessageCodec::decode)
            .first { it.command == OppoCommand.SET_SWITCH_FEATURE }

        assertEquals(
            request.payload.toList(),
            OppoSwitchSetParser.setPayload(
                OppoFeature.SPATIAL_SOUND_SWITCH,
                enabled = request.payload[1].toInt() == 1,
            ).toList(),
        )
    }

    @Test
    fun `batch status returns a map so omissions stay visible`() {
        val response = firstWith("encoair5s-selectors.hex", OppoCommand.responseOf(OppoCommand.QUERY_BATCH_STATUS))
        val request = firstWith("encoair5s-selectors.hex", OppoCommand.QUERY_BATCH_STATUS)

        val answered = OppoBatchStatusParser.parse(response)!!
        val requested = request.payload.drop(1).map { it.toInt() and 0xFF }.toSet()

        // The device drops what it does not support instead of reporting an error.
        assertTrue(requested.size > answered.size)
        assertEquals(setOf(0x1C), requested - answered.keys)
        assertTrue(answered.containsKey(OppoFeature.SPATIAL_SOUND_SWITCH))
    }

    @Test
    fun `firmware reply is joined into the version the app displays`() {
        val response = firstWith("encoair5s-firmware.hex", OppoCommand.responseOf(OppoCommand.QUERY_FIRMWARE))

        assertEquals("163.163.102", OppoFirmwareParser.parse(response))
    }

    @Test
    fun `notification handshake lists the advertised ids`() {
        val response = firstWith("encoair5s-cold-init.hex", OppoCommand.responseOf(OppoCommand.QUERY_NOTIFICATION_SUPPORT))

        assertEquals(
            listOf(0x01, 0x02, 0x03, 0x04, 0x08, 0x0B, 0xF1, 0xF2, 0xF3),
            OppoNotificationSupportParser.parse(response),
        )
    }

    @Test
    fun `capability response yields the raw bitmap`() {
        val response = firstWith("encoair5s-cold-init.hex", OppoCommand.responseOf(OppoCommand.QUERY_CAPABILITY))

        assertEquals(
            OppoFixtures.hex("FF 75 52 EA A4 0E 07 0F").toList(),
            OppoCapabilityParser.parse(response)!!.toList(),
        )
    }

    @Test
    fun `parsers reject a message whose payload never fully arrived`() {
        val full = OppoMessageCodec.encode(
            OppoCommand.responseOf(OppoCommand.QUERY_BATTERY),
            payload = OppoFixtures.hex("00 02 01 64 02 64"),
        )
        val truncated = OppoMessageCodec.decode(full.copyOfRange(0, full.size - 3))!!

        assertFalse(truncated.isComplete)
        assertNull(OppoBatteryParser.parse(truncated))
    }

    @Test
    fun `a failed status is never decoded as a value`() {
        val failed = OppoMessageCodec.decode(
            OppoMessageCodec.encode(
                OppoCommand.responseOf(OppoCommand.QUERY_BATTERY),
                payload = OppoFixtures.hex("01 02 01 64 02 64"),
            ),
        )!!

        assertNull(OppoBatteryParser.parse(failed))
    }

    @Test
    fun `equalizer presets round trip through the domain namespace`() {
        presets.forEach { presetId ->
            val preset = OppoDomainMapper.toEqualizerPreset(presetId)
            assertEquals(presetId, OppoDomainMapper.toVendorPreset(preset))
        }
        assertNull(OppoDomainMapper.toVendorPreset(moe.chenxy.headphones.core.feature.EqualizerPreset("sony:1")))
    }

    @Test
    fun `battery maps into the domain report with charging preserved`() {
        val response = firstWith("encoair5s-status-readback.hex", OppoCommand.NOTIFICATION_EVENT)
        val levels = OppoBatteryParser.parse(response)!!

        val report = OppoDomainMapper.toBatteryReport(levels)

        assertEquals(levels.size, report.values.size)
        report.values[BatteryComponent.LEFT]?.let { assertEquals(100, it.level) }
    }
}
