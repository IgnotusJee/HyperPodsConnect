package moe.chenxy.headphones.protocol.oppo.feature.equalizer

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.EqualizerBandSpec
import moe.chenxy.headphones.core.feature.EqualizerCurve
import moe.chenxy.headphones.core.feature.EqualizerCurveSpec
import moe.chenxy.headphones.core.feature.EqualizerPreset
import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoMessageCodec

data class OppoCustomEqualizerSlot(
    val selected: Boolean,
    val minGain: Int,
    val maxGain: Int,
    val eqId: Int,
    val name: String,
    val frequenciesHz: List<Int>,
    val gains: List<Int>,
) {
    val slotId: String get() = OppoCustomEqualizerFeature.slotId(eqId)
}

/** Official HeyMelody 16.7.1 custom-EQ command family: GET 0x0122 / SET 0x0418. */
object OppoCustomEqualizerFeature {
    const val CAPABILITY_BIT = 34
    const val ACTION_ADD = 1
    const val ACTION_UPDATE_OR_SELECT = 2
    const val ACTION_DELETE = 3

    private const val MAX_SLOT_COUNT = 16
    private const val MAX_NAME_BYTES = 128
    private const val MAX_BAND_COUNT = 32
    private const val SLOT_PREFIX = "oppo:eq:custom:"

    fun query(): ByteArray = OppoMessageCodec.encode(OppoCommand.QUERY_CUSTOM_EQ)

    fun parse(message: OppoMessage): List<OppoCustomEqualizerSlot>? {
        if (!message.isComplete) return null
        if (message.command != OppoCommand.responseOf(OppoCommand.QUERY_CUSTOM_EQ)) return null
        if (!message.isSuccess || message.payload.size < 2) return null

        val payload = message.payload
        val slotCount = u8(payload[1])
        if (slotCount > MAX_SLOT_COUNT) return null
        var offset = 2
        val slots = ArrayList<OppoCustomEqualizerSlot>(slotCount)
        repeat(slotCount) {
            if (offset + 5 > payload.size) return null
            val selected = when (u8(payload[offset])) {
                0 -> false
                1 -> true
                else -> return null
            }
            val minGain = payload[offset + 1].toInt()
            val maxGain = payload[offset + 2].toInt()
            val eqId = u8(payload[offset + 3])
            val nameLength = u8(payload[offset + 4])
            offset += 5
            if (minGain > maxGain || nameLength > MAX_NAME_BYTES || offset + nameLength > payload.size) {
                return null
            }
            val nameBytes = payload.copyOfRange(offset, offset + nameLength)
            val name = nameBytes.toString(Charsets.UTF_8)
            if (!name.toByteArray(Charsets.UTF_8).contentEquals(nameBytes)) return null
            offset += nameLength

            if (offset >= payload.size) return null
            val bandCount = u8(payload[offset++])
            if (bandCount == 0 || bandCount > MAX_BAND_COUNT || offset + bandCount * 3 > payload.size) {
                return null
            }
            val frequencies = ArrayList<Int>(bandCount)
            val gains = ArrayList<Int>(bandCount)
            repeat(bandCount) {
                val frequency = u8(payload[offset]) or (u8(payload[offset + 1]) shl 8)
                val gain = payload[offset + 2].toInt()
                if (frequency <= 0 || gain !in minGain..maxGain) return null
                frequencies += frequency
                gains += gain
                offset += 3
            }
            if (frequencies.distinct().size != frequencies.size) return null
            slots += OppoCustomEqualizerSlot(
                selected = selected,
                minGain = minGain,
                maxGain = maxGain,
                eqId = eqId,
                name = name,
                frequenciesHz = frequencies,
                gains = gains,
            )
        }
        if (offset != payload.size || slots.map { it.eqId }.distinct().size != slots.size) return null
        if (slots.count { it.selected } > 1) return null
        return slots
    }

    fun setCurve(
        curve: EqualizerCurve,
        activeSlot: OppoCustomEqualizerSlot,
        spec: EqualizerCurveSpec,
    ): ByteArray? {
        if (!activeSlot.selected || curve.slotId != activeSlot.slotId || !spec.accepts(curve)) return null
        return encode(ACTION_UPDATE_OR_SELECT, activeSlot, curve.gains)
    }

    fun select(slot: OppoCustomEqualizerSlot): ByteArray? =
        encode(ACTION_UPDATE_OR_SELECT, slot, slot.gains)

    /**
     * Creates a slot using the same action used by HeyMelody. The device assigns the final EQ id,
     * so an add request must carry id 0 and must be followed by [query] before the slot is used.
     */
    fun add(slot: OppoCustomEqualizerSlot): ByteArray? =
        slot.takeIf { it.eqId == 0 && !it.selected }
            ?.let { encode(ACTION_ADD, it, it.gains) }

    /** Deletes an exact slot previously returned by [parse]. */
    fun delete(slot: OppoCustomEqualizerSlot): ByteArray? =
        slot.takeIf { it.eqId != 0 }
            ?.let { encode(ACTION_DELETE, it, it.gains) }

    fun toReport(slot: OppoCustomEqualizerSlot): DeviceReport.Equalizer =
        DeviceReport.Equalizer(
            preset = EqualizerPreset(slot.slotId, slot.name.ifBlank { "Custom ${slot.eqId}" }),
            curve = EqualizerCurve(slot.slotId, slot.gains),
        )

    fun curveSpec(slot: OppoCustomEqualizerSlot, writable: Boolean = true): EqualizerCurveSpec =
        EqualizerCurveSpec(
            bands = slot.frequenciesHz.map { frequency ->
                EqualizerBandSpec(
                    id = "oppo:eq:frequency:$frequency",
                    displayName = formatFrequency(frequency),
                    minGain = slot.minGain,
                    maxGain = slot.maxGain,
                    centerFrequencyHz = frequency,
                )
            },
            writableSlotIds = if (writable) setOf(slot.slotId) else emptySet(),
        )

    fun slotId(eqId: Int): String = "$SLOT_PREFIX$eqId"

    fun eqId(slotId: String): Int? = slotId.removePrefix(SLOT_PREFIX)
        .takeIf { slotId.startsWith(SLOT_PREFIX) }
        ?.toIntOrNull()
        ?.takeIf { it in 0..0xFF }

    private fun encode(action: Int, slot: OppoCustomEqualizerSlot, gains: List<Int>): ByteArray? {
        if (action !in ACTION_ADD..ACTION_DELETE) return null
        if (slot.minGain !in -128..127 || slot.maxGain !in -128..127 || slot.minGain > slot.maxGain) {
            return null
        }
        if (slot.eqId !in 0..0xFF || slot.frequenciesHz.distinct().size != slot.frequenciesHz.size) {
            return null
        }
        if (slot.frequenciesHz.isEmpty() || slot.frequenciesHz.size != gains.size) return null
        if (slot.frequenciesHz.size > MAX_BAND_COUNT || gains.any { it !in slot.minGain..slot.maxGain }) return null
        val nameBytes = slot.name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_NAME_BYTES || nameBytes.size > 0xFF) return null

        val payload = ByteArray(6 + nameBytes.size + slot.frequenciesHz.size * 3)
        payload[0] = action.toByte()
        payload[1] = slot.minGain.toByte()
        payload[2] = slot.maxGain.toByte()
        payload[3] = slot.eqId.toByte()
        payload[4] = nameBytes.size.toByte()
        nameBytes.copyInto(payload, 5)
        var offset = 5 + nameBytes.size
        payload[offset++] = slot.frequenciesHz.size.toByte()
        slot.frequenciesHz.indices.forEach { index ->
            val frequency = slot.frequenciesHz[index]
            if (frequency !in 1..0xFFFF) return null
            payload[offset] = (frequency and 0xFF).toByte()
            payload[offset + 1] = ((frequency shr 8) and 0xFF).toByte()
            payload[offset + 2] = gains[index].toByte()
            offset += 3
        }
        return OppoMessageCodec.encode(OppoCommand.SET_CUSTOM_EQ, payload = payload)
    }

    private fun formatFrequency(frequency: Int): String = when {
        frequency >= 1_000 && frequency % 1_000 == 0 -> "${frequency / 1_000} kHz"
        else -> "$frequency Hz"
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}
