package moe.chenxy.headphones.protocol.oppo

import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.headphones.protocol.oppo.feature.equalizer.OppoCustomEqualizerFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OppoCustomEqualizerFeatureTest {

    private fun parsedSlot() = OppoCustomEqualizerFeature.parse(
        requireNotNull(
            OppoMessageCodec.decode(OppoFixtures.officialSource("custom-eq-response.hex")),
        ),
    )!!.single()

    @Test
    fun `official parser layout exposes selected six-band custom slot`() {
        val slot = parsedSlot()

        assertTrue(slot.selected)
        assertEquals(-6, slot.minGain)
        assertEquals(6, slot.maxGain)
        assertEquals(5, slot.eqId)
        assertEquals("Custom 1", slot.name)
        assertEquals(listOf(62, 250, 1_000, 4_000, 8_000, 16_000), slot.frequenciesHz)
        assertEquals(listOf(0, 1, -1, 2, -2, 3), slot.gains)
        assertEquals(
            listOf("62 Hz", "250 Hz", "1 kHz", "4 kHz", "8 kHz", "16 kHz"),
            OppoCustomEqualizerFeature.curveSpec(slot).bands.map { it.displayName },
        )
        assertEquals(EqualizerCurve(slot.slotId, slot.gains), OppoCustomEqualizerFeature.toReport(slot).curve)
    }

    @Test
    fun `official serializer layout matches exact update frame`() {
        val slot = parsedSlot()
        val changed = EqualizerCurve(slot.slotId, listOf(1, 1, -1, 2, -2, 3))

        val encoded = OppoCustomEqualizerFeature.setCurve(
            changed,
            slot,
            OppoCustomEqualizerFeature.curveSpec(slot),
        )

        assertEquals(
            OppoFixtures.officialSource("custom-eq-update.hex").toList(),
            requireNotNull(encoded).toList(),
        )
        assertEquals(OppoCommand.SET_CUSTOM_EQ, OppoMessageCodec.decode(encoded)!!.command)
    }

    @Test
    fun `parser and serializer reject malformed custom state`() {
        val validMessage = requireNotNull(
            OppoMessageCodec.decode(OppoFixtures.officialSource("custom-eq-response.hex")),
        )
        val truncatedPayload = validMessage.payload.copyOf(validMessage.payload.size - 1)
        val truncated = validMessage.copy(
            payload = truncatedPayload,
            declaredPayloadLength = truncatedPayload.size,
        )
        assertNull(OppoCustomEqualizerFeature.parse(truncated))

        val slot = parsedSlot()
        val duplicateFrequency = slot.copy(
            frequenciesHz = slot.frequenciesHz.toMutableList().apply { this[1] = this[0] },
        )
        assertNull(OppoCustomEqualizerFeature.select(duplicateFrequency))
        assertNull(
            OppoCustomEqualizerFeature.setCurve(
                EqualizerCurve("oppo:eq:custom:99", slot.gains),
                slot,
                OppoCustomEqualizerFeature.curveSpec(slot),
            ),
        )
    }

    @Test
    fun `add requires unassigned slot and delete requires device assigned slot`() {
        val assigned = parsedSlot()
        val draft = assigned.copy(selected = false, eqId = 0)

        val add = requireNotNull(OppoCustomEqualizerFeature.add(draft))
        assertEquals(
            OppoCustomEqualizerFeature.ACTION_ADD,
            OppoMessageCodec.decode(add)!!.payload[0].toInt(),
        )
        assertNull(OppoCustomEqualizerFeature.add(assigned))

        val delete = requireNotNull(OppoCustomEqualizerFeature.delete(assigned))
        assertEquals(
            OppoCustomEqualizerFeature.ACTION_DELETE,
            OppoMessageCodec.decode(delete)!!.payload[0].toInt(),
        )
        assertNull(OppoCustomEqualizerFeature.delete(draft))
    }
}
