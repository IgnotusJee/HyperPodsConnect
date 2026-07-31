package moe.chenxy.oppopods.profile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Phase11ArchitectureTest {
    private fun source(relative: String): String? = listOf(
        File(relative),
        File("app/$relative"),
    ).firstOrNull(File::isFile)?.readText()

    @Test
    fun `release build explicitly disables raw and dangerous protocol operations`() {
        val build = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            .firstOrNull(File::isFile)?.readText()
        assumeTrue(build != null)

        assertTrue(
            build!!.contains(
                "buildConfigField(\"boolean\", \"ALLOW_DANGEROUS_PROTOCOL_OPERATIONS\", \"false\")",
            ),
        )
        val release = build.substringAfter("release {").substringBefore("}")
        assertTrue(release.contains("ALLOW_RAW_PROTOCOL_CONSOLE\", \"false"))
    }

    @Test
    fun `runtime host independently rejects raw frames when the build gate is closed`() {
        val runtime = source(
            "src/main/java/moe/chenxy/oppopods/runtime/bluetoothprocess/" +
                "BluetoothProcessRuntimeHost.kt",
        )
        assumeTrue(runtime != null)

        assertTrue(
            runtime!!.contains("if (!BuildConfig.ALLOW_RAW_PROTOCOL_CONSOLE) return false"),
        )
        assertFalse(runtime.contains("if (!BuildConfig.DEBUG) return false"))
    }
}
