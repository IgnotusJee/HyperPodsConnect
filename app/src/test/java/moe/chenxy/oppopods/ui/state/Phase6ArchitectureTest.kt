package moe.chenxy.oppopods.ui.state

import java.io.File
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import moe.chenxy.oppopods.integration.HyperOsHeadphoneAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Phase6ArchitectureTest {
    private fun source(relative: String): String? {
        val paths = listOf(
            File(relative),
            File("app/$relative"),
            File("../$relative"),
            File("../app/$relative"),
        )
        return paths.firstOrNull(File::isFile)?.readText()
    }

    @Test
    fun `main UI consumes UiStore and sends only domain commands`() {
        val main = source("src/main/java/moe/chenxy/oppopods/ui/MainUI.kt")
        assumeTrue(main != null)

        assertTrue(main!!.contains("HeadphoneUiStore.state.collectAsState()"))
        assertTrue(main.contains("HeadphoneCommandClient.execute"))
        assertTrue(main.contains("FeatureCommand.SetEqualizerPreset"))
        assertFalse(main.contains("detectDeviceCapabilities("))
        assertFalse(main.contains("OppoPodsAction.ACTION_ANC_SELECT"))
        assertFalse(main.contains("\"oppo:"))
    }

    @Test
    fun `detail page is capability driven and exposes operation truth`() {
        val detail = source("src/main/java/moe/chenxy/oppopods/ui/pages/PodDetailPage.kt")
        assumeTrue(detail != null)

        assertTrue(detail!!.contains("features[\"NOISE_CONTROL\"]"))
        assertTrue(detail.contains("equalizer.options"))
        assertTrue(detail.contains("feature_read_only"))
        assertTrue(detail.contains("UiOperationStatus.PENDING"))
        assertTrue(detail.contains("UiOperationStatus.TIMED_OUT"))
        assertTrue(detail.contains("UiOperationStatus.CONFIRMED"))
        assertFalse(detail.contains("EqPreset.ALL"))
    }

    @Test
    fun `HyperOS integrations use profile adapter without remembered OPPO addresses`() {
        val files = listOf(
            "src/main/java/moe/chenxy/oppopods/hook/BluetoothUpstreamHeadsetHook.kt",
            "src/main/java/moe/chenxy/oppopods/hook/MiBluetoothToastHook.kt",
            "src/main/java/moe/chenxy/oppopods/hook/milink/MiLinkServiceHook.kt",
            "src/main/java/moe/chenxy/oppopods/hook/SettingsHeadsetHook.kt",
        )
        files.forEach { path ->
            val value = source(path)
            assumeTrue(value != null)
            assertTrue(value!!.contains("HyperOsHeadphoneAdapter"))
            assertFalse(value.contains("knownOppoAddresses"))
            assertFalse(value.contains("Intent(OppoPodsAction.ACTION_ANC_SELECT)"))
        }
    }

    @Test
    fun `HyperOS integer APIs map to vendor-neutral domain values`() {
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            HyperOsHeadphoneAdapter.noiseControlFromPlatform(2),
        )
        assertEquals(
            NoiseControlMode.TRANSPARENCY,
            HyperOsHeadphoneAdapter.noiseControlFromPlatform(3),
        )
        assertEquals(
            SpatialAudioMode.HEAD_TRACKING,
            HyperOsHeadphoneAdapter.spatialAudioFromPlatform(11),
        )
    }

    @Test
    fun `migration keeps application id and legacy action contract`() {
        val build = source("app/build.gradle.kts")
        val legacy = source(
            "src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/data/OppoPodsAction.kt",
        )
        assumeTrue(build != null)
        assumeTrue(legacy != null)

        assertTrue(build!!.contains("applicationId = \"moe.chenxy.oppopods\""))
        assertTrue(legacy!!.contains("ACTION_ANC_SELECT"))
    }
}
