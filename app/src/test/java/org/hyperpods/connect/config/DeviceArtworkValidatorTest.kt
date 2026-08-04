package org.hyperpods.connect.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceArtworkValidatorTest {
    @Test
    fun acceptsOfficialAir5sPngDimensions() {
        val result = DeviceArtworkValidator.validate(pngHeader(width = 408, height = 660))

        assertNotNull(result)
        assertEquals("image/png", result?.mimeType)
        assertEquals(408, result?.width)
        assertEquals(660, result?.height)
        assertEquals(64, result?.sha256?.length)
    }

    @Test
    fun rejectsOversizedAndUnknownPayloads() {
        assertNull(DeviceArtworkValidator.validate(pngHeader(width = 4097, height = 1)))
        assertNull(DeviceArtworkValidator.validate("not-an-image".encodeToByteArray()))
        assertNull(DeviceArtworkValidator.validate(ByteArray(DeviceArtworkValidator.MAX_BYTE_COUNT + 1)))
    }

    @Test
    fun explicitUserArtworkWinsOverOfficialCache() {
        val pref = EarphonePref(
            address = "AA:BB:CC:DD:EE:FF",
            name = "OPPO Enco Air5s",
            boxImagePath = "/user/box.img",
            officialBoxImagePath = "/official/box.img",
        )

        assertEquals("/user/box.img", pref.imagePath(PodImageResource.BOX))
        assertEquals(
            "/official/box.img",
            pref.copy(boxImagePath = null).imagePath(PodImageResource.BOX),
        )
    }

    @Test
    fun heroArtworkUsesOfficialAnimationThenDetailThenTopologyFallback() {
        val pref = EarphonePref(
            address = "AA:BB:CC:DD:EE:FF",
            name = "OPPO Enco Air5s",
            officialHeroAnimationPath = "/official/detail.webp",
            officialDetailImagePath = "/official/detail.png",
            officialLeftImagePath = "/official/left.png",
        )

        assertEquals("/official/detail.webp", pref.heroArtworkPath())
        assertEquals(
            "/official/detail.png",
            pref.copy(officialHeroAnimationPath = null).heroArtworkPath(),
        )
        assertEquals(
            "/official/left.png",
            pref.copy(officialHeroAnimationPath = null, officialDetailImagePath = null).heroArtworkPath(),
        )
        assertEquals("/user/box.png", pref.copy(boxImagePath = "/user/box.png").heroArtworkPath())
    }

    @Test
    fun settingsHeroUsesStaticUserThenOfficialDetailFallbackOrder() {
        val pref = EarphonePref(
            address = "AA:BB:CC:DD:EE:FF",
            name = "OPPO Enco Air5s",
            boxImagePath = "/user/box.png",
            officialBoxImagePath = "/official/box.png",
            officialDetailImagePath = "/official/detail.png",
            officialHeroAnimationPath = "/official/detail.webp",
        )

        assertEquals(
            listOf("/user/box.png", "/official/detail.png", "/official/box.png"),
            pref.settingsHeroArtworkPaths(),
        )
        assertEquals(
            listOf("/official/detail.png", "/official/box.png"),
            pref.copy(boxImagePath = null).settingsHeroArtworkPaths(),
        )
    }

    private fun pngHeader(width: Int, height: Int): ByteArray = ByteArray(24).apply {
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ).copyInto(this)
        byteArrayOf(0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
            .copyInto(this, destinationOffset = 8)
        writeIntBigEndian(16, width)
        writeIntBigEndian(20, height)
    }

    private fun ByteArray.writeIntBigEndian(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
