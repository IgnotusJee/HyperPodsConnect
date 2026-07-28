package moe.chenxy.headphones.protocol.sony.feature

import java.security.MessageDigest
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.message.SonyDeviceInfoType
import moe.chenxy.headphones.protocol.sony.message.SonyMdrMessage

enum class SonyProtocolGeneration { V1, V2 }

data class SonyProtocolInfo(
    val generation: SonyProtocolGeneration,
    val version: Long?,
    val table1Enabled: Boolean,
    val table2Enabled: Boolean,
    val rawFingerprint: String,
)

data class SonyCapabilityInfo(
    val raw: ByteArray,
    val fingerprint: String,
)

data class SonySupportInfo(
    val functions: List<Int>,
    val raw: ByteArray,
    val fingerprint: String,
)

object SonyHandshake {
    fun getProtocolInfo(): ByteArray =
        byteArrayOf(SonyCommand.CONNECT_GET_PROTOCOL_INFO.toByte(), 0x00)

    fun getCapabilityInfo(): ByteArray =
        byteArrayOf(SonyCommand.CONNECT_GET_CAPABILITY_INFO.toByte(), 0x00)

    fun getDeviceInfo(type: SonyDeviceInfoType): ByteArray =
        byteArrayOf(SonyCommand.CONNECT_GET_DEVICE_INFO.toByte(), type.code.toByte())

    fun getSupportFunction(): ByteArray =
        byteArrayOf(SonyCommand.CONNECT_GET_SUPPORT_FUNCTION.toByte(), 0x00)

    fun parseProtocolInfo(message: SonyMdrMessage): SonyProtocolInfo? {
        val bytes = message.payload
        if (
            message.command != SonyCommand.CONNECT_RET_PROTOCOL_INFO ||
            bytes.size !in setOf(4, 8) ||
            (bytes[1].toInt() and 0xFF) != 0
        ) return null
        val generation = if (bytes.size == 8) SonyProtocolGeneration.V2 else SonyProtocolGeneration.V1
        val version = if (bytes.size >= 6) readU32Be(bytes, 2) else null
        return SonyProtocolInfo(
            generation = generation,
            version = version,
            // The two trailing bytes in the v2 response are reserved, not
            // command-table flags. LinkBuds S 4.2.1 reports 00 00 and the
            // official app immediately uses both DATA_MDR and DATA_MDR_NO2.
            table1Enabled = true,
            table2Enabled = generation == SonyProtocolGeneration.V2,
            rawFingerprint = fingerprint(bytes),
        )
    }

    fun parseCapabilityInfo(message: SonyMdrMessage): SonyCapabilityInfo? {
        val bytes = message.payload
        if (
            message.command != SonyCommand.CONNECT_RET_CAPABILITY_INFO ||
            bytes.size < 2 ||
            (bytes[1].toInt() and 0xFF) != 0
        ) return null
        return SonyCapabilityInfo(bytes.copyOf(), fingerprint(bytes))
    }

    fun parseDeviceInfo(message: SonyMdrMessage, expected: SonyDeviceInfoType): String? {
        val bytes = message.payload
        if (
            message.command != SonyCommand.CONNECT_RET_DEVICE_INFO ||
            bytes.size < 3 ||
            (bytes[1].toInt() and 0xFF) != expected.code
        ) return null
        val length = bytes[2].toInt() and 0xFF
        if (length >= 0x80 || bytes.size != length + 3) return null
        return bytes.copyOfRange(3, bytes.size)
            .toString(Charsets.UTF_8)
            .trimEnd('\u0000')
            .takeIf(String::isNotBlank)
    }

    fun parseSupportFunction(message: SonyMdrMessage): SonySupportInfo? {
        val bytes = message.payload
        if (
            message.command != SonyCommand.CONNECT_RET_SUPPORT_FUNCTION ||
            bytes.size < 3 ||
            (bytes[1].toInt() and 0xFF) != 0
        ) return null
        val lengthOrCount = bytes[2].toInt() and 0xFF
        val functionCount = when {
            lengthOrCount % 2 == 0 && bytes.size == 3 + lengthOrCount ->
                lengthOrCount / 2
            bytes.size == 3 + lengthOrCount * 2 ->
                lengthOrCount
            else -> return null
        }
        val values = (0 until functionCount).map { index ->
            val offset = 3 + index * 2
            ((bytes[offset].toInt() and 0xFF) shl 8) or
                (bytes[offset + 1].toInt() and 0xFF)
        }
        return SonySupportInfo(values, bytes.copyOf(), fingerprint(bytes))
    }

    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun readU32Be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
