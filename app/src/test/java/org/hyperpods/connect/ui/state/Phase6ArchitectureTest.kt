package org.hyperpods.connect.ui.state

import java.io.File
import moe.chenxy.headphones.core.feature.NoiseControlMode
import moe.chenxy.headphones.core.feature.SpatialAudioMode
import org.hyperpods.connect.integration.HyperOsHeadphoneAdapter
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
        val main = source("src/main/java/org/hyperpods/connect/ui/MainUI.kt")
        assumeTrue(main != null)

        assertTrue(main!!.contains("HeadphoneUiStore.state.collectAsState()"))
        assertTrue(main.contains("HeadphoneCommandClient.execute"))
        assertTrue(main.contains("FeatureCommand.SetEqualizerPreset"))
        assertFalse(main.contains("detectDeviceCapabilities("))
        assertFalse(main.contains("HeadphoneActionContract.ACTION_ANC_SELECT"))
        assertFalse(main.contains("\"oppo:"))
    }

    @Test
    fun `detail page is capability driven and exposes operation truth`() {
        val detail = source("src/main/java/org/hyperpods/connect/ui/pages/PodDetailPage.kt")
        assumeTrue(detail != null)

        assertTrue(detail!!.contains("features[\"NOISE_CONTROL\"]"))
        assertTrue(detail.contains("features[\"AMBIENT_SOUND_LEVEL\"]"))
        assertTrue(detail.contains("ancMode == NoiseControlMode.TRANSPARENCY"))
        assertFalse(detail.contains("it in 1..20"))
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
            "src/main/java/org/hyperpods/connect/hook/BluetoothUpstreamHeadsetHook.kt",
            "src/main/java/org/hyperpods/connect/hook/MiBluetoothToastHook.kt",
            "src/main/java/org/hyperpods/connect/hook/milink/MiLinkServiceHook.kt",
            "src/main/java/org/hyperpods/connect/hook/SettingsHeadsetHook.kt",
        )
        files.forEach { path ->
            val value = source(path)
            assumeTrue(value != null)
            assertTrue(value!!.contains("HyperOsHeadphoneAdapter"))
            assertFalse(value.contains("knownOppoAddresses"))
            assertFalse(value.contains("Intent(HeadphoneActionContract.ACTION_ANC_SELECT)"))
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
    fun `fresh app identity owns the internal action contract`() {
        val build = source("app/build.gradle.kts")
        val contract = source(
            "src/main/java/org/hyperpods/connect/ipc/HeadphoneActionContract.kt",
        )
        assumeTrue(build != null)
        assumeTrue(contract != null)

        assertTrue(build!!.contains("applicationId = \"org.hyperpods.connect\""))
        assertTrue(contract!!.contains("org.hyperpods.connect.action.ui_init"))
        assertFalse(contract.contains("ACTION_ANC_SELECT"))
        assertFalse(contract.contains("ACTION_GAME_MODE_SET"))
        assertFalse(contract.contains("ACTION_EQ_PRESET_SET"))

        val actions = Regex("const val (ACTION_[A-Z0-9_]+)")
            .findAll(contract)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf(
                "ACTION_HEADPHONE_UI_INIT",
                "ACTION_HEADPHONE_UI_CLOSED",
                "ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE",
                "ACTION_CONNECT_POD_REQUEST",
                "ACTION_DISCONNECT_POD_REQUEST",
                "ACTION_CYCLE_ANC",
                "ACTION_AUTO_GAME_MODE_CHANGED",
                "ACTION_GAME_MODE_IMPLEMENTATION_CHANGED",
                "ACTION_RFCOMM_LOG_CONNECT",
                "ACTION_RFCOMM_LOG_DISCONNECT",
                "ACTION_RFCOMM_LOG_CLEAR",
                "ACTION_RFCOMM_LOG",
                "ACTION_RFCOMM_DEBUG_UNLOCK",
                "ACTION_RFCOMM_DEBUG_LOCK",
                "ACTION_RFCOMM_DEBUG_SEND",
                "ACTION_CONFIG_CHANGED",
            ),
            actions,
        )
    }
}
