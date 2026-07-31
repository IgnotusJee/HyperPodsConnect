package moe.chenxy.oppopods.pods

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OppoSystemIntegrationAdapterArchitectureTest {

    private fun adapterSource(): String? {
        val paths = listOf(
            File("src/main/java/moe/chenxy/oppopods/pods/OppoSystemIntegrationAdapter.kt"),
            File("app/src/main/java/moe/chenxy/oppopods/pods/OppoSystemIntegrationAdapter.kt"),
        )
        return paths.firstOrNull(File::isFile)?.readText()
    }

    @Test
    fun `adapter has no transport ownership protocol constants or parsers`() {
        val source = adapterSource()
        assumeTrue("OppoSystemIntegrationAdapter source is not reachable", source != null)

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
    fun `every legacy UI write is translated to a FeatureCommand`() {
        val source = adapterSource()
        assumeTrue("OppoSystemIntegrationAdapter source is not reachable", source != null)

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
    fun `android system effects remain isolated in adapter`() {
        val source = adapterSource()
        assumeTrue("OppoSystemIntegrationAdapter source is not reachable", source != null)

        listOf(
            "MediaRouter2",
            "MiuiStrongToastUtil",
            "setRegularBatteryLevel",
        ).forEach { effect ->
            assertTrue("missing explicit Android integration effect: $effect", source!!.contains(effect))
        }
    }
}
