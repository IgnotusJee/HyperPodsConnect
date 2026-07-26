package moe.chenxy.headphones.protocol.oppo.feature

import moe.chenxy.headphones.protocol.oppo.message.OppoCommand
import moe.chenxy.headphones.protocol.oppo.message.OppoFeature
import moe.chenxy.headphones.protocol.oppo.message.OppoMessage
import moe.chenxy.headphones.protocol.oppo.message.OppoNotificationType

/** Vendor-level results. Mapping to the domain happens in `OppoDomainMapper`. */

enum class OppoComponent(val id: Int) {
    LEFT(0x01),
    RIGHT(0x02),
    CASE(0x03);

    companion object {
        fun fromId(id: Int): OppoComponent? = entries.firstOrNull { it.id == id }
    }
}

data class OppoBatteryLevel(val level: Int, val charging: Boolean)

enum class OppoWearState(val value: Int) {
    DISCONNECTED(0x00),
    IN_CASE(0x04),
    REMOVED(0x05),
    WEARING(0x07);

    companion object {
        fun fromValue(value: Int): OppoWearState? = entries.firstOrNull { it.value == value }
    }
}

enum class OppoAncMode {
    OFF,
    NOISE_CANCELLATION,
    NOISE_CANCELLATION_SMART,
    NOISE_CANCELLATION_LIGHT,
    NOISE_CANCELLATION_MEDIUM,
    NOISE_CANCELLATION_DEEP,
    TRANSPARENCY,
    ADAPTIVE,
}

/** Some models encode "off" and "noise cancelling" the other way round. */
enum class OppoAncEncoding { STANDARD, COMPATIBLE }

/**
 * Battery, from either the query response or the notification channel.
 *
 * Components are returned exactly as reported. A real Air5s sends two entries
 * while in use and three once the case is involved, so inventing a missing case
 * would be wrong.
 */
object OppoBatteryParser {

    fun parse(message: OppoMessage): Map<OppoComponent, OppoBatteryLevel>? {
        if (!message.isComplete) return null
        return when (message.command) {
            OppoCommand.responseOf(OppoCommand.QUERY_BATTERY) -> {
                if (!message.isSuccess) return null
                readPairs(message.payload, from = 1)
            }

            OppoCommand.NOTIFICATION_EVENT -> {
                if (message.payload.firstOrNull()?.toInt() != OppoNotificationType.BATTERY) return null
                readPairs(message.payload, from = 1)
            }

            else -> null
        }
    }

    private fun readPairs(payload: ByteArray, from: Int): Map<OppoComponent, OppoBatteryLevel>? {
        if (payload.size <= from) return null
        val count = payload[from].toInt() and 0xFF
        val start = from + 1
        if (count <= 0 || payload.size < start + count * 2) return null

        val result = LinkedHashMap<OppoComponent, OppoBatteryLevel>(count)
        for (index in 0 until count) {
            val component = OppoComponent.fromId(payload[start + index * 2].toInt() and 0xFF) ?: continue
            val raw = payload[start + index * 2 + 1].toInt() and 0xFF
            // Bit 7 is the charging flag, the low seven bits are the percentage.
            val level = raw and 0x7F
            if (level > 100) continue
            result[component] = OppoBatteryLevel(level, charging = (raw and 0x80) != 0)
        }
        return result.takeIf { it.isNotEmpty() }
    }
}

object OppoWearParser {

    fun parse(message: OppoMessage): Map<OppoComponent, OppoWearState>? {
        if (!message.isComplete) return null
        if (message.command != OppoCommand.NOTIFICATION_EVENT) return null
        val payload = message.payload
        if (payload.firstOrNull()?.toInt() != OppoNotificationType.WEAR) return null
        if (payload.size < 2) return null

        val count = payload[1].toInt() and 0xFF
        if (count <= 0 || payload.size < 2 + count * 2) return null

        val result = LinkedHashMap<OppoComponent, OppoWearState>(count)
        for (index in 0 until count) {
            val component = OppoComponent.fromId(payload[2 + index * 2].toInt() and 0xFF) ?: continue
            val state = OppoWearState.fromValue(payload[2 + index * 2 + 1].toInt() and 0xFF) ?: continue
            result[component] = state
        }
        return result.takeIf { it.isNotEmpty() }
    }
}

/**
 * Noise control.
 *
 * The query takes a two-byte selector and the reply echoes it. Only selector
 * `01 01` reports the current mode: the official app also queries `02 03` and
 * `02 04` after each write, but those answer with a constant regardless of the
 * mode in effect, so treating them as a readback would produce a value that
 * never changes. A non-zero status means the selector is unsupported.
 */
object OppoAncParser {

    val CURRENT_MODE_SELECTOR = byteArrayOf(0x01, 0x01)

    fun parse(
        message: OppoMessage,
        encoding: OppoAncEncoding = OppoAncEncoding.STANDARD,
    ): OppoAncMode? {
        if (!message.isComplete) return null

        val payload = when (message.command) {
            OppoCommand.responseOf(OppoCommand.QUERY_ANC) -> {
                if (!message.isSuccess) return null
                message.payload
            }

            OppoCommand.NOTIFICATION_EVENT -> {
                if (message.payload.firstOrNull()?.toInt() != OppoNotificationType.ANC) return null
                message.payload
            }

            else -> return null
        }

        return decodeAfterMarker(payload, encoding)
    }

    /** Locates the `01 01` marker and reads the two value bytes that follow. */
    private fun decodeAfterMarker(payload: ByteArray, encoding: OppoAncEncoding): OppoAncMode? {
        for (index in 0 until payload.size - 2) {
            if (payload[index] != 0x01.toByte() || payload[index + 1] != 0x01.toByte()) continue
            val first = payload[index + 2].toInt() and 0xFF
            val second = if (index + 3 < payload.size) payload[index + 3].toInt() and 0xFF else 0x00
            return decodeValues(first, second, encoding)
        }
        return null
    }

    private fun decodeValues(first: Int, second: Int, encoding: OppoAncEncoding): OppoAncMode? = when {
        first == 0x08 && second == 0x00 -> OppoAncMode.OFF
        first == 0x02 && second == 0x00 ->
            if (encoding == OppoAncEncoding.COMPATIBLE) OppoAncMode.OFF else OppoAncMode.NOISE_CANCELLATION
        first == 0x01 && second == 0x00 ->
            if (encoding == OppoAncEncoding.COMPATIBLE) OppoAncMode.NOISE_CANCELLATION else OppoAncMode.OFF
        first == 0x80 && second == 0x00 -> OppoAncMode.NOISE_CANCELLATION_SMART
        first == 0x40 && second == 0x00 -> OppoAncMode.NOISE_CANCELLATION_LIGHT
        first == 0x20 && second == 0x00 -> OppoAncMode.NOISE_CANCELLATION_MEDIUM
        first == 0x10 && second == 0x00 -> OppoAncMode.NOISE_CANCELLATION_DEEP
        first == 0x04 && second == 0x00 -> OppoAncMode.TRANSPARENCY
        first == 0x00 && (second == 0x01 || second == 0x02) -> OppoAncMode.TRANSPARENCY
        first == 0x00 && second == 0x08 -> OppoAncMode.ADAPTIVE
        else -> null
    }
}

object OppoEqParser {

    fun parse(message: OppoMessage, allowedPresets: Set<Int>): Int? {
        if (!message.isComplete) return null
        return when (message.command) {
            OppoCommand.responseOf(OppoCommand.QUERY_EQ),
            OppoCommand.responseOf(OppoCommand.SET_EQ),
            -> {
                if (!message.isSuccess || message.payload.size < 2) return null
                (message.payload[1].toInt() and 0xFF).takeIf { it in allowedPresets }
            }

            OppoCommand.EQ_PRESET_NOTIFICATION -> {
                // The event channel reports the preset directly, without a status.
                message.payload.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in allowedPresets }
            }

            else -> null
        }
    }
}

/**
 * Batch feature status.
 *
 * The device answers only for features it supports and silently omits the rest,
 * so the caller must compare what it asked for against what came back to learn
 * which features exist. Returning a map rather than fixed fields keeps that
 * comparison possible.
 */
object OppoBatchStatusParser {

    fun parse(message: OppoMessage): Map<Int, Int>? {
        if (!message.isComplete) return null
        if (message.command != OppoCommand.responseOf(OppoCommand.QUERY_BATCH_STATUS)) return null
        if (!message.isSuccess) return null

        val payload = message.payload
        if (payload.size < 2) return null
        val count = payload[1].toInt() and 0xFF
        if (count <= 0 || payload.size < 2 + count * 2) return null

        return buildMap {
            for (index in 0 until count) {
                put(
                    payload[2 + index * 2].toInt() and 0xFF,
                    payload[2 + index * 2 + 1].toInt() and 0xFF,
                )
            }
        }
    }

    fun requestPayload(features: List<Int>): ByteArray =
        byteArrayOf(features.size.toByte()) + features.map { it.toByte() }.toByteArray()
}

/**
 * Switch-style set response.
 *
 * The payload is a single status byte with no echo of the value written, which
 * is why an accepted write cannot confirm the new state on its own.
 */
object OppoSwitchSetParser {

    fun accepted(message: OppoMessage): Boolean? {
        if (!message.isComplete) return null
        if (message.command != OppoCommand.responseOf(OppoCommand.SET_SWITCH_FEATURE)) return null
        return message.status?.let { it == 0x00 }
    }

    fun setPayload(featureId: Int, enabled: Boolean): ByteArray =
        byteArrayOf(featureId.toByte(), if (enabled) 0x01 else 0x00)
}

/**
 * Firmware version.
 *
 * The reply carries an ASCII list of `component,field,value` triples; the app
 * shows fields numbered 2 joined as `left.right.case`.
 */
object OppoFirmwareParser {

    fun parse(message: OppoMessage): String? {
        if (!message.isComplete) return null
        if (message.command != OppoCommand.responseOf(OppoCommand.QUERY_FIRMWARE)) return null
        if (!message.isSuccess || message.payload.size < 3) return null

        val text = message.payload.copyOfRange(2, message.payload.size)
            .toString(Charsets.US_ASCII)
            .trim()
        val parts = text.split(',')
        if (parts.size < 3 || parts.size % 3 != 0) return null

        val byComponent = LinkedHashMap<Int, String>()
        for (index in parts.indices step 3) {
            val component = parts[index].toIntOrNull() ?: return null
            val field = parts[index + 1].toIntOrNull() ?: return null
            if (field == 2) byComponent[component] = parts[index + 2]
        }
        return byComponent.entries
            .sortedBy { it.key }
            .joinToString(".") { it.value }
            .takeIf { it.isNotEmpty() }
    }
}

object OppoNotificationSupportParser {

    fun parse(message: OppoMessage): List<Int>? {
        if (!message.isComplete) return null
        if (message.command != OppoCommand.responseOf(OppoCommand.QUERY_NOTIFICATION_SUPPORT)) return null
        if (!message.isSuccess) return null

        val payload = message.payload
        if (payload.size < 2) return null
        val count = payload[1].toInt() and 0xFF
        if (count <= 0 || payload.size < 2 + count) return null

        return (0 until count).map { payload[2 + it].toInt() and 0xFF }
    }

    fun subscribePayload(ids: List<Int>): ByteArray =
        byteArrayOf(ids.size.toByte()) + ids.map { it.toByte() }.toByteArray()
}

object OppoCapabilityParser {

    /** Returns the raw capability bitmap; interpretation is model specific. */
    fun parse(message: OppoMessage): ByteArray? {
        if (!message.isComplete) return null
        if (message.command != OppoCommand.responseOf(OppoCommand.QUERY_CAPABILITY)) return null
        if (!message.isSuccess || message.payload.size < 2) return null
        return message.payload.copyOfRange(1, message.payload.size)
    }
}

/** Feature ids the batch query may carry, kept next to the parser that reads them. */
object OppoBatchFeatures {
    val SPATIAL_SOUND_SWITCH = OppoFeature.SPATIAL_SOUND_SWITCH
    val LOW_LATENCY = OppoFeature.LOW_LATENCY
    val DUAL_DEVICE = OppoFeature.DUAL_DEVICE
    val GAME_MODE = OppoFeature.GAME_MODE
}
