package org.hyperpods.connect

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.hyperpods.connect.config.DeviceArtworkSelector
import org.hyperpods.connect.config.PodImagePrefs
import org.hyperpods.connect.config.PodImageResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceArtworkCacheDeviceTest {
    @Test
    fun publishesOfficialRolesAndKeepsUserOverride() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("device_artwork_cache_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val address = "phase15-test-device"
        val image = png(width = 24, height = 32)
        val images = PodImageResource.entries.associateWith { image }

        val official = PodImagePrefs.saveOfficialImages(
            context = context,
            prefs = prefs,
            service = null,
            address = address,
            name = "OPPO Enco Air5s",
            selector = DeviceArtworkSelector(
                vendorId = "oppo",
                productId = "06C810",
                colorId = "1",
                model = "OPPO Enco Air5s",
                firmware = "163.163.102",
            ),
            images = images,
        ).single()

        assertNotNull(official.officialArtwork)
        assertEquals(PodImageResource.entries.size, official.officialArtwork?.assets?.size)
        PodImageResource.entries.forEach { resource ->
            assertTrue(File(official.officialImagePath(resource).orEmpty()).isFile)
        }

        val withUserOverride = PodImagePrefs.saveImageBytes(
            context = context,
            prefs = prefs,
            service = null,
            address = address,
            name = "OPPO Enco Air5s",
            images = mapOf(PodImageResource.BOX to png(width = 12, height = 12)),
        ).single()
        assertEquals(
            withUserOverride.userImagePath(PodImageResource.BOX),
            withUserOverride.imagePath(PodImageResource.BOX),
        )

        val beforeRejectedUpdate = withUserOverride.officialImagePath(PodImageResource.BOX)
        val afterRejectedUpdate = PodImagePrefs.saveOfficialImages(
            context = context,
            prefs = prefs,
            service = null,
            address = address,
            name = "OPPO Enco Air5s",
            selector = official.officialArtwork!!.selector.copy(colorId = "2"),
            images = images + (PodImageResource.LEFT to "broken".encodeToByteArray()),
        ).single()
        assertEquals(beforeRejectedUpdate, afterRejectedUpdate.officialImagePath(PodImageResource.BOX))
    }

    @Test
    fun publishesTopologySpecificPartialSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("device_artwork_partial_cache_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val official = PodImagePrefs.saveOfficialImages(
            context = context,
            prefs = prefs,
            service = null,
            address = "phase15-partial-device",
            name = "OPPO Headphones",
            selector = DeviceArtworkSelector("oppo", "ABC123", "1", "OPPO Headphones"),
            images = mapOf(PodImageResource.DETAIL to png(width = 780, height = 780)),
        ).single()

        assertEquals(1, official.officialArtwork?.assets?.size)
        assertTrue(File(official.officialDetailImagePath.orEmpty()).isFile)
        assertEquals(official.officialDetailImagePath, official.heroArtworkPath())
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
