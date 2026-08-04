package org.hyperpods.connect.pods

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HeadphoneSessionCoordinatorArchitectureTest {

    private fun adapterSource(): String? {
        val paths = listOf(
            File("src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt"),
            File("app/src/main/java/org/hyperpods/connect/pods/HeadphoneSessionCoordinator.kt"),
        )
        return paths.firstOrNull(File::isFile)?.readText()
    }

    @Test
    fun `adapter has no transport ownership protocol constants or parsers`() {
        val source = adapterSource()
        assumeTrue("HeadphoneSessionCoordinator source is not reachable", source != null)

        listOf(
            "Enums.",
            "Cmd.",
            "OppoCommand",
            "OppoMessageCodec",
            "RfcommTransportBridge",
            "Parser.parse",
        ).forEach { forbidden ->
            assertFalse("adapter must not contain $forbidden", source!!.contains(forbidden))
        }
        assertFalse(
            "adapter must not contain wire-level hexadecimal constants",
            Regex("""0x[0-9A-Fa-f]{2,}""").containsMatchIn(source!!),
        )
    }

    @Test
    fun `removed legacy UI writes cannot reenter the adapter`() {
        val source = adapterSource()
        assumeTrue("HeadphoneSessionCoordinator source is not reachable", source != null)

        listOf(
            "ACTION_ANC_SELECT",
            "ACTION_GAME_MODE_SET",
            "ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET",
            "ACTION_SPATIAL_AUDIO_SET",
            "ACTION_EQ_PRESET_SET",
            "ACTION_DUAL_DEVICE_CONNECTION_SET",
        ).forEach { action ->
            assertFalse("removed legacy write returned: $action", source!!.contains(action))
        }
        assertTrue(source!!.contains("BluetoothProcessRuntimeHost.execute"))
    }

    @Test
    fun `snapshot state is not republished as legacy feature broadcasts`() {
        val source = adapterSource()
        assumeTrue("HeadphoneSessionCoordinator source is not reachable", source != null)

        listOf(
            "ACTION_HEADPHONE_CONNECTION_STATE_CHANGED",
            "ACTION_HEADPHONE_BATTERY_CHANGED",
            "ACTION_HEADPHONE_WEAR_STATUS_CHANGED",
            "ACTION_HEADPHONE_ANC_CHANGED",
            "ACTION_HEADPHONE_GAME_MODE_CHANGED",
            "ACTION_HEADPHONE_SPATIAL_AUDIO_CHANGED",
            "ACTION_HEADPHONE_EQ_PRESET_CHANGED",
            "ACTION_HEADPHONE_DUAL_DEVICE_CONNECTION_CHANGED",
            "ACTION_HEADPHONE_SMART_ANC_LEVEL_CHANGED",
            "sendExternalPodsStatusBroadcast",
        ).forEach { legacyOutput ->
            assertFalse("removed legacy output returned: $legacyOutput", source!!.contains(legacyOutput))
        }
    }

    @Test
    fun `android system effects remain isolated in adapter`() {
        val source = adapterSource()
        assumeTrue("HeadphoneSessionCoordinator source is not reachable", source != null)

        listOf(
            "MiuiStrongToastUtil",
            "setRegularBatteryLevel",
        ).forEach { effect ->
            assertTrue("missing explicit Android integration effect: $effect", source!!.contains(effect))
        }
        assertTrue(source!!.contains("HyperOsBluetoothBatteryBridge.setBatteryLevel"))
        assertFalse(source.contains("mAdapterService"))
    }

    @Test
    fun `removed media routing and duplicate projection code cannot return`() {
        val source = adapterSource()
        assumeTrue("HeadphoneSessionCoordinator source is not reachable", source != null)

        listOf(
            "ACTION_REFRESH_STATUS",
            "MediaRouter2",
            "MediaRoute2Info",
            "connectAudio(",
            "disconnectAudio(",
            "miuiRefreshPayload(",
            "legacyNoiseMode(",
            "mergeWearStatus(",
        ).forEach { removed ->
            assertFalse("dead integration branch returned: $removed", source!!.contains(removed))
        }
    }
}
