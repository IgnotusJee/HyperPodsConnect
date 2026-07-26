package moe.chenxy.oppopods.pods

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RfcommControllerArchitectureTest {

    private fun controllerSource(): String? {
        val paths = listOf(
            File("src/main/java/moe/chenxy/oppopods/pods/RfcommController.kt"),
            File("app/src/main/java/moe/chenxy/oppopods/pods/RfcommController.kt"),
        )
        return paths.firstOrNull(File::isFile)?.readText()
    }

    @Test
    fun `controller is a facade with no protocol constants or parsers`() {
        val source = controllerSource()
        assumeTrue("RfcommController source is not reachable", source != null)

        listOf(
            "Enums.",
            "Cmd.",
            "OppoCommand",
            "OppoMessageCodec",
            "RfcommTransportBridge",
            "Parser.parse",
        ).forEach { forbidden ->
            assertFalse("controller must not contain $forbidden", source!!.contains(forbidden))
        }
        assertFalse(
            "controller must not contain wire-level hexadecimal constants",
            Regex("""0x[0-9A-Fa-f]{2,}""").containsMatchIn(source!!),
        )
    }

    @Test
    fun `every legacy UI write is translated to a FeatureCommand`() {
        val source = controllerSource()
        assumeTrue("RfcommController source is not reachable", source != null)

        listOf(
            "FeatureCommand.SetNoiseControl",
            "FeatureCommand.SetTransparencyVocalEnhancement",
            "FeatureCommand.SetEqualizerPreset",
            "FeatureCommand.SetLowLatency",
            "FeatureCommand.SetSpatialAudio",
            "FeatureCommand.SetDualDeviceConnection",
        ).forEach { command ->
            assertTrue("missing UI translation for $command", source!!.contains(command))
        }
    }

    @Test
    fun `legacy status broadcasts remain present`() {
        val source = controllerSource()
        assumeTrue("RfcommController source is not reachable", source != null)

        listOf(
            "ACTION_PODS_BATTERY_CHANGED",
            "ACTION_PODS_ANC_CHANGED",
            "ACTION_PODS_GAME_MODE_CHANGED",
            "ACTION_PODS_EQ_PRESET_CHANGED",
            "ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED",
            "ACTION_PODS_CONNECTED",
            "ACTION_PODS_DISCONNECTED",
        ).forEach { action ->
            assertTrue("legacy broadcast removed: $action", source!!.contains(action))
        }
    }
}
