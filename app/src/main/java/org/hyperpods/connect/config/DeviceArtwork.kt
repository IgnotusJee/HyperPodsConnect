package org.hyperpods.connect.config

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class DeviceArtworkSource {
    OFFICIAL_LOCAL_CACHE,
    OFFICIAL_CDN,
}

@Serializable
data class DeviceArtworkSelector(
    val vendorId: String,
    val productId: String,
    val colorId: String,
    val model: String,
    val firmware: String? = null,
)

@Serializable
data class DeviceArtworkAsset(
    val resource: PodImageResource,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteCount: Int,
    val sha256: String,
)

@Serializable
data class DeviceArtworkDescriptor(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val selector: DeviceArtworkSelector,
    val source: DeviceArtworkSource,
    val assets: List<DeviceArtworkAsset>,
    val resolvedAtMillis: Long,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

internal data class ValidatedImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sha256: String,
) {
    fun toAsset(resource: PodImageResource) = DeviceArtworkAsset(
        resource = resource,
        mimeType = mimeType,
        width = width,
        height = height,
        byteCount = bytes.size,
        sha256 = sha256,
    )
}

/** Header-only validation performed before untrusted image bytes enter the shared cache. */
internal object DeviceArtworkValidator {
    const val MAX_BYTE_COUNT = 8 * 1024 * 1024
    const val MAX_DIMENSION = 4096
    const val MAX_PIXEL_COUNT = 16_777_216L

    fun validate(bytes: ByteArray): ValidatedImagePayload? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTE_COUNT) return null
        val dimensions = parsePng(bytes)
            ?: parseJpeg(bytes)
            ?: parseWebp(bytes)
            ?: return null
        val (mimeType, width, height) = dimensions
        if (
            width <= 0 || height <= 0 ||
            width > MAX_DIMENSION || height > MAX_DIMENSION ||
            width.toLong() * height.toLong() > MAX_PIXEL_COUNT
        ) return null
        return ValidatedImagePayload(
            bytes = bytes,
            mimeType = mimeType,
            width = width,
            height = height,
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) },
        )
    }

    private fun parsePng(bytes: ByteArray): ImageDimensions? {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
        if (bytes.copyOfRange(12, 16).decodeToString() != "IHDR") return null
        return ImageDimensions("image/png", bytes.readIntBigEndian(16), bytes.readIntBigEndian(20))
    }

    private fun parseJpeg(bytes: ByteArray): ImageDimensions? {
        if (bytes.size < 4 || bytes[0].u() != 0xFF || bytes[1].u() != 0xD8) return null
        var offset = 2
        while (offset + 3 < bytes.size) {
            if (bytes[offset].u() != 0xFF) {
                offset++
                continue
            }
            while (offset < bytes.size && bytes[offset].u() == 0xFF) offset++
            if (offset >= bytes.size) return null
            val marker = bytes[offset++].u()
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) continue
            if (offset + 1 >= bytes.size) return null
            val length = (bytes[offset].u() shl 8) or bytes[offset + 1].u()
            if (length < 2 || offset + length > bytes.size) return null
            if (marker in JPEG_START_OF_FRAME_MARKERS && length >= 7) {
                val height = (bytes[offset + 3].u() shl 8) or bytes[offset + 4].u()
                val width = (bytes[offset + 5].u() shl 8) or bytes[offset + 6].u()
                return ImageDimensions("image/jpeg", width, height)
            }
            offset += length
        }
        return null
    }

    private fun parseWebp(bytes: ByteArray): ImageDimensions? {
        if (
            bytes.size < 30 ||
            bytes.copyOfRange(0, 4).decodeToString() != "RIFF" ||
            bytes.copyOfRange(8, 12).decodeToString() != "WEBP"
        ) return null
        return when (bytes.copyOfRange(12, 16).decodeToString()) {
            "VP8X" -> ImageDimensions(
                "image/webp",
                1 + bytes.readInt24LittleEndian(24),
                1 + bytes.readInt24LittleEndian(27),
            )
            "VP8L" -> {
                if (bytes.size < 25 || bytes[20].u() != 0x2F) return null
                val bits = bytes[21].u() or
                    (bytes[22].u() shl 8) or
                    (bytes[23].u() shl 16) or
                    (bytes[24].u() shl 24)
                ImageDimensions(
                    "image/webp",
                    (bits and 0x3FFF) + 1,
                    ((bits ushr 14) and 0x3FFF) + 1,
                )
            }
            "VP8 " -> {
                if (bytes.size < 30 || bytes[23].u() != 0x9D || bytes[24].u() != 0x01 || bytes[25].u() != 0x2A) {
                    return null
                }
                ImageDimensions(
                    "image/webp",
                    (bytes[26].u() or (bytes[27].u() shl 8)) and 0x3FFF,
                    (bytes[28].u() or (bytes[29].u() shl 8)) and 0x3FFF,
                )
            }
            else -> null
        }
    }

    private fun ByteArray.readIntBigEndian(offset: Int): Int =
        (this[offset].u() shl 24) or
            (this[offset + 1].u() shl 16) or
            (this[offset + 2].u() shl 8) or
            this[offset + 3].u()

    private fun ByteArray.readInt24LittleEndian(offset: Int): Int =
        this[offset].u() or (this[offset + 1].u() shl 8) or (this[offset + 2].u() shl 16)

    private fun Byte.u(): Int = toInt() and 0xFF

    private data class ImageDimensions(val mimeType: String, val width: Int, val height: Int)

    private val JPEG_START_OF_FRAME_MARKERS = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )
}
