package org.hyperpods.connect.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HyperOsRomContractArchitectureTest {
    private fun source(relative: String): String? =
        listOf(File(relative), File("app/$relative")).firstOrNull(File::isFile)?.readText()

    @Test
    fun `obsolete reverse engineered symbols and persistent hook state are absent`() {
        val hookRoot = listOf(File("src/main/java/org/hyperpods/connect/hook"), File("app/src/main/java/org/hyperpods/connect/hook"))
            .firstOrNull(File::isDirectory)
        assumeTrue(hookRoot != null)
        val source = hookRoot!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        listOf(
            "getDeviceRunInfo",
            "getSpatialMode",
            "setSpatialMode",
            "getMiAudioEffect",
            "setMiAudioEffect",
            "setHeadTracking",
            "deviceSpatialType",
            "C4705R2",
            "f18107b",
            "BinderC6776v",
            "onTransact",
            "hyperpods_connect_milink_state",
        ).forEach { obsolete -> assertFalse("obsolete symbol $obsolete", source.contains(obsolete)) }
    }

    @Test
    fun `verified semantic seams and contract diagnostics are present`() {
        val milink = source("src/main/java/org/hyperpods/connect/hook/milink/MiLinkSpatialAudioHook.kt")
        val upstream = source("src/main/java/org/hyperpods/connect/hook/BluetoothUpstreamHeadsetHook.kt")
        val compatibility = source("src/main/java/org/hyperpods/connect/hook/HyperOsHookCompatibility.kt")
        assumeTrue(milink != null && upstream != null && compatibility != null)

        assertTrue(milink!!.contains("getAudioSpatialEffectState"))
        assertTrue(milink.contains("setAudioEffectState"))
        assertTrue(upstream!!.contains("handleShowConnectedToast"))
        assertTrue(upstream.contains("showNewConnectedToast"))
        assertTrue(compatibility!!.contains("HYPEROS_CONTRACT"))
        assertTrue(compatibility.contains("status=\$status"))
    }
}
